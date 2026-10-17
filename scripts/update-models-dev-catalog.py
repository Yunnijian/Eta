#!/usr/bin/env python3
"""同步 APK 内的 models.dev 离线资源：模型目录快照 + 提供商图标。

数据源全部来自 models.dev：
  - https://models.dev/api.json                  模型目录（压缩后放 assets）
  - https://models.dev/logos/<provider-id>.svg   提供商图标（转 VectorDrawable 放 res/drawable）

图标是单调色 `currentColor` 字形，因此统一转成 fillColor=#FF000000 的 VectorDrawable，
在渲染侧用 tint 上色。转换只搬运结构、不重写路径：SVG 的 transform 链逐层映射为
<vector> 的嵌套 <group>。少数无法无损转换的写法（pattern/image/text/style/非对角
matrix/多色填充）整体跳过，由 App 侧回退到通用图标。
"""

from __future__ import annotations

import gzip
import hashlib
import io
import json
import math
import os
import re
import tempfile
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import NamedTuple


CATALOG_URL = "https://models.dev/api.json"
MAX_CATALOG_BYTES = 16 * 1024 * 1024
SNAPSHOT = (
    Path(__file__).resolve().parents[1]
    / "app/src/main/assets/catalog/models-dev-api.json.gzip"
)

LOGO_URL = "https://models.dev/logos/{}.svg"
DRAWABLE_DIR = Path(__file__).resolve().parents[1] / "app/src/main/res/drawable"
LOGO_RES_PREFIX = "catalog_logo_"
KOTLIN_MAP = (
    Path(__file__).resolve().parents[1]
    / "app/src/main/kotlin/io/github/mangi/eta/ui/components/CatalogLogoResources.kt"
)

USER_AGENT = "Eta-Catalog-Updater (+https://github.com/Mangi-11/Eta)"

# 与 CommunityCatalogParser.kt 同源的宽松过滤：只要能被解析成「可导入提供商」就纳入图标集合。
# 这里只粗筛（有 api 或属于已知 chat 端点 + 至少一个合格模型），比 Kotlin 侧少了 baseUrl 校验，
# 因此结果必然是其超集，不会漏图标；连带多生成几个用不到的图标无关紧要。
KNOWN_CHAT_ENDPOINTS = {"xai", "mistral"}
PROVIDER_ID_PATTERN = re.compile(r"[a-z0-9][a-z0-9_-]{0,79}")
DEAD_STATUS = {"alpha", "deprecated", "retired", "removed"}

