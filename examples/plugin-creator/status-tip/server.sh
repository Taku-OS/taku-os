#!/system/bin/sh
while IFS= read -r line; do
 id=$(printf '%s' "$line" | sed -n 's/.*"id"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p')
 [ -n "$id" ] || continue
 case "$line" in
  *'"initialize"'*) printf '{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":"2024-11-05","capabilities":{"tools":{}},"serverInfo":{"name":"status-tip","version":"1.0.0"}}}\n' "$id" ;;
  *'"tools/list"'*) printf '{"jsonrpc":"2.0","id":%s,"result":{"tools":[{"name":"status_tip","description":"Show a short break reminder","inputSchema":{"type":"object","properties":{}}}]}}\n' "$id" ;;
  *'"tools/call"'*) printf '{"jsonrpc":"2.0","id":%s,"result":{"content":[{"type":"text","text":"Time for a short break."}]}}\n' "$id" ;;
  *) printf '{"jsonrpc":"2.0","id":%s,"result":{}}\n' "$id" ;;
 esac
done
