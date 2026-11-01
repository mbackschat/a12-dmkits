# Packed-npm TypeScript consumer

A standalone TypeScript project that installs the **packed** `a12-interpreter` tarball and consumes it by package name — the npm resolution route, where [`../kotlin-jvm`](../kotlin-jvm) and [`../kotlin-js`](../kotlin-js) cover the Maven one.

```sh
./build.sh :interpreter:verifyExternalInterpreterTypeScriptConsumer
```

Its `check` script does three things in order, and each answers a different question: `tsc` proves the **published type declarations** compile a real consumer's code, `node --test dist/boundary-contract.test.js` runs the boundary contract below, and `node dist/index.js` proves the artifact actually **runs**.

The clock check loads the typed-authored [renewal fixture](../../src/commonTest/resources/workspace-preparation/self-contained/renewal.dm.json), so its model frame follows the same authoring API as the other consumer models. `:adapter:workspacePreparationFixtureGuardTest` checks the committed JSON against its author; `:adapter:syncWorkspacePreparationFixtures` regenerates it.

**The boundary contract is the unusual part.** [`boundary-contract.ts`](src/boundary-contract.ts) walks the published `.d.ts` with the TypeScript compiler API and classifies what the package exposes, so a type that leaks out of the intended surface is caught by a program rather than by review. Its own test, [`boundary-contract.test.ts`](src/boundary-contract.test.ts), keeps that classifier honest — a checker with no test can always classify everything as safe.

**The `guide-*.ts` files are documentation under test:** [`guide-finite.ts`](src/guide-finite.ts), [`guide-provider.ts`](src/guide-provider.ts) and [`guide-customization.ts`](src/guide-customization.ts) are the compiled sources behind the published API guide's TypeScript snippets, for the three workspace shapes a consumer chooses between — a finite set of models, a provider that resolves them on demand, and project field/condition callbacks. A snippet that stops compiling against the real artifact fails the build.
