# `a12-consistency` — real-browser consumer

A Vite-bundled TypeScript page that imports the packed `a12-consistency` tarball by package name, replays the [shared consumer cases](../shared) in **real system Chrome**, and fails the build on any case the page reports as failed.

Run it from the repository root — never by hand, because the tarball must be packed and installed into this project first:

```sh
./build.sh :consistency:browserConsumerTest      # needs pnpm + a system Chrome
```

**Why a real browser and not `jsdom` or Karma.** The acceptance names the *packed artifact*, so the thing under test has to be what a consumer installs. `jsdom` is not a browser, and enabling Karma pulls its whole transitive tree into the committed [`yarn.lock`](../../../gradle/kotlin-js-store/yarn.lock) — it would also exercise the **compiler's output** rather than the bundled tarball, which is the one substitution this arm exists to refuse.

## Layout

**`src/` — what runs in the page.** [`main.ts`](src/main.ts) is the replay itself: the same three moves the Node arm makes, with a browser's spellings substituted. [`cold.ts`](src/cold.ts), [`prepared.ts`](src/prepared.ts) and [`after-edit.ts`](src/after-edit.ts) are the browser arms of the resource boundaries the Node project measures on a heap API, each served by its own page ([`cold.html`](cold.html), [`prepared.html`](prepared.html), [`after-edit.html`](after-edit.html)) so one boundary is never timed inside another.

**`scripts/` — what drives the page from outside.** [`replay.ts`](scripts/replay.ts) serves the built bundle, opens it in Chrome through `playwright-core`, and fails on any reported failure; [`harness.ts`](scripts/harness.ts) is the shared driver the boundary scripts use. These compile under their own `tsconfig` because they are Node programs, not browser code.

**Three TypeScript configurations, deliberately.** [`tsconfig.json`](tsconfig.json) covers the browser sources, [`tsconfig.scripts.json`](tsconfig.scripts.json) the Node drivers, and [`tsconfig.config.json`](tsconfig.config.json) the Vite config itself. `pnpm run check` type-checks all three; the Gradle task runs `pnpm run verify`, which builds and then replays.

**This arm is off `check` and local/release-gated,** where the Node arm runs in push CI: it needs pnpm and a browser, and a JVM lane that silently required them would fail on machines where the rest of the build is fine.
