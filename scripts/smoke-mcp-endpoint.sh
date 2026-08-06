#!/usr/bin/env bash
# Prove a running MCP endpoint actually serves — locally or after a deploy.
#
# Why this exists: a deployment that answers "connection refused" and one that answers 200-but-wrong both
# look like "it started" in a CI log. This drives the real protocol over the real URL and checks the answers,
# so the pipeline fails on a broken endpoint rather than on nobody looking.
#
#   ./scripts/smoke-mcp-endpoint.sh https://example.alwaysdata.net/mcp
#
# It is deliberately read-only: discovery, the tool surface, and a `model check` on a model it sends inline.
# Nothing it does can write anything anywhere — the server holds no state and this sends no edit.
set -euo pipefail

ENDPOINT="${1:?usage: smoke-mcp-endpoint.sh <url of the /mcp endpoint>}"
REVISION="2026-07-28"
META="\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"$REVISION\",\"io.modelcontextprotocol/clientCapabilities\":{}}"

fail() { echo "SMOKE FAILED: $*" >&2; exit 1; }

# One POST, with the mirrored headers this revision requires. $3 is the Mcp-Name, empty when not applicable.
post() {
  local method="$1" body="$2" name="${3:-}"
  local args=(-sS --fail-with-body -X POST "$ENDPOINT"
    -H 'Content-Type: application/json'
    -H "MCP-Protocol-Version: $REVISION"
    -H "Mcp-Method: $method")
  [[ -n "$name" ]] && args+=(-H "Mcp-Name: $name")
  curl "${args[@]}" --data "$body"
}

echo "==> 1/4 server/discover — the revision served, and that it is this server"
discover="$(post server/discover "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\",\"params\":{$META}}")" \
  || fail "the endpoint did not answer server/discover"
echo "$discover" | grep -q "\"$REVISION\"" || fail "server/discover does not advertise $REVISION: $discover"
echo "$discover" | grep -q '"name":"dmtool"' || fail "the endpoint is not dmtool: $discover"

echo "==> 2/4 tools/list — the derived surface arrives"
tools="$(post tools/list "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{$META}}")" \
  || fail "tools/list did not answer"
echo "$tools" | grep -q '"dm.model.read"' || fail "the tool surface is missing dm.model.read"

echo "==> 3/4 tools/call — a model carried in, a verdict back"
model='{"header":{"id":"smoke","modelVersion":"28.4.0","locales":["en_US"]},"rootGroup":{"name":"Smoke","groups":[],"fields":[]}}'
call="$(post tools/call "$(printf '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"dm.model.read","arguments":{"op":"check","model":%s},%s}}' \
  "$(printf '%s' "$model" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))')" "$META")" dm.model.read)" \
  || fail "tools/call did not answer"
echo "$call" | grep -q '"structuredContent"' || fail "the call returned no envelope: $call"

echo "==> 4/4 the transport's own refusals still fire"
# A header that disagrees with the body must be refused; an endpoint that accepts it is not conformant, and
# an intermediary routing on that header would be routing on something the server ignored.
mismatch="$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$ENDPOINT" \
  -H 'Content-Type: application/json' -H "MCP-Protocol-Version: $REVISION" -H 'Mcp-Method: tools/list' \
  --data "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"server/discover\",\"params\":{$META}}")"
[[ "$mismatch" == "400" ]] || fail "a header/body mismatch returned $mismatch, expected 400"

echo "OK — $ENDPOINT serves MCP $REVISION"
