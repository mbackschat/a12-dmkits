# dmtool over MCP — proposal

**Status:** **PARKED** (2026-08-04, owner decision). The design is settled and **nothing is built**; no present need triggers building it. The re-open triggers are in §1. Graduates into the owning specs if and when a phase lands, per [DOC-DISCIPLINE.md](DOC-DISCIPLINE.md) §5.

> ### Positioning — read this before anything else
>
> **MCP is for callers that cannot execute the binary. Where an agent can run `dmtool` directly, the CLI is the better surface and should be used instead.**
>
> It is the *same executable*: an MCP server would be one more leaf in the same command tree, routing every call to an existing verb and adding no authoring capability. So for a local, shell-capable agent, MCP is pure indirection — and under §9's content-carrying rules it is actively worse, because it gives up in-place editing and pays a read-modify-write round trip the CLI does not.
>
> MCP earns its place in exactly three situations, none of which is "local convenience": a **host that cannot spawn a subprocess** (a web client, a hosted agent platform, a mobile or sandboxed IDE integration), a **hosted deployment** where the caller installs nothing, and **host-enforced approval**, where allowlisting a read-only tool is a guarantee that granting shell access can never be.

**Scope.** Expose the dmtool surface over the Model Context Protocol, revision **`2026-07-28`**. Two transports are described: a **stdio server inside the existing binary** (`dmtool mcp`), and hosted HTTP in §13 plus [Appendix A](#appendix-a--hosting-candidate-assessments). Per the positioning above, the *hosted* transport is the one that carries the value; the stdio server is a development and testing vehicle for it, not a recommended local surface. MCP Apps is a later phase. **Nothing here is proposed for implementation while this document is parked.**

**Owner decisions already taken:**

| decision | date |
|---|---|
| stdio-first; hosted is a later, separately justified phase | 2026-08-02 |
| target `2026-07-28` hand-rolled; the official Java SDK kept as a possible later substitution for a hosted transport only | 2026-08-02 |
| hosting requirements govern any candidate, with "never silently enter a paid tier" top priority | 2026-08-03 |
| hosted requests carry their own content; the service retains nothing between requests — per-caller isolation by statelessness rather than by mechanism | 2026-08-03 |
| the carried set is a **caller-declared request-local resource tree**, not a derived closure; the closure is a client-side payload-minimisation rule only | 2026-08-04 |
| a verb whose correctness depends on input-set completeness runs only under an explicit **authority assertion**, and is refused without it | 2026-08-04 |
| the write-durability finding is answered by publishing `FailurePersistence`'s scope, not by making writes atomic | 2026-08-04 |
| target `2026-07-28` **only** — no `2025-11-25` fallback; anything below it is **refused with a corrective diagnostic**, never partly served (§5.3) | 2026-08-04 |
| **zero new dependencies** — no MCP SDK (none implements this revision), no JSON-RPC library, no schema validator; Jackson tree plus the JDK, both already in the binary (§5.2) | 2026-08-04 |
| a later SDK is confined to **one binding class**, prepared by three things the design wants anyway and by nothing else — no second implementation, no factory, no MCP-shaped types (§5.4) | 2026-08-04 |
| **positioning: MCP serves callers that cannot execute the binary; the CLI is the better surface wherever it can be run.** The proposal is PARKED on this basis, with three re-open triggers (§1) | 2026-08-04 |
| the tool surface is the **joint partition of `Effect` × `ReturnKind`** inside each target — 30 tools, both published facts truthful everywhere they can be; a verb is never changed to make a tool eligible (§6) | 2026-08-04 |

**Evidence basis — four source classes, deliberately not conflated:**

| class | what it governs | how it is cited |
|---|---|---|
| **normative specification** — `modelcontextprotocol.io/specification/2026-07-28/**` | every protocol MUST/SHOULD in §2, §5, §8, §9 | linked inline; read 2026-08-02 |
| **non-normative client guidance** — `modelcontextprotocol.io/docs/2026-07-28/**` | progressive discovery, retrieval strategies, categorical approval (§6.1, §7) | labelled *guidance* at each use |
| **host documentation** — Claude Code plugin reference | plugin packaging (§10), tool-name scoping | labelled *host docs*; not part of MCP |
| **this repository** — source read, or a dated measurement | every dmtool claim | §4 separates measured from unverified |

**Measurement provenance.** Every dmtool figure below was re-derived **2026-08-04** by running the packaged launcher built from the tree at **`5c0d5f03`** and analysing `manifest`, or by source read at a cited locus. `:cli` sources moved while this revision was being written (`SchemaCommand`, `VersionInfo`, `result.schema.json`), so the figures were re-measured against the current build rather than carried from an earlier one — reused numbers were the dominant defect class in both review rounds, and one figure in the first draft of *this* revision was carried from a reviewer rather than re-derived, which the re-measurement caught.

---

## 1. Motivation

### Why this is parked, and what would un-park it

**No present need triggers this work.** The reach argument below is real but was never the trigger — building the Claude Code and Codex plugins was near-identical work, so the "N native wrappers" friction RELEASE-SPEC §12 named as the condition has not arrived. And for the one host in daily use, an MCP surface would be strictly worse than the CLI it wraps (see *Positioning*). A proposal whose own motivation section declines to claim a trigger should say so at the top rather than read as a plan.

**The three re-open triggers**, each sufficient on its own:

| trigger | why it changes the answer |
|---|---|
| A wanted caller **cannot execute the binary** — a web client, a hosted agent platform, a sandboxed or mobile IDE integration | MCP stops being indirection and becomes the only route to dmtool at all |
| A **hosted** dmtool is wanted, so callers install nothing | MCP is the transport by construction. **Decide §13 first:** hosting is distribution under EUPL Article 1 (§13.2), so this trigger carries a licensing obligation the local case never has |
| **Host-enforced approval** becomes a requirement — a user allowlisting a read-only tool rather than granting shell access | This is the one benefit the CLI cannot provide at any effort, because approving a shell is unconstrained by construction |

**What this document already paid for while parked**, recorded so the work is not mistaken for sunk cost: it produced `CommandContracts` (the per-verb operational contract now published by help, manifest, and schema), the `FailurePersistence` scope publication (§14.1), the per-label persistence discriminator, and the `CustomJsWorker` residency defect (§9). None of those needed MCP to ship, and all of them stay.

### What it buys

**Reach without a wrapper per ecosystem.** [RELEASE-SPEC.md](RELEASE-SPEC.md) §12 already named this — *"MCP is the write-once alternative — one MCP server reachable by all agents instead of N native wrappers"* — and kept it on the table *"only if maintaining several native wrappers becomes the actual friction."* This proposal does **not** claim that friction has arrived: the Claude Code and Codex plugins were near-identical to build. Reach is a benefit, not the trigger. §14 addresses the grounds on which that same spec rejected MCP.

**A machine-addressable rendering of the self-describing surface.** MCP resources make per-verb content addressable and host-cacheable: `dm:///help/rule/add` returns one verb's record, and `ttlMs` lets the host keep it without re-asking. *Honest scope of that gain:* `dmtool rule add --help` is already a single call and needs the same prior knowledge of target and op. The delta is host-side caching and machine-addressability, not fewer calls — and no measurement shows help text is re-fetched often enough for the caching half to matter.

**A published contract MCP can consume directly.** `CommandContracts` publishes, per verb and per argument-selected route, the facts MCP annotations want: actual effect, persistence after failure, dry-run behaviour, aggregate success, output form, and expected provenance. §4 and §7 build on it. This is the strongest cheapness argument, and it is also where §7's precision now comes from.

**It makes a deferred feature reachable.** [MODEL-REVIEW-GAPS.md](MODEL-REVIEW-GAPS.md) §2 defers an in-chat HTML report widget, re-opening *"when dmtool **ships** as an MCP server."* Phase 1 shipping fires the trigger; the widget is phase 2 (§12).

### What it does not buy

- **No performance gain.** The shipped native binary starts in ~37 ms; there is nothing to amortise, and §9 forbids a warm model outright.
- **No new authoring capability.** Every tool routes to an existing verb, and no *authoring* verb is added, changed, or removed. The `mcp` command itself is one new leaf in the picocli tree — §12 states what that costs.
- **No replacement for the plugin or the CLI.** The plugin stays the primary distribution vehicle and the CLI the primary surface.
- **It is not vocabulary-neutral.** Tool names are a new vocabulary layered over the verb tree; §6 states exactly which part is invented.
- **No verb parity over a hosted transport.** §13.4 names the exclusions and why.

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

Roots — the mechanism by which a client told a server which directories it could reach — is deprecated, and the migration advice is *"pass directories or files via tool parameters, resource URIs, or server configuration instead."* Read precisely, that licenses **a location parameter where one is needed** and names **resource URIs** as an equal alternative; it is **not** an endorsement of a server accepting client filesystem paths. An earlier revision cited it as the specification recommending §9's then-path-based addressing — an overstatement, and §9 now decides the opposite (Appendix B).

## 3. Why statelessness is the enabling change

Under every revision through `2025-11-25`, an MCP server was a **stateful conversational peer**: a connection-scoped session established by an `initialize` handshake, a session-id header, and the ability to initiate its own JSON-RPC requests mid-conversation.

That shape is close to what this project already examined and rejected. [FEATURE-GAPS.md](FEATURE-GAPS.md) §4 records `dmtool serve` as **won't-build** (2026-06-15) on five grounds. Classifying them honestly: **three are performance arguments** this proposal does not make (the dead premise of amortising JVM startup, the retired `--kernel` eval warm-up, and the cost of a per-model bootstrap). **Two are not**, and both need answering:

**"`batch`/`apply` already occupy the space."** True, and unaffected — those are *batching* surfaces that amortise warm-up across a known op list and a transaction. This proposal claims no batching benefit for stdio. Under hosting the relationship inverts: because a request carries its own content (§13.4), `apply`/`batch` amortise one payload over many ops and become the primary shape rather than a competitor.

**"The cheap tier doesn't fix the real cost"** — a stateless serve re-pays the per-model bootstrap every op, so only a *stateful warm-model* serve would remove it. This proposal's server **is** that stateless variant (§9: every call re-reads), so this objection applies in full to any *performance* case for it. That is why §1 makes none.

**"It fights the agent-facing thesis — serve adds session/lifecycle/reload/stale-write semantics, a second mental model."** The revision deletes the *session* half: a conforming server is now free to be a pure per-call transport — read one self-contained request, run the verb, write the envelope, forget everything. That is already dmtool's own model; [CLI-SPEC.md](CLI-SPEC.md) §3a: *"a fully-specified, stateless invocation is easier for an agent to generate and to reproduce/debug."*

**The objection is not fully retired.** Protocol statelessness removes sessions, reload, and stale writes. It does **not** remove **process lifecycle** (R1 is nothing but lifecycle), **a second tool-name vocabulary** (§6), or **a second eval material boundary** (R5). Honest statement: the *session* ground no longer applies, on evidence the 2026-06-15 decision could not have had; the *second-mental-model* ground is reduced, not eliminated.

Three consequences follow, and they are why the work is small:

1. **The adapter is thin.** No session store, no handshake state machine, no resumability bookkeeping, no server-initiated request path.
2. **Hand-rolling the transport is reasonable.** What remains is one JSON-RPC dispatch table behind a single entry point (§5.2).
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

All figures re-derived **2026-08-04** from `manifest` on a dist built from `b5aba1ec`, after the batch-observations, candidate-eval, and absolute-path commits landed:

| quantity | value |
|---|---|
| live command-tree leaves | **81** |
| operational profiles across them | **143** |
| profiles per verb | 31 verbs have 1 · 45 have 2 · 2 have 3 · one each has 4, 5, 6 |
| `Effect` over profiles | `READ_ONLY` 94 · `MODEL_WRITE` 37 · `ARTIFACT_WRITE` 6 · `MULTI_FILE_WRITE` 3 · `DISPATCH` 1 · `TRANSACTION` 1 |
| `ReturnKind` over profiles | `RESULT_ENVELOPE` 126 · `RAW_JSON` 10 · `RAW_TEXT` 6 · `EMPTY_STDOUT` 1 |
| `FailurePersistence` over profiles | `NO_WRITE` 137 · `PARTIAL` 3 · `CONTINUE_AFTER_CHILD_FAILURE` 2 · `ROLLBACK` 1 |
| **`NO_WRITE` profiles whose effect is not read-only** | **43** (37 `MODEL_WRITE` + 6 `ARTIFACT_WRITE`) — the basis of §14's contract finding |
| verbs `READ_ONLY` in **every** profile | **34** |
| verbs whose profiles **disagree** on effect | **47** |
| verbs `RESULT_ENVELOPE` in **every** profile | **68** |
| verbs with at least one non-envelope route | **13** — of which **7** also have an envelope route (`model new`, `model expand`, `model import-jsonschema`, `model export-jsonschema`, `profile synthesize`, `workspace list`, `workspace graph`) and **6** never return an envelope (`model seed`, `export`, `schema`, `operators`, `diagnostics`, `manifest`) |
| verbs carrying more than one distinct `ReturnKind` | **9** — the 7 above plus `export` (`RAW_TEXT`/`EMPTY_STDOUT`) and `schema` (`RAW_TEXT`/`RAW_JSON`), which are mixed without ever being an envelope |
| `manifest` size, pretty-printed | **276,995 bytes** |

**On the 47 effect-disagreeing verbs.** Two mechanisms account for most of them — a `--dry-run` selector, and an output-flag selector where an absent `-o`/`--output`/`--out-dir` means "print to stdout" (`batch` joined this class when its observations artifact landed, taking the count from 46 to 47). **Two verbs use neither:** `patterns` discriminates `READ_ONLY` from `MODEL_WRITE` on the parsed *value* of `--apply`, and `meta` on the presence of any of twelve metadata-write flags (`anySet` versus `noneSet`). Whether every remaining verb uses only the two systematic mechanisms is **not enumerated**, so no universal is claimed here.

**Seven of the 47 have no `--dry-run` at all** — `model new`, `model expand`, `model import-jsonschema`, `model export-jsonschema`, `profile extract`, `profile synthesize`, `export` (`dryRun: NOT_APPLICABLE` on every profile). §7 depends on this.

**The manifest does not publish which parameters carry external content.** Only **12 parameters across 8 verbs** are tagged `kind: "FILE"`; the `kind` field is absent on `field add <spec.json>`, `model eval --instance`, `--predefined-types`, `--custom-conditions-js`, `--custom-field-types-js`, and `workspace roles <dir>`/`--roles`/`--users`, among others. §13.4's enumeration therefore rests on a source-and-help sweep rather than on a derived surface, and that gap is itself a design input for phase 3.

### The reuse, stated per route and per tool

| MCP field | dmtool artifact | applies to |
|---|---|---|
| `structuredContent` | the result envelope | the **68** verbs whose every profile returns `RESULT_ENVELOPE` |
| `content[0].text` | the `--text` rendering of that envelope | the same 68 |
| tool-execution error (`isError: true`) | `rejected` / `refused` / `error` outcomes with their diagnostics | the same 68 |
| `outputSchema` ← `result.schema.json` | the published result schema | **all 68**, under §6's joint partition — the 13 remaining verbs are ineligible under any grouping |
| `readOnlyHint` ← `Effect.READ_ONLY` | the operational profile | derived per tool; all **34** always-read-only verbs are covered (§7) |

**Why `outputSchema` forces the grouping, and how it was resolved.** MCP carries one `outputSchema` per `Tool`, and the specification requires that "if an output schema is provided, servers MUST provide structured results that conform to this schema." So a tool may declare it only when *every* profile of *every* op it exposes returns an envelope. An `Effect`-only grouping therefore left **6 of 26 tools** unable to declare it, stripping it from **14 otherwise-eligible verbs** — the largest read tool in the surface losing it *solely* because `model seed` (`RAW_JSON`-only) sat in it.

The resolution was to stop treating the two hints as competing for one partition: `Effect` and `ReturnKind` are **independent axes**, and partitioning on both recovers every fact on both, at four extra tool definitions. §6 records the decision and the measurement; the 13 verbs still ineligible are ineligible under *every* grouping, 9 of them because they mix return kinds within a single op.

### Also proven

| claim | basis |
|---|---|
| The tool list can be **derived** from the live picocli tree, inheriting the no-drift property `manifest` has | `manifest` is recomputed from that tree; `ManifestVerbTest` guards the verb set under a **set-equality** assertion over 81 entries |
| A Claude Code plugin can bundle skills **and** an MCP server in one plugin | *host docs*: components include skills, agents, hooks, MCP servers, LSP servers, monitors; config via `.mcp.json` at the plugin root or inline `mcpServers` |
| The official Java SDK cannot serve this revision, and carries a dependency we do not want | **Re-verified 2026-08-04:** v2.0.0 is the latest release and still tracks `2025-11-25`; no release or pre-release supports `2026-07-28`, and Java is absent from the Tier-1 beta set. `mcp-core`'s compile deps include `reactor-core` (§5.2). *Re-check trigger: a Java SDK release supporting `2026-07-28`* |
| `Cli.run` is already a pure per-call entry point | it constructs a fresh `new CommandLine(new RootCommand())` per invocation and writes to passed sinks, so no option state can leak between calls (§5.1) |
| Reference resolution is already content-first | `ModelResolver` is a `@FunctionalInterface` (`id → DM-JSON`) whose own javadoc names *"a map, a service, a classpath"* as intended sources; `EditSession.read(json, resolver)` and `BoundModel.ofJson(json, resolver)` take JSON strings. Only the CLI layer requires paths |
| Every verb resolves a machine-readable contract | all 81 `schema <target> <op>` calls parse. **The emitted shape is not uniform**: there are **17** distinct key sets. The commonest is `{op, returns, resultSchema, operationalProfiles, contractVocabulary}` (28 verbs); 17 add `data` + `dataSchema` and 9 add `data` alone; only **8** carry `input`; the directionally exempt form `{op, contract: "operational-only", directionalExemption, operationalProfiles}` holds for **9**; single cases add `verdict`, `docInput`, `notes`, or `artifact`; and `apply`/`batch` return a bare JSON Schema with no `op` key at all. `contractVocabulary` (§14.1) rides exactly the **47** verbs that can write, which splits several of those shapes in two. So a consumer must not assume one contract shape |

### Unverified — settle before building

| open item | why it matters | tracked as |
|---|---|---|
| **Which revision the intended host implements** | decides whether phase 1 is *usable*; no longer decides what gets built (§5.3) — an older host is refused legibly, so this is an acceptance test, not a prerequisite | **R7** |
| **MCPB** (MCP Bundles) as a packaging path | may resolve binary presence outright | R2 |
| Real host support for `resources` versus `tools` | a resource-only design could be invisible in some clients | R6 |
| Per-call **workspace index** cost | the only honest input to any future caching discussion, and it scales with the tree rather than the model | R3 |
| Token cost of the tool definitions | decides nothing in this design, but §6.1's secondary argument rests on it | R8 |
| The **closed-world** denominator (§13.4) | five leaves are known to need input-set completeness; whether any other verb makes a claim quantified over its input set is unaudited | R10 |

**Verdict: feasible and small on the protocol and licence axes. No longer blocked for scheduling** — the revision target is decided (§5.3), so R7 is a usability risk carried into build rather than a gate ahead of it.

## 5. Architecture

**The MCP command lives in `:cli`, not a new module.** `RootCommand`, `ManifestCommand`, and `TextRenderer` are package-private, so a class outside `io.github.mbackschat.a12.dm.cli` can neither build the live tree, nor walk it to derive tools, nor produce the `--text` rendering. Placing the command in that package makes all three reachable without widening any visibility. A separate module would additionally require `:cli` to depend on it while it depends on `:cli` — both edges cannot hold.

```
cli/…/mcp/McpStdioBinding     owns the wire: framing, the JSON-RPC envelope, _meta, headers   ← outermost
  └── McpDispatcher           method name -> handler over OUR request/result records
        ├── McpToolRegistry   derived from the live picocli tree + CommandContracts (the walk `manifest` uses)
        └── McpInvoker        Cli.run per call; envelope -> structuredContent + content (§5.1)
```

**The seam rule, stated precisely.** Only the binding may name a **transport or library type** — the JSON-RPC envelope, header objects, and (later) any SDK class. Everything inside it speaks our own records.

Two clarifications, because the earlier wording of this rule was both inverted and overbroad (Appendix B):

- **Protocol *facts* live inside; protocol *types* stay out.** `resultType`, `ttlMs`, and `cacheScope` are plain fields on our result record, because *which* op is cacheable and *what* its freshness is are semantics we own — a transport must not decide caching policy. What the rule bans is depending on someone else's classes to express them.
- **Method-name strings are the dispatcher's key, not a leak.** `"tools/call"` is how the table is addressed; forbidding it would leave the dispatcher unable to dispatch.

### 5.1 The invocation contract — one entry point, per call

**Every `tools/call` routes through `Cli.run(args, out, err)`.** This is a contract, not an implementation note, because it is what makes §9's no-state rule mechanically true: `Cli.run` builds a fresh command tree per invocation, so picocli's `@Option` instance fields cannot carry `-m`, `-w`, or `--dry-run` from one call into the next. A dispatcher that instead retained a `CommandLine` would inherit the previous call's options silently — a bug unreachable in a one-shot process and a coin flip in a resident one.

Three consequences of stdio framing (§2) shape the rest:

1. **The server serialises its own compact frames; it never relays CLI stdout.** dmtool prints pretty-printed multi-line JSON and stdio MCP forbids embedded newlines in a message. `McpInvoker` captures the run's output and parses it to a tree; the binding is what serialises the frame, compactly — which is the same split the seam rule draws, since only the binding knows what a frame is.
2. **`--text` must not be passed through, for the 68 envelope verbs.** `Cli.run` diverts stdout into an internal buffer when `--text` is present and then applies `TextRenderer`. **`TextRenderer` is gated**: it returns the payload unchanged when that payload is not an envelope, so `--text operators` and `--text manifest` emit their JSON intact. So passing `--text` would lose the JSON only on an envelope route — but that is exactly the route MCP needs both forms from, and re-invoking a mutating verb to obtain the other form would apply the edit twice. Rule: run without `--text`, parse once, and apply the renderer in process. For the **13 verbs with a non-envelope route** there is no envelope to parse and no rendering to produce, and `content` carries the raw payload as text.
3. **The verb must not write to the real stdout.** dmtool's entry point takes passed sinks rather than static streams; the dispatcher supplies its own. A strict process-boundary sweep of all 81 leaves found 79 JSON-promising routes emitting exactly one JSON value with EOF immediately after ([CLI-CONFORMANCE-GAPS.md](CLI-CONFORMANCE-GAPS.md) §2), with `schema` bare and `export card` deliberately raw. A wire protocol nonetheless sets a stricter bar than a CLI, which is why the dispatcher supplies its own sinks rather than relying on ambient purity.

**Serialised dispatch is an S1 decision, not a global one.** stdio delivers requests on one channel, so overlapping `tools/call` invocations are possible unless dispatch is serialised. Phase 1 serialises — one request at a time — because it is the only posture whose thread-safety is currently established, and because no dmtool verb is long enough to make queueing costly *for a single caller*. That justification does not survive multiple callers: §13.5 records why, and any multi-caller shape must revisit both the queueing policy and the thread-safety claim (R9). `notifications/cancelled` must at minimum be accepted and produce no further messages for the cancelled request; in-flight interruption is out of scope for phase 1.

### 5.2 On the official Java SDK, and the dependency budget

**Re-verified 2026-08-04** (§5.2 carried its own re-check trigger, and the §5.3 decision fires it): the Java SDK's latest release is still **v2.0.0**, still tracking revision **`2025-11-25`**, with **no release and no pre-release supporting `2026-07-28`**. Java is a **Tier 2** SDK; the `2026-07-28` beta announcement covers the four Tier 1 SDKs — Python, TypeScript, Go, C# — and does not mention Java or a Tier 2 timeline.

**So for this target the SDK is not a trade-off, it is unavailable.** The earlier dependency argument still holds and is worth keeping, because it decides the *hosted* case too: `mcp-core`'s compile dependencies are `slf4j-api`, `jackson-annotations`, **`reactor-core`**, and `jakarta.servlet-api` (provided), and Reactor is not optional — a substantial new reflective surface in a binary whose native-image metadata is test-suite-captured, purchased to replace newline-delimited JSON framing. But it is now the second reason, not the first.

**Owner decision, 2026-08-04: zero new dependencies.** Phase 1 adds **no** declared dependency — not the MCP Java SDK, not a JSON-RPC library, not a JSON-Schema validator. Stated exactly, since "zero dependencies" would be false: the layer is **Jackson tree plus the JDK**, both already in the binary (`:cli` declares `jackson-databind` and `picocli` directly, with `slf4j-nop` at runtime). What makes that sufficient is the shape §3 already derived — newline-delimited JSON on stdio, one JSON-RPC dispatch table, no HTTP, no OAuth, no SSE, no reactive streams — and the fact that `inputSchema`/`outputSchema` are *published documents* we already own, not runtime validators: incoming arguments are still validated by picocli and the verbs themselves.

**Consequence for phase 3.** The 2026-08-02 row keeping the SDK as a possible later substitution for a hosted transport is now conditional on an SDK that implements this revision existing at all. **Re-open trigger:** a Java SDK release supporting `2026-07-28`. Until then the hosted transport would also have to be hand-rolled, or built on a dependency chosen on its own merits rather than on MCP-SDK grounds — which is a phase-3 decision, not a phase-1 one.

### 5.3 Refusing a client below `2026-07-28`

**Owner decision, 2026-08-04: target `2026-07-28` only, and refuse anything below it.** No `2025-11-25` compatibility path is built, and no request from an older client is partly served. The refusal must be *corrective* — it names the revision this server implements, what it observed instead, and that `server/discover` is where the answer is published — because a bare `Method not found` reads as a broken server rather than a version mismatch, and a silently partial service is the failure mode this project rejects outright.

**This is the specification's own mechanism, not a house rule.** §2 records that the revision moved the protocol version into required per-request `_meta` (`io.modelcontextprotocol/protocolVersion`), that a mismatch returns `UnsupportedProtocolVersionError`, that `-32022` is the allocated code, and that `server/discover` advertises `supportedVersions` and doubles as the stdio backward-compatibility probe. So the refusal is conformance, and the discovery answer is its positive half: a client can learn the supported set *before* being refused.

| what arrives | what the server does |
|---|---|
| `_meta.io.modelcontextprotocol/protocolVersion` present and **below** `2026-07-28` | `-32022` `UnsupportedProtocolVersion`, naming the supported set |
| that required field **absent** | the same refusal, not a generic invalid-params — its absence is the signature of a pre-`2026-07-28` client, since the field is required in this revision |
| `initialize` (or `notifications/initialized`) | a corrective refusal naming the revision. **Which code is a design choice, not a spec finding:** `-32601` is the literal JSON-RPC answer for a method this revision removed, while `-32022` states the actual cause. The recommendation is **`-32022` with the removed-method name in the message**, since the cause is what the client can act on — but the spec was not re-read on this point, so it stays a recommendation |
| `server/discover` | answered normally, advertising `supportedVersions: ["2026-07-28"]` — the one call an older client may legitimately make |

**Obligation on landing:** the refusal path gets a test that proves it *fires*, per the standing rule that a refusal is not shippable until its refusal is tested — one case per row above, asserting the code and that the message names the supported revision.

### 5.4 Preparing for an SDK that does not exist yet

**Owner decision, 2026-08-04: build for the substitution, but add nothing for it beyond one boundary.** The requirement is a single location to change; the trap is paying for flexibility now — a second implementation, a factory, a revision-parameterised abstraction, or types that mirror MCP's so an SDK can slot in. None of that is proposed. The whole preparation is three things the design wants anyway:

**1. The handler table is data, and the binding consumes it.** `McpDispatcher` exposes method name → handler over our records. Today `McpStdioBinding` walks that table and dispatches itself; with an SDK, an `McpSdkBinding` walks the *same* table and registers each entry with the SDK. Without this the substitution would touch both the transport *and* every method registration; with it, one class is added and one deleted. Cost today: one accessor.

**2. Handlers are synchronous pure functions.** `request → result`, no callbacks, no streams. This is the direction that adapts: a synchronous function wraps into whatever an SDK wants (including a Reactor `Mono`, which §5.2 measures as likely), while an async handler cannot be un-wrapped as cheaply. Phase 1 serialises dispatch anyway (§5.1), so this costs nothing.

**3. Protocol tests assert on the wire, not on internals.** Given these bytes in, these bytes out. Such a test does not know which binding produced the answer, so it survives the substitution intact and becomes the SDK's acceptance suite rather than something to rewrite. The §5.3 refusal test is the first instance and the reason the rule is worth stating now.

**What is deliberately NOT abstracted:** the tool derivation, the annotations, `Cli.run` invocation, and the §5.3 refusal *semantics*. An SDK replaces framing, envelope handling, and method registration — it does not know our registry, and pre-abstracting against a library nobody has read is how a seam ends up fitting the imagined SDK rather than the real one.

**No import lint today, because there is nothing to forbid.** The interpreter's `verifyInterpreterPurity` is the precedent for enforcing exactly this kind of boundary, and an equivalent belongs here — but with zero SDK on the classpath it would assert against an empty set. It is bound to the re-open trigger above: the SDK arriving is what makes the lint meaningful, and writing it is part of that work, not of phase 1.

**Conformance testing, stated accurately.** The official **MCP Inspector** CLI connects to a server, invokes the single method named by `--method`, prints the result, and exits. It is a per-method smoke tool with usable exit codes and documented CI recipes — worth wiring, and it will catch a broken frame or a missing method. It is **not** a conformance suite: it does not enumerate the revision's MUSTs, nor validate `_meta` requiredness, cacheable-result obligations, header rules, or error-code allocation. So: *smoke-tested per method in CI, plus hand-written assertions for the MUSTs we implement* — not "provably conformant."

## 6. The tool surface — the partition of the two facts MCP can carry

**Owner decision, 2026-08-04 (§15 Q2): partition on `Effect` *and* `ReturnKind` jointly — 30 tools.** MCP lets a tool carry exactly two facts about what it does: `readOnlyHint`, which is true only if every op is read-only, and `outputSchema`, which may be declared only if every op always returns a conforming structured result. Both are **per-`Tool`**, so any tool spanning ops that disagree must drop the fact. That makes the correct grouping not a matter of taste: **it is the partition of the ops by those two properties**, inside each target. A coarser grouping must lie about one fact; a finer one adds definitions without adding truth.

**Measured against the live tree.** 34 of 81 verbs are `READ_ONLY` in every profile; 68 are `RESULT_ENVELOPE` in every profile. Grouping by **target alone** yields 17 tools with only **three** wholly read-only (`dm.workspace`, `dm.where-used`, `dm.catalog`) — 25 read-only verbs lose a published fact, and one grant of `dm.rule` would span `rule read` and `rule remove`. Splitting by **effect only** yields 26 tools that recover every read-only hint but strip `outputSchema` from 14 otherwise-eligible verbs. The joint partition yields **30**, and loses nothing on either axis.

| | target only | effect only | **joint (decided)** |
|---|---|---|---|
| tools | 17 | 26 | **30** |
| read-only tools | 3 | 12 | **14** |
| verbs under a read-only tool | 9 | 34 | **34** |
| `outputSchema`-eligible tools | — | 20 | **24** |
| verbs keeping `outputSchema` | — | 54 | **68** |

68 is the ceiling, not a compromise: the other 13 verbs cannot be rescued by *any* grouping. **9 mix return kinds within themselves** (`model new`, `model expand`, `model import-jsonschema`, `model export-jsonschema`, `profile synthesize`, `export`, `schema`, `workspace list`, `workspace graph`), and one op cannot be split across two tools; the remaining 4 are the catalog verbs, which never return an envelope at all.

**The naming rule is derived, not chosen.** Each tool is one cell of *(target × effect-class × return-class)*, and the suffix names the cell:

| cell | suffix | meaning to a caller |
|---|---|---|
| read-only, envelope | `.read` | inspects; returns the standard envelope |
| writes, envelope | `.edit` | changes the model; returns the envelope |
| read-only, raw | `.emit` | hands back a document or rendering; changes nothing |
| writes, raw | `.generate` | produces a document, and with an output option persists it |

**A target carries suffixes only where it has more than one cell**; a single-cell group stays bare. That is why `dm.catalog` and the root-verb tools are unsuffixed, and it is the same rule that left `dm.workspace` bare under the 26-tool shape — where it had one cell and now has two.

| tool | ops | `readOnlyHint` | `outputSchema` |
|---|---|---|---|
| `dm.model.read` | check, compute, describe, diff, eval, info, read, report, usage | **true** | ✓ |
| `dm.model.emit` | seed | **true** | — |
| `dm.model.edit` | normalize, rename | false | ✓ |
| `dm.model.generate` | new, expand, import-jsonschema, export-jsonschema | false | — |
| `dm.rule.read` | check, read, explain, deps, format, eval | **true** | ✓ |
| `dm.rule.edit` | add, modify, remove, rename | false | ✓ |
| `dm.computation.read` | read, explain, format | **true** | ✓ |
| `dm.computation.edit` | add, modify, remove | false | ✓ |
| `dm.field.read` | read | **true** | ✓ |
| `dm.field.edit` | add, modify, remove, rename, move | false | ✓ |
| `dm.group.read` | read | **true** | ✓ |
| `dm.group.edit` | add, multiselect, attachment, modify, remove, rename, move, extract | false | ✓ |
| `dm.typedef.read` | read | **true** | ✓ |
| `dm.typedef.edit` | add, modify, remove, rename, extract, inline, import, unimport | false | ✓ |
| `dm.include.read` | read | **true** | ✓ |
| `dm.include.edit` | add, set-reference, rename, remove, inline | false | ✓ |
| `dm.config.read` | read | **true** | ✓ |
| `dm.config.edit` | modify | false | ✓ |
| `dm.profile.read` | compare | **true** | ✓ |
| `dm.profile.edit` | extract | false | ✓ |
| `dm.profile.generate` | synthesize | false | — |
| `dm.workspace.read` | check, roles | **true** | ✓ |
| `dm.workspace.emit` | list, graph | **true** | — |
| `dm.where-used` | *(one root verb)* | **true** | ✓ |
| `dm.catalog` | operators, diagnostics, schema, manifest | **true** | — |
| `dm.apply` | *(one root verb)* | false | ✓ |
| `dm.batch` | *(one root verb)* | false | ✓ |
| `dm.export` | *(one root verb)* | false | — |
| `dm.meta` | *(one root verb, argument-selected routes)* | false | ✓ |
| `dm.patterns` | *(one root verb, argument-selected routes)* | false | ✓ |

**30 tools, 14 truthfully read-only, 24 declaring `outputSchema`.** The op union equals exactly the 81 live leaves, with no duplicate and no omission — verified against the live `manifest`, not asserted from this table.

**Non-goal, stated because the temptation is real: never change a verb to tidy the projection.** The four ineligible tools are ineligible because of what their verbs actually return, and the fix that suggests itself — give a verb a different `ReturnKind`, or move an op between targets so a tool becomes eligible — is a **breaking CLI change made to flatter a derived view**. The projection adapts to the command tree; the command tree never adapts to the projection. Choosing among the three groupings above costs **no `:cli` source change at all** — each is one grouping function in `McpToolRegistry`, which is why the whole 30-tool alternative was derived from published `manifest` output before any code existed.

**What is invented, named plainly.** `ManifestCommand` emits `target` + `op` only for a nested verb; **root-level verbs carry `op` alone, with no target**. So `dm.catalog` grouping four target-less root verbs is a new grouping, and the `.read`/`.edit` suffixes are new name components. Both are deliberate deviations in a repository whose first interop rule is never to invent a shape A12 already defines — mitigated because the *partition* is derived from published `Effect`, but the *names* are ours.

`dm.where-used` keeps the live verb's hyphen — hyphens are legal in MCP tool names, so renaming it would invent vocabulary for nothing.

**Naming.** The `dm.` prefix is a choice, not a requirement. The specification notes that `serverInfo.name` is unreliable for disambiguation and assigns the remedy to **clients and proxies**; it does not ask servers to self-prefix. Under a Claude Code plugin the host scopes names anyway, as `mcp__plugin_<plugin-name>_<server-name>__<tool>` (*host docs*). The prefix is retained only for direct-connect hosts that do neither.

**Treat every tool name as a public contract.** List results are cacheable, guidance tells hosts to memoise definitions, and a rename invalidates prompt caches and any user allowlist. Choose names once.

### 6.1 Why grouped at all, and not one tool per verb

**The primary argument is that the grouping already exists for nested verbs.** Ten of the twelve targets are real op-record targets, so `{op, args}` under a target tool is a faithful rendering for 71 of 81 leaves. The effect × return-kind partition subdivides that existing grouping rather than replacing it — every tool stays inside one target.

**A secondary argument, with its limits stated.** Client guidance recommends **progressive discovery** once tool definitions occupy a large share of the context window, at which point the host stops injecting definitions and supplies a `search_tools` meta-tool. Fewer, denser tools are less likely to trip that threshold. That argument is **weak**: the threshold is a non-normative example range ("for example, 1%-5%"), and this proposal has not measured its own token cost (R8). A lexical probe over the manifest showed only that *substring* retrieval fails on intent phrasing (`required` matched 3 verb descriptions, `mandatory` and "make a field required" matched none) — which says nothing about the embedding, subagent, or provider-native strategies the same guidance names.

**Consequence for authoring:** put each tool's op list in its **description**, not only in its `inputSchema`, so the discriminating words sit in the ranked text if retrieval does engage.

**Division of labour:** dmtool publishes good names and descriptions; semantic intent-to-verb retrieval belongs to the host. **This proposal considered and rejected a `dmtool docs` verb**, for two reasons: no consumer failure has been measured, and the lexical probe above shows substring search would not have closed the gap that motivated it. Recorded here as this proposal's own assessment; no ledger owns it, and none should until a consumer needs it.

### 6.2 Input schemas

`inputSchema` is a `type: "object"` root — required by the `Tool` signature — with `{op?: <enum>, args: <object>, model?: string, workspace?: string}` for stdio; `op` is absent for single-route root-verb tools, and §13.4 replaces the addressing keys for a hosted transport. The revision permits any JSON Schema 2020-12 keywords, so a `oneOf` discriminated on `op` is legal alongside that root type — but **`args` stays loosely typed on purpose**. Per-op argument validation is what dmtool's corrective diagnostics already do well, and a large discriminated union degrades model tool-calling accuracy for no correctness gain. The per-op contract stays discoverable through `dm:///schema/{target}/{op}`.

Each `op` enum value carries a **derived** description — its `Effect`, `FailurePersistence`, and `DryRun` from the registry — rather than hand-written prose. That is the route-level fact `annotations` cannot express (§7), and deriving it means it cannot drift.

The root verbs stay separate tools because their contracts differ where it matters: `apply` is `Effect.TRANSACTION` with `FailurePersistence.ROLLBACK`, `batch` is `Effect.DISPATCH` with child-defined outcomes, `export` is `MULTI_FILE_WRITE`, and `meta`/`patterns` select their route from write flags and parsed values.

## 7. Annotations — derived, and precise where the protocol allows

`ToolAnnotations` defaults are `readOnlyHint` **false**, `destructiveHint` **true**, `idempotentHint` false, `openWorldHint` true. So:

- **`readOnlyHint: true` exactly where every profile of every op in the tool is `Effect.READ_ONLY`** — the 14 tools marked in §6, covering all 34 always-read-only verbs, derived rather than asserted, and guarded by the conformance test in §12.
- **`readOnlyHint: false` elsewhere**, with `destructiveHint` at its default.
- **`openWorldHint: false` on every tool.** The basis is that no dmtool verb reaches an *open world of external entities* — there is no network egress, no third-party service, no unbounded resource space. It is **not** that nothing external is touched: `model eval`, `model compute`, and `rule eval` spawn the system `node` for the custom-type bridge (`CustomJsWorker`), and `model diff --since` spawns `git cat-file --batch` (`GitBaseline`). Both are local subprocesses under the caller's own account, which is why the hint still reads `false`, but the earlier "touches local files and nothing external" wording was wrong.
- **`idempotentHint` omitted** — not crisply statable per verb, and its default costs nothing.
- **Route-level effect lives in the derived `op` enum descriptions** (§6.2), because MCP has no per-route annotation.

**The residual imprecision, stated exactly.** 46 verbs have profiles that disagree on effect, so a mutating tool's `readOnlyHint: false` is correct but pessimistic for its read-only routes — and MCP cannot say so. Two caveats on the usual gloss that "the same call with `--dry-run` is read-only": **seven of those verbs have no `--dry-run` at all** (§4), and two (`patterns`, `meta`) select their effect from a value or a flag-presence set rather than from dry-run or an output flag. The derived op descriptions carry each route's actual `DryRun` value, which is why they are derived rather than written.

**On the untrusted-server caveat.** The specification says clients "should never make tool use decisions based on `ToolAnnotations` received from **untrusted** servers." A plugin-installed server is trusted, so that caveat does not license imprecision here.

**Categorical approval is safe on the read tools.** Client guidance describes approval keyed on tool *name* — *"allow `ticketing_createIssue` for this script run"* — in the Programmatic Tool Calling security section. Under §6's partition, granting `dm.rule.read` cannot reach `rule remove`. This is the main safety argument for that shape, and it is why the effect axis is the one that must never be collapsed.

**The per-verb side-effect axis is no longer a deferral.** It was demoted in [CLI-CONFORMANCE-GAPS.md](CLI-CONFORMANCE-GAPS.md) §2 on 2026-07-21 after a probe found side-effect inferable from `--help` for 5 of 6 sampled verbs; the axis then landed as `CommandContracts.Effect`, published on every route, and **the ledger row was deleted in `7427c417`** when the operational contract graduated. Nothing remains open there.

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

## 9. Content in, content out — the server knows no client path and stores nothing

**Owner decision, 2026-08-04. Two rules, both hard, both applying to every transport:**

1. **The server never receives, resolves, or returns a client-side path.** Every input the caller controls crosses the wire as **content**. Every path the CLI needs is **server-generated**.
2. **The server stores nothing locally.** No model, no resolved model, no workspace index, no cache — and **nothing a call creates survives that call**.

**This is a simplification, not a cost — it deletes a hazard class rather than defending against one.** The three addressing hazards this section used to carry existed *only* because the caller names locations: `$DMTOOL_WORKSPACE` freezing into ambient state the caller can neither see nor change; relative paths resolving against a server CWD the caller does not know; and traversal from caller-supplied paths, which the specification requires servers to sanitise and which — as the earlier text conceded — reached far past `model`/`workspace` into at least 12 `FILE`-tagged parameters plus several positional operands inside `args`. Under rule 1 none of them is reachable, so none needs a guard, a corrective diagnostic, or an echo of resolved roots.

**The mechanism, and why one is needed.** `Cli.run` is path-based: measured on the live tree, **seven verbs accept their content only as a file** (`rule add`, `computation add`, `computation modify`, `field modify`, `profile compare`, `apply`, `batch`) with no inline alternative and no stdin route anywhere in `:cli` — nor could there be, since stdio's stdin is the JSON-RPC channel — and `-m` is a path. So the MCP layer materialises: per request, a fresh temporary tree; inbound content written under **server-generated** names; the argument vector built against those names; `Cli.run` invoked; outbound artifacts read back into the response; the tree deleted. This is [§13.4](#134-the-hosted-design--requests-carry-their-content)'s route A, so **stdio and hosted now share one content mechanism instead of each having its own.**

**The residual, stated plainly.** Content does *transiently* touch the server's filesystem during a call. Rule 2 is therefore a **lifetime** guarantee — nothing outlives the request — rather than an absence of writes, and that distinction is recorded here rather than left for a reader to discover. The exit, if transient disk becomes unacceptable, is a content-first invocation path in `:cli`; it is larger than phase 1 and deliberately excluded. An in-memory `FileSystem` provider is **not** an option: the JDK ships none, and adding one violates the zero-new-dependency decision (§5.2).

| direction | what crosses the wire |
|---|---|
| the subject model | its DM-JSON content |
| models it references | the content of each, as §13.4's caller-declared resource tree |
| a spec or document-instance input | the JSON, inline |
| an edited model | the new DM-JSON content in the response — **the caller owns persistence** |
| a multi-file result | each artifact inline, keyed by a server-assigned relative name |

**Consequences that are now decisions rather than preferences:**

- **`-m`, `-w`, `-o`, `--out-dir`, `--observations`, and every content path are not caller-settable over MCP.** `$DMTOOL_WORKSPACE` plays no part. The `--output`/`--out-dir` distinction remains a CLI fact and stops being an MCP one.
- **Directory-scope inputs are excluded from phase 1** — `workspace check|list|graph|roles <dir>` and the custom-JS directories. Inline content for a *tree* is a different shape from inline content for a document, and §13.4's exclusions already name it.
- **Nothing a call creates may outlive it**, which is stricter than this section's former rule and reaches a real defect the cold review surfaced: `CustomJsWorker` registers an **unremoved JVM shutdown hook per call**, and its `node` child lives until process exit. In a one-shot CLI that is correct and costs nothing; under rule 2 it is a violation. The old wording forbade only a cached *model*, which is exactly why it did not reach this. A residency audit — spawned processes, shutdown hooks, temp trees, `deleteOnExit` — is a phase-1 obligation (§12).
- **Process-lifetime *service* singletons remain permitted**: `Backends`, `ReadBackends`, `OperatorCatalog.INSTANCE` hold no caller content. The line is caller content and per-call resources, not all state.
- **R3 changes shape.** There is no directory to index, so the eager id-keyed index `ReferenceResolvers.directory(roots)` builds — `Files.readString` plus `mapper.readTree` over every candidate, with no header-only parse — no longer runs over a tree the caller points at. Per-call cost becomes proportional to the content the caller *sends*, which is the quantity §13.4 was already reasoning about.

**Content-carrying puts this layer on an A12 DM-JSON boundary, which is the one place a default JSON mapper is a defect.** `A12JsonConstructionGuardTest` exists for exactly this distinction — *"a default JSON parser is correct for a dmtool-owned envelope, profile, or catalog and wrong at an A12 DM-JSON / Document-JSON boundary"* — and it enumerates every mapper construction in production source, failing closed on a new one. Before §9 the MCP layer only ever touched dmtool's own envelope, where a default mapper is right. Now it carries **model content** in both directions, so the number-fidelity contract applies in full: parse configuration, read stringification, and write emission are three separately load-bearing stages, and a mapper configured for `BigDecimal` still loses a scale if the write stage re-normalises.

**Recommendation: never parse model content at all — carry it as opaque text in both directions.** Inbound, the bytes go to the temp tree verbatim and the CLI does the parsing it already does correctly, with its own diagnostics. Outbound, the edited model is read back as bytes and carried as text. That makes fidelity **unlosable by construction** rather than dependent on configuring a mapper correctly, and it leaves the MCP layer with one mapper whose role is the dmtool-owned JSON-RPC frame — a single, easily-justified row in that guard's allowlist.

Two notes, because this sits next to a rule that reads the other way. Carrying the document as text is **not** the forbidden "play safe by changing the encoding": that rule forbids re-encoding a *value inside* a document (quoting `250.00` to protect its zeros), which breaks read→write identity on genuine A12 documents; here the document's own bytes are untouched and only the transport container differs. And if a consumer ever needs the model as a parsed tree in `structuredContent`, that is legitimate but not free — it requires the A12 parse and write contract end to end, plus its own reviewed role row.

**Audience consequence.** The caller must be able to obtain and persist model content itself, which means a **host with file access** — Claude Code, an IDE, a desktop client with a filesystem connector — not a host with shell access to run `dmtool`. §10's "bare-MCP" framing was derived from the wrong constraint and is corrected there.

## 10. Packaging and the skill

**Phase 1 does not ship an MCP server registration in the plugin.** `dmtool mcp` is documented as a **user-registered** server (a project `.mcp.json`, or the host's own add flow) once the binary is present. This is what makes R1 not a phase-1 blocker: a plugin MCP server's `command` must resolve **when the plugin is enabled**, before any skill has run, which is precisely what [RELEASE-SPEC.md](RELEASE-SPEC.md) §6's on-demand skill-triggered delivery was designed to avoid needing. Deferring plugin auto-wiring to phase 1.5, gated on R1/R2, costs nothing that phase 1 needs and touches no settled release decision.

**Which skill serves the MCP surface — and the answer is "none, deliberately".** `skill/SKILL.md` is invocation-shaped: **167 lines carrying 20 `dmtool …` invocations**, every one of which `CommandReferencesResolveTest` requires to resolve against the live command tree. Its tool section is `dmtool --help`, `dmtool operators`, `dmtool -m <model> rule check --field … --condition … --code …`. **An agent holding `dm.rule.read` and no shell can execute none of it.** So a claim that "the plugin's own skill already delivers it" would hold only for a host that also has the CLI — and under a plugin both are present, so an agent reading that skill will shell out and ignore the MCP tools.

**The audience, corrected.** An earlier revision concluded from this that the audience is a *"bare-MCP host — one with MCP but no skill or plugin support."* That named the wrong constraint. Skill support is irrelevant; **two capabilities are, and they are independent**: whether the caller can *execute the binary*, and whether it can *do file I/O*. §9's content-carrying rules require the second — the caller supplies model content and owns persistence — while the positioning at the top of this document says MCP is worth using only when the first is absent. So the audience is a host that **can read and write files but cannot run a subprocess**, which is a different and larger population than "has no skill".

**No new skill and no marketplace entry are needed** — that conclusion survives, and for a cleaner reason than the original: a skill teaches judgment, and judgment is worthless to a caller that cannot get its spec to the verb, which was the real blocker (§9). Once content-carrying fixes that, the question becomes only *where the judgment lives*, and most of it is already somewhere MCP reaches.

**How much judgment a caller actually loses, measured rather than assumed.** The skill's content splits three ways:

| lane | reachable over MCP? |
|---|---|
| **invocation syntax** — the 20 `dmtool …` command forms | irrelevant: replaced by the tool schemas |
| **facts the other three surfaces already own** — operator `gotchas`/`semantics`, schema field descriptions, the `fix`/`explain` riding each diagnostic | **yes** — via `dm.catalog`, the published schemas, and the envelope's own diagnostics |
| **cross-cutting judgment with no other home** | **no** — this is the whole residue |

The residue is short, and **part of it is misplaced rather than irreducible.** Two spot-checks against the live tool: `operators GroupFilled` carries three gotchas about *filled* semantics and error-field referencing but **not** the skill's "guard row existence with `GroupFilled`, not `FieldFilled`" — an operator-relation fact that CLAUDE.md's placement rule says belongs in the catalog; and `export --help` does not carry the skill's "`export` is not an inspection tool" warning, which is a verb-description fact. `config modify --add-locale`'s help *does* already carry part of the bilingual cascade (rules and computations), though not the enum-label or pattern-message halves. **Relocating those per the project's own placement rule would make them MCP-reachable for free and shrink the measured skill** — a win independent of MCP. What stays irreducible is the judgment no registry can derive: **polarity** (the kernel accepts both directions, so no diagnostic can catch an inverted rule), the *is this even a rule?* gate, and the "each X" per-row-versus-aggregate recasting warning.

**Correction, because the original claim was checkable and wrong:** that revision said the judgment carried by tool descriptions is *"measurable against EVAL-SPEC §2a's bare-tool arm."* §2a's **bare-tool** mode means *the CLI without the skill* — it is not an MCP arm, and `benchmark.workflow.js` drives `${CLI}` command forms throughout. **No MCP eval arm exists**, and building one is new harness work that §12 must list rather than assume. The claim borrowed an existing arm's name to imply coverage that was never there.

**`dm:///skill` stays excluded**, and so does excerpting the skill into `instructions`. Note the tension that leaves: §6.2 derives every `op` description from the registry *"rather than hand-written prose"*, and judgment is precisely what no registry holds — so the irreducible residue above has no home on the MCP surface at all unless it is written fresh, which is the duplication that rejected two skills. Under the parked status this is recorded as unresolved rather than papered over; the honest options are relocating what can be relocated (above), accepting a short purpose-written `instructions` string as a bounded duplicate, or accepting that an MCP caller gets less judgment than a CLI one.

Two rejected alternatives, recorded so they are not re-proposed: an **invocation-neutral rewrite** of `skill/SKILL.md` would collide with `CommandReferencesResolveTest`, degrade the skill for CLI users, and move the N6 eval baseline; **two skills** (`a12-dmtool` plus an MCP variant) is duplicated judgment content on the one file the project says only an expensive probe run can validate. Note also that **MCP has no skill concept in any revision** — Agent Skills is a separate portable standard, and skills compose with MCP only at the packaging layer.

## 11. Risks and constraints

**R7 — no evidence the intended host implements `2026-07-28`.** §5.2 establishes that the Java SDK tracks `2025-11-25` and that Tier-1 SDKs have betas; neither says what a *host* speaks, and Claude Code's MCP documentation states no protocol revision. **The owner decision of 2026-08-04 does not remove this risk; it fixes what happens when it fires** (§5.3): an older client is refused with `-32022` and a message naming the supported revision, so the outcome is a legible diagnosis rather than a mystery failure — but phase 1 delivers nothing usable on that host, and no fallback exists by design. The residual risk is therefore *unchanged in severity and changed in character*: it no longer gates the build decision, and the host check becomes an acceptance test rather than a prerequisite. Still cheap: one connection against the real host once a skeleton exists.

**R1 — binary presence at plugin-enable time.** Reduced to a phase-1.5 item by §10. It returns in full if auto-wiring is wanted: the workable route is a wrapper as the `command` (resolve-or-download, then `exec "$BIN" mcp`), which moves a network download inside the MCP startup window — **and inverts a failure policy**, since `ensure-dmtool.sh` deliberately warns and exits 0 on any download problem, whereas a `command` that exits 0 without speaking JSON-RPC is a broken server.

**R2 — MCPB is unresearched.** The MCP documentation names **MCP Bundles** as the deployment path for a local server that must touch the user's machine, packaging server and runtime as one `.mcpb` archive. dmtool has no runtime to provision, so the headline benefit is partly moot — but it may resolve R1 by shipping the executable. Note it would create a **second** binary delivery channel beside the version-keyed cache whose invariant `EnsureDmtoolCacheTest` locks.

**R3 — the `dmtool serve` won't-build.** [FEATURE-GAPS.md](FEATURE-GAPS.md) §4, decided 2026-06-15, with an explicit *"don't re-litigate without new evidence."* §3 classifies its five grounds and answers all of them. **Conditional on §9's no-warm-model rule.** Measure the per-call workspace index cost before any cache is discussed.

**R4 — native image.** The MCP command adds code and Jackson usage to the shipped binary, so the test-suite-captured reflection metadata stales. Re-capture before any native build, then run the native gate ([NATIVE-IMAGE-GRAALVM.md](NATIVE-IMAGE-GRAALVM.md) §5.1). The zero-new-dependency decision (§5.2) is what keeps this near zero: the added reflective surface is Jackson usage the binary already has, not a new library's. **Severity reduced 2026-08-05:** the capture now records what it saw, and the probe and ship gates check that provenance and fail closed — so the failure mode this risk described (edit source, skip the capture, rebuild a binary newer than every source file, and pass both mtime gates on metadata describing older code) is no longer available to forget.

**R5 — eval material boundary.** [EVAL-SPEC.md](EVAL-SPEC.md) §2a defines a **bare-tool** arm where agents get the self-describing CLI and the model file with **no skill**. §10's exclusion of `dm:///skill` keeps that arm intact, and §10's audience decision makes an MCP bare-tool arm the natural measurement of whether the derived tool descriptions suffice.

**R6 — host support for resources is unverified.** Support for `resources` has historically lagged `tools` in some clients, but nothing in the official documentation characterises that. If it holds for a target host, `dm.catalog` must carry everything and the resources become an optimisation.

**R8 — the tool definitions' token cost is unmeasured.** It decides nothing in this design; recorded so §6.1's secondary argument is not mistaken for a measured one.

**R9 — concurrent dispatch has no established thread-safety basis.** §5.1's serialisation is what makes phase 1 safe. Any multi-caller shape needs the claim established, and `CustomJsWorker` — a single shared Node worker with `synchronized` methods — is a known cross-request bottleneck.

**R10 — the closed-world denominator is unaudited.** Five leaves are known to require input-set completeness (§13.4). Whether any other verb makes a claim quantified over its input set has not been audited; establishing it means checking every verb for such a claim.

## 12. Scope

**The Required list is a sequence, not a set.** `CommandReferencesResolveTest` resolves every command reference in `cli/src/main`, the rulekit catalog, the root and plugin READMEs, `skill/SKILL.md`, the Codex `AGENTS.md`, and the eval workflow against the **live** command tree — `docs/` is deliberately not scanned, which is why this proposal may name `dmtool mcp` while no such verb exists. The verb must exist in the picocli tree **before** any command example naming it enters one of those seven surfaces.

**The positioning must ride the verb itself, not just this document.** If the `mcp` leaf ever lands, its `@Command` description is where a cold agent meets the surface, so that description — not an external doc — must say that the verb serves callers which cannot execute the binary and that direct verb invocation is preferable wherever it is available. The self-explaining rule already requires discoverability to live in the tool; here it also has to prevent a *wrong* choice, which is stronger than describing a capability. Until the verb exists there is no help text to carry it, which is why this is recorded as an obligation rather than done.

**Adding the `mcp` leaf carries the standing new-verb obligations**, which the CLAUDE.md schema-discipline table imposes and which this proposal does not exempt itself from: `ManifestVerbTest.EXPECTED_VERBS` moves from 81 to 82 under its set-equality assertion, the conformance matrix must pass (described · params-described · schema-resolves · demonstrated), a `verb-examples.json` demonstration is added or a reasoned `DEMONSTRATION_PENDING` row recorded, and the verb needs an operational profile in `CommandContracts` — it renders an artifact rather than an envelope, so it is a `SchemaCommand.CONTRACT_EXEMPT` candidate with a stated reason.

**What a phase 1 would owe.** This is a settled list, not a plan — the document is parked (§1). It already folds in the corrections a cold design review produced against the pre-parked revision; each is marked so a future reader can see which items came from the review rather than the original design.

- The `mcp` command in `:cli` (§5); the `McpDispatcher` seam with its handler table exposed as data and synchronous handlers (§5.4); the `Cli.run` invocation contract (§5.1); serialised dispatch.
- **Package placement decided, not left to a diagram** *(review)*. §5's rationale is that `RootCommand`, `ManifestCommand`, `TextRenderer`, and `CommandContracts` are package-private and reachable **without widening any visibility** — all four are declared `final class` with no modifier, verified. A `cli/…/mcp/` subpackage is a *different* package and reaches none of them, so the two options are the **root package** (recommended: it needs no widening, which is the whole argument) or a subpackage plus an explicitly enumerated, guarded widening. An earlier diagram asserted the subpackage while the prose asserted no widening; they cannot both hold.
- **Content materialisation per §9** — inbound content written to a per-request temp tree under **server-generated** names, the argument vector built against them, outbound artifacts read back inline, the tree deleted. This replaces the former "addressing hygiene" item outright: with no caller-supplied path, relative-path rejection, the resolved-roots echo, and `args` traversal sanitising all defend an unreachable surface.
- **Directional parameter metadata, derived** — ~~owed~~ **SHIPPED 2026-08-05, independently of this proposal.** Every parameter that names something on the filesystem now publishes a `contentRole` (`INBOUND_CONTENT` / `OUTBOUND_DESTINATION` / `SCOPE_DIRECTORY` / `SUBJECT_MODEL`) plus its `paramLabel`, derived in `ContentRole` from the label and flag rather than hand-listed, with the vocabulary on `manifest.contentRoles` and `ParamContentRoleTest` failing on any file-shaped parameter it cannot place. **82** parameters carry a role, against **12** direction-blind `kind:"FILE"` tags before. *(Corrected 2026-08-05: this row previously cited a source scan of "~68 file-shaped parameters with 7 tagged `FILE`"; both figures were wrong — the scan under-counted the tag and had a known recall limit.)* So a phase 1 would no longer owe this: the materialisation §9 needs is derivable from the published manifest today. The reasoning and its rejected alternatives live in [`CLI-CONFORMANCE-SPEC`](CLI-CONFORMANCE-SPEC.md); it was tracked as CG8 until it graduated.
- **A residency audit** *(review)* — spawned processes, JVM shutdown hooks, temp trees, `deleteOnExit`. §9's rule is that nothing a call creates outlives it, and `CustomJsWorker` currently violates it: an unremoved shutdown hook per call, with its `node` child living until process exit.
- **The framer owns file descriptor 1 for the server's lifetime** *(review)*. Supplying `Cli.run` its own sinks redirects dmtool's writes but does not guard FD 1, so any library `System.out.print` corrupts a frame — and on a resident stdio server that kills the session, not one call. dmtool's own sources are clean (`CliMain` aside) and no subprocess inherits stdout, so the residual risk is third-party code in the same JVM. A hostile fixture that provokes library output is the guard.
- `server/discover` with `supportedVersions` (exactly `["2026-07-28"]`), `capabilities`, `serverInfo`, `instructions`, `ttlMs`, `cacheScope`.
- **The §5.3 revision refusal**, with the test that proves each row of its table fires — **scoped to requests** *(review)*: JSON-RPC forbids responding to a notification, so the "required field absent → refuse" row cannot apply to one.
- **A notification-shaped entry point** *(review)*, because §5.4's `request → result` handler cannot express a notification. Either that, or `notifications/cancelled` moves to Excluded — under serialised dispatch the reader is blocked inside `Cli.run`, so a cancellation is read only after the result is written and acceptance can suppress nothing. There is no observable behaviour to assert, so the honest choice is to exclude it until a reader thread is in scope.
- `tools/list` — the 30 tools of §6, **generated from the live picocli tree plus `CommandContracts`** by partitioning each target on `Effect` × `ReturnKind`, with `ttlMs`/`cacheScope` and deterministic ordering.
- `tools/call` — envelope into `structuredContent` plus the in-process `--text` rendering into `content`; raw payload as `content` text otherwise. **Decided per observed route, not per verb** *(review)*: 7 of the 13 verbs with a non-envelope route *also* have an envelope route, so a per-verb rule would discard `structuredContent` exactly where it exists. The sniff already exists — `TextRenderer.renderIfEnvelope` gates on the payload being an object with a textual `outcome`.
- **An exit-code → result mapping** *(review)*. `Cli.run` answers a malformed invocation with **empty stdout and the whole corrective surface on stderr** — `specInputHint`, `semanticSubcommandHint`, `locatorPositionalHint` and the rest — which a design reading only stdout drops silently, on the likeliest outcome of a generated call. Exit 0/1 map to the envelope; exit 2 maps to `isError` carrying the stderr corrective.
- **`outputSchema` bundled, not referenced** *(review)*. `result.schema.json` `$ref`s `diagnostic.schema.json` by its `$id` — a non-fetchable URI — so an inlined schema has an unresolvable reference on exactly the results that carry diagnostics. It must be emitted `$defs`-inlined, with a guard. Note also that MCP makes conformance a **MUST** across every route, while the local guard is a sampled key-presence check; what discharges that MUST with no runtime validator (§5.2 forbids one) is unsettled.
- Annotations **derived** from `CommandContracts` exactly as §7 specifies.
- **A stated exclusion set for the tool union** *(review)*. §6 pins the union at exactly the 81 live leaves while the `mcp` leaf makes the tree 82, so the guard fails on day one unless the exclusion is data with a reason per entry, in the shape `SchemaCommand.CONTRACT_EXEMPT` already uses. Latent sibling: `Cli.run` removes `JVM_ONLY_VERBS` from the dispatchable tree under a native profile (empty today), so a registry that builds its own tree rather than reading the live one would advertise verbs the same binary refuses.
- A conformance guard that the tool set, the op union against the live tree, and every derived `readOnlyHint` and `outputSchema` eligibility match the registry, plus hand-written assertions for the MUSTs implemented.
- **Each owed guard placed in a named lane.** A `:interpreter:check :adapter:test :cli:test` checkpoint is not the whole estate: root `test` and `:schemakit:test` are lanes too. For this work the manifest/tool-union, refusal, exit-code, and wire tests are `:cli:test`, but two obligations land outside it — **every mapper the layer constructs needs a reviewed role row in root-lane `A12JsonConstructionGuardTest`** (the §9 boundary above is exactly why), and new test sources invalidate root `docGuardTest`, which declares `**/src/test/**` among its inputs. Two mechanical facts worth carrying: CI reaches root, `:schemakit`, `:cli`, and `:adapter` through the **unqualified** `test` name, while `:interpreter` is Kotlin Multiplatform and has **no `test` task**, so nothing selects it that way; and the demo gate is decided by grepping `examples/` for the **right token** — a verb for a verb change, the **operator id** for a catalog edit, since `cli-tour` pins operator `meaning` and `gotchas` verbatim through `rule explain` and never names `operators` at all.
- **An MCP eval arm, if the judgment question is to be measured at all** — §10 records that no such arm exists and that EVAL-SPEC §2a's bare-tool mode measures something else entirely.

**Optional**

- Per-tool `title` annotations.
- `resources/list`, `resources/templates/list`, `resources/read` for the §8 URIs — **moved out of Required** *(review)*. R6 already concedes that if host support lags, `dm.catalog` must carry everything anyway, so the tool path is mandatory regardless and the resource path is a second implementation of the same content; §1 concedes the only delta is host-side caching, unmeasured. Gate it on R6 producing a host that reads resources.

**Excluded — deliberately**

- Multi Round-Trip Requests, `subscriptions/listen`, sampling, roots, logging, and `io.modelcontextprotocol/tasks`.
- **Any new dependency** (§5.2) — the MCP Java SDK does not implement this revision, and no JSON-RPC or schema-validation library is added. **This also excludes MCP Inspector in CI** *(review)*: §5.2 scopes it to catching a broken frame or a missing method, which the wire tests catch deterministically and in-process, so it buys nothing and costs a Node dependency in the phase whose headline decision is zero new dependencies.
- **Any `2025-11-25` compatibility path** — no `initialize` handling beyond the §5.3 refusal, and no negotiated downgrade.
- **Any caller-supplied path, of any kind** (§9) — including `-m`, `-w`, `-o`, `--out-dir`, and `--observations`.
- **Directory-scope inputs** (§9) — `workspace check|list|graph|roles`, the custom-JS directories. Inline content for a tree is a different shape from inline content for a document.
- Any warm model, resolved-model cache, or workspace-index cache (§9, R3).
- Any new **authoring** verb — the `mcp` leaf is the one addition, with the obligations above.
- `dm:///skill` and any excerpt of the skill into `instructions` (§10, where the residual tension is recorded).
- A plugin MCP-server registration (§10), gated on R1/R2.
- In-flight cancellation; concurrent dispatch (§5.1, R9).
- **Response streaming** *(review)* — not a capability the stdio shape has: a response is one newline-delimited message, and §2 records that even Streamable HTTP lost resumability. The nearest real thing is a progress notification, which needs the notification seam above.
- **MCP Apps**, whose trigger §1 records.
- **A local-convenience framing of any kind** — the positioning at the top of this document forbids recommending MCP where the binary can be run.

## 13. Hosting — shapes, requirements, and the design

Phase 1 depends on none of this. It is recorded because EU-sovereign hosted access is where the idea started, and re-deriving it later is the more expensive mistake. **Candidate assessments and their rationale are [Appendix A](#appendix-a--hosting-candidate-assessments)**; this section carries only what governs *any* candidate.

### 13.1 Three deployment shapes

| | shape | licensing delta | ops burden | who answers the sovereignty question |
|---|---|---|---|---|
| **S1** | `dmtool mcp` — stdio inside the existing binary (**phase 1**) | none; a local artifact under the posture [LICENSING.md](LICENSING.md) already sets | none | nobody has to — nothing leaves the machine |
| **S2** | Publish a **container image**; the customer runs it in their own tenancy | none; distribution, exactly as the binary is today | theirs | theirs, by construction |
| **S3** | **We host it** — either as a public demonstration endpoint or as a service | the hosted combined work must be offered under EUPL-1.2 with source (§13.2) | authentication only if state is held; otherwise availability and abuse handling | ours |

**S1 for phase 1; S2 as the answer to "can we have it hosted"; S3 as a separately justified decision.** S1 is most of the code for S3 and none of the risk, because statelessness means there is no session layer to build twice; S2 answers EU sovereignty better than S3, since a customer who cares about sovereignty wants the thing in *their* tenancy; and S3's real cost is the licensing posture in §13.2, which has no undo.

With §13.4's design, a demonstration endpoint and a customer-serving service differ only in what is promised about availability, not in what they hold — so they are one shape, not two.

**GitHub Actions is not a hosting option.** CI jobs are ephemeral, with no inbound endpoint and no stable URL. It is the right place to *build and deploy* the image and the wrong place to *run* the server.

### 13.2 The gate is licensing, not infrastructure

EUPL-1.2 already applies to the shipped bundle: [LICENSING.md](LICENSING.md) sets the rule as *"EUPL-1.2 for any artifact in which we bundle the kernel"*, so distributing the binary — and an S2 container image — changes nothing. What **hosting** adds is that Article 1 counts *"providing access to its essential functionalities"* as Distribution and/or Communication, so the copyleft and source-availability clauses attach to a service that hands out **no copies at all**, and the hosted combined work must be offered under EUPL-1.2 with a maintained source repository. **That consequence is indifferent to §13.4 and to whether the service is free** — it is the one hosting obligation content-carrying does not reduce.

What content-carrying *does* reduce is the data-protection posture. Holding customers' document models at rest makes the operator a **GDPR processor** with a DPA, a retention policy, and a deletion obligation. A service that holds nothing between requests is a processor only for the duration of a request: no retention policy, no deletion duty, no storage to breach.

Dropping to a kernel-free `STRUCTURAL_ONLY` backend to escape the EUPL clause is not a real option: it removes the consistency oracle, which is the most valuable thing dmtool does. *(Engineering analysis, not legal advice — the same caveat LICENSING.md carries.)*

### 13.3 Requirements for any hosted deployment

**H1–H6 recorded 2026-08-03; H7–H12 derived from the measurements that produced them.** The worked examples are in Appendix A.

| # | requirement | basis | |
|---|---|---|---|
| **H1** | **Never silently enter a paid tier.** The maximum possible charge must be bounded **by construction** — a platform-enforced ceiling, an absent collection channel, or a configuration whose worst-case consumption is provably inside a free allowance. Monitoring, alerting, and after-the-fact shutdown do **not** satisfy this | owner | **top priority** |
| **H2** | **The bound must hold under hostile traffic.** A flood must not be able to raise the bill, and the safe setting must not depend on platform defaults being sane | owner | **top priority** |
| **H3** | **Service unavailability is preferred over any charge.** On exhausting the allowance the service may stop; it may not bill | owner | required |
| **H4** | **Free at the expected load** — at most a few thousand calls per day, normally within a 4–8 hour window | owner | required |
| **H5** | **EU jurisdiction and GDPR compliance.** SecNumCloud qualification only if a customer's requirement is literally SecNumCloud | owner | required |
| **H6** | **EUPL-1.2 source availability accepted** for the hosted combined work (§13.2) | licence | gate |
| **H7** | **≥ 256 MB RAM**, and a pinned `-Xmx` below the host limit | measured: authoring and inspection are flat at **~43 MiB**, dominated by the mapped image rather than by model size; document evaluation reached **519 MiB** on a 714 KB / 100-row stress fixture. 256 MB carries ~6× headroom for the authoring workload. At `-Xmx200m` an over-budget evaluation returns `outcome: "error"` / `RK_INTERNAL_ERROR` and the server keeps serving, whereas an unpinned native image sizes its heap from *physical* host RAM and would be killed, taking the endpoint down for every caller | required |
| **H8** | **A long-lived process that binds a port.** Streamable HTTP needs a resident listener; per-instance-concurrency-1 function products are event handlers, not JSON-RPC endpoints | design | required |
| **H9** | **`linux/amd64` artifacts.** The maintainer's machine is ARM, so the release pipeline needs an amd64 build for any hosted path | environment | required |
| **H10** | **No platform retention of request content.** Backups or snapshots that persist request bodies are disqualifying for a service that must hold nothing. §13.4's implementation route must satisfy this from our side; the platform must not undo it | §13.2 | required |
| **H11** | **A contractual floor, for a customer-serving endpoint.** A plan permitting suspension *or deletion* at unbounded discretion cannot carry an availability or retention commitment. Acceptable for a demonstration endpoint, where H3 already trades availability away | Appendix A | conditional |
| **H12** | **A bounded, configurable request-payload cap**, with a corrective diagnostic on exceeding it — the analogue of the decoder's existing `document_code_units` limit, now applying to the carried content | §13.4 | required |

**The acceptance test a candidate must pass: can the maximum possible monthly charge be stated as a number derived only from settings we control?** If not, the candidate fails H1 whatever else it offers. Two candidate *classes* can satisfy H1 structurally — a platform that **suspends rather than bills** when a free tier is exhausted, and a **fixed-price plan whose ceiling is the price**. **Usage-metered serverless with an uncapped invoice is disqualified as a class, not as a vendor.**

**S2 satisfies every requirement here trivially**, by moving the exposure to whoever runs it — a further argument for §13.1's recommendation.

### 13.4 The hosted design — requests carry their content

**Owner decision, 2026-08-03: a hosted request carries the content the operation needs, and the service retains nothing between requests.** Isolation stops being a mechanism to build and audit and becomes a property of the transport: two callers cannot reach each other's models because the service holds none. A mutating op returns the mutated model in the response rather than persisting it, so there is no write to authorise and no atomicity question on the server.

**Owner decision, 2026-08-04: the carried set is caller-declared, not derived.** An earlier draft defined it as the subject's transitive resolution closure. **That definition is withdrawn.** It is wrong in kind, not merely incomplete:

- **Reference-introducing edits need post-edit dependencies.** `include add --reference <id>` mounts a previously unreferenced model, so the needed model is by construction outside the subject's pre-edit closure. Executed: run in a workspace holding only the subject, it returns `rejected` / `RK_UNRESOLVED_REFERENCE` naming the absent id. `typedef import` and `include set-reference` are the same class.
- **`model rename` needs *inward* siblings**, which no outward closure contains — see the safety finding below.
- **`model diff` has two subjects and two independent resolution roots** (`<baseFile>`, `<headFile>`, `--base-workspace`, `--head-workspace`, the last two documented as *"use when BASE and HEAD come from different workspaces"*), and can therefore legitimately carry **two models with the same `header.id`**.
- **Many inputs are not models at all** — document instances, rule/computation/field/typedef specs, profiles, JSON Schemas, `workspace roles` YAML, custom-JS directories, and `apply`/`batch` op frames.
- **`batch` recursively inherits every child's input needs.**

**The safety finding, which is the dispositive one.** `model rename` runs an explicit **inward** workspace scan: `crossModelRefusal` walks every `-w` root via `Workspace.scan(root, true)` and refuses on a new-id collision (`RK_NAME_EXISTS`) or on a sibling that includes or imports the old id (`RK_REFERENCED`). Its contract is *"Refuses — never a best-effort cascade"*, and it returns `null` — safe — when it finds nothing. **Given a request carrying only the subject and its closure, it would find nothing and permit the rename**, converting "not supplied" into "proved absent". That is a silent degradation of a refusal gate, which the refactoring-safety-gate rule forbids outright, and no missing-reference diagnostic can catch it because inward absence produces *success*, not an unresolved reference.

**This also corrects a guard an earlier draft proposed.** "Referrer analysis is outward-only" is true of `where-used` and the field/group reference-rewriting refactors — both analyse the subject's expansion, verified by execution — but **false as a general statement**, and `model rename` is the counterexample.

**So: a closed-world requirement is a third axis**, independent of `Effect` (does it write) and `ReturnKind` (what it emits). It asks whether a verb's *correctness* depends on its input set being complete. Five leaves need it on current evidence, for two different reasons: **`model rename`** (a refusal that must prove absence) and **`workspace list`/`graph`/`check`/`roles`** (results quantified over a set). **R10 records that this denominator is unaudited.** If phase 3 is built, the axis belongs in `CommandContracts` beside `Effect`, or the hosted contract is hand-maintained and will drift — and the same argument applies to *which parameters carry content*, which no published surface states today (§4).

**The contract that follows.** Direction only; the resource protocol is deliberately **not** specified here, because specifying one inside an unimplemented phase-3 section is speculative work.

- Content is a **request-local resource tree addressed by request-relative path**, with **models and non-model attachments in separate namespaces**. Paths refer only into the request; outputs come back in the same namespace and are never retained.
- **Subject cardinality is 0, 1, or 2** — `model new` has no subject, the ordinary case has one, `model diff` has two. `model new` and `model diff` are mirrors of the same exception rather than two special cases.
- **No id-keyed carried map.** `model diff`'s same-id base/head makes it unrepresentable, and id uniqueness is not an invariant anywhere: both `ReferenceResolvers.indexDocumentModels` and `modelkit`'s `Workspace` resolve a duplicate id with `putIfAbsent` — **first wins** — and `workspace list` over two files declaring the same id lists both without rejection.
- **An authority assertion is part of the request.** A caller may declare a supplied tree to be an *authoritative complete workspace*. Verbs on the closed-world axis run their set-quantified logic **only** under that assertion and are **refused** without it — never silently degraded. This keeps `model rename` reachable and subsumes the `workspace` family under the same precondition rather than treating each as a special case.
- **The closure is a client-side payload-minimisation rule**, useful for ordinary single-subject requests, and carries no contract weight.

**Excluded from a hosted transport, with reasons:** `model diff --since` (reads git history through a spawned `git cat-file --batch`); host-path-bearing output such as `model info`'s resolved paths (meaningless to the caller and a layout leak — an output-mapping concern, like `-o`); and anything on the closed-world axis absent the authority assertion.

**How many verbs need content beyond the closure: 24.** Established by a per-verb `--help` sweep across all 81 leaves plus a source grep over the read/write and process-spawn sites — 3 argument-introduced-model verbs, 1 multi-subject/git verb, 3 runtime/document-input verbs, 10 schema/profile/spec-input verbs, 2 op-frame verbs, 1 inward-workspace refactor, 4 workspace-set verbs. **Recall limit, stated because it is a design input:** this could not be derived from the published surface — filtering `manifest` on `kind: "FILE"` yields only 6 verbs (§4), so the count rests on a source sweep and would need re-doing by hand after any verb change. Optional `-o` destinations are deliberately **excluded** from the 24: content-carrying removes output destinations rather than needing to carry them.

**Implementation: materialise per request, do not re-implement dispatch.** The kits are already content-first (§4), but the CLI layer requires paths and `emitMutation` writes to one. Three routes:

| route | how | assessment |
|---|---|---|
| **A** | per request, a fresh temporary directory holding the supplied content; run `Cli.run` against it; read back; delete | **the cheapest start.** Zero CLI change and keeps §5.1's single entry point. But **not "every verb works immediately"** — `model diff`'s two roots and git route need explicit handling. Request content transiently touches disk, so H10 must be checked against the platform. All filenames are server-generated; no caller-supplied path component is used |
| **B** | drive the kits directly with a map-backed `ModelResolver` | **rejected.** Nothing touches disk, but the dispatcher would re-implement verb dispatch for 81 verbs — exactly the drift the derived-from-picocli design exists to prevent |
| **C** | teach the CLI content addressing: inline content in, mutated content out, still through `Cli.run` | **the graduation path** if a programmatic-client API becomes a product. Small, single-sourced, and it makes the CLI itself a pure function. New CLI surface, needing its own gate |

**On a map-backed resolver factory:** `ModelResolver` is a functional interface, so routes B and C need only a lambda at the call site. A `ReferenceResolvers` map factory is **not** proposed — but note the honest position, since an earlier draft justified that by an unenumerated "no other caller": `GitBaseline.GitModelResolver` is already a production map-backed `ModelResolver` of exactly that shape, and a test fixture hand-rolls another. So the factory has plausible callers; it is simply not needed by any route this proposal recommends, and building it now would be speculative. (`GroupExtractCommand`'s in-memory resolver is a *different* shape — delegation-with-override — and a map factory would not replace it.)

**Who this serves, which answers §13.1's open question.** Inlining content is token-fatal when an **LLM writes the tool call** and free when the **client is code** — then it is a request body nobody pays tokens for. The two shapes have disjoint consumers:

| | consumer | addressing |
|---|---|---|
| **S1 stdio** | an agent, whose host already has the files locally | filesystem paths (§9) |
| **S3 hosted** | a program — an app, a CI job, a web UI, A12 Tools | the carried resource tree |

### 13.5 What content-carrying does not solve

Throughput is unchanged — callers share the CPU, and §5.1's serialised dispatch would head-of-line block a second caller behind a slow `model check` or `model eval`. A **bounded queue returning an immediate busy error** is required, because this revision removed stream resumability and a timed-out request is re-issued as a *new* one, so unbounded queueing invites a retry storm. Memory scales with concurrency, so H7's pinned `-Xmx` and H12's cap both bind. R9's thread-safety claim is still needed for concurrent dispatch — though content-carrying reduces it to a *pure* thread-safety question, since requests share no filesystem state.

**One temptation to refuse: a content-addressed cache.** Once payloads grow, having the client send hashes and the server request only what it lacks is the obvious optimisation. It reintroduces server state and with it every isolation question this design deletes, in exchange for bandwidth. If payload size becomes the actual problem, revisit it then, with a measurement.

## 14. How this stands against decisions already recorded

Prose rather than a table, because a status board over other documents' decisions is what [DOC-DISCIPLINE.md](DOC-DISCIPLINE.md) §5 forbids. Each owning document remains authoritative.

**[RELEASE-SPEC.md](RELEASE-SPEC.md) §6 and §12 rejected MCP** — §6: *"not MCP — wrong model for a local offline CLI, and it would undercut the self-describing-CLI thesis"*; §12: *"it adds a running process and undercuts the offline self-describing-CLI thesis."* On "adds a running process": true, and §10 keeps that process out of the plugin entirely for phase 1. On "undercuts the self-describing-CLI thesis": the counter-argument is that the MCP surface is *derived* — `tools/list`, every annotation, and every resource recompute from the same picocli tree and contract registry the CLI describes itself from, so it cannot describe the tool differently. What MCP genuinely does not preserve is **offline zero-install simplicity**: it adds a process, a lifecycle, and a second name vocabulary. Neither section is repudiated: the plugin remains the primary vehicle, and MCP is an additional surface, which is the shape §12 left on the table.

**[FEATURE-GAPS.md](FEATURE-GAPS.md) §4's `dmtool serve` won't-build** is addressed ground by ground in §3, with the residue stated there. R3 records the condition.

**[CLI-CONFORMANCE-GAPS.md](CLI-CONFORMANCE-GAPS.md) §3's remote/async cluster** stays void. §3 scopes those recommendations to a *resident or remote* mode; a local, synchronous, stateless-per-call stdio server is the posture §3 already describes dmtool as having. Two notes: pagination is **not** answered by `ttlMs`/`cacheScope` — the list endpoints remain cursor-based and the mechanisms are orthogonal (§2); and trace context is not "free" — `_meta` carries client-supplied OpenTelemetry conventions, while the voided recommendation was `--trace-id` *plus a correlated audit log*, which MCP neither supplies nor obliges.

**[MODEL-REVIEW-GAPS.md](MODEL-REVIEW-GAPS.md) §2's MCP Apps widget** re-opens when dmtool *ships* as an MCP server. Phase 1 shipping fires it; the widget is phase 2 (§12).

### 14.1 A write-durability finding this proposal surfaced but does not own

**The observation.** Model writes use `Files.writeString` truncate-and-write with no temporary file, no `ATOMIC_MOVE`, and no lock. `Files.writeString` without options opens `CREATE` + `TRUNCATE_EXISTING` + `WRITE`, so once the open succeeds the previous contents are already gone: a failure on the first output write can leave an empty file, and a later one a partial file. **Ten sites write DM-JSON to a destination that may be an existing model** — `BaseCommand.emitMutation` (the shared funnel, serving **36** leaves, not the 33 its javadoc names, which counts pre-funnel duplication), `ApplyCommand`, `PatternsCommand`, `GroupExtractCommand` (two files), `ModelNewCommand`, `ModelExpandCommand`, `ProfileSynthesizeCommand`, and `ModelImportJsonschemaCommand` (two sites, the second being the bundle route).

**The defect is a published overclaim, not the write itself.** **43 profiles declare `FailurePersistence.NO_WRITE` while actually writing** (§4). `emitMutation` catches `IOException` and returns a failed invocation — so an ENOSPC, quota, or EIO failure is an ordinary *invocation-observable* failure that leaves the destination truncated on a route promising no write. The registry's own javadoc scope statement was *"persistence after a command/domain/child failure"*, which arguably excludes environment I/O; but `CLI-SPEC.md` worded it as *"after a failed/rejected invocation"*, which includes it, and the manifest emitted a bare `NO_WRITE` with no scope qualifier at all. So the one statement that narrowed the claim was the one a consumer could not read. Both are fixed below.

**Owner decision, 2026-08-04: publish the scope; do not make the writes atomic.** Atomic replacement was assessed and **rejected as the remedy**: it would replace rather than follow a symlink, break hard links, change inode identity, drop permissions and ACLs unless deliberately carried, and — since `ATOMIC_MOVE` is provider-dependent — force refusals where writes work today, all traded against a rare failure whose common case (an in-place edit of a tracked model) git already undoes. It also could not change any multi-file classification: `group extract` would still commit two files in sequence and remain `PARTIAL`.

**Shipped 2026-08-04, in `cli/`.** `FailurePersistence` now carries its scope as published data: every value has a meaning, and the axis states that it covers a rejected invocation, a domain refusal, and a failing child op, and that it excludes an I/O failure mid-write and abnormal process termination — because no destination is atomically replaced. It reaches `manifest.contractVocabulary` once, `schema <verb>` for each of the **47** verbs that can write, and the generated help footer of those same leaves; the 34 always-read-only leaves stay free of it, since a bare `NO_WRITE` cannot mislead where nothing is written. The vocabulary is derived from the enum, so a new value cannot leave it behind, and `CommandContractsTest` fails on both a missing and an indiscriminate vocabulary. `CLI-SPEC.md`'s broader *"after a failed/rejected invocation"* wording is corrected there, which is where the contract now lives.

**Re-open trigger:** a resident server shipping, which raises the rate of unannounced termination from incidental to once per session; or any report of a truncated model.

**Also shipped, independently:** `DmtoolMutationSafetyTest`'s discriminator no longer merges `ROLLBACK` and `PARTIAL` into one set before asserting equality — the union is invariant under swapping which verb carries which label, so the behavioural evidence (a reverted destination versus a surviving first child) was never joined to the published label. Each label is now cross-checked against its own behavioural set.

## 15. Owner decisions — resolved and open

Numbering is stable: a resolved question keeps its number, because §4 and §6 cite these by number.

**Q1 — which revision, given no evidence the host speaks `2026-07-28`? RESOLVED 2026-08-04: target `2026-07-28` only, and refuse anything below it** — over the recommendation to verify the host first. The contract is §5.3; R7 records what the decision did and did not change. The refusal, not a fallback, is what keeps an older host from looking like a broken server.

**Q2 — is the effect-split tool shape right, now that its cost is measured? RESOLVED 2026-08-04: partition on `Effect` × `ReturnKind` jointly, 30 tools.** The question dissolved once the two axes were measured as independent rather than competing: the joint partition costs **four** more tool definitions than the effect-only split and recovers **every** fact on both axes — 14 read-only tools over all 34 always-read-only verbs, 24 tools declaring `outputSchema` over all 68 eligible verbs. §6 carries the decision, the comparison against the 17- and 26-tool alternatives, the derived naming rule, and the non-goal that forbids changing a verb to make a tool eligible. The residual cost is four more **public tool names**, which is why §6 restates that a name is chosen once.

**Q3 — is the bare-MCP-host audience decision (§10) accepted? WITHDRAWN 2026-08-04 — the question was built on a wrong constraint.** It asked whether to accept an audience defined by *lacking skill support*, when the two capabilities that actually decide the audience are whether the caller can **execute the binary** and whether it can **do file I/O** (§10). Its "recommendation: accept" also rested on two claims that did not survive checking: that tool descriptions can carry the judgment — §6.2 derives them from the registry, and judgment is what no registry holds — and that an eval arm existed to measure it, which it does not. What survives is the conclusion that no MCP skill and no marketplace entry are needed; §10 carries it on better grounds, along with the measured judgment-lane split and the one residual tension it leaves open.

**Q4 — is §13.4 ratified as the phase-3 direction?** Caller-declared request-local resource tree, subject cardinality 0/1/2, no id-keyed map, an explicit authority assertion gating the closed-world verbs, and the named exclusions. It reduces §13.2's processor role to transient processing and does not reduce the EUPL obligation. **Recommendation: ratify as direction**, with the resource protocol deliberately unspecified until a consumer exists.

---

## Appendix A — hosting candidate assessments

Dated research, kept because §13.3's requirements were derived from it and because the sovereignty findings generalise. **Re-measure against whatever candidate is actually chosen.** No per-unit pricing table is recorded — those numbers rot and were never the decision input. Free allowances *are* recorded, because free hosting is a stated requirement and an allowance is therefore a design constraint.

### A.1 Scaleway — rejected on billing model, 2026-08-03

Assessed because it was recommended on sovereignty grounds, and **rejected on its billing model rather than on sovereignty**, which it largely satisfies.

**Sovereignty**, read from the vendor's own security-and-compliance page and a procurement announcement on 2026-08-02. *Satisfied:* 100% European infrastructure under EU jurisdiction, stated by the vendor as *"free from the US CLOUD Act"*; **ISO/IEC 27001:2022** and **HDS** certified; selected in April 2026 under the European Commission's **Cloud III Dynamic Purchasing System** after evaluation under the Cloud Sovereignty Framework. *Not satisfied:* **SecNumCloud is not held** — the vendor's page says *"currently undergoing qualification."* And the substitution is not like-for-like: providers that do hold it offer no equivalent serverless-container product, so satisfying SecNumCloud changes the deployment shape to managed VMs. **Verdict: sound for GDPR and EU-jurisdiction sovereignty; not a choice for SecNumCloud-regulated workloads today.**

**Technical fit.** Serverless **Containers** fit the workload: an hour request ceiling, HTTP/2, scale to zero when idle, and a generous image budget against a 33 MB / ~90 ms native binary. Serverless **Functions** are the wrong product — per-instance concurrency of one and a small request-payload cap make them event handlers (the origin of H8). Two facts generalise: an **amd64** image is needed whatever the platform (H9), and **process start is not container start**, so 33 MB / ~90 ms is a floor for cold-start latency rather than a measurement of it.

**Why it fails H1**, verified 2026-08-03 against the vendor's billing-alerts and billing-FAQ pages: the free tier is an **allowance deducted on the invoice**, not a ceiling, with consumption beyond it billed pay-as-you-go automatically; **billing alerts only notify** — a monthly budget plus a percentage threshold by SMS, e-mail, or webhook, with nothing that stops, throttles, or caps usage; and there is **no spending limit and no prepaid mode**, prepaid cards being explicitly rejected. A cost-triggered webhook cannot close the gap: inside the allowance the cost is €0, so a *cost* threshold necessarily fires **after** billing has begun.

**The H2 exposure, quantified.** Charges are tier × run duration × instances, so a flood cannot exceed the ceiling *we* configure — but the published ceilings are high: maximum scale runs to 50 instances, so 50 instances pinned for a month is on the order of **€1,900 at the default tier** and **€11,000 at the maximum**. Two details make it real: **the defaults are not safe**, so maximum scale must be lowered by hand; and **rejecting a request still consumes run duration**, so authentication narrows the blast radius without removing it. With alert-only detection, an unnoticed weekend flood at default settings is a three-figure invoice.

**One configuration would have satisfied H1 and H2, and is recorded because it is the shape any candidate must offer.** At the platform floor — 70 mvCPU / 128 MB, maximum scale capped at **one** instance — continuous operation for a 31-day month consumes ~187,000 vCPU-s and ~335,000 GB-s against a 200,000 / 400,000 allowance. Since consumption is exactly tier × time and time cannot exceed the month, exceeding the allowance is **arithmetically impossible** regardless of traffic, and a flood becomes cost-neutral. The one-instance cap is the load-bearing half. It did not save the vendor for three reasons: whether the allowance is per-container or account-wide is not settled by the documentation; its guarantee is *our* arithmetic rather than the platform's promise, so a change to the allowance or tier floor silently breaks it; and 70 mvCPU is ~7% of a core as a *ceiling* rather than a reservation, making every CPU-bound call take seconds. On memory, 128 MB would have held the authoring workload (~43 MiB) and not document evaluation (up to 519 MiB) — the split that produced H7.

**Decision (owner, 2026-08-03): out.** H1 outranks the sovereignty fit that motivated the candidate.

### A.2 alwaysdata Free — the first candidate to pass H1 by construction, 2026-08-03

Forwarded by the owner from independent research and re-verified against the vendor's own pricing, legal, and documentation pages.

**The plan:** €0/month for life, 1 GB SSD, **256 MB RAM**, **¼ CPU**, 3 days of backups, an `alwaysdata.net` hostname, offered "for personal needs". The publisher is **ALWAYSDATA SARL**, a French company (Paris RCS 492 893 490) with a CNIL processing declaration, which is what H5 asks at the jurisdiction level. It carries **no ISO 27001, HDS, or SecNumCloud claim**, so it cannot serve H5's second clause.

**Why it passes H1 — a stronger mechanism than an arithmetic bound.** The plan is a fixed €0 subscription rather than a metered allowance, and the platform can be used with **no payment instrument registered at all**. Accounts with neither a card nor a prior payment sit in "restricted mode", and the documented way out is deliberate — *"fill in your credit card details … or make at least one payment"*, with the doc noting that adding a bank account explicitly does **not** lift it. The maximum charge is €0 because there is no collection channel, and opening one requires an affirmative act by us.

**Restricted mode does not block the endpoint** — the decisive check, since restricted mode *is* the H1 configuration. Its documented effects are that *"HTTP: processing may take a bit longer as requests will be filtered using rules that are a little stricter"* and that outgoing SMTP may be limited; the doc calls the restriction *"not an inhibiting one"*. **Unverified residual:** those stricter filter rules are unspecified, and an endpoint receiving automated agent traffic is plausibly what they exist to catch. One deployment settles it.

**H2 and H3 pass by construction.** Resources are fixed rather than request-metered and no replicas are created automatically, so hostile traffic converts into queueing, throttling, or suspension rather than spend — and the vendor reserves the right to suspend free accounts running resource-intensive applications, which is precisely the H3 failure mode, chosen deliberately.

**H7 and H8 fit.** 256 MB is exactly the H7 floor, with ~6× headroom for the authoring workload and a pinned `-Xmx` needed for document evaluation. The platform's **user program** site type takes a start command, a working directory, and environment variables and expects the program to bind the IP and port it is given, with SSH available to test first and Java among the supported languages — a first-class fit for H8, and a better one than a scale-to-zero container.

**What weakens it, and the correction to the forwarded assessment.** The research reported that commercial use is explicitly prohibited; re-reading the vendor's pages, **no such contractual clause was found**. What exists is softer and, for a production endpoint, worse: the pricing card frames the plan as "for personal needs", the documentation describes the free offer as for low-resource needs or evaluating the platform, and the legal page states that **"alwaysdata reserves the right to suspend or delete without notice a free account if its legitimacy is contested."** That is unbounded discretion rather than a bright line — there is no clause to comply with, so there is no way to make the arrangement safe by conforming to it. This produced **H11**.

Note the verb: **delete**, not only suspend. Under H3 that is acceptable. The forwarded research also correctly flagged that three days of rolling backups mean payloads written to disk persist beyond the request, and drew the right conclusion — *do not write request bodies to the filesystem*. §13.4's route A writes them to a temporary directory, so **H10 must be checked against this platform specifically**; route C removes the question.

**Assessment: viable for a public endpoint holding no customer models**, where deletion without notice costs a redeploy. It satisfies **H1–H4, H7, H8** and **H5 at the jurisdiction level**, fails **H5's certification clause** and **H11**, and **H10 depends on the implementation route**. As infrastructure serving customers or the company it still fails — not on resources, but on the absence of any contractual floor.

---

## Appendix B — positions superseded

What was held earlier in this proposal's life and is no longer held. Recorded here so the body can carry a single live statement of each fact; the body also notes a superseded position inline wherever the *reason* for the change is load-bearing to the current design.

| earlier position | status | why |
|---|---|---|
| Envelope classification is `returns: "result" \| "artifact"`, split 70 / 2 / 9 contract-exempt | **superseded** | `CommandContracts` publishes a 4-way `ReturnKind` per route. 68 verbs are envelope-only; 13 have a non-envelope route; 9 carry more than one return kind |
| **17 target-shaped tools**, 3 read-only | **superseded** | 34 verbs are `READ_ONLY` in every profile, so target-only grouping discards a published fact about 25 of them and bundles read with remove under one approval name (§6) |
| *"Precision is not available"* for annotations | **superseded** | Effect is published per route; `readOnlyHint` is derived and true for 14 tools (§7) |
| `outputSchema` reaches the same set as `structuredContent` | **corrected** | `outputSchema` is a per-`Tool` declaration, so it reaches **54** verbs, not 68 — the `Effect`-versus-`ReturnKind` tension in §4 |
| A per-verb `sideEffect` axis is an open deferral to route to the owner | **closed by events** | The axis landed as `CommandContracts.Effect`, and the ledger row was deleted in `7427c417` |
| Model writes occur at *four* sites; the atomic-write remedy is small and single-sourced | **corrected, and the remedy rejected** | Ten sites write DM-JSON to a possible model destination. The defect is the **published overclaim on 43 profiles**, and the remedy is a machine-visible scope, not atomic replacement (§14.1) |
| Only *process death* is left uncovered by `FailurePersistence` | **corrected** | A caught `IOException` mid-write is an invocation-observable failure that also truncates, on routes declaring `NO_WRITE` |
| Hosted state lives in **server-minted handles** over object storage with OAuth 2.1 identity | **superseded** | Requests carry their content and the service retains nothing (§13.4). Handles were also the wrong granularity — a model handle cannot resolve an include |
| *"An endpoint without authentication must not expose mutating tools"*; prefer a **read-only** demonstration endpoint | **superseded** | With nothing persisted there is no write to authorise, so the full authoring surface is exposable; S4 merged into S3 |
| The carried set **is** the subject's transitive resolution closure | **withdrawn** | Wrong in kind: reference-introducing edits, `model rename`'s inward scan, `model diff`'s two subjects, non-model inputs, and `batch`'s recursion all fall outside it (§13.4) |
| 57 of 81 leaves need out-of-closure input | **withdrawn** | Conflated carried *inputs* with optional `-o` *destinations*, which content-carrying eliminates. The figure is **24** under the stricter definition |
| `subject: "<header.id>"` matches `ModelResolver` exactly rather than inventing | **corrected** | Invented *and* unsound — the subject is addressed by path everywhere, `ModelResolver` is only asked for *referenced* models, and id uniqueness is not an invariant (`putIfAbsent`, first wins, in both indexes) |
| Duplicate ids let last-one-win | **corrected** | First wins |
| Referrer analysis is outward-only | **corrected** | True for `where-used` and the rewriting refactors, false for `model rename` — the finding that produced the closed-world axis |
| Route A means "every verb works immediately" | **corrected** | `model diff`'s two roots and git route need explicit handling |
| A `ReferenceResolvers` map factory has no other caller | **corrected** | Unenumerated; `GitBaseline.GitModelResolver` is a production map-backed resolver. The factory is still not proposed, but for want of need rather than want of callers |
| Phase 1 framed as work to ship, with reach *"a benefit, not the trigger"* stated but never carried to its conclusion | **superseded 2026-08-04 — the proposal is PARKED** | Joining §1's own admission (no trigger) to §10's (a plugin agent will shell out and ignore the tools) yields the conclusion neither drew: for a caller that can run the binary, MCP is indirection. §9's content-carrying decision then made the local case strictly worse than the CLI, since it gives up in-place editing. The three real value cases are recorded as re-open triggers (§1) |
| *"Over stdio the server shares a filesystem with the user, so `model` and `workspace` are paths"*, defended by relative-path rejection, a resolved-roots echo, and traversal sanitising across `args` | **superseded 2026-08-04** | The server accepts no client path at all (§9), so all three guards protect an unreachable surface. A path from a caller is caller-controlled input into a filesystem; refusing the shape is strictly safer than sanitising it |
| A model-as-path / specs-as-content split, justified by the model being the durable artifact the user owns | **rejected before it was written** | It kept per-caller isolation true for one transport and false for the other, reproducing the split the 2026-08-03 content-carrying decision existed to remove |
| The Roots migration advice read as *"the specification's own recommendation"* for path addressing | **corrected 2026-08-04** | It licenses a location parameter where one is needed and names resource URIs as an equal alternative; it endorses nothing about accepting client filesystem paths (§2) |
| The 26-tool effect-only split, with the `outputSchema` loss on 14 verbs framed as *"the cost this shape carries"* and a `ReturnKind` sub-split costed only as *"more tools"* | **superseded 2026-08-04** | The two axes are independent, so nothing had to be traded: the joint partition costs **4** tools — a number the earlier text never derived — and recovers both facts fully (54→68 verbs with `outputSchema`, 12→14 read-only tools). Framing it as a trade came from partitioning on one axis and treating the other as its casualty (§6) |
| The seam rule: *"nothing **below** `McpDispatcher` may name a protocol type — no `_meta`, no headers, no `resultType`, no JSON-RPC envelope"*, with the transport drawn **under** the dispatcher | **corrected — it was inverted and overbroad** | The transport is the *outermost* layer, not an inner one, so the diagram contradicted the rule it illustrated. And banning `resultType`/`ttlMs`/`cacheScope` from the inner layers would have pushed caching *policy* into the transport; the ban is on library **types**, not protocol **facts** (§5) |
| `--text` emits only the rendering, so passing it would lose the JSON | **corrected** | `TextRenderer` is gated and passes a non-envelope payload through unchanged; the loss applies only on envelope routes (§5.1) |
| "No path components on the wire, so the traversal surface does not exist" | **corrected** | 12 `FILE`-tagged parameters plus positional operands carry caller-supplied paths inside `args` (§9) |
| `openWorldHint: false` because dmtool "touches local files and nothing external" | **corrected** | It spawns `node` and `git` as local subprocesses; the hint still holds, on the narrower basis that there is no open world of external entities (§7) |
| Excluded: *"any new CLI verb"* | **corrected** | The `mcp` leaf is itself a new verb, with the standing new-verb obligations (§12) |
| `manifest` is 94,681 / 118,226 bytes; `resolverFor` is called by ~30 verbs; `emitMutation` serves 33; `skill/SKILL.md` is 152 lines with 19 invocations; `schema` returns one of two shapes | **all corrected** | ~59 verbs; 36 leaves; 167 lines with 20 invocations. The manifest size and the schema key-set count are live figures that moved again when §14.1 shipped — read them from §4, never from this row |
| *"15 distinct key sets"*, published in this revision's first draft | **corrected to 14**, and since moved by §14.1 | Taken from a reviewer rather than re-derived — the one figure in this revision that repeated the defect class it documents. Caught by re-measuring after `:cli` sources moved mid-revision. The live count is in §4 |