# 无法用 VectorDrawable 无损表达的写法：命中即整体跳过该图标。
SKIP_TAGS = {"pattern", "image", "text", "style", "mask", "filter", "use", "foreignObject"}
SKIP_ATTRIBUTES = ("mask", "filter")
CONTAINER_TAGS = {"defs", "clipPath", "title", "desc", "metadata"}
DRAWN_TAGS = {"path", "rect", "circle", "ellipse", "polygon", "polyline", "line"}
ACCEPTED_FILLS = {"currentcolor", "black", "#000", "#000000"}
NUMERIC = r"[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?"
PATH_TOKEN = re.compile(rf"([MmLlHhVvCcSsQqTtAaZz])|({NUMERIC})")
PATH_ARITY = {"M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "S": 4, "Q": 4, "T": 2, "A": 7, "Z": 0}
# aapt2 字符串池的 UTF-8 长度上限为 32767；超限的字符串会被字面量 STRING_TOO_LARGE 顶替，
# 运行期解析该字符串必然抛 "Unknown command for: R"。留出余量后作为硬闸门。
MAX_PATH_DATA_CHARS = 30_000


class UnsupportedLogo(Exception):
    """该 SVG 含有无法无损映射到 VectorDrawable 的写法。"""


class _Style(NamedTuple):
    """沿 SVG 树继承的着色/描边上下文。"""

    fill: str
    stroke: str
    stroke_width: float
    fill_rule: str
    line_cap: str
    line_join: str
    fill_alpha: float
    stroke_alpha: float


def _resolve_style(node: ET.Element, parent: _Style) -> _Style:
    # opacity 不严格继承，但按乘法逐层累积是对组透明度的合理近似。
    opacity = _number(node.get("opacity"), 1.0) if node.get("opacity") is not None else 1.0
    fill_opacity = _number(node.get("fill-opacity"), 1.0) if node.get("fill-opacity") is not None else 1.0
    stroke_opacity = _number(node.get("stroke-opacity"), 1.0) if node.get("stroke-opacity") is not None else 1.0
    return _Style(
        fill=node.get("fill", parent.fill),
        stroke=node.get("stroke", parent.stroke),
        stroke_width=_number(node.get("stroke-width"), parent.stroke_width)
        if node.get("stroke-width") is not None
        else parent.stroke_width,
        fill_rule=node.get("fill-rule", parent.fill_rule),
        line_cap=node.get("stroke-linecap", parent.line_cap),
        line_join=node.get("stroke-linejoin", parent.line_join),
        fill_alpha=parent.fill_alpha * opacity * fill_opacity,
        stroke_alpha=parent.stroke_alpha * opacity * stroke_opacity,
    )


def _style_attribute_supported(value: str) -> bool:
    """style 属性只要没有声明影响上色的项就可忽略（display/尺寸类不影响图形）。"""
    return not re.search(r"(?:^|;)\s*(?:fill|stroke|opacity|color|visibility)\s*:", value)


# --- 模型目录快照（原逻辑，保持不变） -------------------------------------------


def download_catalog() -> bytes:
    request = urllib.request.Request(
        CATALOG_URL,
        headers={
            "User-Agent": USER_AGENT,
            "Accept": "application/json",
            "Accept-Encoding": "identity",
        },
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        if response.geturl() != CATALOG_URL:
            raise ValueError("模型目录跳转到了非预期地址")
        if response.headers.get_content_type() != "application/json":
            raise ValueError("模型目录不是 JSON 响应")
        declared_size = response.headers.get("Content-Length")
        if declared_size is not None and int(declared_size) > MAX_CATALOG_BYTES:
            raise ValueError("模型目录超过大小限制")
        data = response.read(MAX_CATALOG_BYTES + 1)

    if len(data) > MAX_CATALOG_BYTES:
        raise ValueError("模型目录超过大小限制")
    catalog = json.loads(data.decode("utf-8"))
    if not isinstance(catalog, dict) or not catalog:
        raise ValueError("模型目录顶层结构无效")
    if not all(
        isinstance(provider_id, str)
        and isinstance(provider, dict)
        and isinstance(provider.get("models"), dict)
        for provider_id, provider in catalog.items()
    ):
        raise ValueError("模型目录提供商结构无效")
    return data


def gzip_snapshot(data: bytes) -> bytes:
    output = io.BytesIO()
    with gzip.GzipFile(fileobj=output, mode="wb", filename="", mtime=0, compresslevel=9) as stream:
        stream.write(data)
    return output.getvalue()


def write_if_changed(path: Path, content: bytes) -> bool:
    if path.exists() and path.read_bytes() == content:
        return False
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb", dir=path.parent, prefix=f".{path.name}-", suffix=".tmp", delete=False
        ) as temporary:
            temporary_path = Path(temporary.name)
            temporary.write(content)
        os.chmod(temporary_path, 0o644)
        os.replace(temporary_path, path)
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)
    return True


# --- 图标集合筛选 --------------------------------------------------------------


def catalog_provider_ids(catalog: dict) -> list[str]:
    ids: list[str] = []
    for provider_id, provider in catalog.items():
        if not isinstance(provider, dict) or not PROVIDER_ID_PATTERN.fullmatch(provider_id):
            continue
        if not (provider.get("api") or provider_id in KNOWN_CHAT_ENDPOINTS):
            continue
        if any(_model_is_candidate(model) for model in (provider.get("models") or {}).values()):
            ids.append(provider_id)
    return sorted(ids)


