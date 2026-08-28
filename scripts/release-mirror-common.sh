#!/usr/bin/env bash

# Shared, side-effect-free guards for generated public release mirrors. dmtool fully regenerates its
# latest-only plugin mirror; the interpreter must retain cumulative Maven and API version directories,
# so only the validation and identity boundaries are shared between the two product assemblers.

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
  local temporary
  temporary="$(mktemp)"
  while IFS= read -r relative; do
    printf '%s  %s\n' "$(release_sha256 "$root/$relative")" "$relative"
  done < <(release_public_files "$root") > "$temporary"
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
  local match
  while IFS= read -r -d '' file; do
    if ! grep -Iq . "$file" 2>/dev/null; then
      continue
    fi
    match="$(
      grep -En -m1 \
        -e 'mgm-tp\.com' \
        -e '/Users/' \
        -e '/home/[^/[:space:]]+/' \
        -e '[A-Za-z]:\\Users\\' \
        -e 'file:(//)?(/Users/|/home/|[A-Za-z]:)' \
        -e '(gh[pousr]|github_pat|npm)_[A-Za-z0-9_]{20,}' \
        -e '-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----' \
        "$file" 2>/dev/null || true
    )"
    if [[ -n "$match" ]]; then
      case "$match" in
        *'/Users/'*|*'/home/'*|*'\\Users\\'*|*'file:'*)
          echo "public mirror contains a machine-local path in ${file#"$root/"}" >&2
          ;;
        *)
          echo "public mirror contains a secret/private-source marker in ${file#"$root/"}" >&2
          ;;
      esac
      return 1
    fi
  done < <(find "$root" -path "$root/.git" -prune -o -type f -print0)
}
