# The Codex plugin tree

The Codex half of the agent distribution; [`../plugin`](../plugin) is the Claude Code half. **This file is for maintainers of this repository** and is not published — the release assembler copies this directory file by file, not wholesale, so only the four files below reach the public mirror.

| file | where it ships | what it is |
|---|---|---|
| [`AGENTS.md`](AGENTS.md) | `codex/AGENTS.md` | the in-context guidance Codex reads. Also a ready-made template a user can copy into their own project's `AGENTS.md` |
| [`.codex-plugin/plugin.json`](.codex-plugin/plugin.json) | `codex/.codex-plugin/` | the plugin manifest — name, version, and the `skills` directory it exposes |
| [`marketplace.json`](marketplace.json) | `.agents/plugins/marketplace.json` | the marketplace entry, pointing at `./codex` in the public release repository |
| [`skills/`](skills) | `codex/skills/` | **generated copies** — see below |

**Never hand-edit `skills/`.** Both plugin trees' skill copies are synchronized from the canonical sources in [`../skill`](../skill) by [`../scripts/sync-plugin-assets.sh`](../scripts/sync-plugin-assets.sh), which also carries a `--check` mode that fails on drift. Editing a copy is how one of two plugins silently ships a different skill from the other.

**Why the Codex tree is separate from the Claude Code one rather than a variant of it.** The manifests differ in shape and the in-context file differs in name and audience (`AGENTS.md` against `CLAUDE.md`), while the skills are byte-identical. Keeping the trees apart makes the *shared* part obviously shared — it is generated into both — and the divergent part obviously divergent.

Distribution contract: [`docs/RELEASE-SPEC.md`](../docs/RELEASE-SPEC.md) §6 and §12.
