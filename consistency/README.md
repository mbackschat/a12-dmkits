# a12-consistency

A standalone, Kernel-free consistency checker for A12 **DocumentModels** — the rules and computations, checked without a server, a Kernel or a licence. Kotlin Multiplatform: one implementation for the JVM, Node and the browser.

**Read this first: the checker cannot tell you a model is VALID.** It reports `INVALID`, or `INCONCLUSIVE` with the coverage it achieved — never a pass. It is not feature-complete, so "no findings" means "nothing I checked rejected this", and every report carries `complete: false` and the gaps that explain why. A consumer that reads no findings as a green light is reading something the report never said.

```ts
import { checkEditJson, checkModelJson } from 'a12-consistency';

const report = JSON.parse(checkModelJson(modelJson));
report.status;   // 'INVALID' | 'INCONCLUSIVE'
report.complete; // always false today — see `gaps`
report.findings; // A12's verdict per declaration, with its code, span and corrective

// Before saving an edit: judged by what IT introduced, not by the state of the model it lands in.
const edit = JSON.parse(checkEditJson(baselineJson, editedJson));
edit.introduced;
```

The whole JavaScript surface is those two functions, strings in and out. The full contract — the report's members, `checkDraft` for unsaved editor text, `impactOf` for what a change reaches, `snapshotOf` for holding one analysis across many questions, and the JVM entry points — is [`docs/CONSISTENCY-API.md`](https://github.com/mbackschat/a12-dmkits/blob/main/docs/CONSISTENCY-API.md).

Worked, executable consumers live beside the module: [`consumer-tests/node`](consumer-tests/node) for a plain Node project (including a CI gate that cannot manufacture a pass) and [`consumer-tests/browser`](consumer-tests/browser) for a bundled browser page. Both install the packed tarball and import it by package name.

MIT licensed, and independent: not affiliated with, endorsed by or supported by mgm technology partners, and not an A12 product.