def _model_is_candidate(model: object) -> bool:
    if not isinstance(model, dict) or "provider" in model:
        return False
    if str(model.get("status", "")).lower() in DEAD_STATUS:
        return False
    if not model.get("tool_call"):
        return False
    modalities = model.get("modalities")
    if not isinstance(modalities, dict):
        return False
    inputs = [str(x).lower() for x in (modalities.get("input") or [])]
    outputs = [str(x).lower() for x in (modalities.get("output") or [])]
    if "text" not in inputs or outputs != ["text"]:
        return False
    context = (model.get("limit") or {}).get("context")
    return isinstance(context, int) and context >= 1


def fetch_logo(provider_id: str) -> str | None:
    request = urllib.request.Request(
        LOGO_URL.format(provider_id),
        headers={"User-Agent": USER_AGENT, "Accept": "image/svg+xml"},
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            content_type = response.headers.get_content_type()
            if content_type not in ("image/svg+xml", "text/xml", "application/xml"):
                raise UnsupportedLogo(f"响应类型不是 SVG（{content_type}）")
            return response.read(4 * 1024 * 1024).decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise


# --- SVG → VectorDrawable -----------------------------------------------------


def _local_name(tag: str) -> str:
    return tag.split("}", 1)[-1]


def _escape(value: str) -> str:
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


def _number(text: str | None, default: float = 0.0) -> float:
    if text is None:
        return default
    match = re.match(NUMERIC, text.strip())
    return float(match.group(0)) if match else default


def _fmt(value: float) -> str:
    if abs(value) < 5e-7:
        return "0"
    text = f"{value:.6f}".rstrip("0").rstrip(".")
    return text if text not in ("", "-0") else "0"


def _points_to_path(points: str, close: bool) -> str:
    numbers = [float(n) for n in re.findall(NUMERIC, points)]
    pairs = list(zip(numbers[0::2], numbers[1::2]))
    if not pairs:
        return ""
    parts = [f"M{_fmt(pairs[0][0])} {_fmt(pairs[0][1])}"]
    parts += [f"L{_fmt(x)} {_fmt(y)}" for x, y in pairs[1:]]
    if close:
        parts.append("Z")
    return "".join(parts)


def _rect_to_path(node: ET.Element) -> str:
    x, y = _number(node.get("x")), _number(node.get("y"))
    w, h = _number(node.get("width")), _number(node.get("height"))
    if w <= 0 or h <= 0:
        return ""
    rx = _number(node.get("rx")) if node.get("rx") is not None else None
    ry = _number(node.get("ry")) if node.get("ry") is not None else None
    if rx is None and ry is None:
        rx = ry = 0.0
    elif rx is None:
        rx = ry
    elif ry is None:
        ry = rx
    rx, ry = min(rx, w / 2), min(ry, h / 2)
    if rx <= 0 or ry <= 0:
        return f"M{_fmt(x)} {_fmt(y)}H{_fmt(x + w)}V{_fmt(y + h)}H{_fmt(x)}Z"
    return (
        f"M{_fmt(x + rx)} {_fmt(y)}"
        f"H{_fmt(x + w - rx)}A{_fmt(rx)} {_fmt(ry)} 0 0 1 {_fmt(x + w)} {_fmt(y + ry)}"
        f"V{_fmt(y + h - ry)}A{_fmt(rx)} {_fmt(ry)} 0 0 1 {_fmt(x + w - rx)} {_fmt(y + h)}"
        f"H{_fmt(x + rx)}A{_fmt(rx)} {_fmt(ry)} 0 0 1 {_fmt(x)} {_fmt(y + h - ry)}"
        f"V{_fmt(y + ry)}A{_fmt(rx)} {_fmt(ry)} 0 0 1 {_fmt(x + rx)} {_fmt(y)}Z"
    )


def _ellipse_to_path(cx: float, cy: float, rx: float, ry: float) -> str:
    if rx <= 0 or ry <= 0:
        return ""
    return (
        f"M{_fmt(cx - rx)} {_fmt(cy)}"
        f"A{_fmt(rx)} {_fmt(ry)} 0 1 0 {_fmt(cx + rx)} {_fmt(cy)}"
        f"A{_fmt(rx)} {_fmt(ry)} 0 1 0 {_fmt(cx - rx)} {_fmt(cy)}Z"
    )


def _shape_path_data(node: ET.Element, tag: str) -> str:
    if tag == "path":
        return (node.get("d") or "").strip()
    if tag == "rect":
        return _rect_to_path(node)
    if tag == "circle":
        radius = _number(node.get("r"))
        return _ellipse_to_path(_number(node.get("cx")), _number(node.get("cy")), radius, radius)
    if tag == "ellipse":
        return _ellipse_to_path(
            _number(node.get("cx")), _number(node.get("cy")), _number(node.get("rx")), _number(node.get("ry"))
        )
    if tag == "polygon":
        return _points_to_path(node.get("points") or "", close=True)
    if tag == "polyline":
        return _points_to_path(node.get("points") or "", close=False)
    if tag == "line":
        return (
            f"M{_fmt(_number(node.get('x1')))} {_fmt(_number(node.get('y1')))}"
            f"L{_fmt(_number(node.get('x2')))} {_fmt(_number(node.get('y2')))}"
        )
    return ""


def _parse_transform_functions(transform: str | None) -> list[tuple[str, list[float]]]:
    """把 SVG transform 拆成 (函数名, 数值) 列表；无法映射的写法抛异常。"""
    if not transform:
        return []
    functions = re.findall(r"([a-zA-Z]+)\s*\(([^)]*)\)", transform)
    if not functions:
        raise UnsupportedLogo("transform 无法解析")
    parsed: list[tuple[str, list[float]]] = []
    for name, raw in functions:
        lowered = name.lower()
        if lowered not in ("translate", "scale", "rotate", "matrix"):
            raise UnsupportedLogo(f"不支持的 transform 函数 {name}")
        numbers = [float(n) for n in re.findall(NUMERIC, raw)]
        if lowered == "matrix" and (len(numbers) != 6 or abs(numbers[1]) > 1e-9 or abs(numbers[2]) > 1e-9):
            # 仅支持对角矩阵 matrix(a,0,0,d,e,f)，可无损映射为 translate+scale。
            raise UnsupportedLogo("matrix 变换非对角，无法映射")
        parsed.append((lowered, numbers))
    return parsed


def _group_attributes(transform: str | None) -> list[str]:
    """把 SVG transform 映射为若干 <group> 的属性串（每层一个）。"""
    groups: list[str] = []
    for name, numbers in _parse_transform_functions(transform):
        if name == "translate":
            tx = numbers[0] if len(numbers) > 0 else 0.0
            ty = numbers[1] if len(numbers) > 1 else 0.0
            groups.append(f'android:translateX="{_fmt(tx)}" android:translateY="{_fmt(ty)}"')
        elif name == "scale":
            sx = numbers[0] if len(numbers) > 0 else 1.0
            sy = numbers[1] if len(numbers) > 1 else sx
            groups.append(f'android:scaleX="{_fmt(sx)}" android:scaleY="{_fmt(sy)}"')
        elif name == "rotate":
            angle = numbers[0] if len(numbers) > 0 else 0.0
            if len(numbers) >= 3:
                groups.append(
                    f'android:rotation="{_fmt(angle)}"'
                    f' android:pivotX="{_fmt(numbers[1])}" android:pivotY="{_fmt(numbers[2])}"'
                )
            else:
                groups.append(f'android:rotation="{_fmt(angle)}"')
        else:  # matrix（对角）
            groups.append(
                f'android:translateX="{_fmt(numbers[4])}" android:translateY="{_fmt(numbers[5])}"'
                f' android:scaleX="{_fmt(numbers[0])}" android:scaleY="{_fmt(numbers[3])}"'
            )
    return groups


def _matrix_multiply(first: tuple[float, ...], second: tuple[float, ...]) -> tuple[float, ...]:
    a1, b1, c1, d1, e1, f1 = first
    a2, b2, c2, d2, e2, f2 = second
    return (
        a1 * a2 + b1 * c2,
        a1 * b2 + b1 * d2,
        c1 * a2 + d1 * c2,
        c1 * b2 + d1 * d2,
        a1 * e2 + b1 * f2 + e1,
        c1 * e2 + d1 * f2 + f1,
    )


def _matrix_from_functions(functions: list[tuple[str, list[float]]]) -> tuple[float, ...]:
    """把 transform 函数链合成为仿射矩阵 (a, b, c, d, e, f)。"""
    a, b, c, d, e, f = 1.0, 0.0, 0.0, 1.0, 0.0, 0.0
    for name, numbers in functions:
        if name == "translate":
            current = (1.0, 0.0, 0.0, 1.0, numbers[0] if numbers else 0.0, numbers[1] if len(numbers) > 1 else 0.0)
        elif name == "scale":
            sx = numbers[0] if numbers else 1.0
            sy = numbers[1] if len(numbers) > 1 else sx
            current = (sx, 0.0, 0.0, sy, 0.0, 0.0)
        elif name == "rotate":
            angle = math.radians(numbers[0] if numbers else 0.0)
            cos, sin = math.cos(angle), math.sin(angle)
            current = (cos, sin, -sin, cos, 0.0, 0.0)
            if len(numbers) >= 3:
                px, py = numbers[1], numbers[2]
                current = _matrix_multiply(
                    (1.0, 0.0, 0.0, 1.0, px, py),
                    _matrix_multiply(current, (1.0, 0.0, 0.0, 1.0, -px, -py)),
                )
        else:  # matrix（对角）
            current = (numbers[0], numbers[1], numbers[2], numbers[3], numbers[4], numbers[5])
        a, b, c, d, e, f = _matrix_multiply((a, b, c, d, e, f), current)
    return a, b, c, d, e, f


def _iter_path_commands(path_data: str) -> list[tuple[str, list[float]]]:
    tokens = [match.group(1) or match.group(2) for match in PATH_TOKEN.finditer(path_data)]
    commands: list[tuple[str, list[float]]] = []
    index = 0
    while index < len(tokens):
        command = tokens[index]
        if not re.fullmatch(r"[A-Za-z]", command):
            raise UnsupportedLogo(f"路径出现孤立数字 {command}")
        index += 1
        arity = PATH_ARITY[command.upper()]
        if arity == 0:
            commands.append((command, []))
            continue
        if index + arity > len(tokens):
            raise UnsupportedLogo(f"路径 {command} 缺参数")
        commands.append((command, [float(value) for value in tokens[index : index + arity]]))
        index += arity
        # 隐式重复：M 后的多余坐标按 L 处理，m 后按 l，其余命令沿用自身。
        repeat = "L" if command == "M" else ("l" if command == "m" else command)
        while index < len(tokens) and not re.fullmatch(r"[A-Za-z]", tokens[index]):
            if index + arity > len(tokens):
                raise UnsupportedLogo("路径隐式重复缺参数")
            commands.append((repeat, [float(value) for value in tokens[index : index + arity]]))
            index += arity
    return commands


def _transform_path_data(path_data: str, matrix: tuple[float, ...]) -> str:
    """把仿射矩阵烘进路径坐标（相对命令只吃线性部分，不吃平移）。"""
    a, b, c, d, e, f = matrix
    has_rotation = abs(b) > 1e-9 or abs(c) > 1e-9
    scale = math.sqrt(abs(a * d - b * c))
    parts: list[str] = []

    def point(x: float, y: float, relative: bool) -> str:
        nx = a * x + b * y + (0.0 if relative else e)
        ny = c * x + d * y + (0.0 if relative else f)
        return f"{_fmt(nx)} {_fmt(ny)}"

    for command, values in _iter_path_commands(path_data):
        upper = command.upper()
        relative = command.islower()
        if upper == "Z":
            parts.append("Z")
        elif upper == "H":
            if has_rotation:
                raise UnsupportedLogo("旋转变换下水平线无法映射")
            parts.append(command + _fmt(a * values[0] + (0.0 if relative else e)))
        elif upper == "V":
            if has_rotation:
                raise UnsupportedLogo("旋转变换下垂直线无法映射")
            parts.append(command + _fmt(d * values[0] + (0.0 if relative else f)))
        elif upper == "A":
            if has_rotation:
                raise UnsupportedLogo("旋转变换下圆弧无法映射")
            rx, ry, rotation, large, sweep, x, y = values
            parts.append(
                f"{command}{_fmt(rx * scale)} {_fmt(ry * scale)} {_fmt(rotation)}"
                f" {int(large)} {int(sweep)} {point(x, y, relative)}"
            )
        else:
            pairs = [point(values[i], values[i + 1], relative) for i in range(0, len(values), 2)]
            parts.append(command + " ".join(pairs))
    return "".join(parts)


def _stroke_cap(value: str) -> str:
    lowered = value.strip().lower()
    return lowered if lowered in ("butt", "round", "square") else "butt"


def _stroke_join(value: str) -> str:
    lowered = value.strip().lower()
    return lowered if lowered in ("miter", "round", "bevel") else "miter"


def _clip_path_lines(parent: ET.Element) -> dict[str, str]:
    """收集 <defs><clipPath> 声明：id → 已烘入自身 transform 的 pathData。"""
    found: dict[str, str] = {}
    for element in list(parent):
        if _local_name(element.tag) != "defs":
            continue
        for clip in list(element):
            if _local_name(clip.tag) != "clipPath" or not clip.get("id"):
                continue
            for shape in list(clip):
                path_data = _shape_path_data(shape, _local_name(shape.tag))
                if not path_data:
                    continue
                try:
                    matrix = _matrix_from_functions(_parse_transform_functions(shape.get("transform")))
                    baked = _transform_path_data(path_data, matrix)
                    if len(baked) <= MAX_PATH_DATA_CHARS:
                        found[clip.get("id")] = baked
                except UnsupportedLogo:
                    # 裁剪图形无法烘入时放弃该 clip；缺裁剪只是轻微溢出，不影响可用性。
                    pass
                break
    return found


def svg_to_vector_drawable(svg: str) -> str:
    root = ET.fromstring(svg)
    if _local_name(root.tag) != "svg":
        raise UnsupportedLogo("根元素不是 svg")

    view_box = [part for part in re.split(r"[ ,]+", (root.get("viewBox") or "").strip()) if part]
    min_x = min_y = 0.0
    if len(view_box) >= 4:
        min_x, min_y = _number(view_box[0]), _number(view_box[1])
        width, height = _number(view_box[2]), _number(view_box[3])
    else:
        width, height = _number(root.get("width"), 24.0), _number(root.get("height"), 24.0)
    if width <= 0 or height <= 0:
        raise UnsupportedLogo("viewBox 无效")

    root_style = _Style(
        fill=root.get("fill") or "currentColor",
        stroke=root.get("stroke") or "",
        stroke_width=_number(root.get("stroke-width"), 1.0),
        fill_rule=root.get("fill-rule") or "",
        line_cap=_stroke_cap(root.get("stroke-linecap") or ""),
        line_join=_stroke_join(root.get("stroke-linejoin") or ""),
        fill_alpha=1.0,
        stroke_alpha=1.0,
    )
    body: list[str] = []
    _emit_children(root, "    ", root_style, body)
    if not body:
        raise UnsupportedLogo("没有可绘制的图元")
    # VectorDrawable 没有 viewBox 原点概念：非零原点时用平移 group 把内容挪回左下角。
    if abs(min_x) > 1e-6 or abs(min_y) > 1e-6:
        body = (
            [f'    <group android:translateX="{_fmt(-min_x)}" android:translateY="{_fmt(-min_y)}">']
            + ["    " + line for line in body]
            + ["    </group>"]
        )

    header = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<!-- 由 scripts/update-models-dev-catalog.py 生成，请勿手改。 -->',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="24dp"',
        '    android:height="24dp"',
        f'    android:viewportWidth="{_fmt(width)}"',
        f'    android:viewportHeight="{_fmt(height)}">',
    ]
    return "\n".join(header + body + ["</vector>", ""])


