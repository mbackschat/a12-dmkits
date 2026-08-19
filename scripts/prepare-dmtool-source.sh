#!/usr/bin/env bash
# Resolve a JVM dmtool launcher built from this exact clean source revision. This is the reproducible sibling-
# project evidence route: it never selects a native image, never needs native reflection capture, and invokes
# Gradle only when the installed launcher's own provenance does not match the current commit.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Anchor the working directory, as every other script here does. `build.sh` ends in `exec "$ROOT/gradlew"`
# with no `cd` and no `-p`, so Gradle takes the CALLER's directory as the project directory: a sibling repo
# calling this by absolute path got a working launcher only while one already matched HEAD, and any rebuild
# failed. This is the cross-project entry point, so it is the one that must not depend on where it is called.
cd "$ROOT"
# shellcheck source=scripts/gradle-environment.sh
. "$ROOT/scripts/gradle-environment.sh"
LAUNCHER="$ROOT/cli/build/install/dmtool/bin/dmtool"

# The BUILD's JDK is pinned by build.sh; the LAUNCHER's was not, because it starts under whatever JAVA_HOME the
# caller exports. A JAVA_HOME naming a JDK that no longer exists therefore stops the launcher from starting at
# all — and reading that as a provenance verdict reported a correctly rebuilt launcher as the wrong source
# (dmtool feedback 2026-08-19). So the check applies the SAME pin the build does, and "could not start" is a
# separate outcome from "started and named another revision"; neither ever borrows the other's message.
PINNED_JDK21="$(a12_pinned_jdk21_home 2>/dev/null || true)"

fail() {
  echo "dmtool source preparation: $*" >&2
  exit 2
}

[[ -d "$ROOT/.git" || -f "$ROOT/.git" ]] \
  || fail "$ROOT is not a Git worktree"

revision="$(git -C "$ROOT" rev-parse --verify HEAD 2>/dev/null)" \
  || fail "cannot resolve the source revision"
[[ "$revision" =~ ^[0-9a-f]{40}$ ]] \
  || fail "source revision is not a full Git commit: $revision"

source_status="$(git -C "$ROOT" status --porcelain --untracked-files=normal)" \
  || fail "cannot inspect the source worktree"
[[ -z "$source_status" ]] \
  || fail "source worktree is not clean; commit or remove its changes before retaining evidence"

# `absent` (nothing to ask) · `unstartable` (asked, no answer) · `mismatch` (answered, another revision) ·
# `current` (answered, this revision). Set as a global rather than echoed, because the start diagnostic has to
# survive with it and a command substitution would run the classifier in a subshell.
launcher_state=""
launcher_version=""
launcher_diagnostic=""

resolve_launcher_state() {
  launcher_version=""
  launcher_diagnostic=""
  if [[ ! -x "$LAUNCHER" ]]; then
    launcher_state=absent
    return
  fi

  local stderr_file status=0
  stderr_file="$(mktemp)"
  if [[ -n "$PINNED_JDK21" ]]; then
    launcher_version="$(JAVA_HOME="$PINNED_JDK21" "$LAUNCHER" --version 2>"$stderr_file")" || status=$?
  else
    launcher_version="$("$LAUNCHER" --version 2>"$stderr_file")" || status=$?
  fi
  launcher_diagnostic="$(cat "$stderr_file")"
  rm -f "$stderr_file"

  if [[ $status -ne 0 || -z "$launcher_version" ]]; then
    launcher_state=unstartable
  elif printf '%s\n' "$launcher_version" | grep -Fqx "  source: $revision (clean)"; then
    launcher_state=current
  else
    launcher_state=mismatch
  fi
}

# A launcher that never ran told us nothing about its provenance, so say exactly that and keep its own
# diagnostic — it is the only place the real cause (a JAVA_HOME naming a removed JDK) is written down.
fail_unstartable() {
  {
    echo "dmtool source preparation: the JVM launcher did not start, so its source provenance is UNKNOWN"
    echo "  launcher: $LAUNCHER"
    echo "  JAVA_HOME used: ${PINNED_JDK21:-${JAVA_HOME:-<unset>}}"
    if [[ -n "$launcher_diagnostic" ]]; then
      printf '%s\n' "$launcher_diagnostic" | sed 's/^/  | /'
    fi
    echo "  fix: export a working JDK 21, e.g. macOS: export JAVA_HOME=\"\$(/usr/libexec/java_home -v 21)\""
  } >&2
  exit 2
}

resolve_launcher_state
[[ "$launcher_state" != unstartable ]] || fail_unstartable

if [[ "$launcher_state" != current ]]; then
  echo "dmtool source preparation: rebuilding the JVM launcher for $revision" >&2
  "$ROOT/build.sh" --console=plain -q :cli:installDist >&2 \
    || fail "the JVM launcher build failed"

  [[ "$(git -C "$ROOT" rev-parse --verify HEAD 2>/dev/null)" == "$revision" ]] \
    || fail "source HEAD changed while the JVM launcher was being built"
  [[ -z "$(git -C "$ROOT" status --porcelain --untracked-files=normal)" ]] \
    || fail "source worktree changed while the JVM launcher was being built"

  resolve_launcher_state
  case "$launcher_state" in
    current) ;;
    unstartable) fail_unstartable ;;
    *) fail "rebuilt launcher does not report source $revision (clean)" ;;
  esac
fi

# The path printed here is run by the CALLER, in the CALLER's environment. When only the pin made the check
# work, the caller's very next command would hit the failure this script just absorbed, so name it.
if [[ -n "$PINNED_JDK21" && -n "${JAVA_HOME:-}" \
      && ! -x "$JAVA_HOME/bin/java" && ! -x "$JAVA_HOME/jre/sh/java" ]]; then
  {
    echo "dmtool source preparation: note - JAVA_HOME=$JAVA_HOME holds no runnable java;"
    echo "  the provenance check used $PINNED_JDK21. Export that before running the launcher."
  } >&2
fi

launcher_dir="$(cd "$(dirname "$LAUNCHER")" && pwd)"
printf '%s\n' "$launcher_dir/$(basename "$LAUNCHER")"
