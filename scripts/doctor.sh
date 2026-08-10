#!/usr/bin/env bash
# Build-environment doctor — checks the preconditions whose failure modes are confusing rather than obvious.
#
# Each one has bitten someone: a missing JDK 21 reads as a toolchain error, an unpublished kernel as a
# dependency error, and a machine-global Gradle init script injecting a private registry reads as a network
# error. Prose cannot keep up with that, so this reports the actual state of THIS machine.
#
# PASS = fine. WARN = optional, or informational. FAIL = the build will not work correctly; exit status 1.
# Rationale + the measurements behind each check: docs/BUILD-ENVIRONMENT.md.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=scripts/gradle-environment.sh
. "$ROOT/scripts/gradle-environment.sh"

failures=0
pass() { printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
warn() { printf '  \033[33mWARN\033[0m  %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; failures=$((failures + 1)); }
note() { printf '        %s\n' "$1"; }

printf '\nBuild environment for %s\n\n' "$ROOT"

# 1 — JDK 21. build.sh pins it through java_home on macOS; elsewhere the caller owns JAVA_HOME.
printf 'Toolchain\n'
if [[ -x /usr/libexec/java_home ]]; then
    if jdk21="$(/usr/libexec/java_home -v 21 2>/dev/null)"; then
        pass "JDK 21 available — $jdk21"
    else
        fail "no JDK 21 registered; build.sh pins the toolchain with java_home -v 21"
        note "install one, e.g. brew install openjdk@21, then link it so java_home sees it"
    fi
else
    if [[ -n "${JAVA_HOME:-}" ]]; then
        pass "JAVA_HOME set — $JAVA_HOME (no java_home on this platform; you own the pin)"
    else
        warn "no java_home and no JAVA_HOME; the build will use whatever java is on PATH"
    fi
fi
[[ -n "${JAVA_HOME:-}" ]] && note "current JAVA_HOME: $JAVA_HOME"

# 2 — the Gradle home this build would actually use, and whether anything injects into it.
printf '\nGradle home\n'
home="$(a12_gradle_user_home "$ROOT")"
if [[ -n "${GRADLE_USER_HOME:-}" ]]; then
    pass "GRADLE_USER_HOME set explicitly — $home"
else
    note "GRADLE_USER_HOME unset; ./build.sh would use $home, a bare ./gradlew would use \$HOME/.gradle"
fi
own_init='public-repos.init.gradle'
foreign=()
for candidate in "$home"/init.d/*.gradle "$home"/init.d/*.gradle.kts "$home"/init.gradle; do
    [[ -f "$candidate" ]] || continue
    [[ "$(basename "$candidate")" == "$own_init" ]] && continue
    foreign+=("$(basename "$candidate")")
done
if ((${#foreign[@]} == 0)); then
    pass "no foreign init script in that home"
else
    fail "that home runs init script(s) this project does not own: ${foreign[*]}"
    note "an init script adds repositories to every build and cannot be removed from inside one"
fi

# 3 — the machine-default home, which a bare ./gradlew uses, and the marker that opts this build out.
printf '\nPublic-only resolution\n'
if [[ -f "$ROOT/.no-global-gradle-repos" ]]; then
    pass "the .no-global-gradle-repos marker is present in this checkout"
else
    fail "the .no-global-gradle-repos marker is missing; an init script that honours it would inject again"
fi
default_init=("$HOME"/.gradle/init.d/*.gradle "$HOME"/.gradle/init.gradle)
default_present=0
for candidate in "${default_init[@]}"; do [[ -f "$candidate" ]] && default_present=1; done
if ((default_present == 0)); then
    pass "the machine-default Gradle home runs no init script"
else
    honoured=0
    for candidate in "${default_init[@]}"; do
        [[ -f "$candidate" ]] || continue
        grep -q 'no-global-gradle-repos' "$candidate" && honoured=1
    done
    if ((honoured == 1)); then
        pass "the machine-default home has init script(s), and they honour the marker"
    else
        warn "the machine-default home has init script(s) that do NOT honour the marker"
        note "a bare ./gradlew resolves through whatever they inject; use ./build.sh, or teach them the marker"
    fi
fi

# 4 — the kernel, which no public repository serves.
printf '\nKernel\n'
if [[ -d "$HOME/.m2/repository/com/mgmtp" ]]; then
    pass "kernel artifacts present in the local Maven repository"
else
    fail "no kernel in \$HOME/.m2; publish it once with the sibling a12-kernel-bootstrap kit"
    note "without it every module fails to resolve, which reads as a dependency error"
fi

# 5 — optional conveniences. Never required; their absence has just been misread before.
printf '\nOptional\n'
if command -v direnv >/dev/null 2>&1; then
    if grep -rqs 'direnv hook' "$HOME/.zshrc" "$HOME/.bashrc" "$HOME/.zprofile" 2>/dev/null; then
        pass "direnv installed and hooked into the shell"
    else
        warn "direnv installed but no shell hook found; .envrc will never apply"
        note "add: eval \"\$(direnv hook zsh)\" to your shell profile, then re-open the shell"
    fi
else
    note "direnv not installed; optional — ./build.sh sets the same two variables explicitly"
fi
if command -v node >/dev/null 2>&1; then
    pass "node on PATH — $(node --version)"
else
    warn "node not on PATH; the JS lanes and verify-demos.sh need it"
    note "a missing node reads as demo DRIFT rather than a missing tool, so check here first"
fi

printf '\n'
if ((failures > 0)); then
    printf '%d check(s) failed. See docs/BUILD-ENVIRONMENT.md.\n\n' "$failures"
    exit 1
fi
printf 'Environment looks good.\n\n'
