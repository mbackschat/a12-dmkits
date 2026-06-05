# CLI-TOUR-SPEC — verifiable CLI demos

The stable **method** for the rulekit CLI demos: how to author, verify, and extend them. The demos themselves (the moving artifacts) live in [`../examples/`](../examples/) — same split as [`EVAL-SPEC.md`](EVAL-SPEC.md) (method) ↔ `eval/` (artifacts).

The demos are authored with [showboat](https://github.com/simonw/showboat): each `examples/*.md` interleaves prose, a command, and its **captured output**, and `showboat verify` re-runs every command to prove the output still matches the live CLI. So a demo is documentation *and* a doc-drift regression test.

Started as a spike (2026-06-05). [`../examples/cli-tour.md`](../examples/cli-tour.md) is the first demo: a verb tour (`describe`·`validate`·`ingest`·`explain`·`check`) that opens with the model it guards, then shows each verb with a "what just happened" note.

## Run / verify / regenerate

Prerequisites: a built CLI (`./gradlew :cli:installDist`), [`uv`](https://docs.astral.sh/uv/), and `jq`. No global showboat install — `uvx` fetches the pinned version on demand.

```bash
# re-check a demo against the live CLI (exit 0 = still matches, 1 = drift)
uvx showboat@0.6.1 verify examples/cli-tour.md

# print the commands that recreate it (output blocks omitted)
uvx showboat@0.6.1 extract examples/cli-tour.md
```

Run from the **repo root** — commands use repo-root-relative paths (`bin/rk …`, `cli/src/test/...`).

## The `bin/rk` shim

Demos call the CLI through [`../bin/rk`](../bin/rk), never a packaging artifact, so the doc text is identical whatever built the CLI. `rk` resolves to the **installDist JVM launcher** by default (always current); set **`RK_NATIVE=1`** to opt into a native binary for the startup win. Native is opt-in, never auto-preferred — a stale native artifact would otherwise silently shadow freshly-built code. (Mirrors the packaging policy: [`ARCHITECTURE.md`](ARCHITECTURE.md) §5.1, [`CLI-SPEC.md`](CLI-SPEC.md) §B1/§B2.)

## Determinism — the contract for a passing `verify`

`showboat verify` is **exact-match** — it has no normalization, redaction, or tolerance (by design; see showboat issue #13). So a demo only stays green if its commands produce byte-stable output. Two tiers:

1. **Prefer real determinism.** The CLI's JSON is already stable (no timestamps, no absolute paths, stable ordering). Keep it that way.
2. **Scrub the irreducible bits in-band** — inside the exec block, so `verify` re-runs the scrubber too. Known volatile spots:

| Volatile source | In-band fix |
|---|---|
| absolute paths (`dump --output`) | pipe through `sed 's#/Users/[^"]*/#<repo>/#g'` |
| German kernel messages (a failing model) | prefix `LANG=en_US.UTF-8` |
| field/array ordering, extra keys | project with `jq` (as the model section does) |

A fork of showboat to add a redaction hook is the wrong bet — in-band scrubbing covers every case and issue #13 shows upstream prefers the deterministic-output route.

## Expanding this approach (backlog)

The spike proved one demo; the method scales to a small suite, one demo per workflow:

- **Authoring loop** — `check` → `add` → re-`validate` → `ingest` (show the write path round-trips).
- **Edit loop** — `ingest` → re-express → `diff` → write-back (the F8 loop end-to-end).
- **Comprehension** — read foreign rules back and explain them (the T7 family).
- **Teaching / error paths** — deliberately author a wrong rule (inverted polarity, unguarded number) and show the lint/diagnostics fire. Needs the `LANG` pin above for the kernel's German messages.
- **`batch`** — show the self-describing, per-op-attributable output in one warm JVM.

Two cross-cutting follow-ups:

- **CI doc-drift gate** — run `uvx showboat@0.6.1 verify` on each demo in CI (after `:cli:installDist`), joining the F11 "loud detection at build" guardians. This is the highest-value next step: it turns the demos into a guard that the CLI's contract hasn't silently changed.
- **Reuse the verified snippets** — they can seed the README quickstart and the N1 skill examples. Mind the eval material boundary: keep any skill example structurally distinct from the eval tasks ([`EVAL-SPEC.md`](EVAL-SPEC.md) §3).
