#!/usr/bin/env python3
"""Regenerate the clickable, §-grouped Contents block in docs/KERNEL-FINDINGS.md.

The doc is appended to on every kernel-behavior finding, so its Contents is a DERIVED
surface — re-run this after adding/renaming a `## ` finding to keep it fresh:

    python3 scripts/gen-kernel-findings-toc.py

Idempotent: it strips the existing block between the markers (if any) and re-inserts a
fresh one before the first `## ` header. Anchors are GitHub-flavored slugs (dup-aware),
so links resolve in GitHub and GitHub-compatible markdown viewers. Insertion-only w.r.t.
the findings themselves — it never touches finding content."""
import re
import sys

PATH = "docs/KERNEL-FINDINGS.md"
BEGIN = "<!-- toc:begin -->"
END = "<!-- toc:end -->"


def slug(text, seen):
    # GitHub: downcase, drop all but word chars / hyphen / space, spaces -> hyphens, dup -> -N.
    s = re.sub(r'[^\w\- ]', '', text.lower()).replace(' ', '-')
    n = seen.get(s, 0)
    seen[s] = n + 1
    return s if n == 0 else f"{s}-{n}"


def group_key(header):
    m = re.match(r'§(\d+)', header)
    return f"§{m.group(1)}" if m else "Cross-cutting / other"


def gsort(k):
    m = re.match(r'§(\d+)', k)
    return (0, int(m.group(1))) if m else (1, 0)


def main():
    lines = open(PATH, encoding="utf-8").read().split("\n")

    # Strip any existing block between the markers (inclusive) + a single trailing blank line.
    if any(BEGIN in ln for ln in lines):
        b = next(i for i, ln in enumerate(lines) if BEGIN in ln)
        e = next(i for i, ln in enumerate(lines) if END in ln)
        end = e + 2 if e + 1 < len(lines) and lines[e + 1] == "" else e + 1
        lines = lines[:b] + lines[end:]

    headers, seen, first = [], {}, None
    for i, ln in enumerate(lines):
        if ln.startswith("## "):
            headers.append((ln[3:].strip(), slug(ln[3:].strip(), seen)))
            if first is None:
                first = i
    if first is None:
        print("no '## ' headers found — aborting", file=sys.stderr)
        sys.exit(1)

    groups = {}
    for text, sl in headers:
        groups.setdefault(group_key(text), []).append((text, sl))

    toc = [BEGIN, "## Contents", "",
           f"The {len(headers)} findings below, grouped by the KERNEL-SEMANTICS §-section they supplement "
           "(entries without a § are cross-cutting). **Generated — regenerate with "
           "`python3 scripts/gen-kernel-findings-toc.py` after adding a finding.**", ""]
    for k in sorted(groups, key=gsort):
        toc += [f"**{k}**", ""]
        toc += [f"- [{text}](#{sl})" for text, sl in groups[k]]
        toc.append("")
    toc += [END, ""]

    out = lines[:first] + toc + lines[first:]
    open(PATH, "w", encoding="utf-8").write("\n".join(out))
    print(f"Contents: {len(headers)} entries, {len(groups)} groups, before line {first + 1}")


if __name__ == "__main__":
    main()
