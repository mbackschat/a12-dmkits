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

**Every report also says what it did NOT check.** `checksCompleted` names the families that ran and `gaps` names what nobody examined — a measured storefront model returns 18 families and 7 gaps (2026-09-13). A consumer that shows a user "no problems found" without surfacing the gaps is reporting a verdict the checker did not give.

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
  "justifiesRejection": true
}
```

`code` is A12's own `MVK_*` code wherever the Kernel has one, so it joins against A12 diagnostics directly. Codes beginning `CK_` are this checker's own and mark a tier A12 has no code for — `CK_MODEL_NOT_BUILDABLE` means the source never loads at all, so no consistency verdict about that model exists in either direction.

**Read `justifiesRejection`, not `severity`, to decide whether a model is rejected.** The two come apart on purpose: `category` is `A12_CONSTRAINT` for a measured Kernel verdict and `AUTHORING_POLICY` for a house rule that the Kernel accepts, and an `AUTHORING_POLICY` finding at `ERROR` deliberately does not make a model invalid. `justifiesRejection` is the conjunction, published because a consumer cannot derive it from the fields without knowing that rule.

`at` is the declaration's model path, or `null` for a finding about the model as a whole.

### `diagnostics` — what the checker could not read

A diagnostic is the checker speaking about itself; a finding is A12's verdict about a declaration. **They stay separate arrays and a consumer must not merge them**, because a merged list reports a model defect that was never measured — which is precisely the conflation the JVM `ModelLoader.loadAndCheck` makes. Each carries a `code`, `modelId`, `explain`, `at` and the `diagnosticLabel` the caller supplied.

### `checksCompleted` and `gaps` — the coverage evidence

`checksCompleted` is evidence that a family ran, never a claim that the families are sufficient. `gaps` is the other half: each entry is a `reason` and an optional `at`, naming something nobody examined. Gaps are not defects in the model — they are the checker disclosing its own reach, and they include both permanent limits (the parser checks the shape the grammar's productions share, not each operator's argument arity) and a standing entry for every family not yet implemented.

## Calling it from Node

The worked example is [`consistency/consumer-tests/node`](../consistency/consumer-tests/node), which is executable rather than illustrative: `./build.sh :consistency:nodeConsumerTest` packs the library into a real npm tarball, clean-installs it into a staged copy of that project, and runs it. It needs `node` and `npm` and reaches no network — the package declares no runtime dependency.

That test is also the only thing in the build that can catch an unconsumable artifact. Every other test has the checker on the compile classpath by construction, and the JS target compiled green for the module's whole life while emitting nothing importable.

## What this API does not promise

- **No `VALID`.** See the first section; this is the contract, not a temporary state.
- **No stable Kotlin/TypeScript object model.** `checkModelJson` returns a string. The JSON shape above is the contract; the Kotlin types behind it are not exported and will change.
- **No guidance text on findings.** A finding carries its code and an `explain` of what was observed, and no `fix`. The corrective prose has one owner on the JVM side (`StructuralKernelFix`), and restating it here would mint a second live copy; sharing it is part of the report contract still to be designed.
- **No document validation.** This checker reads a DocumentModel and says whether A12 would accept the model. Evaluating a Document against a model is `:interpreter`, a different component with a different contract.
