# Browser consumer — and the published showcase

This project has **two jobs at once**, which is why it is larger than its siblings: it is the real-browser arm of the consumer tests, and it is the source of the [live showcase](https://mbackschat.github.io/a12-interpreter-releases/showcase/) published to the interpreter's release mirror. Both run against the **packed** `a12-interpreter` tarball, Vite-bundled — never against the compiler's output.

```sh
./build.sh :interpreter:verifyExternalInterpreterBrowserConsumer
```

`pnpm run verify` builds, runs the Node-side script tests, then drives a real Chrome through `playwright-core` for a **smoke** pass and an **offline** pass.

## Layout

**`src/` — what runs in the page.** [`main.ts`](src/main.ts) and [`showcase.ts`](src/showcase.ts) are the showcase itself; [`performance.ts`](src/performance.ts) times first-answer and warm validation in the page; [`qualification.ts`](src/qualification.ts) and [`delivery.ts`](src/delivery.ts) report what the page actually loaded; [`service-worker.ts`](src/service-worker.ts) is what makes the offline pass possible — and offline is a real claim about this artifact, not a nicety, since the interpreter needs no server.

**`scripts/` — Node programs that drive or measure the page.** [`smoke.ts`](scripts/smoke.ts) and [`offline.ts`](scripts/offline.ts) are the two browser passes; [`performance.ts`](scripts/performance.ts) collects the published figures and [`performance-policy.ts`](scripts/performance-policy.ts) decides whether a sample is admissible at all; [`write-bundle-size.ts`](scripts/write-bundle-size.ts) measures the delivered bundle so the README's size claim is generated rather than remembered. [`harness.test.ts`](scripts/harness.test.ts) and [`performance-policy.test.ts`](scripts/performance-policy.test.ts) test the drivers themselves — a measurement harness with no test reports whatever it reports.

**`public/examples/documents/`** carries the own-domain sample documents the showcase offers: a valid permit, a rule firing, a computation, a computed error, a formally invalid document and a workspace-invalid one — one per outcome a reader should be able to see for themselves.

**Everything here is TypeScript, enforced.** `verifyExternalInterpreterBrowserTypeScriptSources` rejects a checked-in `.js` or `.mjs` sibling; generated JavaScript exists only under ignored build output. Three `tsconfig` files separate the browser sources, the Node drivers and the service worker, because they target three different runtimes.

**The size claim is guard-locked.** `verifyInterpreterBrowserSizeDocumentation` fails the build when a public README's browser-size figure drifts from the packed showcase measurement, and `syncInterpreterBrowserSize` rewrites every such claim from it.
