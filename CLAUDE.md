# a12-rulekit — project instructions

Design and rationale live in [`docs/PROJECT-PROPOSAL.md`](docs/PROJECT-PROPOSAL.md); verified kernel-integration facts in [`docs/KERNEL-INTEGRATION-NOTES.md`](docs/KERNEL-INTEGRATION-NOTES.md).

## Test & sample document models

- **Never copy domains, fields, or rules directly from the a12 kernel repos** (`a12-kernel`, `a12-kernel-mm`, `a12-base`, etc.). Invent your own domain and naming instead. The one exception is **`a12-kernel-demo`** — copying or adapting its example models is fine.
- Whatever you invent **must make sense**: coherent domain, realistic field names and types, plausible rules. No nonsense placeholders.

## Build

Build with an isolated, public-repos-only Gradle home so the machine's global config (private registry) is bypassed, on JDK 21 — see the README's "Build & test" section. The kernel must first be published to `mavenLocal` via the sibling `a12-kernel-bootstrap` kit.
