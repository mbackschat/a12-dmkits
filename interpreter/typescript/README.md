# The TypeScript facade

**Part of the published `a12-interpreter` package, not a test.** This is the hand-written TypeScript surface that sits in front of the Kotlin/JS output — so a TypeScript consumer meets idiomatic TypeScript types and errors rather than the shapes a Kotlin/JS compiler happens to emit.

**`src/` — what ships.** [`index.ts`](src/index.ts) is the public barrel and the single place the surface is declared. Beside it: [`errors.ts`](src/errors.ts) (the error taxonomy a consumer catches — document encoding, coordinates, model ownership, preparation, source providers), [`documents.ts`](src/documents.ts), [`evaluation.ts`](src/evaluation.ts) and [`customization.ts`](src/customization.ts). [`internal/bridge.ts`](src/internal/bridge.ts) is the seam to the Kotlin/JS module and is deliberately not exported — the facade exists precisely so that boundary is not a consumer's problem.

**`scripts/` — the API surface, derived rather than maintained.** [`public-api.mjs`](scripts/public-api.mjs) extracts the published surface and [`api-reference.mjs`](scripts/api-reference.mjs) renders the reference documentation from it, each with its own test. The point is that the documentation cannot drift from the types: it is generated from them, and the generator is tested.

**What proves it works is elsewhere, on purpose.** The facade's own tests cannot establish that a *consumer* can use it — that needs the packed artifact. See [`../consumer-tests/typescript`](../consumer-tests/typescript) for the npm-installed TypeScript consumer and its boundary contract, and [`../consumer-tests/browser`](../consumer-tests/browser) for the bundled browser arm.

Module instructions: [`../CLAUDE.md`](../CLAUDE.md).
