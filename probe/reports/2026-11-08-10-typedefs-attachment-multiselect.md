# Probe report — task 10 (typedefs, attachment, multi-select), 2026-11-08

**Headline: both tiers CLEAN and matched the gold on all six turns; sonnet found a silent-loss defect in `typedef modify`.** A spec that carries `kind` replaces the type's whole per-kind config, so a key the caller leaves out disappears from the type and from every field typed by it, with `applied` and no diagnostic. The agents avoided it only by resending the full config.

## Provenance and integrity

| | |
|---|---|
| native binary | as in [the task 08 report](2026-11-08-08-booking-date-ranges.md) |
| start | `clean` (from scratch) |
| agents | first runs under [`clean-agent.ts`](../../scripts/typescript/clean-agent.ts); haiku at default effort, sonnet at `xhigh` |
| audit | **CLEAN 7/7** on both |
| shared temp directory | one `TMPDIR` per user until `5d020421b`; the two agents wrote no file name in common there |
| digest | haiku 75 calls, 1 nonzero, 0 RED FLAGS · sonnet 195 calls, 2 nonzero, 0 RED FLAGS |

## Gold result

| turn | haiku | sonnet |
|---|---|---|
| T1 one NUMBER `AMOUNT` typedef, both cost fields typed by reference | ✓ `MoneyAmount` | ✓ `Money amount` (the kernel accepts the space; `model check` KERNEL_CONFIRMED) |
| T2 scale changed once, in the typedef | ✓ 3 decimals, both fields inherit | ✓ |
| T3 `group attachment` | ✓ | ✓ |
| T4 `group multiselect` | ✓ free-text values | ✓ free-text values |
| T5 `[ApprovedCost] > [EstimatedCost]` | ✓ presence-guarded | ✓ presence-guarded |
| T6 read-back through `typedef read` / `group read` | ✓ | ✓ |

## Observations and disposition

| observation | basis | disposition |
|---|---|---|
| `typedef modify` with `{"kind":"NUMBER","number":{"maxFractionalDigits":3}}` drops `unit`, `min` and `minFractionalDigits`; `kind` alone leaves `{"maxFractionalDigits":0}`; both `applied`, no diagnostic | reproduced with the native binary | defect class shared with `field modify` ([task 02 report](2026-11-08-02-registration-field-constraints.md)); fixed in `546ae2cf9` |
| `schema typedef modify` serves the add contract (`$id …/typedef-add.schema.json`, "INPUT of … typedef add") | `schema typedef modify` | defect, fixed in `546ae2cf9`: the shared schema now names `typedef modify` and its whole-type replacement, as `computation-add` does for `computation modify` |
| both agents extracted only the `number` block from a 20 KB `schema typedef add` (`grep -A12 '"number"'`, a python filter) | transcripts | evidence for a per-kind schema drill-down; the agent's "51 KB" was three schemas chained in one command |
| `model describe` omits typedefs, the field-to-typedef link, `required` and rules | `model describe` output | discoverability gap, raised by both runs; fixed 2026-11-08 as CG30 |
| the second kernel diagnostic for `field add --name "Bad Name"` has no `code` | `field add --dry-run` | likely faithful: the kernel message carries no `[CODE]` suffix; not verified at the kernel layer |
| a euro currency cannot be expressed; NUMBER `unit` is `AMOUNT`, `PERCENT` or `PERMILLE` | `schema typedef add` | not a dmtool gap unless A12 has a currency concept; not pursued |

## Replay prompts

The six turns of [task 10](../tasks/10-typedefs-attachment-multiselect.md), verbatim, after [`agent-preamble.md`](../agent-preamble.md).

## Self-audit

Sonnet shrank the defect to a standalone dmtool-only script with a control row (`field modify` with `required` alone keeps the config). Haiku noted that its own typedef description went stale after T2; that is its content, not a tool defect.