def _emit_children(parent: ET.Element, indent: str, style: _Style, out: list[str]) -> None:
    clips = _clip_path_lines(parent)
    for child in list(parent):
        tag = _local_name(child.tag)
        if tag in CONTAINER_TAGS:
            continue
        _emit_element(child, tag, indent, style, out, clips)


def _emit_element(
    child: ET.Element,
    tag: str,
    indent: str,
    parent_style: _Style,
    out: list[str],
    clips: dict[str, str],
) -> None:
    if tag in SKIP_TAGS:
        raise UnsupportedLogo(f"含不支持的元素 <{tag}>")
    for attribute in SKIP_ATTRIBUTES:
        if child.get(attribute):
            raise UnsupportedLogo(f"含不支持的属性 {attribute}")
    style_attribute = child.get("style")
    if style_attribute and not _style_attribute_supported(style_attribute):
        raise UnsupportedLogo("style 属性声明了着色")

    style = _resolve_style(child, parent_style)
    normalized_fill = style.fill.strip().lower()
    normalized_stroke = style.stroke.strip().lower()
    if normalized_fill not in ACCEPTED_FILLS | {"none"}:
        raise UnsupportedLogo(f"含非常规填充色 {style.fill}")
    if normalized_stroke not in ACCEPTED_FILLS | {"", "none"}:
        raise UnsupportedLogo(f"含非常规描边色 {style.stroke}")

    groups = _group_attributes(child.get("transform"))

    clip_ref = child.get("clip-path") or child.get("clipPath")
    clip = None
    if clip_ref:
        match = re.search(r"url\(#([^)]+)\)", clip_ref)
        if match and match.group(1) in clips:
            clip = clips[match.group(1)]

    if tag == "g":
        # clip-path 必须与它要裁剪的图元同级、且排在前面；无 transform 时补一层空 group。
        opens = list(groups)
        if clip is not None and not opens:
            opens = [""]
        children_indent = indent + "    " * len(opens)
        inner: list[str] = []
        _emit_children(child, children_indent, style, inner)
        if not inner and clip is None:
            return

        current = indent
        for group_attributes in opens:
            suffix = f" {group_attributes}" if group_attributes else ""
            out.append(f"{current}<group{suffix}>")
            current += "    "
        if clip is not None:
            out.append(f'{current}<clip-path android:pathData="{_escape(clip)}" />')
        out.extend(inner)

        closing = current
        for _ in opens:
            closing = closing[:-4]
            out.append(f"{closing}</group>")
        return

    if tag not in DRAWN_TAGS:
        return

    path_data = _shape_path_data(child, tag)
    if not path_data:
        return

    has_stroke = normalized_stroke not in ("", "none")
    if normalized_fill == "none" and not has_stroke:
        return

    attributes: list[str] = []
    if normalized_fill != "none":
        attributes.append('android:fillColor="#FF000000"')
        if style.fill_rule.strip().lower() == "evenodd":
            attributes.append('android:fillType="evenOdd"')
        if style.fill_alpha < 0.999:
            attributes.append(f'android:fillAlpha="{_fmt(style.fill_alpha)}"')
    if has_stroke:
        attributes.append('android:strokeColor="#FF000000"')
        attributes.append(f'android:strokeWidth="{_fmt(style.stroke_width)}"')
        attributes.append(f'android:strokeLineCap="{_stroke_cap(style.line_cap)}"')
        attributes.append(f'android:strokeLineJoin="{_stroke_join(style.line_join)}"')
        if style.stroke_alpha < 0.999:
            attributes.append(f'android:strokeAlpha="{_fmt(style.stroke_alpha)}"')
    suffix = (" " + " ".join(attributes)) if attributes else ""

    current = indent
    for group_attributes in groups:
        out.append(f'{current}<group {group_attributes}>')
        current += "    "
    for chunk in _split_path_data(path_data):
        out.append(f'{current}<path android:pathData="{_escape(chunk)}"{suffix} />')
    for _ in groups:
        current = current[:-4]
        out.append(f"{current}</group>")


