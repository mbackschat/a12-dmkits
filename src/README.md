# The root project's source — tests only

**There is no main source here, and that is deliberate.** The root project `:` publishes no jar; it is the `dmkits` aggregator POM plus the host for tests that belong to no single module. If you are looking for production code, it is in the named modules — [`../core`](../core), [`../rulekit`](../rulekit), [`../modelkit`](../modelkit), [`../edit`](../edit), [`../schemakit`](../schemakit), [`../interpreter`](../interpreter), [`../consistency`](../consistency), [`../adapter`](../adapter), [`../cli`](../cli).

| source set | what lives there | run with |
|---|---|---|
| [`test/`](test) | the cross-kit integration suite, the structural-law guardians, the doc and gap-ledger guards, and the published-API lock | `./build.sh :test` |
| [`noBackendTest/`](noBackendTest) | the degraded lane: the kits with **no** SPI backend on the classpath, proving they still load, build and render — and report `STRUCTURAL_ONLY` rather than claiming a verdict nobody gave | `./build.sh :noBackendTest` |
| [`releaseGuardTest/`](releaseGuardTest) | rehearsals of the release publishers against a scratch tree, so a publishing defect surfaces before it touches a public repository | `./build.sh :interpreterReleaseGuardTest` (the task's name differs from the source set's) |

**Why the kits' tests live here rather than in each kit.** The suites genuinely span all four — a rule authored through `rulekit` against structure built through `modelkit`, both gated by the kernel — so they need every kit plus [`../adapter`](../adapter)'s kernel oracle at test scope. Putting them at the root keeps that dependency out of the kits themselves: `rootTest → adapterMain → {kit}Main` is acyclic, and no kit acquires a test-scope edge to the kernel.

**Several guards read repository files at runtime and therefore do not run under plain `:test`.** They live in their own tasks with exactly those files declared as *their* inputs, because declaring a doc on the broad suite would re-run hundreds of tests on any Markdown edit, while omitting it lets the task go up-to-date into a false green. `./build.sh :test` lists which tasks `-Pfast` skips; `:docGuardTest` is the one a docs change needs.

Testing map — what kind of test goes where: [`docs/TESTING-SPEC.md`](../docs/TESTING-SPEC.md). Which lane owns what: [`docs/AGENT-RUN-DISCIPLINE.md`](../docs/AGENT-RUN-DISCIPLINE.md).
