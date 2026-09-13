# The portable consistency checker — public API

> **What this is.** The consumer-facing contract of `:consistency`, the Kernel-free consistency checker for a DocumentModel's rules and computations: the entry point, what a report means, and how to call it from Node. Design and scope live in [PORTABLE-CONSISTENCY-PROPOSAL.md](PORTABLE-CONSISTENCY-PROPOSAL.md); what has landed and what has not is the Status column of [PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md](PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md).

## Read this first: the checker cannot tell you a model is VALID

**A report with no findings does not mean the model is good.** It means this checker found nothing wrong in the families it has implemented, and it says so as `INCONCLUSIVE` — there is no `VALID` status, by construction and not by omission. Treating `INCONCLUSIVE` as a pass is the single most likely way to misuse this API, which is why the status is a word rather than a boolean and why `complete` sits beside it.

Two statuses exist today:

| status | means | safe reading |
|---|---|---|
| `INVALID` | at least one measured A12 acceptance constraint is violated | the Kernel would reject this model; the findings say where |
| `INCONCLUSIVE` | no violation was established, and coverage is incomplete | **not** "valid" — only "nothing this checker looked at was wrong" |

`complete` is `false` on every report today and is published anyway, so that a consumer switching on it keeps working when it starts carrying information. `VALID` arrives with the coverage evidence that earns it, not with the next family.

**Every report also says what it did NOT check.** `checksCompleted` names the families that ran and `gaps` names what nobody examined — a measured storefront model returns 25 families and 4 gaps (2026-09-13). A consumer that shows a user "no problems found" without surfacing the gaps is reporting a verdict the checker did not give.

## The entry point

One function, strings in and strings out. It is deliberately the whole JavaScript surface: the checker is not feature-complete and its Kotlin types are still moving, so exporting them would publish an ABI that has to change under every consumer.

```js
import { checkModelJson } from 'a12-consistency';

const report = JSON.parse(checkModelJson(modelJson));
```

`checkModelJson(modelJson, diagnosticLabel?)` takes the DocumentModel as JSON text and returns a report as JSON text. The optional label is echoed back on diagnostics, for a caller checking many sources that needs to say which one spoke.

**It never throws.** An unreadable source — malformed JSON, a model the reader refuses — comes back as a report whose `diagnostics` explain the failure at the source level, with `findings` empty and `checksCompleted` empty. No `try`/`catch` is needed to get an answer, and a caller that wraps it in one will never see the branch.

From Kotlin or Java on the JVM the same check is `check(ModelSource(json))`, returning the typed `ConsistencyReport`; `report.toJson()` produces the identical wire shape.

## The report

```json
{
  "modelId": "storefront",
  "status": "INVALID",
  "complete": false,
  "findings": [ … ],
  "diagnostics": [ … ],
  "checksCompleted": [ "DECLARED_REFERENCES", "CONDITION_REFERENCES", … ],
  "gaps": [ … ]
}
```

All seven keys are always present. An empty array and an absent key would mean opposite things — no gap remains, against nothing was disclosed — so nothing is omitted when empty, and an absent path is JSON `null` rather than a dropped key or `""`.

### `findings` — what A12 says about a declaration

```json
{
  "code": "MVK_INVALID_ENTITY",
  "category": "A12_CONSTRAINT",
  "severity": "ERROR",
  "explain": "in `errorCondition`: no declared element matches `/Storefront/NoSuchField`, and no model-wide field short name resolved it",
  "at": "/Storefront/EmailRequiredForPaidOrder",
  "justifiesRejection": true,
  "corrective": {
    "fix": "The group or field name in the condition could not be resolved in the rule's scope. Use its ABSOLUTE path …",
    "meaning": "MVK_INVALID_ENTITY (\"the group or field 'X' is not valid\") means the kernel could not resolve that name …"
  }
}
```

`code` is A12's own `MVK_*` code wherever the Kernel has one, so it joins against A12 diagnostics directly. Codes beginning `CK_` are this checker's own and mark a tier A12 has no code for — `CK_MODEL_NOT_BUILDABLE` means the source never loads at all, so no consistency verdict about that model exists in either direction.

