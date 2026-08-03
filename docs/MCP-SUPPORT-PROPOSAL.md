# dmtool over MCP — proposal

**Status:** PROPOSAL (2026-08-03). Intent + public contract, nothing built. Intended home if adopted: `docs/MCP-PROPOSAL.md`, archiving into the owning specs as phases land.

**Scope.** Expose the dmtool surface over the Model Context Protocol, revision **`2026-07-28`**, as a **stdio server inside the existing native binary** (`dmtool mcp`). Hosted HTTP and MCP Apps are named as later phases with their own gates; they are not proposed here.

**Owner decisions already taken** (2026-08-02): stdio-first; target `2026-07-28` hand-rolled with the official Java SDK kept as a possible later substitution; ~15 target-shaped tools rather than one-per-verb; filesystem paths for stdio, server-minted handles only if hosting ever happens.

---

## 1. Why this is a small change

MCP `2026-07-28` removed the `initialize`/`notifications/initialized` handshake, protocol-level sessions, and the `Mcp-Session-Id` header. Every request is self-contained, carrying its protocol version and client capabilities in `_meta` (`io.modelcontextprotocol/protocolVersion`, `io.modelcontextprotocol/clientCapabilities`, `io.modelcontextprotocol/clientInfo`). Servers do not initiate JSON-RPC requests; server-to-client interaction rides Multi Round-Trip Requests, which we do not use.

That is the same invocation model dmtool already has. [`docs/CLI-SPEC.md`]($HOME/Projects/oss/a12/a12-rulekit/docs/CLI-SPEC.md) §3a states it in the tool's own words: *"a fully-specified, stateless invocation is easier for an agent to generate and to reproduce/debug."* The protocol converged on the same position, so the adapter is genuinely thin.

Three existing artifacts are reused verbatim rather than re-derived:

| MCP field | dmtool artifact |
|---|---|
| tool `outputSchema` | the published `result.schema.json` |
| `structuredContent` | the result envelope, unchanged |
| `content[0].text` | the `--text` rendering produced by `TextRenderer` |

The result envelope's diagnostics contract also lands in the channel the spec designed for it: MCP distinguishes **protocol errors** (unknown tool, malformed request) from **tool execution errors** (`isError: true`) precisely because models can self-correct from the second. That is CLI-SPEC §2's corrective-diagnostics principle restated by the protocol.

## 2. Architecture

One new Gradle module, `:mcp`, depending on `:cli`. It invokes the same picocli tree **in process** — there is no second execution path, no forked subprocess, and therefore no way for the MCP surface to drift from the CLI's behaviour.

```
McpToolRegistry     derived from the live picocli tree (same source as `manifest`)
McpDispatcher       method + params -> result; names no protocol type
   └── StdioTransport      hand-rolled, permanent, native-image-clean
   └── HttpTransport       phase 3 only; hand-rolled or SDK-backed
```

**The seam rule, which is the whole design:** nothing below `McpDispatcher` may name a protocol type — no `_meta`, no headers, no `resultType`, no JSON-RPC envelope. Everything above it is replaceable transport. This is also what makes a later hosted transport a bounded addition rather than a rewrite.

**Do not model the protocol beyond what is branched on.** The registry (`name`, `description`, `inputSchema`, `handler`) should be exactly the shape an SDK registration loop would consume. Records for `CallToolRequest`, `ContentBlock`, or `InputRequiredResult` would create a second type system to reconcile; the result envelope is already JSON and goes straight into `structuredContent`.

### 2.1 On the official Java SDK

