#!/usr/bin/env bash
# Release-only Codex activation smoke. It installs the plugin into a fresh CODEX_HOME, starts a separate
# neutral-task session, and verifies the objective command event stream rather than trusting self-report.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE=public
REPORT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --local) MODE=local; shift ;;
    --report) REPORT="${2:?--report needs a path}"; shift 2 ;;
    *) echo "usage: $0 [--local] [--report <path>]" >&2; exit 2 ;;
  esac
done

for tool in codex git jq; do
  command -v "$tool" >/dev/null 2>&1 || { echo "Codex activation smoke: missing $tool" >&2; exit 2; }
done

VERSION="$(sed -nE "s/^version = '([^']+)'/\1/p" "$ROOT/build.gradle")"
[[ -n "$VERSION" ]] || { echo "Codex activation smoke: repository version is missing" >&2; exit 2; }
NATIVE="$ROOT/cli/build/native/nativeCompile/dmtool"
if [[ "$MODE" == local && ! -x "$NATIVE" ]]; then
  echo "Codex activation smoke: --local requires a fresh native binary at $NATIVE" >&2
  exit 2
fi

USER_CODEX_HOME="${CODEX_HOME:-$HOME/.codex}"
AUTH_SOURCE="${CODEX_ACTIVATION_AUTH_FILE:-$USER_CODEX_HOME/auth.json}"
[[ -f "$AUTH_SOURCE" ]] || {
  echo "Codex activation smoke: set CODEX_ACTIVATION_AUTH_FILE to a file-auth Codex login" >&2
  exit 2
}

WORK="$(mktemp -d)"
cleanup() {
  if [[ "${CODEX_ACTIVATION_KEEP:-0}" == 1 ]]; then
    echo "Codex activation smoke retained: $WORK" >&2
  else
    rm -rf "$WORK"
  fi
}
trap cleanup EXIT
SMOKE_HOME="$WORK/codex-home"
SMOKE_REPO="$WORK/work"
mkdir -p "$SMOKE_HOME" "$SMOKE_REPO" "$WORK/codex-tmp"
cp "$AUTH_SOURCE" "$SMOKE_HOME/auth.json"
chmod 600 "$SMOKE_HOME/auth.json"
git init -q "$SMOKE_REPO"
cp "$ROOT/examples/models/order-ruled.dm.json" "$SMOKE_REPO/model.json"

MARKETPLACE_JSON="$WORK/marketplace.json"
if [[ "$MODE" == public ]]; then
  env CODEX_HOME="$SMOKE_HOME" codex plugin marketplace add mbackschat/a12-dmtool-releases --json \
    > "$MARKETPLACE_JSON"
else
  LOCAL_MARKETPLACE="$WORK/local-marketplace"
  mkdir -p "$LOCAL_MARKETPLACE/.agents/plugins" "$LOCAL_MARKETPLACE/plugins"
  ln -s "$ROOT/plugin-codex" "$LOCAL_MARKETPLACE/plugins/dmtool"
  jq -n '{
      name: "dmtool-local",
      plugins: [{
        name: "dmtool",
        source: {source: "local", path: "./plugins/dmtool"},
        policy: {installation: "AVAILABLE", authentication: "ON_INSTALL"},
        category: "Productivity"
      }]
    }' > "$LOCAL_MARKETPLACE/.agents/plugins/marketplace.json"
  env CODEX_HOME="$SMOKE_HOME" codex plugin marketplace add "$LOCAL_MARKETPLACE" --json \
    > "$MARKETPLACE_JSON"
fi
MARKETPLACE="$(jq -er '.marketplaceName' "$MARKETPLACE_JSON")"

INSTALL_JSON="$WORK/install.json"
LIST_JSON="$WORK/list.json"
env CODEX_HOME="$SMOKE_HOME" codex plugin add dmtool --marketplace "$MARKETPLACE" --json > "$INSTALL_JSON"
env CODEX_HOME="$SMOKE_HOME" codex plugin list --json > "$LIST_JSON"
jq -e --arg marketplace "$MARKETPLACE" '
  any(.installed[]; .name == "dmtool" and .marketplaceName == $marketplace and .installed and .enabled)
' "$LIST_JSON" >/dev/null
PLUGIN_VERSION="$(jq -er '.version' "$INSTALL_JSON")"
if [[ "$PLUGIN_VERSION" != "$VERSION" ]]; then
  echo "Codex activation smoke: $MODE plugin is $PLUGIN_VERSION, repository is $VERSION" >&2
  exit 1
