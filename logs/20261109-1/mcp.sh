#!/bin/bash
# MCP helper: ./mcp.sh <tool> '<json args>'
# 端点与 token 一律从本地 .pi/mcp.json 读取（该文件已 gitignore，不入库）。
CFG="D:/android-workspace/AndroidStudioProjects/ManagedFloatingApp/.pi/mcp.json"
HOST=$(python -c "import json;print(json.load(open(r'$CFG'))['mcpServers']['ManagedFloatingApp']['url'].rsplit('/mcp',1)[0])")
TOK=$(python -c "import json;print(json.load(open(r'$CFG'))['mcpServers']['ManagedFloatingApp']['headers']['Authorization'])")
curl -s -m 60 -X POST "$HOST/mcp" -H 'Content-Type: application/json' -H "Authorization: $TOK" \
  -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"$1\",\"arguments\":${2:-{\}}}}"
