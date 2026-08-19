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
LAUNCHER="$ROOT/cli/build/install/dmtool/bin/dmtool"

fail() {
  echo "dmtool source preparation: $*" >&2
  exit 2
}

# Sourced AFTER fail() so a missing or unreadable helper still exits 2 with this script's own message, which is
# the convention sibling callers key on.
[[ -r "$ROOT/scripts/gradle-environment.sh" ]] \
  || fail "cannot read scripts/gradle-environment.sh"
# shellcheck source=scripts/gradle-environment.sh
. "$ROOT/scripts/gradle-environment.sh"

# The BUILD's JDK is pinned by build.sh; the LAUNCHER's was not, because it starts under whatever JAVA_HOME the
# caller exports. A JAVA_HOME naming a JDK that no longer exists therefore stops the launcher from starting at
# all — and reading that as a provenance verdict reported a correctly rebuilt launcher as the wrong source
# (dmtool feedback 2026-08-19). So the check applies the SAME pin the build does, and "could not start" is a
# separate outcome from "started and named another revision"; neither ever borrows the other's message.
pin_stderr="$(mktemp)"
trap 'rm -f "$pin_stderr"' EXIT
if ! PINNED_JDK21="$(a12_pinned_jdk21_home 2>"$pin_stderr")"; then
  # The pin failing is not fatal — the caller's JAVA_HOME may still work — but swallowing it left a macOS box
  # with no registered JDK 21 unable to tell "the pin was applied" from "the pin was silently unavailable".
  PINNED_JDK21=""
  {
    echo "dmtool source preparation: note - this platform pins a JDK 21 for the provenance check," \
      "but none is registered;"
    [[ ! -s "$pin_stderr" ]] || sed 's/^/  | /' "$pin_stderr"
    echo "  the check will use JAVA_HOME=${JAVA_HOME:-<unset>} instead. Install one: brew install openjdk@21"
  } >&2
fi

# The JDK the launcher check will use, recorded once so the diagnostic cannot re-derive it differently.
LAUNCHER_JAVA_HOME="${PINNED_JDK21:-${JAVA_HOME:-}}"

# Can the JDK we would use actually run this launcher? This is the discriminator between "this machine has no
# usable JVM" — where a rebuild cannot help and `build.sh` would fail naming the wrong cause — and "the
# launcher itself is broken", e.g. a partial installDist, which a rebuild repairs.
#
# It asks the SHARED classifier rather than testing for a file. A presence test answers a different question
# and gets two cases backwards: a JDK 17 and a `java` whose `-version` exits non-zero are both PRESENT, so the
# old probe called them runnable, and the launcher then died anyway — sending the script down the
# "the JDK above IS runnable, so this is the launcher" branch, which advises the rebuild it had just done.
java_is_runnable() {
  a12_classify_java "$LAUNCHER_JAVA_HOME"
  [[ "$a12_java_kind" == ok ]]
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

# `absent` (nothing to ask) · `unstartable` (asked, no provenance answer at all) · `mismatch` (answered,
# another revision) · `unhealthy` (answered THIS revision, then exited non-zero) · `current` (answered this
# revision, cleanly). Set as a global rather than echoed, because the start diagnostic has to survive with it
# and a command substitution would run the classifier in a subshell.
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
  # shellcheck disable=SC2064 -- expand the path now; the trap must survive the local going out of scope
  trap "rm -f '$stderr_file'" RETURN
  if [[ -n "$PINNED_JDK21" ]]; then
    launcher_version="$(JAVA_HOME="$PINNED_JDK21" "$LAUNCHER" --version 2>"$stderr_file")" || status=$?
  else
    launcher_version="$("$LAUNCHER" --version 2>"$stderr_file")" || status=$?
  fi
  launcher_diagnostic="$(cat "$stderr_file" 2>/dev/null || true)"

  # Classify on CONTENT first and on exit status second. The provenance line is what was asked for, so a
  # launcher that writes it to stderr, or writes it and then exits non-zero, HAS answered — reading the status
  # first filed all of those under "never started", the same conflation between an unanswered question and a
  # wrong answer that this script exists to undo. Both streams are searched for the same reason.
  local answer
  answer="$(printf '%s\n%s\n' "$launcher_version" "$launcher_diagnostic")"
  if printf '%s\n' "$answer" | grep -Fqx "  source: $revision (clean)"; then
    # Provenance known and current. A non-zero exit still means the launcher is not usable, and that must not
    # be called `current`: the caller runs this path next. It is not `unstartable` either — it started.
    if [[ $status -ne 0 ]]; then launcher_state=unhealthy; else launcher_state=current; fi
  elif printf '%s\n' "$answer" | grep -Eq '^  source: '; then
    launcher_state=mismatch
  else
    launcher_state=unstartable
  fi
}