**Read `justifiesRejection`, not `severity`, to decide whether a model is rejected.** The two come apart on purpose: `category` is `A12_CONSTRAINT` for a measured Kernel verdict and `AUTHORING_POLICY` for a house rule that the Kernel accepts, and an `AUTHORING_POLICY` finding at `ERROR` deliberately does not make a model invalid. `justifiesRejection` is the conjunction, published because a consumer cannot derive it from the fields without knowing that rule.

`at` is the declaration's model path, or `null` for a finding about the model as a whole.

**`corrective` is A12's own guidance for the CODE, and it is `null` far more often than not.** Its `fix` says what to change and its `meaning` says what the code classifies wherever it is raised — neither is about the declaration in front of you, which is what `explain` is for. The texts are PROJECTED from the dmkits' Java diagnostic owners rather than restated here, over the codes this checker's own sources spell, so there is one live statement of each and a build guard fails when they drift. That bound is a superset of what the checker can actually report — a code it compares an authored value against is spelled too — so a second test measures every entry against a real report and names the residue. A `null` is the ordinary case: most codes are explained by the operator catalog, whose entries are keyed by operator rather than by code, so a code-keyed lookup has nothing to return. The member is always present, because an omitted one and a `null` one would look identical to a consumer reading an older report.

### `diagnostics` — what the checker could not read

A diagnostic is the checker speaking about itself; a finding is A12's verdict about a declaration. **They stay separate arrays and a consumer must not merge them**, because a merged list reports a model defect that was never measured — which is precisely the conflation the JVM `ModelLoader.loadAndCheck` makes. Each carries a `code`, `modelId`, `explain`, `at` and the `diagnosticLabel` the caller supplied.

### `checksCompleted` and `gaps` — the coverage evidence

`checksCompleted` is evidence that a family ran, never a claim that the families are sufficient. `gaps` is the other half: each entry is a `reason` and an optional `at`, naming something nobody examined. Gaps are not defects in the model — they are the checker disclosing its own reach, and they include both permanent limits (the parser checks the shape the grammar's productions share, not each operator's argument arity) and a standing entry for every family not yet implemented.

**A family that ran can still have declined a DECLARATION, and that is disclosed too.** `checksCompleted` names a family once for the whole report, so a family that read most declarations and skipped one would otherwise publish "examined, nothing found" about the one it skipped. Every such skip carries its own gap, `at` the declaration it concerns: a range whose (format, separator) pair the sweep never put to the kernel, an enumeration whose `values` or `categories` is not the shape the pairing law was measured on, an enum value carrying a supplementary-plane character. Two are model-level instead, because they silence a family everywhere at once: a character restriction spelled with a combined grapheme, and the field types declared under `content.typeDefinitions`, which are read for their ids alone. All of it is under-reporting — the safe direction — but undisclosed under-reporting is indistinguishable from a clean result.

## Calling it from Node

The worked example is [`consistency/consumer-tests/node`](../consistency/consumer-tests/node), which is executable rather than illustrative: `./build.sh :consistency:nodeConsumerTest` packs the library into a real npm tarball, clean-installs it into a staged copy of that project, and runs it. It needs `node` and `npm` and reaches no network — the package declares no runtime dependency.

That test is also the only thing in the build that can catch an unconsumable artifact. Every other test has the checker on the compile classpath by construction, and the JS target compiled green for the module's whole life while emitting nothing importable.

## What this API does not promise

- **No `VALID`.** See the first section; this is the contract, not a temporary state.
- **No stable Kotlin/TypeScript object model.** `checkModelJson` returns a string. The JSON shape above is the contract; the Kotlin types behind it are not exported and will change.
- **No guidance for most codes.** A finding's `corrective` is present whenever an A12 diagnostic owner explains its code and `null` otherwise, which is the common case — see the `findings` section. What is deliberately NOT delivered is the operator catalog's own guidance, 293 KB of it: no finding here names an operator id, so there is nothing to join it against, and packaging it would ship data nothing reads. That changes when a finding carries an operator.
- **No document validation.** This checker reads a DocumentModel and says whether A12 would accept the model. Evaluating a Document against a model is `:interpreter`, a different component with a different contract.
