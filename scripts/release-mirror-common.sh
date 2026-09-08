#!/usr/bin/env bash

# Shared, side-effect-free guards for generated public release mirrors. dmtool fully regenerates its
# latest-only plugin mirror; the interpreter must retain cumulative Maven and API version directories,
# so only the validation and identity boundaries are shared between the two product assemblers.

# The markers a generated public mirror must never carry, split by the two kinds the refusal message
# distinguishes. The split IS the classification: naming the kind by re-testing these patterns keeps one
# definition, where matching on the found text needs a second, weaker copy of them that can drift. A file
# carrying both kinds is reported as a machine-local path.
RELEASE_MACHINE_LOCAL_PATTERNS=(
  -e '/Users/'
  -e '/home/[^/[:space:]]+/'
  -e '[A-Za-z]:\\Users\\'
  -e 'file:(//)?(/Users/|/home/|[A-Za-z]:)'
)
RELEASE_SECRET_PATTERNS=(
  -e 'mgm-tp\.com'
  -e '(gh[pousr]|github_pat|npm)_[A-Za-z0-9_]{20,}'
  -e '-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----'
)
RELEASE_PRIVATE_MARKER_PATTERNS=(
  "${RELEASE_MACHINE_LOCAL_PATTERNS[@]}"
  "${RELEASE_SECRET_PATTERNS[@]}"
)

release_sha256() {
  shasum -a 256 "$1" | awk '{print $1}'
}

release_file_size() {
  wc -c < "$1" | tr -d '[:space:]'
}

release_require_safe_derived_directory() {
  local directory="${1:?directory is required}"
  [[ -n "$directory" && "$directory" != "/" && "$directory" != "$HOME" ]] || {
    echo "refusing unsafe derived directory: $directory" >&2
    return 1
  }
}

release_public_files() {
  local root="${1:?public mirror root is required}"
  (
    cd "$root"
    find . -path './.git' -prune -o -type f ! -path './PUBLIC-FILES.sha256' -print |
      sed 's#^\./##' |
      LC_ALL=C sort
  )
}

release_verify_public_inventory() {
  local root="${1:?public mirror root is required}"
  local inventory="$root/PUBLIC-FILES.sha256"
  [[ -f "$inventory" ]] || {
    echo "public file inventory is missing: $inventory" >&2
    return 1
  }

  local temporary expected actual verification
  temporary="$(mktemp -d)"
  expected="$temporary/expected"
  actual="$temporary/actual"
  awk '{sub(/^[0-9a-f]+  /, ""); print}' "$inventory" | LC_ALL=C sort > "$expected"
  release_public_files "$root" > "$actual"
  if ! diff -u "$expected" "$actual" >/dev/null; then
    echo "public file inventory does not match the mirror tree" >&2
    diff -u "$expected" "$actual" >&2 || true
    rm -rf "$temporary"
    return 1
  fi
  if ! verification="$(cd "$root" && shasum -a 256 -c PUBLIC-FILES.sha256 2>&1)"; then
    echo "public file inventory contains a digest mismatch" >&2
    printf '%s\n' "$verification" >&2
    rm -rf "$temporary"
    return 1
  fi
  rm -rf "$temporary"
}

release_write_public_inventory() {
  local root="${1:?public mirror root is required}"
  local target="$root/PUBLIC-FILES.sha256"
  local temporary listing
  temporary="$(mktemp)"
  listing="$(mktemp)"
  release_public_files "$root" > "$listing"
  # `shasum` accepts many files and its own output IS the inventory format, which is also what
  # `shasum -c` reads back in release_verify_public_inventory. The emptiness guard matters: with no
  # arguments shasum would hash its stdin and write a spurious entry.
  if [[ -s "$listing" ]]; then
    ( cd "$root" && tr '\n' '\0' < "$listing" | xargs -0 shasum -a 256 ) > "$temporary"
  else
    : > "$temporary"
  fi
  rm -f "$listing"
  mv "$temporary" "$target"
}

release_check_interpreter_mirror_allowlist() {
  local root="${1:?public mirror root is required}"
  local relative
  while IFS= read -r relative; do
    case "$relative" in
      README.md|PERFORMANCE.md|CHANGELOG.md|LICENSE|DISCLAIMER.md|CLAUDE.md|AGENTS.md|release-manifest.json|PUBLIC-FILES.sha256)
        ;;
      assets/infographics/*.png)
        ;;
      .github/workflows/build-release.yml|.github/workflows/deploy-pages.yml)
        ;;
      site/.nojekyll|site/index.html|site/privacy.html)
        ;;
      site/showcase/*|site/api/latest/*|site/api/v*/*|site/maven/*)
        ;;
      *)
        echo "unexpected public mirror file: $relative" >&2
        return 1
        ;;
    esac
  done < <(
    cd "$root"
    find . -path './.git' -prune -o -type f -print |
      sed 's#^\./##' |
      LC_ALL=C sort
  )
}

release_scan_public_tree() {
  local root="${1:?public mirror root is required}"
  local offender
  # One grep over the whole tree names the first offending file: -I skips binary content and grep reports
  # files in argument order. Nothing parses grep's output — the kind is decided by re-testing the offender
  # against the machine-local set, so no filename or match text is ever split on a delimiter.
  offender="$(
    {
      find "$root" -path "$root/.git" -prune -o -type f -print0 |
        xargs -0 grep -lEI "${RELEASE_PRIVATE_MARKER_PATTERNS[@]}"
    } 2>/dev/null | head -n 1
  )" || true
  [[ -n "$offender" ]] || return 0
  if grep -qEI "${RELEASE_MACHINE_LOCAL_PATTERNS[@]}" "$offender" 2>/dev/null; then
    echo "public mirror contains a machine-local path in ${offender#"$root/"}" >&2
  else
    echo "public mirror contains a secret/private-source marker in ${offender#"$root/"}" >&2
  fi
  return 1
}
