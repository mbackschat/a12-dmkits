# CLI-TOUR-SPEC — verifiable CLI demos

The stable **method** for the rulekit CLI demos: how to author, verify, and extend them. The demos themselves (the moving artifacts) live in [`../examples/`](../examples/) — same split as [`EVAL-SPEC.md`](EVAL-SPEC.md) (method) ↔ `eval/` (artifacts).

The demos are authored with [showboat](https://github.com/simonw/showboat): each `examples/*.md` interleaves prose, a command, and its **captured output**, and `showboat verify` re-runs every command to prove the output still matches the live CLI. So a demo is documentation *and* a doc-drift regression test.

**The demos** cover the whole target-first verb surface, split by workflow — read-one-model, the self-describing surface, review/comprehend, multi-file & workspaces, rulekit write/edit, modelkit structure, the `apply` session, runtime evaluation, custom constructs, and JSON-Schema/OpenAPI. The CI gate re-verifies every one (`.github/workflows/ci.yml`), and `scripts/verify-demos.sh` discovers them by glob so a new one can't go unverified. The **per-demo verb inventory is the single source in [`../examples/README.md`](../examples/README.md)** — not hard-counted or enumerated here, where it drifts on every demo add.

## Run / verify / regenerate

Prerequisites: a built CLI (`./gradlew :cli:installDist`), [`uv`](https://docs.astral.sh/uv/), and `jq`. No global showboat install — `uvx` fetches the pinned version on demand.

```bash
# re-check a demo against the live CLI (exit 0 = still matches, 1 = drift)
uvx showboat@0.6.1 verify examples/cli-tour.md

# print the commands that recreate it (output blocks omitted)
uvx showboat@0.6.1 extract examples/cli-tour.md
```

Run from the **repo root**. The demos call the bare **`dmtool`** (what an installed/plugin user actually types — not the repo artifact `bin/dmtool`), so put the repo `bin/` on PATH for `verify`: the shim there resolves `dmtool` to the JVM launcher. Model paths are **`examples/models/…`** — the demos' **own** sample fixtures (decoupled from the JUnit test fixtures under `cli/src/test/resources/models/`: same kind of model, different *purpose* — user-facing showcase vs. internal coverage — so they live apart and are published with the demos to the mirror). `JAVA_HOME` must point at JDK 21 for both the build and the JVM launcher; the shipped native CLI exposes the same verb surface without requiring a local JVM. Export both first:

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$PWD/bin:$PATH"
```

Long `dmtool` calls are wrapped with trailing `\` (one logical piece per line) for readability — `showboat` runs each ```bash``` block as one shell, so continuations execute identically and `verify` stays green (see CLAUDE.md "Wrap long CLI calls").

## Authoring model — how showboat builds a demo (so you never have to re-derive it)

A demo is **built by an append-only sequence of commands**, not hand-written. The full command set (`showboat <cmd> --help`):

| command | what it does |
|---|---|
| `showboat init <file> <title>` | start a new demo (one H1 title) |
| `showboat note <file> [text]` | append prose (markdown). Reads **stdin** when `text` is omitted — use a `<<'EOF'` heredoc for multi-line/punctuated prose |
| `showboat exec <file> <lang> [code]` | run `code` and append the command **and its captured output**. Reads **stdin** when `code` is omitted — *always pass code via stdin heredoc*, it sidesteps all nested-quote pain (`printf '%s' '{json}'`, `$(grep …)`, `"x"` survive verbatim) |
| `showboat pop <file>` | remove the **most recent** entry (the only edit primitive) |
| `showboat verify <file> [--output <new>]` | re-run every code block, **exact-match** diff (no normalization — issue #13) |
| `showboat extract <file> [--filename <name>]` | print the commands that recreate the file (output blocks omitted) |

Key behaviors that bite if you forget them:

- **`exec` appends regardless of exit code** — a failing command still lands a block; `pop` it and retry. Author a demo by driving these from a **build script** (see [`../examples/`](../examples/) — `cli-edit-loop.md` was built by one), so it's reproducible and re-runnable, not typed by hand.
- **Append-only ⇒ no in-place edit of an early block.** Two ways to fix a block that isn't last: **(a)** manually edit its ```` ```output ```` fence to match the live output, then `verify` to confirm byte-equality (cheapest for a one-line drift, e.g. a new verb in the `manifest` list); **(b)** `extract` → edit the command list → rebuild from scratch (for structural change). You cannot `pop` to a middle block without discarding everything after it.
- **State across blocks:** each `exec` is a **fresh shell** — shell variables do **not** persist between blocks, but **files do** (e.g. a `/tmp` file one block writes, the next reads). `verify` re-runs blocks **in order**, so a later block may depend on a file an earlier one created.
- **Write-verb determinism (the gotcha for every mutating op):** all mutating ops (`rule add`/`rule modify`/`field add`/`group add`/`field remove`/…) write **in place** by default and print the result **envelope** to stdout (not the model) → first `cp` the fixture to a fixed `/tmp` path and operate on *that* (`-m /tmp/copy`), so a re-run never mutates a committed fixture (and the path stays byte-stable). `--dry-run` previews read-only. Don't rely on `2>&1` to show a verb's stderr note — stdout/stderr flush order isn't guaranteed; redirect stderr to `/dev/null` and prove the effect another way (e.g. `grep -c` the written file), or capture the streams separately.

## Reader-facing formatting — three rules, each with its guard

A demo is read far more often than it is written, and all three of these were learned by shipping the opposite.

**1. Break a long command at the pipes.** A one-liner wide enough to scroll horizontally is unreadable in every renderer; `cli-mcp.md` shipped one of **816** characters. End the line with `\`, start the next with the `|`, indented two spaces — [`../examples/cli-jsonschema.md`](../examples/cli-jsonschema.md) does this throughout and is the model to copy. A long `jq` program wraps too, since jq does not care where its newlines fall. The one thing that must **not** wrap is a frame for the **stdio MCP transport**, whose protocol is newline-delimited: build those with `jq -nc`, whose output is a single line however its source is laid out. *Guard:* `verify-demos.sh` **ratchets** the longest command line per demo — a file may only improve, and a new demo must come in under 120.

**2. Every demo carries showboat's header** — the `*<timestamp> by Showboat 0.6.1*` byline and the `<!-- showboat-id: … -->` comment, directly under the title. A hand-authored or hand-split demo that lacks it grows a bare `**` the first time anyone runs `verify --output`, because showboat writes the byline template with both fields empty. The header survives regeneration verbatim, so writing one by hand is the fix. *Guard:* none — the failure is cosmetic and visible.

**3. Never fence a command that does not return.** `verify` executes **every** fenced block (it runs the fence's info string as the interpreter), so a server blocks the gate forever, silently, with nothing in the log. `dmtool mcp --http` is the only such verb we ship. Show it as a four-space **indented** block, which showboat leaves alone. *Guard:* `verify-demos.sh` refuses a fenced `mcp --http` up front.

## The `bin/dmtool` shim

Demos call the CLI through [`../bin/dmtool`](../bin/dmtool), never a packaging artifact, so the doc text is identical whatever built the CLI. `dmtool` resolves to the **installDist JVM launcher** by default (always current); set **`RK_NATIVE=1`** to opt into a native binary for the startup win. Native is opt-in, never auto-preferred — a stale native artifact would otherwise silently shadow freshly-built code. (Mirrors the packaging policy: [`ARCHITECTURE.md`](ARCHITECTURE.md) §5d, [`CLI-SPEC.md`](CLI-SPEC.md) [§11](CLI-SPEC.md#packaging) phases B1/B2.)

## Determinism — the contract for a passing `verify`

`showboat verify` is **exact-match** — it has no normalization, redaction, or tolerance (by design; see showboat issue #13). So a demo only stays green if its commands produce byte-stable output. Two tiers:

1. **Prefer real determinism.** The CLI's JSON is already stable (no timestamps, no absolute paths, stable ordering). Keep it that way.
2. **Scrub the irreducible bits in-band** — inside the exec block, so `verify` re-runs the scrubber too. Known volatile spots:

| Volatile source | In-band fix |
|---|---|
| absolute paths (`export --out-dir`) | pipe through `sed 's#/Users/[^"]*/#<repo>/#g'` |
| German kernel messages (a failing model) | prefix `LANG=en_US.UTF-8` |
| field/array ordering, extra keys | project with `jq` (as the model section does) |
| a runtime verb's `--help` (build-divergent) | capture **`schema <verb>`**, not `--help` |

A fork of showboat to add a redaction hook is the wrong bet — in-band scrubbing covers every case and issue #13 shows upstream prefers the deterministic-output route.

**Runtime-verb `--help` is NOT build-stable — capture `schema`, not `--help` (2026-06-28).** A demo verified on **both** the JVM (`verify-demos.sh` / CI) and the native binary (`native-gate.sh`) must produce byte-identical output on both — and `model eval` / `model compute` / `rule eval` `--help` cannot be trusted to: picocli can **re-wrap the Usage synopsis** across builds (the synopsis line-breaks and option list can differ JVM↔native), and no after-the-fact scrub reproduces the wrap. (The `--kernel` eval flag that once diverged the two option sets was retired in Set V, so it is no longer a source of divergence — but `--help` is still the wrong thing to capture.) The fix is to show the verb's **directional I/O contract** — `schema <verb>` (`{op, returns, docInput, dataSchema}`), which is byte-identical on both builds because it describes I/O, not CLI flags. `cli-runtime.md` does this (it captures `schema model eval`/`schema model compute`); a future runtime-verb demo must too.

## Expanding this approach (backlog)

**The demos cover the whole verb surface** on the `dmtool` target-first surface + the result envelope; the CI gate re-verifies every one (`.github/workflows/ci.yml`), and `scripts/verify-demos.sh` discovers them by glob. Index (the single source for the per-demo inventory): [`../examples/README.md`](../examples/README.md).

- ✅ **Read one model** (`cli-tour.md`) — `model check`/`describe`/`read`/`usage` · `rule read`/`explain`/`format`/`check` · `field`/`group`/`config read`.
- ✅ **The self-describing surface** (`cli-discover.md`) — `manifest` · `operators`(+`<id>`) · `patterns`(+scaffold) · `diagnostics`(+`<code>`) · `schema result`/`<target> <op>` (assemble a call from `manifest` + `schema` alone).
- ✅ **Review & comprehend** (`cli-review.md`) — `model report` (the glossed comprehension catalog) · `model diff` (the structural, risk-ranked semantic diff of two models — incl. `POLARITY_INVERTED`, a silent polarity flip detected structurally from the rule ASTs) · the `--text` risk-sorted human view.
- ✅ **Multi-file & workspaces** (`cli-workspace.md`) — `model info` · `workspace list`/`graph`/`roles`.
- ✅ **Rulekit write/edit** (`cli-edit-loop.md`) — `where-used` · the full `rule` + `computation` CRUD (add/read/explain/modify/remove) · `batch` · the F8 re-express loop (`--dry-run` preview vs in-place write) · **`;;` comments** (the `comment` key / `rule modify --comment`, preserved across a comment-less re-express).
- ✅ **Modelkit structure** (`cli-structure-edit.md`) — `field`/`group` add·read·remove (the safe-delete gate + `--cascade`) · `typedef` + `field --typedef` · `include` (`-w/--workspace`) · `config read`/`modify` · `export`.
- ✅ **The `apply` session** (`cli-apply.md`) — atomic multi-op · rollback · op-arg corrective (`RK_UNKNOWN_ARG`/`RK_UNKNOWN_OP` + did-you-mean) · cross-type corrective · read-mid-sequence · `schema apply` (the op-record frame).
- ✅ **Runtime evaluation** (`cli-runtime.md`) — `model eval` (which rules *fire* on a document instance — the empirical polarity check `check` can't give) · `model compute` (a computed field's value, incl. empty-as-0) · `model seed` (generate a best-effort candidate containing every declared group and field, computation targets present-empty, one repeatable row by default — round-trips into eval/compute).
- ✅ **Custom field types & conditions** (`cli-custom-types.md`) — `model eval --predefined-types` (validate a declarative custom field type) · `data.unsupported` (visible-ignore, never a silent pass) · `model eval --strict-custom` (fail instead of degrade) · `rule eval` on a `CustomCondition` rule (the `unsupported` verdict) · the **JS escape** `--custom-field-types-js` / `--custom-conditions-js` (run the project's own JS impls via a persistent Node worker). The custom-field demo executes the exact contextual `validate(value, context)` object-result contract, including project code and localized field-placeholder message projection (FEATURE-GAPS G29/G30/G31; INTERPRETER-FINDINGS IF44/IF150/IF152).
- ✅ **Versions & compatibility** (`cli-version.md`) — `--version` + `manifest.version` (the seven axes, including full Git revision/tree state and the A12 Tools distribution label) · the model-version load policy (tolerant `RK_MODEL_VERSION_SKEW` vs fail-fast `RK_MODEL_VERSION_INCOMPATIBLE`, kernel as authority) · write-back preserves the version (no implicit bump).

Comprehension (reading foreign rules back) is shown by `rule read`/`rule explain` in the tour; the self-correction headline is the `apply` op-arg + cross-type correctives in `cli-apply.md`. (When the modelkit *agentic* eval lands — EVAL-SPEC §8d / B — these demos are the executable counterparts of the `modelkit/`+`mixed/` tasks.)

Two cross-cutting follow-ups:

- ✅ **CI doc-drift gate** — `.github/workflows/ci.yml` runs `uvx showboat@0.6.1 verify` on both demos (after `:cli:installDist`, reusing the bootstrapped kernel in the same job), joining the F11 "loud detection at build" guardians. A verb rename or output-shape change that wasn't carried into the demos now fails CI instead of rotting silently (the read/export rename bit them twice before this existed).
- **Reuse the verified snippets** — they can seed the README quickstart and the N1 skill examples. Mind the eval material boundary: keep any skill example structurally distinct from the eval tasks ([`EVAL-SPEC.md`](EVAL-SPEC.md) §3).
