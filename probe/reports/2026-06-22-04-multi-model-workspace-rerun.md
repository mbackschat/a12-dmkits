# Probe report — task 04 (multi-model workspace), re-run 2026-06-22 (release binary)

**Headline: CLEAN — 5 turns, 0 internal errors, audit CLEAN 6/6, 0 RED FLAGS.** A re-run on the release-candidate native binary. The multi-file surface — two models, `include add` across them, a shared typedef, a cross-include rule, and a rename-with-includes — authored kernel-valid; every non-success was a legitimate, well-diagnosed rejection (no crash, no exit 70). Two genuine DX findings (one a **missed sibling of the locator-corrector class**, one a read-surface inconsistency on included fields) + one **known deferred capability** (typedef-import has no producing verb).

## Provenance & integrity (the objective backstop)

| | |
|---|---|
| native binary | `a12-dmkits 0.6.0` (kernel 30.8.1; A12 Tools 2025.06-ext5) — release candidate, native gate GREEN |
| start mode | **clean** (from-scratch; the agent authored several models in one workspace) |
| anti-cheat audit | **CLEAN 6/6** — all model files mutated only by dmtool; no hand-edit; no raw `.dm.json` read |
| digest | **CLEAN · 0 RED FLAGS** — 9 nonzero exits, all legitimate (a self-caught re-applied `apply`, two locator-flag slips, the typedef-import gap ×3, the included-field read, the `RK_MOUNT_NOT_EDITABLE` reject) |

## Findings (classified)

| # | finding | target | class | resolution |
|---|---|---|---|---|
| 04-whereused-locator | `field read --field <p>` self-corrects ("locates by a POSITIONAL `<fieldPathInModel>`, not a flag"), but `where-used --field <p>` gets a **bare picocli** "Unknown option: '--field'" — `where-used` slipped through the locator corrector that claims to cover "any locator-positional verb" | CLI (diagnostics) | **DEFECT (confirmed) — missed #80 sibling** | reproduced; fix candidate — extend the locator corrector to `where-used` (root-cause class sweep) |
| 04-readinclude | `model describe -w .` lists an included field (`/Order/Product/Price`), but `field read /Order/Product/Price` refuses it `RK_NO_SUCH_FIELD` with a fix (*"list them with `field read` — no arg"*) whose listing **omits included fields** — the agent is bounced between two surfaces that disagree | CLI/material | **DX (confirmed)** | reproduced; fix candidate — the not-found fix should say included fields read on their owning model (the listing shows only own fields) |
| 04-import | `typedef import` requires a `purpose=typeDefinitions` source model, but **no verb creates one** (`model new` always emits a doc model with a root group, which the kernel refuses to import) — dead-ends the literal "one shared type defined once" reading | CLI (capability) | **known deferred** | the TDM-creation gap (README backlog); an earns-a-verb decision (`model new --purpose` / `typedef extract --to-new-model`), not a defect — the working pattern (define in a doc model + `include`) succeeded |
| — | `RK_MOUNT_NOT_EDITABLE` (a rule's error field can't live inside an include mount) taught the fix cleanly via the diagnostic; the agent recovered without help | skill | not a finding | four-lane: diagnostic-carried → no skill note |

## Confirmed repros (verified on the release binary)

```
# 04-whereused-locator — the corrector fires for field read but NOT where-used:
dmtool -m Order_DM.json field read   --field /Order/Buyer   #  → corrector: "…POSITIONAL <fieldPathInModel>, not a flag…"
dmtool -m Order_DM.json -w . where-used --field /Order/Buyer #  → bare picocli "Unknown option: '--field'"  (no corrector)

# 04-readinclude — describe shows it, field read denies it:
dmtool -m Order_DM.json -w . model describe          #  lists /Order/Product/Price
dmtool -m Order_DM.json -w . field read /Order/Product/Price
#  → refused RK_NO_SUCH_FIELD, fix "list them with `field read` — no arg"  (that list omits included fields)
```

## Replay log (verbatim, in order)
1. create a shared catalog model … then create a separate order model …
2. in the order model, include the catalog …
3. both models' money amounts should use one shared "Money" type defined once.
4. add a rule on the order: when the order is placed, the product's sku must be filled.
5. rename the order's customer field to "buyer" everywhere.

## Self-audit
CLEAN: every model mutation via dmtool verbs; spec/op files were inputs; the `python3` calls parsed the manifest/schema catalogs (machine-read contracts), not model JSON. Confirmed by the audit (6/6) and digest (0 RED FLAGS). One self-caught non-defect: a re-applied `apply` hit duplicate-field rejections (the first had committed — `committed:true` is authoritative; per-op `written:false` is staging).

## Resolution (2026-06-22)
- **CLEAN re-run** — no crash, no real behavioral defect; the include / typedef / rename surfaces behaved as specified.
- **04-whereused-locator — confirmed, fix candidate** (extend the locator corrector to `where-used` — a root-cause sweep of the #80 class). **04-readinclude — confirmed, fix candidate** (corrective wording for an included path). **04-import — known deferred** (TDM-producing verb; earns-a-verb decision).