# A launcher that never ran told us nothing about its provenance, so say exactly that and keep its own
# diagnostic — it is the only place the real cause (a JAVA_HOME naming a removed JDK) is written down.
fail_unstartable() {
  {
    echo "dmtool source preparation: the JVM launcher named no source revision, so its provenance is UNKNOWN"
    echo "  launcher: $LAUNCHER"
    echo "  JAVA_HOME used: ${LAUNCHER_JAVA_HOME:-<unset>}"
    if [[ -n "$launcher_diagnostic" ]]; then
      printf '%s\n' "$launcher_diagnostic" | sed 's/^/  | /'
    fi
    if java_is_runnable; then
      # Only reachable AFTER a rebuild — the pre-build branch refuses before getting here when the JDK is
      # unusable — so do not advise the rebuild that has just run and not helped.
      echo "  the JDK above runs and reports a supported version, so this is the launcher, not the environment"
      echo "  a rebuild has already been attempted; inspect $LAUNCHER and the :cli:installDist output"
    else
      echo "  that JDK is unusable ($a12_java_kind${a12_java_major:+, Java $a12_java_major})"
      echo "  fix: export a working JDK 21, e.g. macOS: export JAVA_HOME=\"\$(/usr/libexec/java_home -v 21)\""
    fi
  } >&2
  exit 2
}

# It answered the provenance question correctly and still failed. Rebuilding is the remedy, so this is only
# reached when a fresh build reproduces it — a real defect in the launcher rather than in the environment.
fail_unhealthy() {
  {
    echo "dmtool source preparation: the JVM launcher reports source $revision (clean) but exits non-zero"
    echo "  launcher: $LAUNCHER"
    echo "  JAVA_HOME used: ${LAUNCHER_JAVA_HOME:-<unset>}"
    if [[ -n "$launcher_diagnostic" ]]; then
      printf '%s\n' "$launcher_diagnostic" | sed 's/^/  | /'
    fi
    echo "  its provenance is right, so this is not a stale build; investigate the launcher itself"
  } >&2
  exit 2
}

resolve_launcher_state
# A launcher that cannot start because this machine has no runnable JDK is refused now: rebuilding cannot fix
# the environment, and `build.sh` would die naming the build instead. Every other non-current state — missing,
# stale, or a launcher that is present but broken — earns exactly one rebuild.
if [[ "$launcher_state" == unstartable ]] && ! java_is_runnable; then
  fail_unstartable
fi

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
    unhealthy) fail_unhealthy ;;
    # `absent` used to fall in with `mismatch` and borrow its message — a provenance verdict on a launcher that
    # was never asked, which is the very conflation this script exists to undo.
    absent) fail "the build reported success but left no runnable launcher at $LAUNCHER" ;;
    *) fail "rebuilt launcher does not report source $revision (clean)" ;;
  esac
fi

# The path printed here is run by the CALLER, in the CALLER's environment — which the pin may have bypassed
# for the provenance check. So classify what the caller actually has, through the same probe `bin/dmtool`'s
# gate uses, and name any reason the launcher will not start there. The note used to cover only a JAVA_HOME
# with no java in it at all, and stayed silent for a runnable JDK of the wrong major version and for an unset
# JAVA_HOME with no java on PATH — both of which stop the launcher just as surely.
a12_classify_java "${JAVA_HOME:-}"
caller_problem=""
case "$a12_java_kind" in
  no-home-binary) caller_problem="JAVA_HOME=$JAVA_HOME holds no runnable java" ;;
  no-java) caller_problem="JAVA_HOME is unset and no java is on PATH" ;;
  failed) caller_problem="\`$a12_java_bin -version\` failed (exit $a12_java_status)" ;;
  unreadable) caller_problem="\`$a12_java_bin -version\` reports no readable version" ;;
  too-old) caller_problem="$a12_java_bin is Java $a12_java_major, and the launcher needs 21 or newer" ;;
esac
if [[ -n "$caller_problem" ]]; then
  {
    echo "dmtool source preparation: note - $caller_problem;"
    if [[ -n "$PINNED_JDK21" ]]; then
      echo "  the provenance check used $PINNED_JDK21. Export that before running the launcher."
    else
      echo "  the launcher path below starts only once a JDK 21 is on PATH or named by JAVA_HOME."
    fi
  } >&2
fi

launcher_dir="$(cd "$(dirname "$LAUNCHER")" && pwd)"
printf '%s\n' "$launcher_dir/$(basename "$LAUNCHER")"