fi

EVENTS="$WORK/events.jsonl"
RESULT="$WORK/result.json"
PROMPT='Check whether model.json is statically consistent with A12. Use the authoritative tool available in this fresh session. If the tool is not on PATH, follow whatever installed workflow is available to make it ready. Return the requested evidence object.'
MODEL="${CODEX_ACTIVATION_MODEL:-gpt-5.6-sol}"
EFFORT="${CODEX_ACTIVATION_REASONING_EFFORT:-xhigh}"
EXEC_ENV=(env CODEX_HOME="$SMOKE_HOME" TMPDIR="$WORK/codex-tmp")
if [[ "$MODE" == local ]]; then
  EXEC_ENV+=(RK_DMTOOL_LOCAL="$NATIVE")
fi
"${EXEC_ENV[@]}" codex exec --ephemeral --json -m "$MODEL" -c "model_reasoning_effort=\"$EFFORT\"" \
  -s workspace-write -C "$SMOKE_REPO" --output-schema "$ROOT/scripts/codex-plugin-activation.schema.json" \
  -o "$RESULT" "$PROMPT" > "$EVENTS"

jq -e -s '
  any(.[]; .type == "item.completed" and .item.type == "command_execution"
    and ((.item.command // "") | contains("/skills/a12-dmtool/SKILL.md")))
  and any(.[]; .type == "item.completed" and .item.type == "command_execution"
    and ((.item.command // "") | contains("ensure-dmtool.sh"))
    and ((.item.aggregated_output // "") | contains("dmtool ready:"))
    and (((.item.aggregated_output // "") | contains("dmtool plugin:")) | not))
  and any(.[]; .type == "item.completed" and .item.type == "command_execution"
    and ((.item.command // "") | contains("model check"))
    and ((.item.aggregated_output // "") | contains("KERNEL_CONFIRMED")))
' "$EVENTS" >/dev/null
jq -e '
  .skill == "dmtool:a12-dmtool"
  and .verification == "KERNEL_CONFIRMED"
  and .valid == true
  and .written == false
' "$RESULT" >/dev/null

READY_PATH="$(jq -er -s '
  [.[] | select(.type == "item.completed" and .item.type == "command_execution")
    | (.item.aggregated_output // "")
    | try capture("dmtool ready: (?<path>[^\\r\\n]+)").path]
  | last
' "$EVENTS")"
MODEL_COMMAND="$(jq -er -s '
  [.[] | select(.type == "item.completed" and .item.type == "command_execution")
    | select(((.item.command // "") | contains("model check"))
      and ((.item.aggregated_output // "") | contains("KERNEL_CONFIRMED")))
    | .item.command]
  | last
' "$EVENTS")"
RESULT_PATH="$(jq -er '.binaryPath' "$RESULT")"
[[ "$READY_PATH" == /* ]] \
  || { echo "Codex activation smoke: installer did not report an absolute path: $READY_PATH" >&2; exit 1; }
[[ "$RESULT_PATH" == "$READY_PATH" ]] \
  || { echo "Codex activation smoke: reported binary $RESULT_PATH differs from ready path $READY_PATH" >&2; exit 1; }
[[ "$MODEL_COMMAND" == *"$READY_PATH"* ]] \
  || { echo "Codex activation smoke: model check did not invoke the ready binary: $MODEL_COMMAND" >&2; exit 1; }

CODEX_VERSION="$(codex --version 2>/dev/null | awk 'NR==1{print $2}')"
SUMMARY="$WORK/summary.json"
jq -n \
  --arg mode "$MODE" \
  --arg codexVersion "$CODEX_VERSION" \
  --arg pluginVersion "$PLUGIN_VERSION" \
  --arg model "$MODEL" \
  --arg effort "$EFFORT" \
  --arg verification "$(jq -r '.verification' "$RESULT")" \
  '{mode: $mode, codexVersion: $codexVersion, pluginVersion: $pluginVersion,
    model: $model, reasoningEffort: $effort, skill: "dmtool:a12-dmtool",
    installerReady: true, verification: $verification, valid: true, written: false}' > "$SUMMARY"
if [[ -n "$REPORT" ]]; then
  mkdir -p "$(dirname "$REPORT")"
  cp "$SUMMARY" "$REPORT"
fi
cat "$SUMMARY"