def _split_path_data(path_data: str) -> list[str]:
    """pathData 超过 VectorDrawable 字符串上限时，按子路径边界切成多条 <path>。

    aapt2 对超长字符串会用字面量 STRING_TOO_LARGE 顶替，运行期解析必然崩溃；
    按子路径（M 起点）切分可保持原始字节与子路径完整性。
    """
    if len(path_data) <= MAX_PATH_DATA_CHARS:
        return [path_data]
    starts = [match.start() for match in PATH_TOKEN.finditer(path_data) if match.group(1) in ("M", "m")]
    if not starts:
        raise UnsupportedLogo("路径过长且无子路径可切分")
    starts.append(len(path_data))
    chunks: list[str] = []
    begin = starts[0]
    for end in starts[1:]:
        piece = path_data[begin:end]
        if len(piece) > MAX_PATH_DATA_CHARS:
            raise UnsupportedLogo("单个子路径仍然超过字符串上限")
        if chunks and len(chunks[-1]) + len(piece) > MAX_PATH_DATA_CHARS:
            chunks.append(piece)
        elif chunks:
            chunks[-1] += piece
        else:
            chunks.append(piece)
        begin = end
    head = path_data[: starts[0]].strip()
    if head:
        chunks.insert(0, head)
    return chunks


# --- 落盘 ---------------------------------------------------------------------


