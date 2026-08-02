#!/usr/bin/env bash
# Release-only Codex activation smoke. It installs the plugin into a fresh CODEX_HOME, starts a separate
# neutral-task session, and verifies the objective command event stream rather than trusting self-report.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE=public
REPORT=""
SELF_TEST=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --local) MODE=local; shift ;;
    --report) REPORT="${2:?--report needs a path}"; shift 2 ;;
    --self-test) SELF_TEST=1; shift ;;
    *) echo "usage: $0 [--local] [--report <path>] [--self-test]" >&2; exit 2 ;;
  esac
done

verify_activation_evidence() {
  local events="$1" result="$2"
  local ready_path model_event model_command command_suffix command_pattern logical_command envelope

  jq -e -s '
    any(.[]; .type == "item.completed" and .item.type == "command_execution"
      and .item.exit_code == 0
      and ((.item.command // "") | contains("/skills/a12-dmtool/SKILL.md")))
  ' "$events" >/dev/null || {
    echo "Codex activation smoke: no successful command read the selected skill" >&2
    return 1
  }

  ready_path="$(jq -er -s '
    [.[] | select(.type == "item.completed" and .item.type == "command_execution"
      and .item.exit_code == 0
      and ((.item.command // "") | contains("ensure-dmtool.sh"))
      and ((.item.aggregated_output // "") | contains("dmtool ready:"))
      and (((.item.aggregated_output // "") | contains("dmtool plugin:")) | not))
      | (.item.aggregated_output // "")
      | capture("dmtool ready: (?<path>[^\\r\\n]+)").path]
    | if length == 1 then .[0] else error("expected exactly one successful installer event") end
  ' "$events")" || return 1
  [[ "$ready_path" == /* ]] || {
    echo "Codex activation smoke: installer did not report an absolute path: $ready_path" >&2
    return 1
  }

  model_event="$(jq -cer -s --arg ready "$ready_path" '
    [.[] | select(.type == "item.completed" and .item.type == "command_execution"
      and .item.exit_code == 0
      and ((.item.command // "") | contains($ready)))
      | .item as $item
      | (($item.aggregated_output // "") | fromjson?) as $envelope
      | select($envelope.target == "model"
          and $envelope.op == "check"
          and $envelope.ok == true
          and $envelope.valid == true
          and $envelope.verification == "KERNEL_CONFIRMED"
          and $envelope.written == false)
      | {command: $item.command, envelope: $envelope}]
    | if length == 1 then .[0] else error("expected exactly one successful model-check envelope") end
  ' "$events")" || return 1
  model_command="$(jq -er '.command' <<< "$model_event")"
  command_suffix="${model_command##*"$ready_path"}"
  command_pattern="^['\"]?[[:space:]]+-m[[:space:]]+model\\.json[[:space:]]+model[[:space:]]+check['\"]?$"
  [[ "$command_suffix" =~ $command_pattern ]] || {
    echo "Codex activation smoke: model check was wrapped, chained, redirected, or used different input: $model_command" >&2
    return 1
  }

  logical_command="$ready_path -m model.json model check"
  envelope="$(jq -c '.envelope' <<< "$model_event")"
  jq -e \
    --arg ready "$ready_path" \
    --arg command "$logical_command" \
    --argjson envelope "$envelope" '
      .skill == "dmtool:a12-dmtool"
      and .binaryPath == $ready
      and .command == $command
      and .verification == $envelope.verification
      and .valid == $envelope.valid
      and .written == $envelope.written
    ' "$result" >/dev/null || {
      echo "Codex activation smoke: final evidence object does not reconcile with the executed envelope" >&2
      return 1
    }

  EVIDENCE_READY_PATH="$ready_path"
  EVIDENCE_LOGICAL_COMMAND="$logical_command"
  EVIDENCE_ENVELOPE="$envelope"
}

self_test_evidence() (
  local work events result ready envelope good_command
  work="$(mktemp -d)"
  trap 'rm -rf "$work"' EXIT
  events="$work/events.jsonl"
  result="$work/result.json"
  ready="$work/dmtool"
  envelope='{"target":"model","op":"check","outcome":"read","ok":true,"valid":true,"verification":"KERNEL_CONFIRMED","diagnostics":[],"written":false}'
  good_command="/bin/zsh -lc '$ready -m model.json model check'"

  jq -nc '{type:"item.completed",item:{type:"command_execution",command:"cat /tmp/plugins/dmtool/skills/a12-dmtool/SKILL.md",aggregated_output:"",exit_code:0}}' > "$events"
  jq -nc --arg ready "$ready" '{type:"item.completed",item:{type:"command_execution",command:"bash /tmp/plugins/dmtool/skills/a12-dmtool/ensure-dmtool.sh",aggregated_output:("dmtool ready: " + $ready + "\n"),exit_code:0}}' >> "$events"
  jq -nc --arg command "$good_command" --arg output "$envelope" '{type:"item.completed",item:{type:"command_execution",command:$command,aggregated_output:$output,exit_code:0}}' >> "$events"
  jq -n --arg ready "$ready" --arg command "$ready -m model.json model check" '{skill:"dmtool:a12-dmtool",binaryPath:$ready,command:$command,verification:"KERNEL_CONFIRMED",valid:true,written:false}' > "$result"
  verify_activation_evidence "$events" "$result" || return 1

  # The pre-fix predicates accepted this: the failed command is hidden behind `||`, then expected text is
  # synthesized. It must fail even though the event exits zero and its output looks like the desired envelope.
  jq -nc --arg command "$ready -m missing.json model check || printf synthetic" --arg output "$envelope" '{type:"item.completed",item:{type:"command_execution",command:$command,aggregated_output:$output,exit_code:0}}' > "$work/malicious.jsonl"
  head -n 2 "$events" > "$work/prefix.jsonl"
  cat "$work/malicious.jsonl" >> "$work/prefix.jsonl"
  if verify_activation_evidence "$work/prefix.jsonl" "$result" >/dev/null 2>&1; then
    echo "Codex activation smoke self-test: chained synthetic evidence survived" >&2
    return 1
  fi

  jq -nc --arg command "$good_command" '{type:"item.completed",item:{type:"command_execution",command:$command,aggregated_output:"KERNEL_CONFIRMED",exit_code:0}}' > "$work/non-json.jsonl"
  head -n 2 "$events" > "$work/prefix.jsonl"
  cat "$work/non-json.jsonl" >> "$work/prefix.jsonl"
  if verify_activation_evidence "$work/prefix.jsonl" "$result" >/dev/null 2>&1; then
    echo "Codex activation smoke self-test: non-JSON success text survived" >&2
    return 1
  fi
  echo "Codex activation smoke self-test: discriminator fires"
)

if [[ "$SELF_TEST" -eq 1 ]]; then
  command -v jq >/dev/null 2>&1 || { echo "Codex activation smoke self-test: missing jq" >&2; exit 2; }
  self_test_evidence
  exit 0
fi

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
PROMPT="Check whether model.json is statically consistent with A12. Use the authoritative tool available in this fresh session. If the tool is not on PATH, follow whatever installed workflow is available to make it ready. After the installer prints its absolute path, run that path as its own command with exactly these arguments: -m model.json model check. Do not pipe, redirect, wrap, or chain the model-check command. Return the requested evidence object from that command's JSON envelope; command is the unwrapped absolute-path command."
MODEL="${CODEX_ACTIVATION_MODEL:-gpt-5.6-sol}"
EFFORT="${CODEX_ACTIVATION_REASONING_EFFORT:-xhigh}"
EXEC_ENV=(env CODEX_HOME="$SMOKE_HOME" TMPDIR="$WORK/codex-tmp")
if [[ "$MODE" == local ]]; then
  EXEC_ENV+=(RK_DMTOOL_LOCAL="$NATIVE")
fi
"${EXEC_ENV[@]}" codex exec --ephemeral --json -m "$MODEL" -c "model_reasoning_effort=\"$EFFORT\"" \
  -s workspace-write -C "$SMOKE_REPO" --output-schema "$ROOT/scripts/codex-plugin-activation.schema.json" \
  -o "$RESULT" "$PROMPT" > "$EVENTS"

verify_activation_evidence "$EVENTS" "$RESULT"

CODEX_VERSION="$(codex --version 2>/dev/null | awk 'NR==1{print $2}')"
SUMMARY="$WORK/summary.json"
jq -n \
  --arg mode "$MODE" \
  --arg codexVersion "$CODEX_VERSION" \
  --arg pluginVersion "$PLUGIN_VERSION" \
  --arg model "$MODEL" \
  --arg effort "$EFFORT" \
  --arg verification "$(jq -r '.verification' <<< "$EVIDENCE_ENVELOPE")" \
  --argjson valid "$(jq '.valid' <<< "$EVIDENCE_ENVELOPE")" \
  --argjson written "$(jq '.written' <<< "$EVIDENCE_ENVELOPE")" \
  '{mode: $mode, codexVersion: $codexVersion, pluginVersion: $pluginVersion,
    model: $model, reasoningEffort: $effort, skill: "dmtool:a12-dmtool",
    installerReady: true, verification: $verification, valid: $valid, written: $written}' > "$SUMMARY"
if [[ -n "$REPORT" ]]; then
  mkdir -p "$(dirname "$REPORT")"
  cp "$SUMMARY" "$REPORT"
fi
cat "$SUMMARY"
