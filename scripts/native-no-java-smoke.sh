#!/usr/bin/env bash
# Execute a native dmtool release artifact with every Java discovery route made unusable. This checks the
# delivered artifact, not the bin/dmtool dispatch shim: substituting a JVM launcher must fail.
set -euo pipefail

verify_without_java() {
  local binary="$1" expected_version="$2" work="$3"
  local output status first_line manifest_output version_pattern

  mkdir -p "$work/empty-path"
  set +e
  output="$(JAVA_HOME="$work/not-a-jdk" PATH="$work/empty-path" "$binary" --version 2>&1)"
  status=$?
  set -e
  if [[ $status -ne 0 ]]; then
    printf 'native no-Java smoke: binary failed with Java unavailable (exit %s): %s\n' "$status" "$output" >&2
    return 1
  fi

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
  version_pattern="\"rulekit\"[[:space:]]*:[[:space:]]*\"${expected_version}\""
  if [[ ! "$manifest_output" =~ $version_pattern || "$manifest_output" != *'"verbs"'* ]]; then
    printf 'native no-Java smoke: functional manifest did not carry rulekit %s and the verb tree: %s\n' \
      "$expected_version" "$manifest_output" >&2
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

  verify_without_java "$independent" 9.9.9 "$work/independent-run"
  if verify_without_java "$dependent" 9.9.9 "$work/dependent-run" >/dev/null 2>&1; then
    echo "native no-Java smoke self-test: a Java-dependent launcher survived" >&2
    return 1
  fi
  echo "native no-Java smoke self-test: discriminator fires"
)

if [[ "${1:-}" == "--self-test" ]]; then
  self_test
  exit 0
fi

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <native-dmtool> <expected-version>" >&2
  exit 2
fi

BINARY="$1"
EXPECTED_VERSION="$2"
[[ -x "$BINARY" ]] || { echo "native no-Java smoke: not executable: $BINARY" >&2; exit 2; }
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
verify_without_java "$BINARY" "$EXPECTED_VERSION" "$WORK"
echo "native no-Java smoke: GREEN ($BINARY, version $EXPECTED_VERSION)"
