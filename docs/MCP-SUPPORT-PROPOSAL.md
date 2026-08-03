# dmtool over MCP — proposal

**Status:** PROPOSAL (rewritten 2026-08-03). Intent, rationale, and public contract; nothing built. This version **supersedes the 2026-08-02/03 revision wholesale** — it is a rewrite rather than an amendment, because the CLI landed a per-verb operational contract registry (`CommandContracts`, commits `2cf6d7bb`…`fba978ba`) that invalidated the previous §4, §6, §7, and one owner question, and because the hosted design changed shape. Positions that were held and are no longer held are recorded in [Appendix B](#appendix-b--decisions-recorded-positions-superseded); stale facts are deleted rather than annotated. Graduates into the owning specs as phases land, per [DOC-DISCIPLINE.md](DOC-DISCIPLINE.md) §5.

**Scope.** Expose the dmtool surface over the Model Context Protocol, revision **`2026-07-28`**, as a **stdio server inside the existing binary** (`dmtool mcp`). Hosted HTTP is §13 plus [Appendix A](#appendix-a--hosting-candidate-assessments), as phase 3 with its own gate. MCP Apps is phase 2. Neither is proposed for implementation here.

**Owner decisions already taken:**

| decision | date |
|---|---|
| stdio-first; hosted is a later, separately justified phase | 2026-08-02 |
| target `2026-07-28` hand-rolled; the official Java SDK kept as a possible later substitution for a hosted transport only | 2026-08-02 |
| hosting requirements govern any candidate, with "never silently enter a paid tier" top priority | 2026-08-03 |
| **hosted requests carry their own models; the service holds nothing between requests** — complete per-caller isolation by statelessness rather than by mechanism | 2026-08-03 |
| **the model set a request carries is the subject's transitive resolution closure** — "workspace" is a relative concept over MCP, not a location | 2026-08-03 |

**Evidence basis — four source classes, deliberately not conflated:**

| class | what it governs | how it is cited |
|---|---|---|
| **normative specification** — `modelcontextprotocol.io/specification/2026-07-28/**` | every protocol MUST/SHOULD in §2, §5, §8, §9 | linked inline; read 2026-08-02 |
| **non-normative client guidance** — `modelcontextprotocol.io/docs/2026-07-28/**` | progressive discovery, retrieval strategies, categorical approval (§6.1, §7) | labelled *guidance* at each use |
| **host documentation** — Claude Code plugin reference | plugin packaging (§10), tool-name scoping | labelled *host docs*; not part of MCP |
| **this repository** — source read, or a dated measurement | every dmtool claim | §4 separates measured from unverified |

**Measurement provenance.** Every dmtool figure below was measured **2026-08-03** against `cli/build/install/dmtool` built from commit `fba978ba`, by running the packaged launcher. Figures from the previous revision are not carried forward — the contract registry changed the denominators.

---

## 1. Motivation

### What it buys

**Reach without a wrapper per ecosystem.** [RELEASE-SPEC.md](RELEASE-SPEC.md) §12 already named this — *"MCP is the write-once alternative — one MCP server reachable by all agents instead of N native wrappers"* — and kept it on the table *"only if maintaining several native wrappers becomes the actual friction."* This proposal does **not** claim that friction has arrived: the Claude Code and Codex plugins were near-identical to build. Reach is a benefit, not the trigger. §14 addresses the grounds on which that same spec rejected MCP.

**A machine-addressable rendering of the self-describing surface.** MCP resources make per-verb content addressable and host-cacheable: `dm:///help/rule/add` returns one verb's record, and `ttlMs` lets the host keep it without re-asking. *Honest scope of that gain:* `dmtool rule add --help` is already a single call and needs the same prior knowledge of target and op. The delta is host-side caching and machine-addressability, not fewer calls — and no measurement shows help text is re-fetched often enough for the caching half to matter. A structural improvement, not a quantified one.

**A newly published contract that MCP can consume directly.** This is new since the previous revision and it is the strongest cheapness argument. `CommandContracts` publishes, per verb and per argument-selected route, the facts MCP annotations want and could not previously be derived: actual effect, persistence after failure, dry-run behaviour, aggregate success, output form, and expected provenance. §4 and §7 build on it rather than working around its absence.

**It makes a deferred feature reachable.** [MODEL-REVIEW-GAPS.md](MODEL-REVIEW-GAPS.md) §2 defers an in-chat HTML report widget, re-opening *"when dmtool **ships** as an MCP server."* Nothing ships at proposal stage; the trigger fires when phase 1 ships, and the widget is phase 2 (§12).

### What it does not buy

- **No performance gain.** The shipped native binary starts in ~37 ms; there is nothing to amortise, and §9 forbids a warm model outright.
- **No new authoring capability.** Every tool routes to an existing verb. No verb is added, changed, or removed.
- **No replacement for the plugin or the CLI.** The plugin stays the primary distribution vehicle and the CLI the primary surface.
- **It is not vocabulary-neutral.** Tool names are a new vocabulary layered over the verb tree; §6 states exactly which part is invented.

## 2. What MCP `2026-07-28` changed

The current revision (predecessor `2025-11-25`) is the largest break since the protocol launched. Recorded because the design below is a consequence of it. Source: [Key Changes](https://modelcontextprotocol.io/specification/2026-07-28/changelog) plus the linked specification pages, read 2026-08-02.

### Statelessness

| change | detail |
|---|---|
| **No session** | Protocol-level sessions and the `Mcp-Session-Id` header are removed from Streamable HTTP. List endpoints no longer vary per connection. Cross-call state becomes **explicit, server-minted handles passed as ordinary tool arguments** |
| **No handshake** | `initialize` and `notifications/initialized` are gone. Every request carries its protocol version and client capabilities in `_meta` — `io.modelcontextprotocol/protocolVersion` and `io.modelcontextprotocol/clientCapabilities` are required; clients SHOULD send `io.modelcontextprotocol/clientInfo`, servers SHOULD return `io.modelcontextprotocol/serverInfo`. A version mismatch returns `UnsupportedProtocolVersionError` |
| **`server/discover`** | New, **mandatory** RPC advertising `supportedVersions`, `capabilities`, identity, and an optional `instructions` string. Clients MAY call it up front; on stdio it doubles as the backward-compatibility probe. Cacheable |
| **No server-initiated requests** | Sampling, elicitation, and roots no longer arrive as server requests. Under **Multi Round-Trip Requests** the server returns an `InputRequiredResult` (`resultType: "input_required"`) carrying `inputRequests`; the client retries the original request with `inputResponses` |
| **`resultType` everywhere** | Every result carries a required `resultType`: `"complete"` or `"input_required"` |
| **No SSE resumability** | `Last-Event-ID` and SSE event IDs are gone. A broken response stream loses the in-flight request; the client re-issues it with a new request ID |

### Everything else that touches a server implementor

- **Request headers on Streamable HTTP POSTs.** `MCP-Protocol-Version` is required on every POST and must match the body's `_meta`, else `400` + `HeaderMismatch`. `Mcp-Method` is required on all requests; **`Mcp-Name` is required only for `tools/call`, `resources/read`, and `prompts/get`** (its source is `params.name` or `params.uri`). Optional `x-mcp-header` annotations mirror tool parameters into `Mcp-Param-{name}` headers.
- **Cacheable results.** Six operations return a `CacheableResult` carrying `ttlMs` and `cacheScope` (`"public"`/`"private"`): `tools/list`, `prompts/list`, `resources/list`, `resources/read`, `resources/templates/list`, **and `server/discover`** (the changelog's list omits the last; the caching page's normative list includes it). `ttlMs` is a freshness hint **in milliseconds from receipt**. Servers SHOULD also return tools in deterministic order.
- **Pagination is separate from caching and still cursor-based.** The list endpoints accept `cursor` and return `nextCursor`; each page carries its own `ttlMs` and is re-fetched by its cursor. The two mechanisms are orthogonal.
- **`subscriptions/listen`** replaces the HTTP GET endpoint and `resources/subscribe`/`unsubscribe`: one long-lived POST-response stream carrying only opted-in change notifications.
- **Removed:** `ping`, `logging/setLevel`, `notifications/roots/list_changed`. Log level is now per-request via `io.modelcontextprotocol/logLevel` in `_meta`.
- **Extensions framework.** Reverse-DNS identified, negotiated through an `extensions` map on client/server capabilities, versioned independently. Tasks left the core protocol for `io.modelcontextprotocol/tasks`; MCP Apps is `io.modelcontextprotocol/ui`.
- **Schema loosening.** `inputSchema`/`outputSchema` accept any JSON Schema 2020-12 keywords; `structuredContent` accepts any JSON value. An `inputSchema` root must still be `type: "object"`.
- **Error-code policy.** `-32020`–`-32099` reserved for the specification: `HeaderMismatch` `-32020`, `MissingRequiredClientCapability` `-32021`, `UnsupportedProtocolVersion` `-32022`. Resource-not-found moved `-32002` → `-32602`.
- **Observability.** OpenTelemetry trace-context conventions are documented for `_meta` (`traceparent`, `tracestate`, `baggage`). These are *client-supplied* conventions; the protocol supplies no log and imposes no retention obligation.
- **Deprecated:** Roots, Sampling, and Logging as features; the HTTP+SSE transport; OAuth Dynamic Client Registration in favour of Client ID Metadata Documents. A formal feature lifecycle guarantees a minimum twelve-month deprecation window.

### stdio framing — a constraint, not a footnote

The stdio binding requires that the server **MUST NOT** write anything to stdout that is not a valid MCP message, and that messages **MUST NOT** contain embedded newlines. Cancellation on stdio is a `notifications/cancelled` notification, after which the server **MUST NOT** send further messages for that request. §5 states what this forces.

### Why one deprecation matters here

The Roots migration advice is *"pass directories or files via tool parameters, resource URIs, or server configuration instead"* — which is exactly the addressing chosen in §9, making that choice the specification's own recommendation rather than a workaround.

## 3. Why statelessness is the enabling change

Under every revision through `2025-11-25`, an MCP server was a **stateful conversational peer**: a connection-scoped session established by an `initialize` handshake, a session-id header, and the ability to initiate its own JSON-RPC requests mid-conversation.

That shape is close to what this project already examined and rejected. [FEATURE-GAPS.md](FEATURE-GAPS.md) §4 records `dmtool serve` as **won't-build** (2026-06-15) on five grounds. Classifying them honestly: **three are performance arguments** this proposal does not make (the dead premise of amortising JVM startup, the retired `--kernel` eval warm-up, and the cost of a per-model bootstrap). **Two are not**, and both need answering:

**"`batch`/`apply` already occupy the space."** True, and unaffected — those are *batching* surfaces that amortise warm-up across a known op list and a transaction. This proposal claims no batching benefit for stdio and does not compete there. Under hosting the relationship inverts: because a request carries its own models (§13.4), `apply`/`batch` become the *primary* shape rather than a competitor, since they amortise one payload over many ops.

**"The cheap tier doesn't fix the real cost"** — a stateless serve re-pays the per-model bootstrap every op, so only a *stateful warm-model* serve would remove it. This proposal's server **is** that stateless variant (§9: every call re-reads), so this objection applies in full to any *performance* case for it. That is why §1 makes none.

**"It fights the agent-facing thesis — serve adds session/lifecycle/reload/stale-write semantics, a second mental model."** The revision deletes the *session* half: a conforming server is now free to be a pure per-call transport — read one self-contained request, run the verb, write the envelope, forget everything. That is already dmtool's own model; [CLI-SPEC.md](CLI-SPEC.md) §3a: *"a fully-specified, stateless invocation is easier for an agent to generate and to reproduce/debug."*

**The objection is not fully retired.** Protocol statelessness removes sessions, reload, and stale writes. It does **not** remove **process lifecycle** (R1 is nothing but lifecycle), **a second tool-name vocabulary** (§6), or **a second eval material boundary** (R5). Honest statement: the *session* ground no longer applies, on evidence the 2026-06-15 decision could not have had; the *second-mental-model* ground is reduced, not eliminated, and §6 and R5 are where the residue lives.

Three consequences follow, and they are why the work is small:

1. **The adapter is thin.** No session store, no handshake state machine, no resumability bookkeeping, no server-initiated request path.
2. **Hand-rolling the transport is reasonable.** What remains is one JSON-RPC dispatch table behind a single entry point (§5.1).
3. **All of it is conditional on no warm model** (§9). Introduce one and the cache-coherence semantics the `serve` decision named return in full.

## 4. Feasibility

### The published contract MCP consumes

`CommandContracts` is *"the authoritative operational contract for every public command invocation"*, owning *"the cross-surface facts that neither [the command tree nor the schema] can derive: actual effect, persistence after a command/domain/child failure, dry-run behavior, aggregate success, output form, and expected provenance"* — rendered into `manifest`, `schema <target> <op>`, and generated leaf help from one typed source. Its enums are the vocabulary this proposal maps onto MCP:

```
Effect             READ_ONLY | MODEL_WRITE | ARTIFACT_WRITE | MULTI_FILE_WRITE | TRANSACTION | DISPATCH
FailurePersistence NO_WRITE | ROLLBACK | PARTIAL
DryRun             NOT_APPLICABLE | SUPPORTED | ACTIVE
ReturnKind         RESULT_ENVELOPE | RAW_JSON | RAW_TEXT | EMPTY_STDOUT
```

The load-bearing structural point: a verb carries **one profile per argument-selected route**, each with its own `when` selector and its own effect. Route-dependent behaviour is therefore *modelled*, not merely lamented.

### Measured

All figures 2026-08-03, from `manifest` on a dist built from `fba978ba`:

| quantity | value |
|---|---|
| live command-tree leaves | **81** |
| operational profiles across them | **142** |
| profiles per verb | 31 verbs have 1 · 45 have 2 · 2 have 3 · one each has 4, 5, 6 |
| `Effect` distribution over profiles | `READ_ONLY` 94 · `MODEL_WRITE` 37 · `ARTIFACT_WRITE` 6 · `MULTI_FILE_WRITE` 3 · `DISPATCH` 1 · `TRANSACTION` 1 |
| `ReturnKind` distribution over profiles | `RESULT_ENVELOPE` 125 · `RAW_JSON` 10 · `RAW_TEXT` 6 · `EMPTY_STDOUT` 1 |
| verbs `READ_ONLY` in **every** profile | **34** |
| verbs whose profiles **disagree** on effect | **46** — every one via a `--dry-run` selector or an output-flag selector (`-o`/`--output`/`--out-dir` absent ⇒ stdout ⇒ read-only) |
| verbs `RESULT_ENVELOPE` in **every** profile | **68** |
| verbs with mixed return kinds | **7** — `model new`, `model expand`, `model import-jsonschema`, `model export-jsonschema`, `profile synthesize` (each `RAW_JSON` when writing to stdout), `workspace list`, `workspace graph` (`RAW_TEXT` in a rendering mode) |
| verbs never returning an envelope | **6** — `model seed`, `export`, `schema`, `operators`, `diagnostics`, `manifest` |
| `manifest` size, pretty-printed | **273,549 bytes** |

Two facts about that last row matter. It is **2.3× the previous revision's 118,226 bytes**, the growth being the operational profiles; and it reinforces §1's point that `manifest` is a machine contract rather than an orientation surface.

### The reuse, stated per route

| MCP field | dmtool artifact | applies to |
|---|---|---|
| `outputSchema` ← `result.schema.json` | the published result schema | the **68** verbs whose every profile returns `RESULT_ENVELOPE` |
| `structuredContent` | the result envelope | the same 68 |
| `content[0].text` | the `--text` rendering | the same 68 |
| tool-execution error (`isError: true`) | `rejected` / `refused` / `error` outcomes with their diagnostics | the same 68 |
| `readOnlyHint` ← `Effect.READ_ONLY` | the operational profile | derived per route; see §7 |

**`outputSchema` is emitted per route from the published `ReturnKind`, and omitted wherever any profile of the exposed op set is not `RESULT_ENVELOPE`** — because MCP states that "if an output schema is provided, servers MUST provide structured results that conform to this schema", and the 7 mixed-return verbs can legally emit raw JSON. This needs no new CLI metadata; the classification is published.

The error-mapping row is not a coincidence: MCP separates **protocol errors** (unknown tool, malformed request) from **tool execution errors** precisely because models can self-correct from the second — which is [CLI-SPEC.md](CLI-SPEC.md) §2's corrective-diagnostics principle restated by the protocol.

Every verb now resolves a contract through `schema <target> <op>`: an envelope route returns `{op, input, returns, resultSchema, operationalProfiles}`, and a directionally exempt one returns `{op, contract: "operational-only", directionalExemption, operationalProfiles}`. So there is no longer a class of verb with no machine-readable contract.

### Also proven

| claim | basis |
|---|---|
| The tool list can be **derived** from the live picocli tree, inheriting the no-drift property `manifest` has | `manifest` is recomputed from that tree; `ManifestVerbTest` guards the verb set under a **set-equality** assertion over 81 entries |
| A Claude Code plugin can bundle skills **and** an MCP server in one plugin | *host docs*: components include skills, agents, hooks, MCP servers, LSP servers, monitors; config via `.mcp.json` at the plugin root or inline `mcpServers` |
| The official Java SDK cannot serve this revision, and carries a dependency we do not want | `mcp` v2.0.0 tracks `2025-11-25`; `mcp-core`'s compile deps include `reactor-core` (§5.1). *Dated 2026-08-02 — re-check before any SDK decision* |
| `Cli.run` is already a pure per-call entry point | it constructs a fresh `new CommandLine(new RootCommand())` per invocation and writes to passed sinks, so no option state can leak between calls (§5) |
| Reference resolution is already content-first | `ModelResolver` is a `@FunctionalInterface` (`id → DM-JSON`) whose own javadoc names *"a map, a service, a classpath"* as intended sources; `EditSession.read(json, resolver)` and `BoundModel.ofJson(json, resolver)` take JSON strings. Only the CLI layer requires paths (§13.4) |

### Unverified — settle before building

| open item | why it matters | tracked as |
|---|---|---|
| **Which revision the intended host implements** | decides whether phase 1 works at all | **R7 / §15 Q1** |
| **MCPB** (MCP Bundles) as a packaging path | may resolve binary presence outright | R2 |
| Real host support for `resources` versus `tools` | a resource-only design could be invisible in some clients | R6 |
| Per-call **workspace index** cost | the only honest input to any future caching discussion, and it scales with the tree rather than the model | R3 |
| Token cost of the tool definitions | decides nothing in this design, but §6.1's secondary argument rests on it | R8 |

**Verdict: feasible and small on the protocol and licence axes; blocked on R7 for scheduling.** Nothing in the protocol or the licence prevents phase 1.

## 5. Architecture

**The MCP command lives in `:cli`, not a new module.** `RootCommand`, `ManifestCommand`, and `TextRenderer` are package-private, so a class outside `io.github.mbackschat.a12.dm.cli` can neither build the live tree, nor walk it to derive tools, nor produce the `--text` rendering. Placing the command in that package makes all three reachable without widening any visibility. A separate module would additionally require `:cli` to depend on it while it depends on `:cli` — both edges cannot hold. If a separate module is ever wanted, that is a deliberate API-widening decision with its own review.

```
cli/…/mcp/McpToolRegistry     derived from the live picocli tree + CommandContracts (the walk `manifest` uses)
cli/…/mcp/McpDispatcher       method + params -> result; names no protocol type
   └── McpStdioTransport      frames JSON-RPC; the only protocol-aware layer
```

**The seam rule:** nothing below `McpDispatcher` may name a protocol type — no `_meta`, no headers, no `resultType`, no JSON-RPC envelope. That keeps a later hosted transport a bounded addition and confines a possible SDK substitution to one class.

### 5.1 The invocation contract — one entry point, per call

**Every `tools/call` routes through `Cli.run(args, out, err)`.** This is a contract, not an implementation note, because it is what makes §9's no-state rule mechanically true: `Cli.run` builds a fresh command tree per invocation, so picocli's `@Option` instance fields cannot carry `-m`, `-w`, or `--dry-run` from one call into the next. A dispatcher that instead retained a `CommandLine` would inherit the previous call's options silently — a bug unreachable in a one-shot process and a coin flip in a resident one.

Three consequences of stdio framing (§2) shape the rest:

1. **The server serialises its own compact frames; it never relays CLI stdout.** dmtool prints pretty-printed multi-line JSON and stdio MCP forbids embedded newlines in a message. The dispatcher captures the run's output, parses it, and re-serialises compactly into `structuredContent`.
2. **`--text` must not be passed through.** `Cli.run` diverts stdout into an internal buffer when `--text` is present and emits only the rendering, so passing it would lose the JSON — and re-invoking a mutating verb to obtain the other form would apply the edit twice. Both payloads come from one execution: the parsed envelope for `structuredContent`, `TextRenderer` applied to that same node for `content`.
3. **The verb must not write to the real stdout.** dmtool's entry point takes passed sinks rather than static streams, which is what makes this possible; the dispatcher supplies its own. A strict process-boundary sweep of all 81 leaves found 79 JSON-promising routes emitting exactly one JSON value with EOF immediately after ([CLI-CONFORMANCE-GAPS.md](CLI-CONFORMANCE-GAPS.md) §2), with `schema` bare and `export card` deliberately raw. A wire protocol nonetheless sets a stricter bar than a CLI, which is why the dispatcher supplies its own sinks rather than relying on ambient purity.

**Serialised dispatch is an S1 decision, not a global one.** stdio delivers requests on one channel, so overlapping `tools/call` invocations are possible unless dispatch is serialised. Phase 1 serialises — one request at a time — because it is the only posture whose thread-safety is currently established, and because no dmtool verb is long enough to make queueing costly *for a single caller*. That justification does not survive multiple callers: §13.3 records why, and any multi-caller shape must revisit both the queueing policy and the thread-safety claim. `notifications/cancelled` must at minimum be accepted and produce no further messages for the cancelled request; in-flight interruption is out of scope for phase 1.

### 5.2 On the official Java SDK

Measured 2026-08-02: `mcp` v2.0.0 (released 2026-06-11) tracks protocol revision **`2025-11-25`**, not `2026-07-28`; `main` is `2.0.1-SNAPSHOT`. Java is a **Tier 2** SDK — Tier 1 (TypeScript, Python, Go, C#) have `2026-07-28` betas. More decisively, `mcp-core`'s compile dependencies are `slf4j-api`, `jackson-annotations`, **`reactor-core`**, and `jakarta.servlet-api` (provided). Reactor is not optional. Against dmtool's current set — picocli, Jackson databind, slf4j-nop, the kits — that is a substantial new reflective surface in a binary whose native-image metadata is test-suite-captured, purchased to replace newline-delimited JSON framing.

**Recommendation: do not adopt the SDK for the stdio path.** If it is ever adopted, that is a *hosted-transport* decision, where Streamable HTTP and OAuth 2.1 plumbing justify the dependency and native-image is not a constraint.

**Conformance testing, stated accurately.** The official **MCP Inspector** CLI connects to a server, invokes the single method named by `--method`, prints the result, and exits. It is a per-method smoke tool with usable exit codes and documented CI recipes — worth wiring, and it will catch a broken frame or a missing method. It is **not** a conformance suite: it does not enumerate the revision's MUSTs, nor validate `_meta` requiredness, cacheable-result obligations, header rules, or error-code allocation. So: *smoke-tested per method in CI, plus hand-written assertions for the MUSTs we implement* — not "provably conformant."

## 6. The tool surface — split by effect, not only by target

The previous revision proposed 17 target-shaped tools. **The contract registry makes that shape lossy in a way it was not before**, and this is the single largest design change in this rewrite.

**The measured cost of target-shaped grouping.** 34 of 81 verbs are `READ_ONLY` in every profile. Grouped by target alone, only **three** tools (`dm.workspace`, `dm.where-used`, `dm.catalog`) are wholly read-only, so `readOnlyHint: true` can be stated on 3 tools covering 9 verbs — and **25 read-only verbs lose a fact the tool now publishes about them**. It also bundles approval: one grant of `dm.rule` spans `rule read` and `rule remove`.

**Splitting each target by effect fixes both, and it is derivation rather than invention** — the partition comes from `Effect`, which the tool publishes. Nine targets have both read-only and mutating ops; `workspace` has only read-only ones.

| tool | ops | `readOnlyHint` |
|---|---|---|
| `dm.model.read` | check, compute, describe, diff, eval, info, read, report, seed, usage | **true** |
| `dm.model.edit` | new, rename, normalize, expand, import-jsonschema, export-jsonschema | false |
| `dm.rule.read` | check, read, explain, deps, format, eval | **true** |
| `dm.rule.edit` | add, modify, remove, rename | false |
| `dm.computation.read` | read, explain, format | **true** |
| `dm.computation.edit` | add, modify, remove | false |
| `dm.field.read` | read | **true** |
| `dm.field.edit` | add, modify, remove, rename, move | false |
| `dm.group.read` | read | **true** |
| `dm.group.edit` | add, multiselect, attachment, modify, remove, rename, move, extract | false |
| `dm.typedef.read` | read | **true** |
| `dm.typedef.edit` | add, modify, remove, rename, extract, inline, import, unimport | false |
| `dm.include.read` | read | **true** |
| `dm.include.edit` | add, set-reference, rename, remove, inline | false |
| `dm.config.read` | read | **true** |
| `dm.config.edit` | modify | false |
| `dm.profile.read` | compare | **true** |
| `dm.profile.edit` | extract, synthesize | false |
| `dm.workspace` | list, graph, check, roles | **true** |
| `dm.where-used` | *(one root verb)* | **true** |
| `dm.catalog` | operators, diagnostics, schema, manifest | **true** |
| `dm.apply` | *(one root verb)* | false |
| `dm.batch` | *(one root verb)* | false |
| `dm.export` | *(one root verb)* | false |
| `dm.meta` | *(one root verb, argument-selected routes)* | false |
| `dm.patterns` | *(one root verb, argument-selected routes)* | false |

**26 tools, 12 of them truthfully read-only** — against 17 tools with 3. The trade is 9 extra tool definitions for 9 extra truthful hints plus safe categorical approval on every read tool. A pure target×effect partition that also grouped the root verbs would be 21 tools; the root verbs are kept separate because `apply`, `batch`, `export`, `meta`, and `patterns` have genuinely different contracts (§6.2).

**What is still invented, named plainly.** `ManifestCommand` emits `target` + `op` only for a nested verb; **root-level verbs carry `op` alone, with no target**. So `dm.catalog` grouping four target-less root verbs is a new grouping, and the `.read`/`.edit` suffixes are new name components. Both are deliberate deviations in a repository whose first interop rule is never to invent a shape A12 already defines — mitigated by the fact that the *partition* is derived from published `Effect` rather than chosen, but the *names* are ours. **§15 Q2 puts the shape to the owner**, with one-tool-per-verb (81 tools, maximal fidelity) and the previous 17-tool grouping as the alternatives.

`dm.where-used` keeps the live verb's hyphen — hyphens are legal in MCP tool names, so renaming it would invent vocabulary for nothing.

**Naming.** The `dm.` prefix is a choice, not a requirement. The specification notes that `serverInfo.name` is unreliable for disambiguation and assigns the remedy to **clients and proxies** ("SHOULD implement a disambiguation strategy such as prefixing tool names with a server identifier"); it does not ask servers to self-prefix. Under a Claude Code plugin the host scopes names anyway, as `mcp__plugin_<plugin-name>_<server-name>__<tool>` (*host docs*). The prefix is retained only for direct-connect hosts that do neither.

**Treat every tool name as a public contract.** List results are cacheable, guidance tells hosts to memoise definitions, and a rename invalidates prompt caches and any user allowlist. Choose names once.

### 6.1 Why grouped at all, and not one tool per verb

**The primary argument is that the grouping already exists for nested verbs.** Ten of the twelve targets are real op-record targets, so `{op, args}` under a target tool is a faithful rendering for 71 of 81 leaves. The effect split subdivides that existing grouping rather than replacing it.

**A secondary argument, with its limits stated.** Client guidance recommends **progressive discovery** once tool definitions occupy a large share of the context window, at which point the host stops injecting definitions and supplies a `search_tools` meta-tool. Fewer, denser tools are less likely to trip that threshold, and a surface whose thesis is discoverability is better served by being injected whole than retrieved into. That argument is **weak**: the threshold is a non-normative example range ("for example, 1%-5%"), and this proposal has not measured its own token cost (R8). A lexical probe over the manifest showed only that *substring* retrieval fails on intent phrasing (`required` matched 3 verb descriptions, `mandatory` and "make a field required" matched none) — which says nothing about the embedding, subagent, or provider-native strategies the same guidance names.

**Consequence for authoring:** put each tool's op list in its **description**, not only in its `inputSchema`, so the discriminating words sit in the ranked text if retrieval does engage.

**Division of labour:** dmtool publishes good names and descriptions; semantic intent-to-verb retrieval belongs to the host. A `dmtool docs` verb was assessed 2026-08-03 and demoted, both for want of a measured failure and because substring search would not have closed the gap.

### 6.2 Input schemas

`inputSchema` is a `type: "object"` root — required by the `Tool` signature — with `{op?: <enum>, args: <object>, model?: string, workspace?: string}` for stdio; `op` is absent for single-route root-verb tools, and §13.4 replaces the addressing keys for a hosted transport. The revision permits any JSON Schema 2020-12 keywords, so a `oneOf` discriminated on `op` is legal alongside that root type — but **`args` stays loosely typed on purpose**. Per-op argument validation is what dmtool's corrective diagnostics already do well, and a large discriminated union degrades model tool-calling accuracy for no correctness gain. The per-op contract stays discoverable through `dm:///schema/{target}/{op}`.

Each `op` enum value carries a **derived** description — its `Effect`, `FailurePersistence`, and `DryRun` from the registry — rather than hand-written prose. That is the route-level fact `annotations` cannot express (§7), and deriving it means it cannot drift.

The root verbs stay separate tools because their contracts differ where it matters: `apply` is `Effect.TRANSACTION` with `FailurePersistence.ROLLBACK`, `batch` is `Effect.DISPATCH` with child-defined outcomes, `export` is `MULTI_FILE_WRITE`, and `meta`/`patterns` select their route from write flags.

## 7. Annotations — now derived, and precision is available

The previous revision argued that per-tool annotation precision was unattainable because effect is argument-selected. **That is no longer true**: `CommandContracts` states effect per route, so the honest mapping is mechanical.

`ToolAnnotations` defaults are `readOnlyHint` **false**, `destructiveHint` **true**, `idempotentHint` false, `openWorldHint` true. So:

- **`readOnlyHint: true` exactly where every profile of every op in the tool is `Effect.READ_ONLY`** — the 12 tools marked in §6, derived rather than asserted, and guarded by the conformance test in §12. This is a *stronger* claim than the previous revision's three hand-checked tools and it comes from the tool's own contract.
- **`readOnlyHint: false` elsewhere**, with `destructiveHint` at its default.
- **`openWorldHint: false` on every tool** — dmtool touches local files and nothing external.
- **`idempotentHint` omitted** — still not crisply statable per verb, and its default costs nothing.
- **Route-level effect lives in the derived `op` enum descriptions** (§6.2), because MCP has no per-route annotation.

**The residual imprecision, stated exactly.** 46 verbs have profiles that disagree on effect, and every one of them disagrees for a *systematic* reason: a `--dry-run` selector, or an output flag whose absence means "print to stdout". So a mutating tool's `readOnlyHint: false` is correct but pessimistic — the same call with `--dry-run` is read-only, and MCP cannot say so. That is a protocol limitation, not a missing dmtool fact, and the derived op descriptions carry the `DryRun` value so a model can find it.

**On the untrusted-server caveat.** The specification says clients "should never make tool use decisions based on `ToolAnnotations` received from **untrusted** servers." A plugin-installed server is trusted, so that caveat does not license imprecision here.

**Categorical approval is now safe on the read tools.** Client guidance describes approval keyed on tool *name* — *"allow `ticketing_createIssue` for this script run"* — in the Programmatic Tool Calling security section. Under the effect split, granting `dm.rule.read` cannot reach `rule remove`, which was the concrete risk the previous revision could only note. This is the main safety argument for §6's shape.

**[CLI-CONFORMANCE-GAPS.md](CLI-CONFORMANCE-GAPS.md) §2's `sideEffect` deferral is closed by events.** It was demoted 2026-07-21 after a probe found side-effect inferable from `--help` for 5 of 6 sampled verbs; the axis has since landed as `CommandContracts.Effect`, published on every route. The previous revision routed this to the owner as a question; there is no longer a question, and the ledger entry should be updated by whoever owns it next.

## 8. Resources, and where documentation lives

Catalogs become resources with `cacheScope: "public"` and a **`ttlMs` in milliseconds** — a duration from receipt, not a build identity. A long duration (hours) is appropriate because the content is fixed for a given binary; the exact value is an implementation choice with no contract weight.

URIs use a **path-absolute form with an empty authority**, so that no path segment is misread as an RFC 3986 authority component:

```
dm:///manifest
dm:///help/{target}/{op}
dm:///schema/{target}/{op}
dm:///operators              dm:///operators/{id}
dm:///patterns/{id}
dm:///diagnostics/{code}
```

`dm://manifest` would parse as authority `manifest` with an empty path, and `dm://help/rule/add` as authority `help` — a namespace smuggled into the authority slot. Custom schemes must follow RFC 3986, so the shape is pinned here rather than left to the implementation.

The split is one rule: **resource templates when you know what you want, a tool when you are looking.** `dm.catalog` serves substring and filter queries, because the project measured that groping by substring is the agent's natural reach (`operators date` matches over id, keyword, and meaning).

`dm:///help/{target}/{op}` is a **projection of the existing manifest record**, computed in process — no CLI change and no new documentation source.

`server/discover` returns `instructions` — in this revision a cacheable field on a mandatory RPC rather than part of a handshake that no longer exists. It holds a **router, never content**: *"DocumentModel authoring over `{op, args}`; `dm:///help/{target}/{op}` for verb detail; `dm:///operators` for the DSL vocabulary."*

**Prompts are not the skill channel.** The specification defines prompts as *user-controlled* — explicitly selected by the user. A skill is model-invoked on relevance. Different trigger model; do not conflate.

## 9. Model addressing over stdio

Over stdio the server shares a filesystem with the user, so `model` and `workspace` are **paths**, carrying `-m`/`-w` semantics unchanged. This is what the specification recommends (§2, the Roots deprecation). Hosted addressing is different in kind and is §13.4.

**Every tool call re-reads from disk. There is no warm model, no resolved-model cache, and no workspace index cache.** A hard constraint, not a performance note — it is the condition under which §3's argument and R3 hold. Two clarifications the previous revision left implicit:

- **The rule is about model content, not about all state.** `Backends`, `ReadBackends`, and `OperatorCatalog.INSTANCE` hold process-lifetime service and catalog singletons. Those are benign and desirable in a resident process; what must never be cached is a model, a resolved model, or a workspace index.
- **The index cost is the real per-call cost.** `ReferenceResolvers.directory(roots)` builds its id-keyed index **eagerly at construction** — it walks the trees and header-parses every `*.json` before any reference is resolved — and `WorkspaceOption.resolverFor` is called once per invocation by roughly thirty verbs. So per-call cost scales with the *workspace tree*, not the subject model. R3 measures that, and the experiment is `model info` on one self-contained model with `-w` pointed at trees of 1 / 50 / 500 models.

**Three addressing hazards that a one-shot process hides and a resident one does not:**

1. **`$DMTOOL_WORKSPACE` becomes frozen ambient state.** The precedence is `-w > $DMTOOL_WORKSPACE > the model's directory`, and the env value is fixed by the host at process start — a value the model can neither see nor change, and stale if the user switches projects. **Phase 1 therefore echoes the resolved roots in every response**, so env-derived resolution is never invisible.
2. **Relative paths resolve against the server's CWD**, which the host chooses and the model does not know. **Phase 1 rejects a relative `model` or `workspace`** with a corrective diagnostic naming the CWD, rather than silently inheriting it.
3. **The model supplies the path**, which is a different trust boundary from a user's shell. The specification requires servers to sanitise file paths against traversal and validate all tool inputs. Phase 1 normalises and resolves both keys, rejects a path escaping the workspace root when one is set, and reports the rejection as a tool-execution error naming the searched roots — the corrective shape the CLI already uses for an unresolved reference.

**On `--output` versus `--out-dir`:** not an inconsistency. `--output`/`-o` selects one file; `--out-dir` selects several — `export` writes four artifacts, `model import-jsonschema --out-dir` imports a bundle into multiple models. Both are visible in the operational profiles as `ARTIFACT_WRITE` and `MULTI_FILE_WRITE`.

## 10. Packaging and the skill

**Phase 1 does not ship an MCP server registration in the plugin.** `dmtool mcp` is documented as a **user-registered** server (a project `.mcp.json`, or the host's own add flow) once the binary is present. This is a change from the previous revision, and it is what makes R1 not a phase-1 blocker: a plugin MCP server's `command` must resolve **when the plugin is enabled**, before any skill has run, which is precisely what [RELEASE-SPEC.md](RELEASE-SPEC.md) §6's on-demand skill-triggered delivery was designed to avoid needing. Deferring plugin auto-wiring to phase 1.5, gated on R1/R2, costs nothing that phase 1 needs and touches no settled release decision.

**Which skill serves the MCP surface — and the answer is "none, deliberately".** `skill/SKILL.md` is invocation-shaped: 152 lines carrying 19 `dmtool …` invocations, every one of which `CommandReferencesResolveTest` requires to resolve against the live command tree. Its tool section is `dmtool --help`, `dmtool operators`, `dmtool -m <model> rule check --field … --condition … --code …`. **An agent holding `dm.rule.read` and no shell can execute none of it.** So the previous revision's claim that "the plugin's own skill already delivers it" holds only for a host that also has the CLI — and under a plugin both are present, so an agent reading that skill will shell out and ignore the MCP tools.

**The consequence is that phase 1's audience is a bare-MCP host** — one with MCP but no skill or plugin support. That is recorded here as the design's own statement of purpose, because it decides three other things: no new skill and no marketplace entry are needed (§15 Q4 disappears as a packaging question); the **tool descriptions and `server/discover.instructions` must carry what judgment a bare host needs**, which is measurable against EVAL-SPEC §2a's bare-tool arm; and `dm:///skill` stays excluded.

Two rejected alternatives, recorded so they are not re-proposed: an **invocation-neutral rewrite** of `skill/SKILL.md` would collide with `CommandReferencesResolveTest`, degrade the skill for CLI users, and move the N6 eval baseline; **two skills** (`a12-dmtool` plus an MCP variant) is duplicated judgment content on the one file the project says only an expensive probe run can validate. Note also that **MCP has no skill concept in any revision** — Agent Skills is a separate portable standard, and skills compose with MCP only at the packaging layer.

## 11. Risks and constraints

**R7 — no evidence the intended host implements `2026-07-28`.** §5.2 establishes that the Java SDK tracks `2025-11-25` and that Tier-1 SDKs have betas; neither says what a *host* speaks, and Claude Code's MCP documentation states no protocol revision. A `2026-07-28`-only server has no fallback for a legacy client: the spec places the backward-compatibility probe obligation on the **client**, so a client that opens with `initialize` meets a method the server does not implement. **This decides whether phase 1 works, and it is cheap to settle** — one connection against the real host once a skeleton exists. §15 Q1.

**R1 — binary presence at plugin-enable time.** Reduced from the largest engineering risk to a phase-1.5 item by §10's decision not to ship a plugin registration. It returns in full if auto-wiring is wanted: the workable route is a wrapper as the `command` (resolve-or-download, then `exec "$BIN" mcp`), which moves a network download inside the MCP startup window — **and inverts a failure policy**, since `ensure-dmtool.sh` deliberately warns and exits 0 on any download problem, whereas a `command` that exits 0 without speaking JSON-RPC is a broken server.

**R2 — MCPB is unresearched.** The MCP documentation names **MCP Bundles** as the deployment path for a local server that must touch the user's machine, packaging server and runtime as one `.mcpb` archive. dmtool has no runtime to provision, so the headline benefit is partly moot — but it may resolve R1 by shipping the executable. Note it would create a **second** binary delivery channel beside the version-keyed cache whose invariant `EnsureDmtoolCacheTest` locks. **Read the MCPB specification before committing to the wrapper.**

**R3 — the `dmtool serve` won't-build.** [FEATURE-GAPS.md](FEATURE-GAPS.md) §4, decided 2026-06-15, with an explicit *"don't re-litigate without new evidence."* §3 classifies its five grounds and answers all of them; the session ground no longer applies on new evidence, and the second-mental-model ground is reduced rather than eliminated. **Conditional on §9's no-warm-model rule.** Measure the per-call workspace index cost before any cache is discussed.

**R4 — native image.** The MCP command adds code and Jackson usage to the shipped binary, so the test-suite-captured reflection metadata stales. Re-capture before any native build, then run the native gate ([NATIVE-IMAGE-GRAALVM.md](NATIVE-IMAGE-GRAALVM.md) §5.1). Keeping the transport hand-rolled and Jackson-tree-based keeps this near zero; the SDK would not (§5.2).

**R5 — eval material boundary.** [EVAL-SPEC.md](EVAL-SPEC.md) §2a defines a **bare-tool** arm where agents get the self-describing CLI and the model file with **no skill**. §10's exclusion of `dm:///skill` keeps that arm intact, and §10's audience decision makes an MCP bare-tool arm the natural measurement of whether the derived tool descriptions suffice.

**R6 — host support for resources is unverified.** Support for `resources` has historically lagged `tools` in some clients, but nothing in the official documentation characterises that. If it holds for a target host, `dm.catalog` must carry everything and the resources become an optimisation.

**R8 — the tool definitions' token cost is unmeasured.** It decides nothing in this design; it is recorded so §6.1's secondary argument is not mistaken for a measured one.

**R9 — concurrent dispatch has no established thread-safety basis.** §5.1's serialisation is what makes phase 1 safe. Any multi-caller shape needs the claim established, and `CustomJsWorker` — a single shared Node worker with `synchronized` methods — is a known cross-request bottleneck.

## 12. Scope

**The Required list is a sequence, not a set.** `CommandReferencesResolveTest` resolves every command reference in `cli/src/main`, the rulekit catalog, the root and plugin READMEs, `skill/SKILL.md`, the Codex `AGENTS.md`, and the eval workflow against the **live** command tree — `docs/` is deliberately not scanned, which is why this proposal may name `dmtool mcp` while no such verb exists. The verb must exist in the picocli tree **before** any command example naming it enters one of those seven surfaces. That is the same ordering the CLAUDE.md schema-discipline table imposes on a new verb, so the two compose — the user-facing documentation of this surface lands last.

**Required (phase 1)**

- The MCP command in `:cli` (§5); the `McpDispatcher` seam; the `Cli.run` invocation contract (§5.1); serialised dispatch.
- `server/discover` with `supportedVersions`, `capabilities`, `serverInfo`, `instructions`, `ttlMs`, `cacheScope`.
- `tools/list` — the 26 tools of §6, **generated from the live picocli tree plus `CommandContracts`**, with `ttlMs`/`cacheScope` and deterministic ordering.
- `tools/call` — compact-serialised envelope into `structuredContent`; the `--text` rendering of that same envelope into `content`; **`outputSchema` only where every exposed profile returns `RESULT_ENVELOPE`** (§4); `isError: true` for `rejected`/`refused`/`error`; JSON-RPC `-32602` for an unknown tool or op.
- `resources/list`, `resources/templates/list`, `resources/read` for the §8 URIs.
- Annotations **derived** from `CommandContracts` exactly as §7 specifies.
- Addressing hygiene per §9: relative-path rejection, resolved-roots echo, traversal and workspace-escape rejection.
- A conformance guard that the tool set **and every derived `readOnlyHint`** match the live tree and registry, in the `ManifestVerbTest` family; MCP Inspector smoke checks in CI plus hand-written assertions for the MUSTs implemented (§5.2).

**Optional**

- Per-tool `title` annotations.
- Response streaming for long `model check` runs on large workspaces.

**Excluded — deliberately**

- Multi Round-Trip Requests, `subscriptions/listen`, sampling, roots, logging, and `io.modelcontextprotocol/tasks`.
- The official Java SDK on the stdio path (§5.2).
- Any warm model, resolved-model cache, or workspace-index cache (§9, R3).
- Any new CLI verb. *(The previous revision also excluded new CLI metadata; that exclusion is void — the metadata landed, and phase 1 consumes it.)*
- `dm:///skill` and any excerpt of the skill into `instructions` (§10).
- A plugin MCP-server registration (§10) — phase 1.5, gated on R1/R2.
- In-flight cancellation; concurrent dispatch (§5.1, R9).
- **MCP Apps** — phase 2, whose trigger §1 records.
- **Hosted HTTP** — phase 3, §13.

## 13. Hosting — shapes, requirements, and the design

Phase 1 depends on none of this. It is recorded because EU-sovereign hosted access is where the idea started, and re-deriving it later is the more expensive mistake. **Candidate assessments and their rationale are [Appendix A](#appendix-a--hosting-candidate-assessments)**; this section carries only what governs *any* candidate.

### 13.1 Three deployment shapes

| | shape | licensing delta | ops burden | who answers the sovereignty question |
|---|---|---|---|---|
| **S1** | `dmtool mcp` — stdio inside the existing binary (**phase 1**) | none; a local artifact under the posture [LICENSING.md](LICENSING.md) already sets | none | nobody has to — nothing leaves the machine |
| **S2** | Publish a **container image**; the customer runs it in their own tenancy | none; distribution, exactly as the binary is today | theirs | theirs, by construction |
| **S3** | **We host it** — either as a public demonstration endpoint or as a service | the hosted combined work must be offered under EUPL-1.2 with source (§13.2) | authentication only if state is held; otherwise availability and abuse handling | ours |

**S1 for phase 1; S2 as the answer to "can we have it hosted"; S3 as a separately justified decision.** In order of weight: S1 is most of the code for S3 and none of the risk, because statelessness means there is no session layer to build twice; S2 answers EU sovereignty better than S3, since a customer who cares about sovereignty wants the thing in *their* tenancy; and S3's real cost is the licensing posture in §13.2, which has no undo.

**The previous revision distinguished a read-only demonstration endpoint from a customer-serving service, and preferred read-only.** §13.4's content-carrying design removes the need for that distinction: with nothing held between requests there is no customer data to protect, so a demonstration endpoint can expose the **full authoring surface** and still have nothing at stake. The two are now one shape differing only in what is promised about availability.

**GitHub Actions is not a hosting option.** CI jobs are ephemeral, with no inbound endpoint and no stable URL. It is the right place to *build and deploy* the image and the wrong place to *run* the server.

**The question that decides S2 versus S3 is who the consumer is** — and §13.4 answers it on a technical axis rather than a business one.

### 13.2 The gate is licensing, not infrastructure

EUPL-1.2 already applies to the shipped bundle: [LICENSING.md](LICENSING.md) sets the rule as *"EUPL-1.2 for any artifact in which we bundle the kernel"*, so distributing the binary — and an S2 container image — changes nothing. What **hosting** adds is that Article 1 counts *"providing access to its essential functionalities"* as Distribution and/or Communication, so the copyleft and source-availability clauses attach to a service that hands out **no copies at all**, and the hosted combined work must be offered under EUPL-1.2 with a maintained source repository. **That consequence is indifferent to §13.4 and to whether the service is free** — it is the one hosting obligation content-carrying does not reduce.

What content-carrying *does* reduce is the data-protection posture. Holding customers' document models at rest makes the operator a **GDPR processor** with a DPA, a retention policy, and a deletion obligation. A service that holds nothing between requests is a processor only for the duration of a request: no retention policy, no deletion duty, no storage to breach. §13.4 is therefore a licensing-adjacent design decision, not only an architectural one.

Dropping to a kernel-free `STRUCTURAL_ONLY` backend to escape the EUPL clause is not a real option: it removes the consistency oracle, which is the most valuable thing dmtool does. *(Engineering analysis, not legal advice — the same caveat LICENSING.md carries.)*

### 13.3 Requirements for any hosted deployment

**H1–H6 recorded 2026-08-03; H7–H12 added in this rewrite from the measurements that produced them.** These govern any candidate. The worked examples that produced them are in Appendix A.

| # | requirement | basis | |
|---|---|---|---|
| **H1** | **Never silently enter a paid tier.** The maximum possible charge must be bounded **by construction** — a platform-enforced ceiling, an absent collection channel, or a configuration whose worst-case consumption is provably inside a free allowance. Monitoring, alerting, and after-the-fact shutdown do **not** satisfy this | owner | **top priority** |
| **H2** | **The bound must hold under hostile traffic.** A flood must not be able to raise the bill, and the safe setting must not depend on platform defaults being sane | owner | **top priority** |
| **H3** | **Service unavailability is preferred over any charge.** On exhausting the allowance the service may stop; it may not bill | owner | required |
| **H4** | **Free at the expected load** — at most a few thousand calls per day, normally within a 4–8 hour window | owner | required |
| **H5** | **EU jurisdiction and GDPR compliance.** SecNumCloud qualification only if a customer's requirement is literally SecNumCloud | owner | required |
| **H6** | **EUPL-1.2 source availability accepted** for the hosted combined work (§13.2) | licence | gate |
| **H7** | **≥ 256 MB RAM**, and a pinned `-Xmx` below the host limit | measured: authoring and inspection are flat at **~43 MiB**, dominated by the mapped image rather than by model size; document evaluation reached **519 MiB** on a 714 KB / 100-row stress fixture. 256 MB carries ~6× headroom for the authoring workload this surface exists for. At `-Xmx200m` an over-budget evaluation returns `outcome: "error"` / `RK_INTERNAL_ERROR` and the server keeps serving, whereas an unpinned native image sizes its heap from *physical* host RAM and would be killed, taking the endpoint down for every caller | required |
| **H8** | **A long-lived process that binds a port.** Streamable HTTP needs a resident listener; per-instance-concurrency-1 function products are event handlers, not JSON-RPC endpoints | design | required |
| **H9** | **`linux/amd64` artifacts.** The maintainer's machine is ARM, so the release pipeline needs an amd64 build for any hosted path | environment | required |
| **H10** | **No platform retention of request content.** Backups or snapshots that persist request bodies are disqualifying for a service that must hold nothing. §13.4's in-memory resolution satisfies this from our side; the platform must not undo it | §13.2 | required |
| **H11** | **A contractual floor, for a customer-serving endpoint.** A plan permitting suspension *or deletion* at unbounded discretion cannot carry an availability or retention commitment. Acceptable for a demonstration endpoint, where H3 already trades availability away | Appendix A | conditional |
| **H12** | **A bounded, configurable request-payload cap**, with a corrective diagnostic on exceeding it — the analogue of the decoder's existing `document_code_units` limit, now applying to the carried model set | §13.4 | required |

**The acceptance test a candidate must pass: can the maximum possible monthly charge be stated as a number derived only from settings we control?** If not, the candidate fails H1 whatever else it offers. Two candidate *classes* can satisfy H1 structurally — a platform that **suspends rather than bills** when a free tier is exhausted, and a **fixed-price plan whose ceiling is the price** (trading "free" for "bounded", which H1 permits and H4 must then be revisited against). **Usage-metered serverless with an uncapped invoice is disqualified as a class, not as a vendor.**

Note that **S2 satisfies every requirement here trivially**, by moving the exposure to whoever runs it — a further argument for §13.1's recommendation.

### 13.4 The hosted design — requests carry their models

**Owner decision, 2026-08-03: a hosted request carries the models the operation needs, and the service retains nothing between requests.** This replaces the previous revision's handle-and-object-storage design (recorded in Appendix B). Isolation stops being a mechanism to build and audit, and becomes a property of the transport: two callers cannot reach each other's models because the service holds none.

**"Workspace" is a relative concept over MCP.** dmtool's workspace exists for exactly one job — resolving `include`s and typedef `import`s by `header.id`. The directory was only ever a way to *find* the models that resolution consumes. So the request carries the subject's **transitive resolution closure**, which is not an approximation of the workspace but precisely what resolution reads. The three-tier `-w > env > model dir` precedence has no referent here and simply does not exist for this transport.

**The wire shape, keyed to match the resolver:**

```
models:  { "<header.id>": "<DM-JSON>", … }     the closure — subject plus what it references, transitively
subject: "<header.id>"                          which one the op acts on
op, args                                        unchanged
```

Keying by `header.id` matches `ModelResolver.resolve(modelId)` exactly rather than inventing an addressing scheme, and three properties follow: there are no path components on the wire, so §9's traversal surface does not exist; duplicate ids are **unrepresentable**, where the directory index silently lets last-one-win; and a mutating op returns the mutated model in the response instead of persisting it, so there is no write to authorise.

**The closure is discoverable by failure, not by a manifest.** `ReferenceResolvers.directory` already fail-fasts on an unresolved id, and `Cli.run` already maps `UnresolvedReferenceException` to a corrective `rejected` / `RK_UNRESOLVED_REFERENCE` classified explicitly as *"an INPUT problem … not an internal error"*. So a client sends what it believes it needs, is told exactly which id is missing, and re-sends. Two stateless round trips, self-correcting, no server memory. The only change needed is the diagnostic naming the supplied set rather than the searched roots. **That the mechanism already exists is the strongest evidence this design fits the tool** — it is the corrective-diagnostics principle doing transport negotiation.

**Two guards the closure needs:**

1. **Workspace-scope verbs are scoped to what was supplied, and must say so.** `workspace list`/`graph`/`check`/`roles` are definitionally about a set. Over this transport the set *is* whatever the client sent, which is well-defined but changes the meaning from "my workspace" to "these models" — a missing model would otherwise make `graph` report a dangling reference that is not dangling in the caller's real tree. Their envelopes must state the scope, never imply completeness.
2. **The referrer direction is outward-only, and this makes a latent limit visible.** `where-used` and the reference-rewriting refactors analyse the *subject's expansion* — `adapter.whereUsed(entity)` and `refactor.referrers(adapter, doc)` — so the resolution closure is exactly sufficient and nothing is lost. But if model B includes model A, a refactor on A never sees B's referring rules, because expansion follows references outward from A. **That is true of the CLI today with a full directory in hand**; content-carrying does not create it, it makes it legible, because the client must decide what to send. Worth stating beside the refactoring safety gate rather than leaving invisible.

**Implementation: materialise per request, do not re-implement dispatch.** The kits are already content-first (§4), but the CLI layer requires paths and `emitMutation` writes to one. Three routes were considered:

| route | how | assessment |
|---|---|---|
| **A** | per request, a fresh temporary directory holding the supplied models; run `Cli.run` with `-w` at it; read back; delete | **recommended for a first endpoint.** Zero CLI change, every verb works immediately, keeps §5.1's single entry point. Costs per-call I/O, and request content transiently touches disk, so H10 needs checking against the platform. Filenames are server-generated — never a caller-supplied component |
| **B** | drive the kits directly with `ReferenceResolvers.map(...)` and `EditSession.read(json, resolver)` | **rejected.** Nothing touches disk, but the dispatcher would re-implement verb dispatch for 81 verbs — exactly the drift the derived-from-picocli design exists to prevent |
| **C** | teach the CLI content addressing: inline model set in, mutated model out, still through `Cli.run` | **the graduation path** if a programmatic-client API becomes a product. Small, single-sourced, covered by existing guards, and it makes the CLI itself a pure function. It is new CLI surface and needs its own gate |

One library gap is worth closing regardless of route: **`ReferenceResolvers` has no map factory**, though `ModelResolver`'s own javadoc names a map as an intended source and [`GroupExtractCommand`](../cli/src/main/java/io/github/mbackschat/a12/dm/cli/group/GroupExtractCommand.java) already hand-rolls one as a lambda. That hand-rolled lambda is the signal; the factory belongs in the library under the dogfood rule.

**Who this serves, which answers §13.1's open question.** The previous revision dismissed inlining as "token-fatal on real models". That is true when an **LLM writes the tool call** and false when the **client is code** — then the model set is a request body nobody pays tokens for. So the two shapes have disjoint consumers and do not compete:

| | consumer | addressing |
|---|---|---|
| **S1 stdio** | an agent, whose host already has the files locally | filesystem paths (§9) |
| **S3 hosted** | a program — an app, a CI job, a web UI, A12 Tools | the carried closure |

The closure reduction also makes the payload proportional to the *operation* rather than the tree, which is what makes H12's cap a real number: most operations are one self-contained model, or a model plus one or two typedef models.

**What content-carrying does not solve.** Throughput is unchanged — callers still share the CPU, and §5.1's serialised dispatch would head-of-line block a second caller behind a slow `model check` or `model eval`; a **bounded queue returning an immediate busy error** is required, because this revision removed stream resumability and a timed-out request is re-issued as a *new* one, so unbounded queueing invites a retry storm. Memory scales with concurrency, so H7's pinned `-Xmx` and H12's cap both bind. And R9's thread-safety claim is still needed for concurrent dispatch — though content-carrying reduces it to a *pure* thread-safety question, since requests share no filesystem state.

**One temptation to refuse: a content-addressed cache.** Once payloads grow, having the client send hashes and the server request only what it lacks is the obvious optimisation. It reintroduces server state and with it every isolation question this design deletes, in exchange for bandwidth. If payload size becomes the actual problem, revisit it then, with a measurement — the same discipline §9's no-warm-model rule applies to parsed models.

## 14. How this stands against decisions already recorded

Prose rather than a table, because a status board over other documents' decisions is what [DOC-DISCIPLINE.md](DOC-DISCIPLINE.md) §5 forbids. Each owning document remains authoritative.

**[RELEASE-SPEC.md](RELEASE-SPEC.md) §6 and §12 rejected MCP** — §6: *"not MCP — wrong model for a local offline CLI, and it would undercut the self-describing-CLI thesis"*; §12: *"it adds a running process and undercuts the offline self-describing-CLI thesis."* On "adds a running process": true, and §10 now keeps that process out of the plugin entirely for phase 1. On "undercuts the self-describing-CLI thesis": the counter-argument is that the MCP surface is *derived* — `tools/list`, every annotation, and every resource recompute from the same picocli tree and contract registry the CLI describes itself from, so it cannot describe the tool differently. What MCP genuinely does not preserve is **offline zero-install simplicity**: it adds a process, a lifecycle, and a second name vocabulary. Neither section is repudiated: the plugin remains the primary vehicle, and MCP is an additional surface, which is the shape §12 left on the table.

**[FEATURE-GAPS.md](FEATURE-GAPS.md) §4's `dmtool serve` won't-build** is addressed ground by ground in §3, with the residue stated rather than dismissed. R3 records the condition.

**[CLI-CONFORMANCE-GAPS.md](CLI-CONFORMANCE-GAPS.md) §2's demoted `sideEffect` axis** is closed by events, not by argument — it landed as `CommandContracts.Effect` (§7).

**[CLI-CONFORMANCE-GAPS.md](CLI-CONFORMANCE-GAPS.md) §3's remote/async cluster** stays void. §3 scopes those recommendations to a *resident or remote* mode; a local, synchronous, stateless-per-call stdio server is the posture §3 already describes dmtool as having. Two specific notes: pagination is **not** answered by `ttlMs`/`cacheScope` — the list endpoints remain cursor-based and the mechanisms are orthogonal (§2); and trace context is not "free" — `_meta` carries client-supplied OpenTelemetry conventions, while the voided recommendation was `--trace-id` *plus a correlated audit log*, which MCP neither supplies nor obliges.

**[MODEL-REVIEW-GAPS.md](MODEL-REVIEW-GAPS.md) §2's MCP Apps widget** re-opens when dmtool *ships* as an MCP server. Phase 1 shipping fires it; the widget is phase 2 (§12).

**A durability observation this proposal surfaced but does not own.** Model-in-place writes are non-atomic — `Files.writeString` truncate-and-write with no temporary file and no `ATOMIC_MOVE` — at **four** sites: the shared `BaseCommand.emitMutation` funnel serving 33 verbs, plus `ApplyCommand`, `PatternsCommand`, and `GroupExtractCommand`'s two-file write. Nothing locks a model. The stdio case sharpens this because two clients means two resident server processes over one tree, where serialised dispatch buys nothing, and because a resident process is terminated on session end or plugin disable at a moment nobody chose. The remedy is a shared atomic-write helper all four sites route through, guarded by a test proving a killed write leaves the original intact. **It belongs to `cli/` as an independent durability fix, not to this proposal's scope** — recorded here because this is where the question arose. Content-carrying makes it moot for hosted (§13.4), never for stdio.

## 15. Open for owner decision

**Q1 — which revision, given no evidence the host speaks `2026-07-28`?** Target `2026-07-28` only, implement a `2025-11-25` fallback alongside it, or verify the host first and decide after. **Recommendation: verify first** — one connection test against the real host once a skeleton exists, and a wrong answer means phase 1 does not work. This is R7 and it gates scheduling.

**Q2 — is the effect-split tool shape right?** §6 proposes 26 tools (12 truthfully read-only) against the previous 17 (3 read-only), buying 9 extra truthful `readOnlyHint`s and safe categorical approval on every read tool for 9 extra definitions. Alternatives: the previous 17-tool target grouping, which discards a published fact about 25 read-only verbs; a pure target×effect partition at 21 tools, which re-groups the root verbs despite their differing contracts; or one tool per verb at 81, maximal fidelity and maximal definition cost. **Recommendation: the 26-tool split** — the partition is derived from published `Effect` rather than chosen, and the approval-bundling risk is the one concrete safety argument in this design. The invented part is the `.read`/`.edit` name components and `dm.catalog`'s grouping, and the owner should see that named.

**Q3 — is the bare-MCP-host audience decision (§10) accepted?** It follows from the skill being invocation-shaped, and it decides that no MCP skill and no marketplace entry are needed, that the derived tool descriptions must carry the judgment a bare host needs, and that an MCP bare-tool eval arm is the way to check that. **Recommendation: accept** — the alternatives (an invocation-neutral skill rewrite, or two skills) both cost more than phase 1 is worth and one of them moves the N6 baseline.

**Q4 — is the hosted content-carrying design (§13.4) ratified as the phase-3 direction?** It replaces handles, object storage, and OAuth with a pure-function service, reduces §13.2's processor role to transient processing, and defines the consumer as a programmatic client rather than a chat agent. It does not reduce the EUPL obligation. **Recommendation: ratify as direction** — nothing is built on it in phase 1, and ratifying now keeps Appendix A's requirements pointed at the right target.

---

## Appendix A — hosting candidate assessments

Dated research, kept because the requirements in §13.3 were derived from it and because the sovereignty findings generalise. **Re-measure against whatever candidate is actually chosen.** No per-unit pricing table is recorded — those numbers rot and were never the decision input. Free allowances *are* recorded, because free hosting is a stated requirement and an allowance is therefore a design constraint.

### A.1 Scaleway — rejected on billing model, 2026-08-03

Assessed because it was recommended on sovereignty grounds, and **rejected on its billing model rather than on sovereignty**, which it largely satisfies.

**Sovereignty, read from the vendor's own security-and-compliance page and a procurement announcement on 2026-08-02.** *Satisfied:* 100% European infrastructure under EU jurisdiction, stated by the vendor as *"free from the US CLOUD Act"*; **ISO/IEC 27001:2022** and **HDS** certified; selected in April 2026 under the European Commission's **Cloud III Dynamic Purchasing System** after evaluation under the Cloud Sovereignty Framework. *Not satisfied:* **SecNumCloud is not held** — the vendor's page says *"currently undergoing qualification."* And the substitution is not like-for-like: providers that do hold it offer no equivalent serverless-container product, so satisfying SecNumCloud changes the deployment shape to managed VMs. **Verdict: sound for GDPR and EU-jurisdiction sovereignty; not a choice for SecNumCloud-regulated workloads today.** Under S2 it stops being ours to answer at all.

**Technical fit.** Serverless **Containers** fit the workload: an hour request ceiling, HTTP/2, scale to zero when idle, and a generous image budget against a 33 MB / ~90 ms native binary. Serverless **Functions** are the wrong product — per-instance concurrency of one and a small request-payload cap make them event handlers (the origin of H8). Two facts generalise: an **amd64** image is needed whatever the platform (H9), and **process start is not container start**, so 33 MB / ~90 ms is a floor for cold-start latency rather than a measurement of it.

**Why it fails H1**, verified 2026-08-03 against the vendor's billing-alerts and billing-FAQ pages: the free tier is an **allowance deducted on the invoice**, not a ceiling, with consumption beyond it billed pay-as-you-go automatically; **billing alerts only notify** — a monthly budget plus a percentage threshold by SMS, e-mail, or webhook, with nothing that stops, throttles, or caps usage; and there is **no spending limit and no prepaid mode**, prepaid cards being explicitly rejected. A cost-triggered webhook cannot close the gap: inside the allowance the cost is €0, so a *cost* threshold necessarily fires **after** billing has begun.

**The H2 exposure, quantified.** Charges are tier × run duration × instances, so a flood cannot exceed the ceiling *we* configure — but the published ceilings are high: maximum scale runs to 50 instances, so 50 instances pinned for a month is on the order of **€1,900 at the default tier** and **€11,000 at the maximum**. Two details make it real rather than theoretical: **the defaults are not safe**, so maximum scale must be lowered by hand; and **rejecting a request still consumes run duration**, so authentication narrows the blast radius without removing it. With alert-only detection, an unnoticed weekend flood at default settings is a three-figure invoice.

**One configuration would have satisfied H1 and H2, and is recorded because it is the shape any candidate must offer.** At the platform floor — 70 mvCPU / 128 MB, maximum scale capped at **one** instance — continuous operation for a 31-day month consumes ~187,000 vCPU-s and ~335,000 GB-s against a 200,000 / 400,000 allowance. Since consumption is exactly tier × time and time cannot exceed the month, exceeding the allowance is **arithmetically impossible** regardless of traffic, and a flood becomes cost-neutral. The one-instance cap is the load-bearing half. It did not save the vendor for three reasons: whether the allowance is per-container or account-wide is not settled by the documentation; its guarantee is *our* arithmetic rather than the platform's promise, so a change to the allowance or tier floor silently breaks it; and 70 mvCPU is ~7% of a core as a *ceiling* rather than a reservation, making every CPU-bound call take seconds. On memory, 128 MB would have held the authoring workload (~43 MiB) and not document evaluation (up to 519 MiB) — the split that produced H7.

**Decision (owner, 2026-08-03): out.** H1 outranks the sovereignty fit that motivated the candidate.

### A.2 alwaysdata Free — the first candidate to pass H1 by construction, 2026-08-03

Forwarded by the owner from independent research and re-verified against the vendor's own pricing, legal, and documentation pages.

**The plan:** €0/month for life, 1 GB SSD, **256 MB RAM**, **¼ CPU**, 3 days of backups, an `alwaysdata.net` hostname, offered "for personal needs". The publisher is **ALWAYSDATA SARL**, a French company (Paris RCS 492 893 490) with a CNIL processing declaration, which is what H5 asks at the jurisdiction level. It carries **no ISO 27001, HDS, or SecNumCloud claim**, so it cannot serve H5's second clause.

**Why it passes H1 — a stronger mechanism than an arithmetic bound.** The plan is a fixed €0 subscription rather than a metered allowance, and the platform can be used with **no payment instrument registered at all**. Accounts with neither a card nor a prior payment sit in "restricted mode", and the documented way out is deliberate — *"fill in your credit card details … or make at least one payment"*, with the doc noting that adding a bank account explicitly does **not** lift it. The maximum charge is €0 because there is no collection channel, and opening one requires an affirmative act by us.

**Restricted mode does not block the endpoint** — the decisive check, since restricted mode *is* the H1 configuration. Its documented effects are that *"HTTP: processing may take a bit longer as requests will be filtered using rules that are a little stricter"* and that outgoing SMTP may be limited; the doc calls the restriction *"not an inhibiting one"*. **Unverified residual:** those stricter filter rules are unspecified, and an endpoint receiving automated agent traffic is plausibly what they exist to catch. One deployment settles it.

**H2 and H3 pass by construction.** Resources are fixed rather than request-metered and no replicas are created automatically, so hostile traffic converts into queueing, throttling, or suspension rather than spend — and the vendor reserves the right to suspend free accounts running resource-intensive applications, which is precisely the H3 failure mode, chosen deliberately.

**H7 and H8 fit.** 256 MB is exactly the H7 floor, with ~6× headroom for the authoring workload and a pinned `-Xmx` needed for document evaluation. The platform's **user program** site type takes a start command, a working directory, and environment variables and expects the program to bind the IP and port it is given, with SSH available to test first and Java among the supported languages — a first-class fit for H8, and a better one than a scale-to-zero container.

**What weakens it, and the correction to the forwarded assessment.** The research reported that commercial use is explicitly prohibited; re-reading the vendor's pages, **no such contractual clause was found**. What exists is softer and, for a production endpoint, worse: the pricing card frames the plan as "for personal needs", the documentation describes the free offer as for low-resource needs or evaluating the platform, and the legal page states that **"alwaysdata reserves the right to suspend or delete without notice a free account if its legitimacy is contested."** That is unbounded discretion rather than a bright line — there is no clause to comply with, so there is no way to make the arrangement safe by conforming to it. This is the observation that produced **H11**.

Note the verb: **delete**, not only suspend. Under H3 that is acceptable. The forwarded research also correctly flagged that three days of rolling backups mean payloads written to disk persist beyond the request, and drew the right conclusion — *do not write request bodies to the filesystem*. The previous revision recorded that this mitigation was unavailable to dmtool because its state *is* files. **§13.4 makes it available**, which is one of the strongest arguments for content-carrying and the origin of **H10**. Route A's temporary directory needs checking against it; route C removes the question.

**Assessment: viable for a public endpoint holding no customer models**, where deletion without notice costs a redeploy. It satisfies **H1–H4, H7, H8** and **H5 at the jurisdiction level**, fails **H5's certification clause** and **H11**, and **H10 depends on the implementation route**. Under §13.4 it can serve the full authoring surface rather than only a read-only one, because there is nothing at rest to protect. As infrastructure serving customers or the company it still fails — not on resources, but on the absence of any contractual floor.

---

## Appendix B — decisions recorded, positions superseded

Kept short and factual: what was held, what is held now, and why it changed. Stale claims are deleted from the body rather than annotated there, so this is the only place the change is recorded.

| position in the previous revision | status now | why |
|---|---|---|
| Envelope classification is `returns: "result" \| "artifact"`, split **70 / 2 / 9 contract-exempt** | **superseded** | `CommandContracts` publishes a 4-way `ReturnKind` **per route**: 142 profiles, `RESULT_ENVELOPE` 125 · `RAW_JSON` 10 · `RAW_TEXT` 6 · `EMPTY_STDOUT` 1. Per verb, 68 are envelope-only. Exempt verbs now return an operational-only contract rather than no contract |
| `manifest` is 94,681 bytes compact / 118,226 pretty | **superseded** | 273,549 bytes pretty, measured 2026-08-03; the growth is the operational profiles |
| **17 target-shaped tools**, 3 read-only | **superseded** | 34 verbs are `READ_ONLY` in every profile, so target-only grouping discards a published fact about 25 of them and bundles read with remove under one approval name. §6 proposes 26 effect-split tools; §15 Q2 puts it to the owner |
| *"Precision is not available"* for annotations; effect is argument-selected and a per-tool boolean would be a false statement | **superseded** | Effect is published per route. `readOnlyHint` is now derived and true for 12 tools. The residual imprecision is narrow and systematic: 46 verbs differ across profiles only via a `--dry-run` or output-flag selector |
| Owner question: *does this fire the `sideEffect` trigger?* | **closed by events** | The axis landed as `CommandContracts.Effect` |
| Two `[ASSUMED-FIXED]` CLI dependencies (C1: exact verb-completeness guards; C2: measured stdout-purity denominator) | **both landed**, markers deleted | `709acf98` and `70f20ed6`. `ManifestVerbTest` asserts set equality over 81 entries; CLI-CONFORMANCE-GAPS §2 records the 81-leaf sweep |
| *"Every mutating verb funnels through one shared path"*; the atomic-write remedy is single-sourced | **corrected** | Model-in-place writes occur at **four** sites: `BaseCommand.emitMutation` (33 verbs), `ApplyCommand`, `PatternsCommand`, and `GroupExtractCommand` (two files). The remedy is a shared helper, and it belongs to `cli/` rather than here (§14) |
| Hosted state lives in **server-minted handles** over object storage, with OAuth 2.1 per-request identity and per-principal handle binding | **superseded** | §13.4: requests carry their models and the service retains nothing. Handles were also the wrong *granularity* — a model handle cannot resolve an include, because resolution is by `header.id` across a set |
| *"An endpoint without authentication must not expose mutating tools"*; prefer a **read-only** demonstration endpoint | **superseded** | The rule was sound given persistence. With nothing persisted there is no write to authorise — a "write" is a response value — so the full authoring surface is exposable without authentication |
| Inlining models is *"token-fatal on real models"* and therefore not an option | **corrected** | True only when an LLM writes the call. For a programmatic client the model set is a request body with no token cost. This is what splits the consumers (§13.4) |
| S4, a read-only public demonstration endpoint, as a shape distinct from S3 | **merged into S3** | The distinction rested on read-only-ness, which §13.4 removes; the two now differ only in what is promised about availability |
| A plugin `.mcp.json` ships in phase 1; R1 is *"the largest concrete engineering risk"* | **deferred** | §10: phase 1 documents a user-registered server, so R1 becomes a phase-1.5 item gated on R2. It also surfaced a failure-policy inversion — `ensure-dmtool.sh` warns and exits 0, which for an MCP `command` reads as a broken server |
| *"The skill stays where it is — the plugin's own skill already delivers it"* | **corrected** | `skill/SKILL.md` is invocation-shaped (19 `dmtool …` invocations, guard-enforced), so it delivers nothing to a bare-MCP host and diverts a plugin host back to the CLI. §10 makes the bare-MCP host the stated audience instead |
| Excluded: *"any new CLI metadata"* | **void** | The metadata landed; phase 1 consumes it |
| R3 measures *"per-call model parse cost"* | **re-framed** | The quantity is the per-call **workspace index** cost: `ReferenceResolvers.directory` indexes eagerly at construction and scales with the tree, not the subject model (§9) |
