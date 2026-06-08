# CLI-TOUR-SPEC — verifiable CLI demos

The stable **method** for the rulekit CLI demos: how to author, verify, and extend them. The demos themselves (the moving artifacts) live in [`../examples/`](../examples/) — same split as [`EVAL-SPEC.md`](EVAL-SPEC.md) (method) ↔ `eval/` (artifacts).

The demos are authored with [showboat](https://github.com/simonw/showboat): each `examples/*.md` interleaves prose, a command, and its **captured output**, and `showboat verify` re-runs every command to prove the output still matches the live CLI. So a demo is documentation *and* a doc-drift regression test.

Two demos so far, split by workflow: [`../examples/cli-tour.md`](../examples/cli-tour.md) — the **read/inspect** surface (`manifest`·`describe`·`validate`·`read`·`explain`·`check`), opening with the model it guards; and [`../examples/cli-edit-loop.md`](../examples/cli-edit-loop.md) — the **write/edit** surface (`where-used`·`explain --computation`·`modify`·`add`·`--include-dir`·`batch`·`operators`/`schema`), the F8 modify-by-re-express loop end to end.

## Run / verify / regenerate

Prerequisites: a built CLI (`./gradlew :cli:installDist`), [`uv`](https://docs.astral.sh/uv/), and `jq`. No global showboat install — `uvx` fetches the pinned version on demand.

```bash
# re-check a demo against the live CLI (exit 0 = still matches, 1 = drift)
uvx showboat@0.6.1 verify examples/cli-tour.md

# print the commands that recreate it (output blocks omitted)
uvx showboat@0.6.1 extract examples/cli-tour.md
```

Run from the **repo root** — commands use repo-root-relative paths (`bin/rk …`, `cli/src/test/...`). `JAVA_HOME` must point at JDK 21 for **both** build and `verify` (`bin/rk` → the JVM launcher); export it first: `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"`.

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
- **Write-verb determinism (the gotcha for `add`/`modify`):** `add` prints the new model to **stdout** → redirect to a fixed `/tmp` path. `modify` writes **in place** → first `cp` the fixture to a fixed `/tmp` path and modify *that*, so a re-run never mutates a committed fixture (and the path stays byte-stable). Don't rely on `2>&1` to show a verb's stderr note — stdout/stderr flush order isn't guaranteed; redirect stderr to `/dev/null` and prove the effect another way (e.g. `grep -c` the written file), or capture the streams separately.

## The `bin/rk` shim

Demos call the CLI through [`../bin/rk`](../bin/rk), never a packaging artifact, so the doc text is identical whatever built the CLI. `rk` resolves to the **installDist JVM launcher** by default (always current); set **`RK_NATIVE=1`** to opt into a native binary for the startup win. Native is opt-in, never auto-preferred — a stale native artifact would otherwise silently shadow freshly-built code. (Mirrors the packaging policy: [`ARCHITECTURE.md`](ARCHITECTURE.md) §5.1, [`CLI-SPEC.md`](CLI-SPEC.md) §B1/§B2.)

## Determinism — the contract for a passing `verify`

`showboat verify` is **exact-match** — it has no normalization, redaction, or tolerance (by design; see showboat issue #13). So a demo only stays green if its commands produce byte-stable output. Two tiers:

1. **Prefer real determinism.** The CLI's JSON is already stable (no timestamps, no absolute paths, stable ordering). Keep it that way.
2. **Scrub the irreducible bits in-band** — inside the exec block, so `verify` re-runs the scrubber too. Known volatile spots:

| Volatile source | In-band fix |
|---|---|
| absolute paths (`export --out-dir`) | pipe through `sed 's#/Users/[^"]*/#<repo>/#g'` |
| German kernel messages (a failing model) | prefix `LANG=en_US.UTF-8` |
| field/array ordering, extra keys | project with `jq` (as the model section does) |

A fork of showboat to add a redaction hook is the wrong bet — in-band scrubbing covers every case and issue #13 shows upstream prefers the deterministic-output route.

## Expanding this approach (backlog)

Two demos cover the read and the write/edit workflows; the method scales to more, one demo per workflow:

- ✅ **Authoring loop** — `check` → `add` → re-`validate` (`cli-edit-loop.md`).
- ✅ **Edit loop** — `explain --computation` → re-express → `modify --dry-run` (preview) → `modify` (write-back); the F8 loop end-to-end (`cli-edit-loop.md`).
- ✅ **`batch`** — the self-describing, per-op-attributable output in one warm JVM (`cli-edit-loop.md`).
- ✅ **Multi-file** — `--include-dir` resolving an included model (`cli-edit-loop.md`).
- **Comprehension** — read foreign rules back and explain them (the T7 family).
- **Teaching / error paths** — deliberately author a wrong rule (inverted polarity, unguarded number) and show the lint/diagnostics fire. Needs the `LANG` pin above for the kernel's German messages.
- **Runtime evaluation** — `eval-doc` a rule against a document instance that *should* and *should not* fire (the empirical polarity check `check` can't give), and `compute-doc` to show a field's computed value (and the empty-operand value/cleared cases). A strong fit for the teaching demo above — it makes "does this fire on real data?" visible.

Two cross-cutting follow-ups:

- ✅ **CI doc-drift gate** — `.github/workflows/ci.yml` runs `uvx showboat@0.6.1 verify` on both demos (after `:cli:installDist`, reusing the bootstrapped kernel in the same job), joining the F11 "loud detection at build" guardians. A verb rename or output-shape change that wasn't carried into the demos now fails CI instead of rotting silently (the read/export rename bit them twice before this existed).
- **Reuse the verified snippets** — they can seed the README quickstart and the N1 skill examples. Mind the eval material boundary: keep any skill example structurally distinct from the eval tasks ([`EVAL-SPEC.md`](EVAL-SPEC.md) §3).
