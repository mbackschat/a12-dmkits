---
name: release
description: Release a12-dmkits — one version to all four repositories (the source, plus the dmtool, interpreter and consistency-checker mirrors). Use whenever the owner says "release", "cut X.Y.Z", "publish", "go live", or asks to resume an interrupted release. The full procedure is maintained once, in the Claude skill — this file points to it.
---

# Releasing a12-dmkits

This skill is a **pointer**, to avoid drift between agents. The maintained playbook is **`.claude/skills/release/SKILL.md`** (path from the repo root); read and follow it before any release step.

The one thing you must not get wrong: **"release" means all four repositories at one version from one source commit**, sequenced by [`scripts/typescript/release.ts`](../../../scripts/typescript/release.ts) as [RELEASE-SPEC §4a](../../../docs/RELEASE-SPEC.md#four-repositories) defines.
