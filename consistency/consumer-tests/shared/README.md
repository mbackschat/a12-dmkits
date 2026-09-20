# The shared consumer corpus — one set of cases, every host

Not a project: the cases, models and report contract that **both** packaged arms replay, so *"the same corpus"* is a structural fact rather than a promise. [`node/`](../node) and [`browser/`](../browser) import from here; the JVM suites in [`../../src/commonTest`](../../src/commonTest) replay the same parity reports.

**The suites are data, and that is the point.** Each host used to carry a hand-written floor (`outcomes.length >= 18`, `MINIMUM_CASES = 18`) that had to be bumped in two files whenever a case was added — a maintenance defect paid for three times. [`suites.ts`](suites.ts) now exposes the suites as data, so a host runs them without knowing how many cases any of them has, and adding a case reaches every arm at once.

| file | what it carries |
|---|---|
| [`cases.ts`](cases.ts) | the accepted / rejected / inconclusive corpus both packaged arms run |
| [`suites.ts`](suites.ts) | those cases as data, so no host hard-codes a count |
| [`editor.ts`](editor.ts) | the editor's draft, impact and snapshot features exercised **together**, including a rejected candidate — each has its own unit acceptance elsewhere; this holds that they compose |
| [`references.ts`](references.ts) | the retained-reference controls, replayed through whichever host loaded the packed package |
| [`parity.ts`](parity.ts) | the three-arm parity runner: one full report contract, compared identically on every host |
| [`models/`](models) | the own-domain fixtures — a storefront model plus the five catalog-reference resolution cases (absent, ambiguous, declaring-wins, no-fallback, resolved) |
| [`parity/`](parity) | the committed expected reports, their manifest and their unreadable-source fixtures — see [`parity/README.md`](parity/README.md) |

**Parity compares the FULL report, not a summary.** The oracle for *"JS matches JVM"* is the same fixtures through Java, packed Node and real-browser consumers with the whole portable report compared — a status-only comparison would pass while `findings`, `checksCompleted` or `gaps` diverged.

**Never edit `parity/reports/` by hand.** Regenerate with `./build.sh :consistency:syncCheckerParityCorpus`, which packs the tarball and installs it first, so the corpus is written by the artifact a consumer receives.
