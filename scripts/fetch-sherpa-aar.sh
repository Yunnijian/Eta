#!/usr/bin/env bash
# 按 SHA-256 拉取 sherpa-onnx（静态链接 onnxruntime）AAR 到 app/libs/，供本地离线识别使用。
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION=1.13.8
SHA256=b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VERSION}/sherpa-onnx-static-link-onnxruntime-${VERSION}.aar"
mkdir -p app/libs
TARGET="app/libs/sherpa-onnx-${VERSION}.aar"
if [ -f "$TARGET" ] && echo "$SHA256  $TARGET" | shasum -a 256 -c - >/dev/null 2>&1; then
  echo "sherpa-onnx AAR 已存在且校验通过"
  exit 0
fi
curl -fL --retry 3 -o "$TARGET" "$URL"
echo "$SHA256  $TARGET" | shasum -a 256 -c -