Measured 2026-08-02: `mcp` v2.0.0 (2026-06-11) tracks protocol revision **`2025-11-25`**, not `2026-07-28`; `main` is `2.0.1-SNAPSHOT`. Java is a **Tier 2** SDK — Tier 1 (TypeScript, Python, Go, C#) already have `2026-07-28` betas. So the SDK cannot deliver the chosen revision today.

More decisively, `mcp-core`'s compile dependencies are `slf4j-api`, `jackson-annotations`, **`reactor-core`**, and `jakarta.servlet-api` (provided). Reactor is not optional. Against dmtool's current dependency set — picocli, Jackson databind, slf4j-nop, the kits — that is a substantial new reflective surface in a binary whose native-image metadata is test-suite-captured, purchased to replace newline-delimited JSON framing.

**Recommendation: never adopt the SDK for the stdio path.** If it is ever adopted, it is a *hosted-transport* decision, where Streamable HTTP, SSE, and OAuth 2.1 plumbing justify the dependency and native-image is not a constraint.

**Replace "stay switch-ready" with "be provably conformant."** The hedge behind switch-readiness is that the hand-rolled transport might be wrong. The direct answer is to gate it with the official **MCP Inspector** in CI, as [`scripts/verify-demos.sh`]($HOME/Projects/oss/a12/a12-rulekit/scripts/verify-demos.sh) gates the CLI. Then the SDK is a convenience that can be declined rather than an eligibility to maintain.

## 3. The tool surface

The live command tree has **81 leaves** (measured 2026-08-02; note `ManifestVerbTest.EXPECTED_VERBS` is a *containment floor*, not an equality assertion). These render as **17 tools**, each a direct rendering of the `{target, op, args}` operation record CLI-SPEC §3 already defines.

Names carry a `dm.` prefix because the spec states `serverInfo.name` is not reliable for disambiguation by aggregating clients. Under a Claude Code plugin the host additionally scopes them as `mcp__plugin_<plugin-name>_<server-name>__<tool>`.

| tool | ops | `readOnlyHint` |
|---|---|---|
| `dm.model` | new, info, check, describe, diff, read, usage, report, rename, normalize, expand, eval, compute, seed, import-jsonschema, export-jsonschema | false |
| `dm.rule` | check, read, explain, deps, format, eval, add, modify, remove, rename | false |
| `dm.computation` | add, read, explain, format, modify, remove | false |
| `dm.field` | add, modify, read, remove, rename, move | false |
| `dm.group` | add, multiselect, attachment, read, modify, remove, rename, move, extract | false |
| `dm.typedef` | add, read, remove, rename, extract, inline, import, unimport | false |
| `dm.include` | add, read, set-reference, rename, remove, inline | false |
| `dm.config` | read, modify | false |
| `dm.profile` | extract, synthesize, compare | false |
| `dm.patterns` | list, read, scaffold, apply | false |
| `dm.workspace` | list, graph, check, roles | **true** |
| `dm.where_used` | — | **true** |
| `dm.catalog` | operators, diagnostics, schema, manifest | **true** |
| `dm.meta` | read, modify | false |
| `dm.export` | — | confirm (§6) |
| `dm.apply` | run | false |
| `dm.batch` | run | false |

`apply` and `batch` stay separate tools: CLI-SPEC deliberately derives their outcomes differently — `apply` from the transaction, `batch` from the item list — and merging them would re-collapse a distinction the project paid to make. `patterns` is its own tool rather than part of `dm.catalog` because `patterns --apply` persists to the model, which would poison a catalog tool's `readOnlyHint`.

### 3.1 Why target-shaped, and not one tool per verb

The op record already exists and is already rendered onto two surfaces; MCP is a third rendering of the same vocabulary, not a new mapping.

There is also a measured argument. MCP's Client Best Practices recommends **progressive discovery** once tool definitions exceed **1–5% of the context window**: the host stops injecting definitions and supplies a `search_tools` meta-tool backed by keyword, embedding, subagent, or hybrid retrieval **ranked over tool names and descriptions**. An assessment on 2026-08-03 probed exactly that corpus: searching the manifest projection for `required` returns six verbs, `mandatory` returns none, `requiredness` returns only `model import-jsonschema`, and `make a field required` returns nothing.

So a one-tool-per-verb surface plausibly trips the injection threshold, falls back to retrieval, and inherits that lexical failure — the model then sees only a searched subset of a surface whose entire thesis is discoverability. Seventeen tools plausibly stay under the threshold, the host injects all of them, and retrieval never engages.

**Consequence for authoring:** put each tool's op list in its **description**, not only in its `inputSchema`, so the discriminating words are in the ranked text if retrieval ever does engage.

**Division of labour:** dmtool publishes good names and descriptions; semantic intent-to-verb retrieval belongs to the host's progressive discovery. Nothing is built on our side for it.

### 3.2 Input schemas

`inputSchema` is `{op: <enum>, args: <object>, model?: string, workspace?: string}`. Revision `2026-07-28` loosened `inputSchema` to permit any JSON Schema 2020-12 keywords, so a `oneOf` discriminated on `op` is legal — but **`args` stays loosely typed on purpose**. Per-op argument validation is what dmtool's corrective diagnostics already do well, and a large discriminated union degrades model tool-calling accuracy for no correctness gain. The per-op contract remains discoverable through `dm://schema/{target}/{op}`.

Each `op` enum value carries a description stating that route's effect — the route-level fact that `annotations` cannot express (§4).

## 4. Annotations — honest and coarse, by necessity

`ToolAnnotations` has hostile defaults: `readOnlyHint` defaults to **false** and `destructiveHint` to **true**, so omitting the block makes every tool read as destructive. It cannot simply be skipped.

But it also cannot be made precise. Effect in dmtool is selected by **arguments**, not by verb: `meta` chooses its route by the presence of write flags, `patterns` has four routes (read, verdict, rejected scaffold, write), `batch` dispatches mutating ops without carrying `--dry-run` itself, and six artifact writers write via `--output` with no `--dry-run` at all. A per-tool `readOnlyHint` boolean over a route-dependent fact would be a **false statement**, and the spec is explicit that annotations are hints which *"clients should never make tool use decisions based on"* from untrusted servers.

**Therefore:**

- `readOnlyHint: true` **only where unconditionally true** — `dm.workspace`, `dm.where_used`, `dm.catalog`.
- `readOnlyHint: false` everywhere else; `destructiveHint` left at its default.
- `openWorldHint: false` on every tool — dmtool touches local files and nothing external. This is the one hint statable with certainty.
- `idempotentHint` **omitted**. The property is not crisply statable per verb (it depends on route, arguments, and which observable counts), and unlike `readOnlyHint` its default costs nothing.
- Route-level effect lives in the `op` enum descriptions, mirroring what `schema patterns` already does for its four routes.

**This proposal requires no new CLI metadata.** A per-verb `sideEffect`/`readOnly` axis is a *recorded deferral* — [`docs/CLI-CONFORMANCE-GAPS.md`]($HOME/Projects/oss/a12/a12-rulekit/docs/CLI-CONFORMANCE-GAPS.md) §2, demoted 2026-07-21 after a cold-agent probe found side-effect reliably inferable from `--help` for 5/6 sampled verbs. MCP does not fire that trigger: the descriptions carry over into tool and op documentation, so the probe's basis transfers, and the protocol field it would feed cannot express a route-dependent fact anyway. **Record that reasoning on the §2 entry** so the trigger reads as considered-and-insufficient rather than unexamined.

**The residual risk is naming, not metadata.** Client Best Practices documents categorical approval keyed on tool *name* — *"allow `ticketing_createIssue` for this script run"*. Under target-shaped tools, granting `dm.rule` grants `rule read` and `rule remove` in one approval. No annotation is involved; the bundling comes from the name. Accepted for phase 1, revisitable cheaply because `tools/list` is re-fetched each session rather than pinned like a CLI verb name.

## 5. Resources, and where documentation lives

Catalogs become resources with `cacheScope: "public"` and `ttlMs` set to the build lifetime — they are static per build, so a client caches them across calls and sessions.

```
dm://manifest
dm://help/{target}/{op}
dm://schema/{target}/{op}
dm://operators            dm://operators/{id}
dm://patterns/{id}
dm://diagnostics/{code}
dm://skill                (bare-MCP hosts only — see §7)
```

The split is one rule: **resource templates when you know what you want, a tool when you are looking.** `dm.catalog` serves substring and filter queries, because the project measured that groping by substring is the agent's natural reach (`operators date` matches over id, keyword, and meaning).

`dm://help/{target}/{op}` is a **projection of the existing manifest record**, computed in-process. This required no CLI change: a proposal for a `dmtool docs` verb was assessed on 2026-08-03 and **demoted** — there is no measured intent-to-verb failure, and substring search would not have closed the gap anyway (§3.1). URI-addressable resources are a rendering affordance of the protocol, exactly as a tree-walked `--help` is one of a CLI; no vocabulary is invented.

This is a genuine capability gain, not parity: the manifest is **94,681 bytes compact / 118,226 pretty** (measured 2026-08-02; the `~66 KB` figure at `docs/CLI-SPEC.md:52` is stale and should be refreshed or dropped), and cold agents rationally never read it for orientation. Resource templates let an agent fetch one verb instead of the whole tree.

`server/discover` returns `instructions` — in this revision a **cacheable** field on a mandatory RPC rather than part of a handshake that no longer exists. It holds a **router, never content**: *"DocumentModel authoring over `{op, args}`; read `dm://skill` before authoring rules; `dm://help/{target}/{op}` for verb detail; `dm://operators` for the DSL vocabulary."*

**Prompts are not the skill channel.** The spec defines prompts as *user-controlled* — explicitly selected by the user, typically via user-initiated commands. A skill is model-invoked on relevance. Different trigger model; do not conflate.

## 6. Model addressing

Over stdio the server shares a filesystem with the user, so `model` and `workspace` are **paths**, carrying `-m`/`-w` semantics unchanged, including the `-w > $DMTOOL_WORKSPACE > model directory` precedence. No new concepts.

This is what the spec now recommends: Roots are deprecated in `2026-07-28`, with the migration advice to *"pass directories or files via tool parameters, resource URIs, or server configuration instead."*

**Every tool call re-reads the model from disk. There is no warm model and no resolved-model cache.** This is a hard constraint, not a performance note — see §8.

One item to confirm before publishing annotations: whether the artifact writers (`dm.export` and the five others carrying `--output` without `--dry-run`) default to stdout and write a file only when `-o` is passed. If so their effect is argument-dependent like `meta` and `patterns`, and `dm.export` takes `readOnlyHint: false`.

## 7. Packaging and the skill

Claude Code plugins bundle skills **and** MCP servers in one plugin — `.mcp.json` at the plugin root or inline `mcpServers` in `plugin.json`, with `${CLAUDE_PLUGIN_ROOT}` substitution. Plugin servers start automatically when the plugin is enabled, appear as ordinary MCP tools, and pass the same per-server approval as a project `.mcp.json`. So [`plugin/`]($HOME/Projects/oss/a12/a12-rulekit/plugin/) gains one file beside the existing `skills/`; no new distribution mechanism and no second marketplace entry.

This is also the established pattern: the MCP documentation ships its **own** development guidance as skills in a Claude Code plugin (`anthropics/claude-plugins-official` → `mcp-server-dev`). Agent Skills is a separate portable open standard; skills and MCP compose at the packaging layer, and MCP itself has no skill concept in any revision.

**The skill is not duplicated.** One canonical `skill/SKILL.md`, three renderings, one sync gate:

- **Plugin-capable hosts** get the skill as the plugin's skill. Already built, already measured. The server need not serve it.
- **Bare-MCP hosts** get `dm://skill`, read verbatim from the same file, with the MCP server added to `scripts/sync-plugin-assets.sh`'s asset list so the copy cannot drift.
- **`instructions`** points at it and never excerpts it. An excerpt would be a second copy of judgment content — the drift the sync gate exists to prevent — and would make the N6 number unfalsifiable.

## 8. Risks and constraints

**R1 — Binary presence at plugin-enable time.** Binary delivery is deliberately on-demand and skill-triggered (`ensure-dmtool.sh`), because a `SessionStart` hook never fires in the session where `/plugin install` ran ([`docs/RELEASE-SPEC.md`]($HOME/Projects/oss/a12/a12-rulekit/docs/RELEASE-SPEC.md) §6). But a plugin MCP server's `command` must resolve **when the plugin is enabled**, before any skill has run. The workable route is a small wrapper as the `command` — resolve-or-download, then `exec "$BIN" mcp` — which moves a network download inside the MCP startup window. First-enable latency and failure handling become part of the contract. This is the largest concrete engineering risk and the only place this proposal touches a settled RELEASE-SPEC decision.

**R2 — MCPB is unresearched.** The MCP docs name **MCP Bundles** (`github.com/modelcontextprotocol/mcpb`) as the deployment path for a local server that must touch the user's machine, packaging server and runtime as one `.mcpb` archive; the Claude Code plugins reference mentions importing from an MCPB/DXT bundle. dmtool has no runtime to provision, so MCPB's headline benefit is partly moot — but it may resolve R1, since the archive ships the executable. **Read the MCPB spec before committing to the wrapper approach.**

**R3 — `dmtool serve` is a recorded won't-build.** [`docs/FEATURE-GAPS.md`]($HOME/Projects/oss/a12/a12-rulekit/docs/FEATURE-GAPS.md) §4, decided 2026-06-15, with an explicit *"don't re-litigate without new evidence."* Four of its five assumptions are performance arguments that MCP does not claim. The live one is *"serve adds session/lifecycle/reload/stale-write semantics, a second mental model."* Revision `2026-07-28` removed exactly those semantics, and that decision predates the revision — so this is new evidence, not a re-litigation. **But it holds only under §6's no-warm-model rule.** Cache a resolved model and the cache-coherence problem the §4 entry named returns in full, and the objection lands. Measure the per-call parse cost before any cache is even discussed.

**R4 — Native image.** `:mcp` is a new dependency of the shipped binary, so the test-suite-captured reflection metadata stales. Re-capture before any native build, then run the native gate ([`docs/NATIVE-IMAGE-GRAALVM.md`]($HOME/Projects/oss/a12/a12-rulekit/docs/NATIVE-IMAGE-GRAALVM.md) §5.1). Keeping the transport hand-rolled and Jackson-tree-based keeps this cost near zero; adopting the Java SDK would not (§2.1).

**R5 — Eval material boundary.** [`docs/EVAL-SPEC.md`]($HOME/Projects/oss/a12/a12-rulekit/docs/EVAL-SPEC.md) §2a defines a **bare-tool** arm where agents get the self-describing CLI and the model file, with **no skill** — the measurement that separates the tool's self-sufficiency from the skill's coverage. A server that serves `dm://skill` makes MCP a skill-always surface, so that arm does not exist for it unless the server can withhold the resource. Either add a flag that omits `dm://skill`, or accept that the bare-tool number is a CLI-only measurement. Both are defensible; it must be chosen, not discovered.

**R6 — Host support for resources is unverified.** Support for `resources` has historically lagged support for `tools` in some clients, but I found nothing in the official documentation characterising that. If it holds for a target host, `dm.catalog` must carry everything and the resources become an optimisation. **Verify against the intended host before relying on resource-only delivery.**

## 9. Scope

**Required (phase 1)**

- `:mcp` module; `dmtool mcp` stdio entry point; the `McpDispatcher` seam.
- `server/discover` with `supportedVersions`, `capabilities`, `serverInfo`, `instructions`, `ttlMs`, `cacheScope`.
- `tools/list` — the 17 tools of §3, **generated from the live picocli tree**, with `ttlMs`/`cacheScope` and deterministic ordering.
- `tools/call` — envelope into `structuredContent`, `--text` into `content`, `outputSchema` from `result.schema.json`, `isError: true` for `rejected`/`refused`/`error`, JSON-RPC `-32602` for unknown tool or op.
- `resources/list`, `resources/templates/list`, `resources/read` for the §5 URIs.
- Annotations exactly as §4 specifies.
- A conformance guard that the tool set matches the live command tree, in the `ManifestVerbTest` family; MCP Inspector in CI.
- Plugin wiring per §7, including R1's resolution.

**Optional**

- `dm://skill` (needed only for bare-MCP hosts; interacts with R5).
- Per-tool `title` annotations.
- Response streaming for long `model check` runs on large workspaces.

**Excluded — deliberately**

- Multi Round-Trip Requests, `subscriptions/listen`, sampling, roots, logging, and the `io.modelcontextprotocol/tasks` extension. Nothing in dmtool asks the client for anything, pushes change notifications, or is known to exceed a comfortable synchronous budget — measure before adopting tasks.
- The official Java SDK on the stdio path (§2.1).
- Any warm model, resolved-model cache, or session state (R3).
- Any new CLI verb, and any new CLI metadata (§4, §5).
- Excerpting the skill into `instructions` (§7).
- **MCP Apps** — phase 2. It unblocks the HTML report widget deferred in [`docs/MODEL-REVIEW-GAPS.md`]($HOME/Projects/oss/a12/a12-rulekit/docs/MODEL-REVIEW-GAPS.md) §2, whose stated re-open trigger is literally *"when dmtool ships as an MCP server."* Land the surface first.
- **Hosted HTTP** — phase 3, §10.

## 10. Phase 3 — hosted, and why it is deferred

Recorded so the decision is not re-derived later.

**Licensing is the gate, not infrastructure.** EUPL-1.2 Article 1 defines *Distribution and/or Communication* to include *"otherwise making available, online or offline, copies of the Work or **providing access to its essential functionalities** at the disposal of any other natural or legal person."* dmtool bundles the kernel and `Backends.hasBackend()` is true for the CLI always, so a hosted dmtool provides access to the kernel's essential functionalities — triggering the copyleft clause and the source-availability clause. The hosted combined work must be offered under **EUPL-1.2** with a maintained source repository. Hosting also makes the operator a **GDPR processor** of customers' document models. Neither is reversible.

Stdio (phase 1) and a customer-run container image change nothing: both are distribution, exactly as the binary is today, under the posture [`docs/LICENSING.md`]($HOME/Projects/oss/a12/a12-rulekit/docs/LICENSING.md) already sets.

**Infrastructure, for completeness.** Scaleway Serverless Containers fit technically: HTTP request duration 10 s–60 min, HTTP/1.1 and HTTP/2, 128 MB–12 GB memory, 70–6000 mvCPU, concurrency ≤80 per instance, scale-to-zero after 15 min idle, `linux/amd64` only, ≤1 GB image recommended. At €0.00001/vCPU-s + €0.000002/GB-s with a monthly free tier of 200,000 vCPU-s + 400,000 GB-s, an always-on 1 vCPU / 1 GB instance is roughly **€28/month**, and bursty use is effectively free. Serverless *Functions* are the wrong product (concurrency 1 per instance, 6 MiB payload cap).

**Sovereignty, honestly.** Scaleway is 100% European-owned, operates solely under EU jurisdiction, is outside the US CLOUD Act, and is ISO/IEC 27001:2022 and HDS certified; it was selected in April 2026 as one of four providers under the European Commission's Cloud III DPS. **SecNumCloud is *not* obtained** — Scaleway's own page says "currently undergoing assessment." If a customer requires SecNumCloud, Scaleway does not satisfy it today.

**If phase 3 ever happens**, model state moves to server-minted opaque handles passed as ordinary tool arguments (the spec's own "Stateful Tools" guidance), authorization becomes OAuth 2.1 resource-server, and the Java SDK becomes worth reconsidering for that transport only.

## 11. Relationship to existing decisions

| decision | status under this proposal |
|---|---|
| [`RELEASE-SPEC`]($HOME/Projects/oss/a12/a12-rulekit/docs/RELEASE-SPEC.md) §12 — MCP rejected as the *primary* distribution vehicle | **Intact.** The plugin remains primary; MCP is an additional surface inside the same plugin, not a replacement |
| [`FEATURE-GAPS`]($HOME/Projects/oss/a12/a12-rulekit/docs/FEATURE-GAPS.md) §4 — `dmtool serve` won't-build | **Intact, conditionally.** New evidence (the stateless revision) and a different premise (protocol reach, not startup amortisation) — but only while §6's no-warm-model rule holds |
| [`CLI-CONFORMANCE-GAPS`]($HOME/Projects/oss/a12/a12-rulekit/docs/CLI-CONFORMANCE-GAPS.md) §2 — `sideEffect`/`readOnly` axis demoted | **Intact.** MCP is not a sufficient trigger; §4 explains why, and that reasoning should be recorded on the entry |
| `CLI-CONFORMANCE-GAPS` §3 — remote/async recommendations voided pending a resident mode | **Partly reopened.** Trace context arrives free (`traceparent`/`tracestate`/`baggage` in `_meta`); pagination is answered by `ttlMs`/`cacheScope` rather than cursors; idempotency keys, LRO, and confirmation tokens stay void |
| [`MODEL-REVIEW-GAPS`]($HOME/Projects/oss/a12/a12-rulekit/docs/MODEL-REVIEW-GAPS.md) §2 — MCP Apps HTML widget | **Trigger fires at phase 2**, not phase 1 |
| `dmtool docs` verb (assessed 2026-08-03) | **Demoted, correctly.** §5 needs no CLI change |

## 12. Open for owner decision

1. **R5 — the eval material boundary.** Does the server get a flag to withhold `dm://skill`, preserving the bare-tool arm for MCP, or is MCP a skill-always surface and the bare-tool number CLI-only?
2. **R1 vs R2 — packaging.** Wrapper-around-`ensure-dmtool.sh` as the plugin server command, or MCPB? R2 must be researched before this can be answered.
3. **Phase ordering.** Does MCP Apps (phase 2) follow immediately, or wait for measured use of phase 1?

Everything else in this proposal follows from decisions already taken or from measurements cited inline.
