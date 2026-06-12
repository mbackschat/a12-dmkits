#!/usr/bin/env bash
# Mirror the committed model fixtures into a /tmp sandbox so the N6 benchmark's clean agents never write a
# committed file. The authoring verbs (`add` / `computation add`) write IN PLACE by default, so a produce
# agent pointed at a committed fixture can corrupt it — it has, repeatedly, even slipping a mutated
# order.dm.json into a commit (EVAL-SPEC §8c). eval/benchmark.workflow.js resolves every model path under
# $SANDBOX, so committed fixtures are never named by any agent.
#
# Run this from the repo root BEFORE launching the benchmark, alongside `./gradlew :cli:installDist` (it is an
# orchestration step, like the build — a subagent cannot be granted permission for a mutating setup command,
# which is why this is a deterministic script the main loop runs, not an agent prompt). The workflow's Prep
# phase only VERIFIES the sandbox is present and halts loudly if it isn't. Idempotent; safe to re-run.
set -euo pipefail

SANDBOX="${RK_EVAL_SANDBOX:-/tmp/rk-eval-sandbox}"   # must match SANDBOX in eval/benchmark.workflow.js
MODELS_ROOT="cli/src/test/resources/models"

if [[ ! -d "$MODELS_ROOT" ]]; then
  echo "error: run from the repo root — $MODELS_ROOT not found" >&2
  exit 1
fi

rm -rf "$SANDBOX"
mkdir -p "$SANDBOX"
cp -R "$MODELS_ROOT/." "$SANDBOX/"

count=$(find "$SANDBOX" -name '*.dm.json' | wc -l | tr -d ' ')
echo "sandbox ready: $SANDBOX ($count model files mirrored from $MODELS_ROOT)"
