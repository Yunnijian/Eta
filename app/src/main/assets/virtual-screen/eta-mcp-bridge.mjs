// Eta 虚拟屏 stdio MCP 桥。由 Eta 自动写入 Linux 工作区，宿主只需启动本脚本。
import readline from "node:readline";

const BASE_URL = (process.env.ETA_MCP_URL || "http://127.0.0.1:18765").replace(/\/+$/, "");
const AUTH = process.env.ETA_MCP_AUTH || "";
const FALLBACK_PROTOCOL = "2024-11-05";
const SERVER_INFO = { name: "eta-virtual-screen", version: "1.0.0" };
const INSTRUCTIONS = [
  "Eta built-in virtual screen. Create it with virtual_display, then use virtual_observe, virtual_tap, virtual_swipe and virtual_input.",
  "Coordinates are virtual display pixels and must never be reused with main-screen tap/swipe.",
  "When the user asks to see the virtual screen, call virtual_observe with mode=screenshot or both.",
].join(" ");

let toolsPromise = null;

async function request(path, options = {}) {
  const response = await fetch(`${BASE_URL}${path}`, {
    ...options,
    headers: {
      Authorization: `Bearer ${AUTH}`,
      "Content-Type": "application/json",
      ...(options.headers || {}),
    },
  });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`ETA_HTTP_${response.status}: ${text.slice(0, 300)}`);
  }
  return JSON.parse(text);
}

function loadTools() {
  if (!toolsPromise) {
    toolsPromise = request("/tools").then((payload) => {
      const tools = Array.isArray(payload.tools) ? payload.tools : [];
      return tools
        .map((entry) => entry && entry.function)
        .filter((fn) => fn && typeof fn.name === "string")
        .map((fn) => ({
          name: fn.name,
          description: fn.description || "",
          inputSchema: fn.parameters || { type: "object", properties: {} },
        }));
    });
  }
  return toolsPromise;
}

async function callTool(name, args) {
  const payload = await request("/tool", {
    method: "POST",
    body: JSON.stringify({ name, arguments: args && typeof args === "object" ? args : {} }),
  });
  const content = [];
  if (typeof payload.content === "string" && payload.content.length > 0) {
    content.push({ type: "text", text: payload.content });
  }
  if (Array.isArray(payload.images)) {
    for (const image of payload.images) {
      if (image && typeof image.data === "string" && image.data.length > 0) {
        content.push({
          type: "image",
          data: image.data,
          mimeType: image.mimeType || "image/jpeg",
        });
      }
    }
  }
  if (content.length === 0) {
    content.push({ type: "text", text: "{}" });
  }
  return { content, isError: payload.isError === true };
}

function write(message) {
  process.stdout.write(`${JSON.stringify(message)}\n`);
}

function reply(id, result) {
  if (id === undefined || id === null) return;
  write({ jsonrpc: "2.0", id, result });
}

function replyError(id, code, message) {
  if (id === undefined || id === null) return;
  write({ jsonrpc: "2.0", id, error: { code, message } });
}

async function handle(message) {
  if (!message || message.jsonrpc !== "2.0") return;
  const { id, method, params } = message;
  try {
    switch (method) {
      case "initialize":
        reply(id, {
          protocolVersion:
            params && typeof params.protocolVersion === "string"
              ? params.protocolVersion
              : FALLBACK_PROTOCOL,
          capabilities: { tools: { listChanged: false } },
          serverInfo: SERVER_INFO,
          instructions: INSTRUCTIONS,
        });
        return;
      case "notifications/initialized":
      case "notifications/cancelled":
        return;
      case "ping":
        reply(id, {});
        return;
      case "tools/list":
        reply(id, { tools: await loadTools() });
        return;
      case "tools/call": {
        const name = params && params.name;
        if (typeof name !== "string" || name.length === 0) {
          replyError(id, -32602, "tools/call requires a tool name");
          return;
        }
        try {
          reply(id, await callTool(name, params.arguments));
        } catch (error) {
          reply(id, {
            content: [{ type: "text", text: String(error && error.message ? error.message : error) }],
            isError: true,
          });
        }
        return;
      }
      case "resources/list":
        reply(id, { resources: [] });
        return;
      case "prompts/list":
        reply(id, { prompts: [] });
        return;
      case "logging/setLevel":
        reply(id, {});
        return;
      default:
        if (id !== undefined && id !== null) replyError(id, -32601, `Unsupported method: ${method}`);
    }
  } catch (error) {
    replyError(id, -32603, String(error && error.message ? error.message : error));
  }
}

const input = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
input.on("line", (line) => {
  const trimmed = line.trim();
  if (!trimmed) return;
  let parsed;
  try {
    parsed = JSON.parse(trimmed);
  } catch (error) {
    process.stderr.write(`eta-mcp-bridge: invalid json: ${error.message}\n`);
    return;
  }
  if (Array.isArray(parsed)) {
    for (const item of parsed) void handle(item);
  } else {
    void handle(parsed);
  }
});
input.on("close", () => process.exit(0));
