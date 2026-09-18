# a12-consistency

A standalone, Kernel-free consistency checker for A12 **DocumentModels** — the rules and computations, checked without a server, a Kernel or a licence. Kotlin Multiplatform: one implementation for the JVM, Node and the browser.

**Read this first: there are two questions here, and only one of them has a pass.** *"Would A12 accept this model?"* is a claim about every rule A12 has, and this checker is not feature-complete, so it answers `INVALID` or `INCONCLUSIVE` and never `VALID`. *"Did everything this checker implements pass?"* is a question about a finite, known list — so it does have a pass, published as `outcome: 'ALL_CHECKS_PASSED'`. The two are not the same green light, and `complete: false` is what keeps them apart.

```ts
import { checkEditJson, checkModelJson } from 'a12-consistency';

const report = JSON.parse(checkModelJson(modelJson));
report.status;    // 'INVALID' | 'INCONCLUSIVE' — A12's verdict; never 'VALID'
report.outcome;   // 'REJECTED' | 'ALL_CHECKS_PASSED' | 'NO_REJECTION' | 'NOT_ESTABLISHED'
report.complete;  // always false today — see `gaps`
report.findings;  // A12's verdict per declaration, with its code, span and corrective

// Before saving an edit: judged by what IT introduced, not by the state of the model it lands in.
const edit = JSON.parse(checkEditJson(baselineJson, editedJson));
edit.introduced;
```

**`ALL_CHECKS_PASSED` means every check this build implements ran and none rejected the model** — a scoped pass, and the scope is the point. It requires the full family denominator to have completed *and* no diagnostic to have been raised, so a model with one declaration the checker could not read gets `NO_REJECTION` instead: families completed, but something went unread. It is not a claim that A12 would accept the model, and `complete: false` still rides along on it. Use it as "this build has nothing to say against your model", never as "your model is valid".

Strings in, strings out, on every call. `checkDraftJson` checks text an editor has not saved, `impactOfJson` says which declarations a change reaches rather than which it broke, and `snapshotOfJson` returns a handle that holds one analysis across many questions — the editor path, where a keystroke costs one check rather than a full re-admission. The full contract — the report's members, each call's arguments, and the JVM entry points — is [`docs/CONSISTENCY-API.md`](https://github.com/mbackschat/a12-dmkits/blob/main/docs/CONSISTENCY-API.md).

Worked, executable consumers live beside the module: [`consumer-tests/node`](consumer-tests/node) for a plain Node project (including a CI gate that cannot manufacture a pass) and [`consumer-tests/browser`](consumer-tests/browser) for a bundled browser page. Both install the packed tarball and import it by package name.

MIT licensed, and independent: not affiliated with, endorsed by or supported by mgm technology partners, and not an A12 product.