def _resource_name(provider_id: str) -> str:
    return LOGO_RES_PREFIX + re.sub(r"[^a-z0-9_]", "_", provider_id)


def _kotlin_map(provider_ids: list[str]) -> str:
    lines = [
        "// 由 scripts/update-models-dev-catalog.py 生成，请勿手改。",
        "package io.github.mangi.eta.ui.components",
        "",
        "import androidx.annotation.DrawableRes",
        "import io.github.mangi.eta.R",
        "",
        "/** models.dev 社区目录的提供商图标（单调色 VectorDrawable，渲染时按主题 tint 上色）。 */",
        "@DrawableRes",
        "internal fun catalogLogoRes(providerId: String): Int? = when (providerId) {",
    ]
    lines += [f'    "{pid}" -> R.drawable.{_resource_name(pid)}' for pid in provider_ids]
    lines += ["    else -> null", "}", ""]
    return "\n".join(lines)


def sync_logos(catalog: dict) -> None:
    candidates = catalog_provider_ids(catalog)
    print(f"图标同步：候选提供商 {len(candidates)} 个")

    converted: dict[str, str] = {}
    skipped: list[tuple[str, str]] = []
    for provider_id in candidates:
        try:
            svg = fetch_logo(provider_id)
            if svg is None:
                skipped.append((provider_id, "站点未提供图标"))
                continue
            converted[provider_id] = svg_to_vector_drawable(svg)
        except UnsupportedLogo as reason:
            skipped.append((provider_id, str(reason)))
        except ET.ParseError as reason:
            skipped.append((provider_id, f"SVG 解析失败：{reason}"))

    written = 0
    for provider_id, xml in converted.items():
        path = DRAWABLE_DIR / f"{_resource_name(provider_id)}.xml"
        if write_if_changed(path, xml.encode("utf-8")):
            written += 1

    removed = 0
    keep = {f"{_resource_name(pid)}.xml" for pid in converted}
    if DRAWABLE_DIR.is_dir():
        for stale in sorted(DRAWABLE_DIR.glob(f"{LOGO_RES_PREFIX}*.xml")):
            if stale.name not in keep:
                stale.unlink()
                removed += 1

    write_if_changed(KOTLIN_MAP, _kotlin_map(sorted(converted)).encode("utf-8"))

    print(f"图标同步完成：生成 {len(converted)} 个 VectorDrawable（新增/更新 {written}，清理 {removed}）")
    if skipped:
        print(f"跳过 {len(skipped)} 个（App 侧回退通用图标）：")
        for provider_id, reason in skipped:
            print(f"  - {provider_id}: {reason}")


def main() -> None:
    data = download_catalog()
    compressed = gzip_snapshot(data)
    if SNAPSHOT.exists() and SNAPSHOT.read_bytes() == compressed:
        print(f"目录未变化：{SNAPSHOT} ({len(compressed)} 字节)")
    else:
        write_if_changed(SNAPSHOT, compressed)
        digest = hashlib.sha256(data).hexdigest()
        print(f"已更新 {SNAPSHOT}：原始 {len(data)} 字节，gzip {len(compressed)} 字节，SHA-256 {digest}")

    sync_logos(json.loads(data.decode("utf-8")))


if __name__ == "__main__":
    main()
