#!/usr/bin/env bash
# Execute a native dmtool release artifact with every Java discovery route made unusable. This checks the
# delivered artifact, not the bin/dmtool dispatch shim: substituting a JVM launcher must fail.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

native_executable_format() {
  local binary="$1" magic
  magic="$(LC_ALL=C od -An -tx1 -N4 "$binary" 2>/dev/null | tr -d '[:space:]')" || return 1
  case "$magic" in
    7f454c46|feedface|feedfacf|cefaedfe|cffaedfe|cafebabe|bebafeca|cafebabf|bfbafeca|4d5a*) return 0 ;;
    *) return 1 ;;
  esac
}

normalize_native_output() {
  sed 's/\r$//' <<< "$1"
}

valid_model_check_envelope() {
  local output="$1"
  jq -e '
    type == "object"
    and .target == "model"
    and .op == "check"
    and .ok == true
    and .valid == true
    and .verification == "KERNEL_CONFIRMED"
    and .written == false
  ' <<< "$output" >/dev/null 2>&1
}

verify_without_java() {
  local binary="$1" expected_version="$2" work="$3" model="$4"
  local output status first_line manifest_output version_pattern check_output

  if ! native_executable_format "$binary"; then
    printf 'native no-Java smoke: artifact is not an ELF, Mach-O, or PE executable: %s\n' "$binary" >&2
    return 1
  fi

  mkdir -p "$work/empty-path"
  set +e
  output="$(JAVA_HOME="$work/not-a-jdk" PATH="$work/empty-path" "$binary" --version 2>&1)"
  status=$?
  set -e
  if [[ $status -ne 0 ]]; then
    printf 'native no-Java smoke: binary failed with Java unavailable (exit %s): %s\n' "$status" "$output" >&2
    return 1
  fi
  output="$(normalize_native_output "$output")"

  first_line="${output%%$'\n'*}"
  if [[ "$first_line" != "a12-dmkits $expected_version" ]]; then
    printf 'native no-Java smoke: expected first line %q, got %q\n' \
      "a12-dmkits $expected_version" "$first_line" >&2
    return 1
  fi

  set +e
  manifest_output="$(JAVA_HOME="$work/not-a-jdk" PATH="$work/empty-path" "$binary" manifest 2>&1)"
  status=$?
  set -e
  if [[ $status -ne 0 ]]; then
    printf 'native no-Java smoke: functional manifest failed with Java unavailable (exit %s): %s\n' \
      "$status" "$manifest_output" >&2
    return 1
  fi
  manifest_output="$(normalize_native_output "$manifest_output")"
  version_pattern="\"rulekit\"[[:space:]]*:[[:space:]]*\"${expected_version}\""
  if [[ ! "$manifest_output" =~ $version_pattern || "$manifest_output" != *'"verbs"'* ]]; then
    printf 'native no-Java smoke: functional manifest did not carry rulekit %s and the verb tree: %s\n' \
      "$expected_version" "$manifest_output" >&2
    return 1
  fi

  set +e
  check_output="$(JAVA_HOME="$work/not-a-jdk" PATH="$work/empty-path" "$binary" -m "$model" model check 2>&1)"
  status=$?
  set -e
  if [[ $status -ne 0 ]]; then
    printf 'native no-Java smoke: functional model check failed with Java unavailable (exit %s): %s\n' \
      "$status" "$check_output" >&2
    return 1
  fi
  check_output="$(normalize_native_output "$check_output")"
  if ! valid_model_check_envelope "$check_output"; then
    printf 'native no-Java smoke: functional model check returned the wrong envelope: %s\n' "$check_output" >&2
    return 1
  fi
}

self_test() (
  local work independent dependent
  work="$(mktemp -d)"
  trap 'rm -rf "$work"' EXIT
  independent="$work/independent"
  dependent="$work/dependent"
  cat > "$independent" <<'EOF'
#!/bin/sh
case "$1" in
  --version) echo "a12-dmkits 9.9.9" ;;
  manifest) echo '{"version":{"rulekit":"9.9.9"},"verbs":[{"name":"manifest"}]}' ;;
  *) exit 43 ;;
esac
EOF
  cat > "$dependent" <<'EOF'
#!/bin/sh
if [ "$1" = --version ]; then echo "a12-dmkits 9.9.9"; exit 0; fi
java -version >/dev/null 2>&1 || exit 42
echo '{"version":{"rulekit":"9.9.9"},"verbs":[{"name":"manifest"}]}'
EOF
  chmod +x "$independent" "$dependent"

  native_executable_format /bin/sh || {
    echo "native no-Java smoke self-test: supported host executable format was not recognized" >&2
    return 1
  }
  [[ "$(normalize_native_output $'first\r\nsecond\r')" == $'first\nsecond' ]] || {
    echo "native no-Java smoke self-test: CRLF output was not normalized" >&2
    return 1
  }
  if verify_without_java "$independent" 9.9.9 "$work/independent-run" "$ROOT/examples/models/order-ruled.dm.json" >/dev/null 2>&1; then
    echo "native no-Java smoke self-test: a script that synthesized version and manifest survived" >&2
    return 1
  fi
  if verify_without_java "$dependent" 9.9.9 "$work/dependent-run" "$ROOT/examples/models/order-ruled.dm.json" >/dev/null 2>&1; then
    echo "native no-Java smoke self-test: a Java-dependent launcher survived" >&2
    return 1
  fi
  if valid_model_check_envelope 'garbage "target":"model" "op":"check" "ok":true "valid":true "verification":"KERNEL_CONFIRMED" "written":false'; then
    echo "native no-Java smoke self-test: non-JSON model-check output survived" >&2
    return 1
  fi
  echo "native no-Java smoke self-test: discriminator fires"
)

if [[ "${1:-}" == "--self-test" ]]; then
  command -v jq >/dev/null 2>&1 || { echo "native no-Java smoke self-test: missing jq" >&2; exit 2; }
  self_test
  exit 0
fi

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <native-dmtool> <expected-version>" >&2
  exit 2
fi

BINARY="$1"
EXPECTED_VERSION="$2"
command -v jq >/dev/null 2>&1 || { echo "native no-Java smoke: missing jq" >&2; exit 2; }
[[ -x "$BINARY" ]] || { echo "native no-Java smoke: not executable: $BINARY" >&2; exit 2; }
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
MODEL="$ROOT/examples/models/order-ruled.dm.json"
[[ -f "$MODEL" ]] || { echo "native no-Java smoke: functional fixture missing: $MODEL" >&2; exit 2; }
verify_without_java "$BINARY" "$EXPECTED_VERSION" "$WORK" "$MODEL"
echo "native no-Java smoke: GREEN ($BINARY, version $EXPECTED_VERSION)"
