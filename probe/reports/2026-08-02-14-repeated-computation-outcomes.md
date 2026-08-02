# Probe report — task 14 (repeated computation outcomes), 2026-08-02

**Headline: CLEAN — the cold agent discovered `model compute`, preserved the document, and correctly distinguished VALUE, CLEARED, and ERROR by their concrete row pointers.** The session produced no internal error, no raw model read, and no confirmed contract defect.

## Provenance and integrity

| | |
|---|---|
| native binary | `a12-dmkits 0.10.1`, freshly captured and built; `scripts/native-gate.sh` green (192 checks, 0 signatures) |
| start | fixture `interpreter/src/commonTest/resources/workspace-preparation/self-contained/permit-basic.dm.json` |
| agent | cold, no-history Codex session; prompts delivered one turn at a time with no look-ahead |
| audit | **CLEAN 6/6** — model mutation only through dmtool; no raw model read |
| digest | **CLEAN** — 18 calls, 0 nonzero exits, 0 RED FLAGS; 2 author / 1 verify / 15 orient |

## Gold result

After setting `SurveyCharge` to `maxFractionalDigits: 0` and `maxIntegerDigits: 4` through `field modify`, the agent ran the read-only computation projection for:

```json
{"Permit":{"SiteSections":[{"Area":75},{"SurveyCharge":50},{"Area":700}]}}
```

It correctly reported:

| concrete field | declaration | outcome |
|---|---|---|
| `Permit/SiteSections/SurveyCharge` | `Permit/SiteSections/SurveyCharge` | `value:1875` |
| `Permit/SiteSections[2]/SurveyCharge` | `Permit/SiteSections/SurveyCharge` | `cleared` |
| `Permit/SiteSections[3]/SurveyCharge` | `Permit/SiteSections/SurveyCharge` | `error:17500` |

It also explained that row 1 canonically omits `[1]`, that all results share one declaration, and that `written:false` means the input document was not changed.

## Observations and disposition

The agent suggested adding a reason to each errored computation result, adding partial-error counts to the top-level summary, and explaining top-level `ok:true` versus a nested computation `outcome:"error"` more prominently.

These are **not opened as defects from this run**. The task was completed correctly from the existing output, and the A12 source owner already defines the relevant shape: `IComputedFieldInstance` carries an exact `DocumentPointer`, attempted value, and optional `IMessage`, while `IDocumentComputationResult` partitions successful, errored, and cleared instances. Designing a new CLI-only diagnostic shape or changing the command-success envelope needs a separate owner-backed contract decision and repeated consumer evidence; neither is established here. Re-open only if another independent consumer fails to classify the nested outcome or a concrete use case requires exposing A12's computation error message.

## Harness correction found during analysis

The original digest incorrectly counted a legitimate nested `computed[].outcome:"error"` as a command-level internal error. `digest-probe.sh` now requires the top-level `outcome:"error"` together with `RK_INTERNAL_ERROR` (or an escaped exception / exit 70), and its self-test proves both directions: a nested computation error stays clean and a top-level internal error fires.

## Replay prompts

1. “Survey charges in this permit model must be whole numbers with at most four integer digits. Make that field constraint explicit and verify that the model remains valid.”
2. “Now calculate the model's computed fields for this permit application. Explain what happened to the survey charge in each site-section row, using the result itself to identify the row. Do not modify the document.” followed by the JSON instance above.

## Self-audit

The model was mutated once through `field modify`; all model inspection used dmtool's structured read surfaces. Directly-created JSON files were command inputs and the report, not hand-edited document-model files. Both computation runs returned `written:false`.
