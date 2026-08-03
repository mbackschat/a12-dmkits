# dmtool over MCP — proposal

**Status:** PROPOSAL (2026-08-03, revised after cold review). Intent, rationale, and public contract; nothing built. Graduates into the owning specs as phases land, per [DOC-DISCIPLINE.md](DOC-DISCIPLINE.md) §5.

**Scope.** Expose the dmtool surface over the Model Context Protocol, revision **`2026-07-28`**, as a **stdio server inside the existing binary** (`dmtool mcp`). Hosted HTTP and MCP Apps are named as later phases with their own gates; they are not proposed here.

**Owner decisions already taken** (2026-08-02): stdio-first; target `2026-07-28` hand-rolled, with the official Java SDK kept as a possible later substitution; target-shaped tools rather than one-per-verb; filesystem paths for stdio, server-minted handles only if hosting ever happens. **§15 carries four questions that must be answered before implementation starts**, one of which (Q1) can invalidate the revision choice.

**Two statements rest on CLI-side fixes that had not landed when this was written.** They are tagged inline as **`[ASSUMED-FIXED: C1]`** / **`[ASSUMED-FIXED: C2]`** and collated in **§16** with how to verify each and what changes if it never lands. Verify and delete the tags when the fixes land; `grep -n 'ASSUMED-FIXED' docs/MCP-SUPPORT-PROPOSAL.md` finds every site.

**Evidence basis — four distinct source classes, deliberately not conflated:**

| class | what it governs | how it is cited |
|---|---|---|
| **normative specification** — `modelcontextprotocol.io/specification/2026-07-28/**` | every protocol MUST/SHOULD in §2, §5, §8, §9 | linked inline; read 2026-08-02 |
| **non-normative client guidance** — `modelcontextprotocol.io/docs/2026-07-28/**` | progressive discovery, retrieval strategies, categorical approval (§6.1, §7) | labelled *guidance* at each use |
| **host documentation** — Claude Code plugin reference | plugin packaging (§10), tool-name scoping | labelled *host docs*; not part of MCP |
| **this repository** — source, or a dated measurement | every dmtool claim | §4 splits proven from measured from unverified |

---

## 1. Motivation

### What it buys

**Reach without a wrapper per ecosystem.** [RELEASE-SPEC.md](RELEASE-SPEC.md) §12 already named this — *"MCP is the write-once alternative — one MCP server reachable by all agents instead of N native wrappers"* — and kept it on the table *"only if maintaining several native wrappers becomes the actual friction."* This proposal does **not** claim that friction has arrived: the Claude Code and Codex plugins were near-identical to build. Reach is a benefit, not the trigger. §14 addresses the grounds on which that same spec rejected MCP.

**Host-cached, addressable documentation.** dmtool's `manifest` emits the whole command tree, and the eval measured that cold agents never use it for orientation, reaching `--help` and the catalog verbs instead. MCP resources make the same per-verb content **addressable and host-cacheable**: `dm:///help/rule/add` returns one verb's record, and `ttlMs` lets the host keep it without re-asking.

*Honest scope of that gain:* `dmtool rule add --help` is already a single call, and `dm:///help/rule/add` needs exactly the same prior knowledge of target and op. The delta is **host-side caching and machine-addressability**, not fewer calls — and this proposal supplies no measurement that help text is re-fetched often enough for the caching half to matter. Treat it as a structural improvement, not a quantified one.

**It makes a deferred feature reachable.** [MODEL-REVIEW-GAPS.md](MODEL-REVIEW-GAPS.md) §2 defers an in-chat HTML report widget, re-opening *"when dmtool **ships** as an MCP server."* Nothing ships at proposal stage; the trigger fires when **phase 1 ships**, and the widget itself is phase 2 work. §12 excludes it from phase 1.

**It is cheap right now, for a specific reason** — the `2026-07-28` revision converged on the invocation model dmtool already has. §3 and §4 argue that rather than assert it.

### What it does not buy

- **No performance gain.** The shipped native binary starts in ~37 ms; there is nothing to amortise, and §9 forbids a warm model outright.
- **No new authoring capability.** Every tool routes to an existing verb. No verb is added, changed, or removed.
- **No replacement for the plugin or the CLI.** The plugin stays the primary distribution vehicle and the CLI the primary surface; MCP is an additional rendering inside the same plugin.
- **It is not vocabulary-neutral.** An earlier draft claimed "no new vocabulary." That was false and is corrected in §6.

## 2. What MCP `2026-07-28` changed

The current revision (predecessor `2025-11-25`) is the largest break since the protocol launched. Recorded in full because the design below is a consequence of it. Source: [Key Changes](https://modelcontextprotocol.io/specification/2026-07-28/changelog) plus the linked specification pages, read 2026-08-02.

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
- **Pagination is separate from caching and still cursor-based.** `tools/list` and the other list endpoints accept `cursor` and return `nextCursor`; the caching page has an explicit *Interaction with Pagination* section in which each page carries its own `ttlMs` and is re-fetched by its cursor. The two mechanisms are orthogonal.
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

The Roots migration advice is *"pass directories or files via tool parameters, resource URIs, or server configuration instead"* — which is exactly the model addressing chosen in §9, making that choice the specification's own recommendation rather than a workaround.

## 3. Why statelessness is the enabling change

Under every revision through `2025-11-25`, an MCP server was a **stateful conversational peer**: a connection-scoped session established by an `initialize` handshake, a session-id header, and the ability to initiate its own JSON-RPC requests mid-conversation.

That shape is close to what this project already examined and rejected. [FEATURE-GAPS.md](FEATURE-GAPS.md) §4 records `dmtool serve` as **won't-build** (2026-06-15) on five grounds. Classifying them honestly: **three are performance arguments** this proposal does not make (the dead premise of amortising JVM startup, the retired `--kernel` eval warm-up, and the cost of a per-model bootstrap). **Two are not**, and both need answering:

**"`batch`/`apply` already occupy the space."** True, and unaffected — those are *batching* surfaces that amortise warm-up across a known op list and a transaction. This proposal claims no batching benefit and does not compete there; `dm.apply` and `dm.batch` route to them unchanged. What MCP adds is a transport and a discovery surface, which neither verb provides.

**"The cheap tier doesn't fix the real cost"** — a stateless serve re-pays the per-model bootstrap every op, so only a *stateful warm-model* serve would remove it. This proposal's server **is** that stateless variant (§9: every call re-reads from disk), so this objection applies in full to any *performance* case for it. That is why §1 makes none. The value claimed is reach, addressable documentation, and MCP Apps reachability — none of which a warm model would improve.

**"It fights the agent-facing thesis — serve adds session/lifecycle/reload/stale-write semantics, a second mental model."** The revision deletes the *session* half: a conforming server is now free to be a pure per-call transport — read one self-contained request, run the verb, write the envelope, forget everything. And that is already dmtool's own model; [CLI-SPEC.md](CLI-SPEC.md) §3a: *"a fully-specified, stateless invocation is easier for an agent to generate and to reproduce/debug."*

**But the objection is not fully retired, and this proposal should not claim it is.** Protocol statelessness removes sessions, reload, and stale writes. It does **not** remove **process lifecycle** (R1 is nothing but lifecycle) or **a second surface with its own tool-name vocabulary** (§6) or **a second eval material boundary** (R5). So the honest statement is: the *session* ground no longer applies, on new evidence the 2026-06-15 decision could not have had; the *second-mental-model* ground is reduced, not eliminated, and §6 and R5 are where the residue lives.

Three consequences follow, and they are why the work is small:

1. **The adapter is thin.** No session store, no handshake state machine, no resumability bookkeeping, no server-initiated request path.
2. **Hand-rolling the transport is reasonable.** What remains is one JSON-RPC dispatch table behind a single entry point (§5.1).
3. **All of it is conditional on no warm model** (§9). Introduce one and the cache-coherence semantics the `serve` decision named return in full.

## 4. Feasibility

### Proven — verified against source or by execution

| claim | basis |
|---|---|
| Per-verb envelope classification is **already published**: `schema <target> <op>` carries a `returns` field — **70 verbs `returns:"result"`, 2 `returns:"artifact"`, 9 contract-exempt with no schema pointer** | source read of `SchemaCommand` plus live `schema` output, 2026-08-03 |
| The tool list can be **derived** from the live picocli tree, inheriting the no-drift property `manifest` has | `manifest` is recomputed from that tree; `ManifestVerbTest` guards it |
| A Claude Code plugin can bundle skills **and** an MCP server in one plugin | *host docs*: components include skills, agents, hooks, MCP servers, LSP servers, monitors; config via `.mcp.json` at the plugin root or inline `mcpServers` |
| The official Java SDK cannot serve this revision, and carries a dependency we do not want | `mcp` v2.0.0 tracks `2025-11-25`; `mcp-core`'s compile deps include `reactor-core` (§5.1) |

**The reuse, corrected.** An earlier draft claimed three artifacts satisfy the tool contract wholesale. That is true only for envelope routes:

| MCP field | dmtool artifact | applies to |
|---|---|---|
| `outputSchema` ← `result.schema.json` | the published result schema | **only the 70 `returns:"result"` routes** |
| `structuredContent` | the result envelope | the same 70 |
| `content[0].text` | the `--text` rendering | the same 70 |
| tool-execution error (`isError: true`) | `rejected` / `refused` / `error` outcomes with their diagnostics | the same 70 |

The 2 artifact routes and 9 contract-exempt routes emit something other than the envelope, and `TextRenderer` passes non-envelopes through unchanged. **Declaring `outputSchema` on those would violate an MCP MUST** — "if an output schema is provided, servers MUST provide structured results that conform to this schema." So `outputSchema` is emitted **per route, from the published `returns` value**, and omitted where `returns` is `artifact` or absent. This needs no new CLI metadata; the classification already exists.

The error-mapping row is not a coincidence: MCP separates **protocol errors** (unknown tool, malformed request) from **tool execution errors** precisely because models can self-correct from the second — which is [CLI-SPEC.md](CLI-SPEC.md) §2's corrective-diagnostics principle restated by the protocol.

### Measured, with dates

- The live command tree has **81 leaves** (2026-08-02, two independent source counts). §6 is derived from the tree. `ManifestVerbTest.EXPECTED_VERBS` pins the same set under an **equality** assertion, so it is a valid independent cross-check — **`[ASSUMED-FIXED: C1]`**, see §16; when this proposal was written it held 80 entries under a *containment* assertion and silently omitted `typedef modify`.
- `manifest` is **94,681 bytes** compact / **118,226** pretty-printed (2026-08-02).
- **Stdout is pretty-printed multi-line JSON on 78 of the 79 JSON-emitting canonical routes** (2026-08-03, a sweep of all 81 leaves through the real launcher). Two routes emit non-JSON by design (`schema` bare, `export card`), and `export --out-dir` emits nothing. §5 states the consequence.
- Lexical search over the manifest, case-insensitively (2026-08-03), with the corpus stated because the result depends on it:

  | query | verb name + `@Command` description | plus parameter flag/key/description |
  |---|---|---|
  | `required` | 3 (`field modify`, `field read`, `config modify`) | 6 |
  | `mandatory` | 0 | 0 |
  | `requiredness` | 0 | 1 (`model import-jsonschema`) |
  | `make a field required` | 0 | 0 |

### Not measured — claims an earlier draft overstated

- **The progressive-discovery threshold is a non-normative example**, not a specified limit: client guidance says hosts *should* implement thresholds, "for example, 1%-5%". This proposal has **not** measured the token cost of its tool definitions, so it cannot say which side of that range 17 tools fall on. §6.1's argument accordingly does not rest on it.
- **Whether host-side help caching matters** is unmeasured (§1).

### Unverified — settle before building

| open item | why it matters | tracked as |
|---|---|---|
| **Which revision the intended host implements** | decides whether phase 1 works at all | **R7 / §15 Q1** |
| **MCPB** (MCP Bundles) as the packaging path | may resolve the binary-presence problem outright | R2 |
| Real host support for `resources` versus `tools` | a resource-only design could be invisible in some clients | R6 |
| Per-call model parse cost | the only honest input to any future caching discussion | R3 |

**Verdict: feasible and small on the protocol and licence axes; blocked on R7 for scheduling.** Nothing in the protocol or the licence prevents phase 1. Two things could: a host that does not speak `2026-07-28` (R7), and an unresolved binary-delivery route (R1).

## 5. Architecture

**The MCP command lives in `:cli`, not a new module.** An earlier draft proposed `:mcp` depending on `:cli` while also making `dmtool mcp` a subcommand of the existing binary — which requires `:cli` to reference the command, so both edges cannot hold. Separately, `RootCommand`, `ManifestCommand`, and `TextRenderer` are all **package-private**, so a class outside `io.github.mbackschat.a12.dm.cli` can neither build the live tree, nor walk it to derive tools, nor produce the `--text` rendering. Placing the command in that package makes all three reachable without widening any visibility. If a separate module is ever wanted, that is a deliberate API-widening decision with its own review, not a side effect of this work.

```
cli/…/mcp/McpToolRegistry     derived from the live picocli tree (same walk `manifest` uses)
cli/…/mcp/McpDispatcher       method + params -> result; names no protocol type
   └── McpStdioTransport      frames JSON-RPC; the only protocol-aware layer
```

**The seam rule:** nothing below `McpDispatcher` may name a protocol type — no `_meta`, no headers, no `resultType`, no JSON-RPC envelope. That keeps a later hosted transport a bounded addition, and keeps a possible SDK substitution confined to one class.

**Three consequences of stdio framing (§2) that shape the implementation:**

1. **The server serialises its own compact frames. It never relays CLI stdout.** dmtool prints pretty-printed multi-line JSON, and stdio MCP forbids embedded newlines in a message. The dispatcher therefore invokes the verb, takes the **envelope object**, and serialises it compactly into `structuredContent`.
2. **The `--text` rendering is produced from the captured envelope, not a second invocation.** `--text` and JSON are mutually exclusive on one CLI invocation, and re-invoking a mutating verb to obtain the other form would apply the edit twice. Both payloads come from one execution: the envelope for `structuredContent`, the renderer applied to it for `content`.
3. **The verb must not write to the real stdout.** dmtool's entry point already takes passed sinks rather than static streams, which is what makes this possible; the dispatcher supplies its own. [CLI-CONFORMANCE-GAPS.md](CLI-CONFORMANCE-GAPS.md) §2 still defers a *wholesale* stdout-purity guard, but now on a measured denominator rather than on absence of observation — **`[ASSUMED-FIXED: C2]`**, see §16. No JSON-promising route was found to contaminate stdout. A wire protocol nonetheless sets a stricter bar than a CLI, which is why the dispatcher supplies its own sinks rather than relying on that measurement holding for every argument mode.

**Concurrency and cancellation are open.** stdio delivers requests on one channel, so overlapping `tools/call` invocations are possible unless dispatch is serialised. This proposal specifies **serialised dispatch for phase 1** — one request at a time — because it is the only posture whose thread-safety is currently established, and because no dmtool verb is long enough to make queueing costly. `notifications/cancelled` must at minimum be accepted and, per the specification, produce no further messages for the cancelled request; with serialised dispatch the practical behaviour is to complete or discard, and in-flight interruption is out of scope for phase 1.

### 5.1 On the official Java SDK

Measured 2026-08-02: `mcp` v2.0.0 (released 2026-06-11) tracks protocol revision **`2025-11-25`**, not `2026-07-28`; `main` is `2.0.1-SNAPSHOT`. Java is a **Tier 2** SDK — Tier 1 (TypeScript, Python, Go, C#) have `2026-07-28` betas. So the SDK cannot deliver the chosen revision today.

More decisively, `mcp-core`'s compile dependencies are `slf4j-api`, `jackson-annotations`, **`reactor-core`**, and `jakarta.servlet-api` (provided). Reactor is not optional. Against dmtool's current dependency set — picocli, Jackson databind, slf4j-nop, the kits — that is a substantial new reflective surface in a binary whose native-image metadata is test-suite-captured, purchased to replace newline-delimited JSON framing.

**Recommendation: do not adopt the SDK for the stdio path.** If it is ever adopted, that is a *hosted-transport* decision, where Streamable HTTP, SSE, and OAuth 2.1 plumbing justify the dependency and native-image is not a constraint.

**Conformance testing, stated accurately.** The official **MCP Inspector** CLI connects to a server, invokes the single method named by `--method`, prints the result, and exits. It is a per-method smoke tool with usable exit codes and documented CI recipes — worth wiring, and it will catch a broken frame or a missing method. It is **not** a conformance suite: it does not enumerate the revision's MUSTs, nor validate `_meta` requiredness, cacheable-result obligations, header rules, or error-code allocation. So the honest position is *smoke-tested per method in CI, plus hand-written assertions for the MUSTs we implement* — not "provably conformant."

## 6. The tool surface

The 81 live leaves render as **17 tools**. Grouping is by target where the CLI has one.

**This does introduce vocabulary, and the proposal should say so.** `ManifestCommand` emits `target` + `op` only for a nested verb; **root-level verbs carry `op` alone, with no target.** So `where-used`, `meta`, `export`, `apply`, `batch`, `patterns`, `operators`, `diagnostics`, `schema`, and `manifest` have no `target` in the op record. Grouping four of them under a `dm.catalog` tool, and giving `dm.apply`/`dm.batch` an `op` where the record has none, are **new groupings invented for this surface**. That is a deliberate, named deviation, not a rendering of an existing shape — which matters in a repository whose first interop rule is never to invent a shape that already exists. The alternatives were one tool per root verb (10 more tools) or a single generic `dm.run`; the grouping was chosen for tool-count economy and is the weakest-justified part of this design. **§15 Q3 puts it to the owner.**

| tool | ops | `readOnlyHint` |
|---|---|---|
| `dm.model` | new, info, check, describe, diff, read, usage, report, rename, normalize, expand, eval, compute, seed, import-jsonschema, export-jsonschema | false |
| `dm.rule` | check, read, explain, deps, format, eval, add, modify, remove, rename | false |
| `dm.computation` | add, read, explain, format, modify, remove | false |
| `dm.field` | add, modify, read, remove, rename, move | false |
| `dm.group` | add, multiselect, attachment, read, modify, remove, rename, move, extract | false |
| `dm.typedef` | add, **modify**, read, remove, rename, extract, inline, import, unimport | false |
| `dm.include` | add, read, set-reference, rename, remove, inline | false |
| `dm.config` | read, modify | false |
| `dm.profile` | extract, synthesize, compare | false |
| `dm.workspace` | list, graph, check, roles | **true** |
| `dm.where-used` | *(no ops — one root verb)* | **true** |
| `dm.catalog` | operators, diagnostics, schema, manifest *(invented grouping)* | **true** |
| `dm.patterns` | *(no ops — one root verb with four argument-selected routes)* | false |
| `dm.meta` | read, modify | false |
| `dm.export` | *(no ops — one root verb)* | false |
| `dm.apply` | *(no ops — one root verb)* | false |
| `dm.batch` | *(no ops — one root verb)* | false |

`typedef modify` is listed on the strength of the live tree — **`[ASSUMED-FIXED: C1]`**, see §16; it was absent from `ManifestVerbTest.EXPECTED_VERBS` when this proposal was written. `dm.where-used` keeps the live verb's hyphen — hyphens are legal in MCP tool names, so renaming it would invent vocabulary for nothing. `dm.patterns` is its own tool rather than part of `dm.catalog` because `patterns --apply` persists to the model, which would make a catalog tool's `readOnlyHint` false; its four routes are argument-selected, not ops. `apply` and `batch` stay separate because CLI-SPEC derives their outcomes differently — `apply` from the transaction, `batch` from the item list.

**Naming.** The `dm.` prefix is a choice, not a requirement. The specification notes that `serverInfo.name` is unreliable for disambiguation and assigns the remedy to **clients and proxies** ("SHOULD implement a disambiguation strategy such as prefixing tool names with a server identifier"); it does not ask servers to self-prefix. Under a Claude Code plugin the host scopes names anyway, as `mcp__plugin_<plugin-name>_<server-name>__<tool>` (*host docs*). The prefix is retained only for direct-connect hosts that do neither.

### 6.1 Why target-shaped, and not one tool per verb

**The primary argument is that the grouping already exists for nested verbs.** Ten of the twelve targets are real op-record targets, so `{op, args}` under a target tool is a faithful rendering for 71 of 81 leaves. The invented part is confined to the root verbs named above.

**A secondary argument, with its limits stated.** Client guidance recommends **progressive discovery** once tool definitions occupy a large share of the context window, at which point the host stops injecting definitions and supplies a `search_tools` meta-tool. Retrieval may be keyword, embedding, subagent, or hybrid, and providers offer native tool search. Fewer, denser tools are less likely to trip that threshold, and a surface whose thesis is discoverability is better served by being injected whole than by being retrieved into.

That argument is **weaker than an earlier draft claimed**, in two ways worth keeping visible. The threshold is an example range, not a specification, and this proposal has not measured its own token cost (§4). And the 2026-08-03 lexical probe shows only that *substring* retrieval over verb text fails on intent phrasing — it says nothing about the embedding, subagent, or provider-native strategies the same guidance names, which are the ones a capable host is likely to use. So: a real consideration, not a decisive one.

**Consequence for authoring:** put each tool's op list in its **description**, not only in its `inputSchema`, so the discriminating words sit in the ranked text if retrieval does engage.

**Division of labour:** dmtool publishes good names and descriptions; semantic intent-to-verb retrieval belongs to the host. A `dmtool docs` verb was assessed on 2026-08-03 and demoted, both for want of a measured failure and because substring search would not have closed the gap.

### 6.2 Input schemas

`inputSchema` is a `type: "object"` root — required by the `Tool` signature — with `{op?: <enum>, args: <object>, model?: string, workspace?: string}`; `op` is absent for the single-route root-verb tools. The revision permits any JSON Schema 2020-12 keywords, so a `oneOf` discriminated on `op` is legal alongside that root type — but **`args` stays loosely typed on purpose**. Per-op argument validation is what dmtool's corrective diagnostics already do well, and a large discriminated union degrades model tool-calling accuracy for no correctness gain. The per-op contract stays discoverable through `dm:///schema/{target}/{op}`.

Each `op` enum value carries a description stating that route's effect — the route-level fact `annotations` cannot express (§7).

## 7. Annotations — honest and coarse, by necessity

`ToolAnnotations` defaults are `readOnlyHint` **false**, `destructiveHint` **true**, `idempotentHint` false, `openWorldHint` true. So for the three read-only tools and for `openWorldHint` everywhere, the block must be present — silence would assert the opposite of the truth. For the other fourteen tools, `readOnlyHint: false` with `destructiveHint` at its default is semantically identical to omitting it; emitting it explicitly is documentation, not information.

Precision is not available. Effect in dmtool is selected by **arguments**, not by verb: `meta` picks its route by the presence of write flags, `patterns` has four argument-selected routes (read, verdict, rejected scaffold, write), `batch` dispatches mutating ops without carrying `--dry-run` itself, and six artifact writers — `model new`, `model expand`, `model import-jsonschema`, `model export-jsonschema`, `profile extract`, `profile synthesize` — write via `--output` with no `--dry-run`. A per-tool `readOnlyHint` boolean over a route-dependent fact would be a **false statement**.

**On the untrusted-server caveat.** The specification says clients "should never make tool use decisions based on `ToolAnnotations` received from **untrusted** servers." A plugin-installed server is a trusted server, so that caveat does **not** license imprecision here. The reason for coarseness is the route-dependence above, full stop.

**Therefore:**

- `readOnlyHint: true` only where unconditionally true — `dm.workspace`, `dm.where-used`, `dm.catalog`. All three were checked route-by-route: no `--output`, no `--out-dir`, no `--dry-run`, no write call, and `workspace roles` reads its YAML as input only.
- `readOnlyHint: false` elsewhere; `destructiveHint` at its default.
- `openWorldHint: false` on every tool — dmtool touches local files and nothing external. The one hint statable with certainty.
- `idempotentHint` **omitted** — not crisply statable per verb, and its default costs nothing.
- Route-level effect lives in the `op` enum descriptions, mirroring what `schema patterns` already does for its four routes.

**Whether this needs new CLI metadata is an open question, not a settled one.** A per-verb `sideEffect`/`readOnly` axis is a recorded deferral — [CLI-CONFORMANCE-GAPS.md](CLI-CONFORMANCE-GAPS.md) §2, demoted 2026-07-21 after a cold-agent probe found side-effect inferable from `--help` for 5 of 6 sampled verbs. Two things pull against simply declaring the trigger unfired. First, the probe measured an agent reading **per-verb `--help` with its flag set**; under this proposal the agent reads a **tool description with an op list**, and §6.1's own argument is that surface shape determines what the model sees — so the basis does not transfer without a discriminating case, which this proposal does not have. Second, the trigger explicitly includes *"a safety incident argues for making the inferred axis explicit (it's a guardrail, so the bar to proceed is lower)"* — and the naming consequence below is a new safety observation. **§15 Q2 puts it to the owner** rather than settling it by assertion.

**The residual risk is naming.** Client guidance describes categorical approval keyed on tool *name* — *"allow `ticketing_createIssue` for this script run"*. That sentence sits in the **Programmatic Tool Calling / Code Mode** security section, describing approval of calls made by model-generated code in a sandbox, and it adds that the broker must still evaluate each call against the grant; it is not documented as generic host behaviour. The underlying concern is nonetheless real: under target-shaped tools, one approval of `dm.rule` spans `rule read` and `rule remove`. No annotation is involved — the bundling comes from the name.

Whether that is cheap to revisit is **not** established. An earlier draft argued `tools/list` is re-fetched each session rather than pinned; but list results are explicitly cacheable, guidance tells hosts to memoise definitions, a rename invalidates prompt caches and any user allowlist, and a tool name in a published server is a public contract. Treat a rename as a breaking change, and choose names once.

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

`dm:///help/{target}/{op}` is a **projection of the existing manifest record**, computed in process — no CLI change and no new documentation source. A `dmtool docs` verb was assessed and demoted; addressable resources are a rendering affordance of the protocol, exactly as a tree-walked `--help` is one of a CLI.

`server/discover` returns `instructions` — in this revision a cacheable field on a mandatory RPC rather than part of a handshake that no longer exists. It holds a **router, never content**: *"DocumentModel authoring over `{op, args}`; `dm:///help/{target}/{op}` for verb detail; `dm:///operators` for the DSL vocabulary."*

**Prompts are not the skill channel.** The specification defines prompts as *user-controlled* — explicitly selected by the user, typically via user-initiated commands. A skill is model-invoked on relevance. Different trigger model; do not conflate.

## 9. Model addressing

Over stdio the server shares a filesystem with the user, so `model` and `workspace` are **paths**, carrying `-m`/`-w` semantics unchanged, including the `-w > $DMTOOL_WORKSPACE > model directory` precedence. No new concepts, and it is what the specification recommends (§2, the Roots deprecation).

**Every tool call re-reads the model from disk. There is no warm model and no resolved-model cache.** A hard constraint, not a performance note — it is the condition under which §3's argument and R3 hold.

**Path handling must be explicit, because the model supplies the path.** The specification requires that servers sanitise file paths against traversal and validate all tool inputs. For a local CLI the user owns the shell and traversal is not a threat; for an MCP server the *model* chooses the string, which is a different trust boundary. Phase 1 therefore normalises and resolves both `model` and `workspace`, rejects a path that escapes the workspace root when one is set, and reports the rejection as a tool-execution error with the searched roots named — the same corrective shape the CLI already uses for an unresolved reference. Whether a stricter confinement (an explicit allow-list root, independent of `-w`) is wanted is an implementation choice within this contract.

**On `--output` versus `--out-dir`:** not an inconsistency. `--output`/`-o` selects one file; `--out-dir` selects several — `export` writes four artifacts, `model import-jsonschema --out-dir` imports a bundle into multiple models. `export` therefore carries `--out-dir` and is **not** one of the six `--output` writers listed in §7. All six default to stdout and write a file only when the flag is given, so `dm.export` and the six writers are argument-dependent in effect, like `meta` and `patterns`.

## 10. Packaging and the skill

Claude Code plugins bundle skills **and** MCP servers in one plugin — `.mcp.json` at the plugin root or inline `mcpServers` in `plugin.json`, with `${CLAUDE_PLUGIN_ROOT}` substitution (*host docs*). Plugin servers start automatically when the plugin is enabled, appear as ordinary MCP tools, and pass the same per-server approval as a project `.mcp.json`. So [`plugin/`](../plugin/) gains one file beside the existing `skills/`; no new distribution mechanism and no second marketplace entry.

This is also the established pattern: the MCP documentation ships its **own** development guidance as skills in a Claude Code plugin (`anthropics/claude-plugins-official` → `mcp-server-dev`). Agent Skills is a separate portable open standard; skills and MCP compose at the packaging layer, and MCP itself has no skill concept in any revision.

**The skill stays where it is.** For plugin-capable hosts the plugin's own skill already delivers it — already built, already measured, and the server need not serve it at all. **Serving the skill over MCP is deliberately excluded from phase 1**, because an earlier draft described a mechanism that does not work: the server lives inside the binary, which `ensure-dmtool.sh` delivers *alone* into a version-keyed cache with no `SKILL.md` beside it, and `sync-plugin-assets.sh` copies assets into **plugin trees**, not into a resource set the binary can read. The only routes are bundling the skill into the binary — which would collide with the skill/binary version-skew contract in [RELEASE-SPEC.md](RELEASE-SPEC.md) §6 — or reading it from the plugin directory via an environment variable the server would have to be given. Both are decisions, not details; they ride **§15 Q4** together with the eval-boundary question, and phase 1 ships without `dm:///skill`.

## 11. Risks and constraints

**R7 — no evidence the intended host implements `2026-07-28`.** §5.1 establishes that the Java SDK tracks `2025-11-25` and that Tier-1 SDKs have betas; neither says what a *host* speaks, and Claude Code's MCP documentation states no protocol revision. A `2026-07-28`-only server has no fallback for a legacy client: the spec places the backward-compatibility probe obligation on the **client**, so a client that opens with `initialize` meets a method the server does not implement. **This is the finding that decides whether phase 1 works, and it is cheap to settle** — one connection against the real host once a skeleton exists. §15 Q1.

**R1 — binary presence at plugin-enable time.** Binary delivery is deliberately on-demand and skill-triggered (`ensure-dmtool.sh`), because a `SessionStart` hook never fires in the session where `/plugin install` ran ([RELEASE-SPEC.md](RELEASE-SPEC.md) §6). But a plugin MCP server's `command` must resolve **when the plugin is enabled**, before any skill has run. The workable route is a small wrapper as the `command` — resolve-or-download, then `exec "$BIN" mcp` — which moves a network download inside the MCP startup window. First-enable latency and failure handling become part of the contract. The largest concrete engineering risk, and the only place this proposal touches a settled RELEASE-SPEC decision.

**R2 — MCPB is unresearched.** The MCP documentation names **MCP Bundles** (`github.com/modelcontextprotocol/mcpb`) as the deployment path for a local server that must touch the user's machine, packaging server and runtime as one `.mcpb` archive; the plugins reference mentions importing from an MCPB/DXT bundle (*host docs*). dmtool has no runtime to provision, so MCPB's headline benefit is partly moot — but it may resolve R1, since the archive ships the executable. **Read the MCPB specification before committing to the wrapper.**

**R3 — the `dmtool serve` won't-build.** [FEATURE-GAPS.md](FEATURE-GAPS.md) §4, decided 2026-06-15, with an explicit *"don't re-litigate without new evidence."* §3 classifies its five grounds and answers all of them; the honest summary is that the session ground no longer applies on new evidence, and the second-mental-model ground is reduced rather than eliminated. **Conditional on §9's no-warm-model rule.** Measure the per-call parse cost before any cache is even discussed.

**R4 — native image.** The MCP command adds code and Jackson usage to the shipped binary, so the test-suite-captured reflection metadata stales. Re-capture before any native build, then run the native gate ([NATIVE-IMAGE-GRAALVM.md](NATIVE-IMAGE-GRAALVM.md) §5.1). Keeping the transport hand-rolled and Jackson-tree-based keeps this cost near zero; adopting the Java SDK would not (§5.1).

**R5 — eval material boundary.** [EVAL-SPEC.md](EVAL-SPEC.md) §2a defines a **bare-tool** arm where agents get the self-describing CLI and the model file, with **no skill** — the measurement that separates the tool's self-sufficiency from the skill's coverage. Excluding `dm:///skill` from phase 1 (§10) keeps that arm intact for now, but if the skill is ever served the arm does not exist for MCP unless the server can withhold it. §15 Q4.

**R6 — host support for resources is unverified.** Support for `resources` has historically lagged support for `tools` in some clients, but nothing in the official documentation characterises that. If it holds for a target host, `dm.catalog` must carry everything and the resources become an optimisation. Verify against the intended host before relying on resource-only delivery.

## 12. Scope

**The Required list below is a sequence, not a set.** `CommandReferencesResolveTest` resolves every command reference in `cli/src/main`, the rulekit catalog, the root and plugin READMEs, `skill/SKILL.md`, the Codex `AGENTS.md`, and the eval workflow against the **live** command tree — `docs/` is deliberately not scanned, which is why this proposal may name `dmtool mcp` while no such verb exists. So the verb must exist in the picocli tree **before** any command example naming it enters one of those seven surfaces, or the build fails. That is the same ordering the CLAUDE.md schema-discipline table already imposes on a new verb (flip `ManifestVerbTest.EXPECTED_VERBS`, add a `verb-examples.json` demonstration, satisfy the conformance matrix, then update the docs), so the two compose — but the implication is that the user-facing documentation of this surface lands last, not alongside.

**Required (phase 1)**

- The MCP command in `:cli` (§5); the `McpDispatcher` seam; serialised dispatch.
- `server/discover` with `supportedVersions`, `capabilities`, `serverInfo`, `instructions`, `ttlMs`, `cacheScope`.
- `tools/list` — the 17 tools of §6, **generated from the live picocli tree**, with `ttlMs`/`cacheScope` and deterministic ordering.
- `tools/call` — compact-serialised envelope into `structuredContent`; the `--text` rendering of that same envelope into `content`; **`outputSchema` only on `returns:"result"` routes** (§4); `isError: true` for `rejected`/`refused`/`error`; JSON-RPC `-32602` for an unknown tool or op.
- `resources/list`, `resources/templates/list`, `resources/read` for the §8 URIs.
- Annotations exactly as §7 specifies.
- Path normalisation and workspace-escape rejection (§9).
- A conformance guard that the tool set matches the live command tree, in the `ManifestVerbTest` family; MCP Inspector smoke checks in CI plus hand-written assertions for the MUSTs implemented (§5.1).
- Plugin wiring per §10, including R1's resolution.

**Optional**

- Per-tool `title` annotations.
- Response streaming for long `model check` runs on large workspaces.

**Excluded — deliberately**

- Multi Round-Trip Requests, `subscriptions/listen`, sampling, roots, logging, and `io.modelcontextprotocol/tasks`. Nothing in dmtool asks the client for anything, pushes change notifications, or is known to exceed a comfortable synchronous budget — measure before adopting tasks.
- The official Java SDK on the stdio path (§5.1).
- Any warm model, resolved-model cache, or session state (§9, R3).
- Any new CLI verb; any new CLI metadata (§4 — the `returns` classification already exists).
- `dm:///skill` and any excerpt of the skill into `instructions` (§10, §15 Q4).
- In-flight cancellation of a running request (§5).
- Concurrent dispatch (§5).
- **MCP Apps** — phase 2, whose trigger §1 records. Land the surface first.
- **Hosted HTTP** — phase 3, §13.

## 13. Phase 3 — hosted, and why it is deferred

**The gate is licensing, and it is one paragraph.** EUPL-1.2 already applies to the shipped bundle: [LICENSING.md](LICENSING.md) sets the rule as *"EUPL-1.2 for any artifact in which we bundle the kernel"*, so distributing the binary — and distributing a container image — changes nothing. What **hosting** adds is that Article 1 counts *"providing access to its essential functionalities"* as Distribution and/or Communication, so the copyleft and source-availability clauses attach to a service that hands out **no copies at all**, and the hosted combined work must be offered under EUPL-1.2 with a maintained source repository. Hosting also makes the operator a **GDPR processor** of customers' document models. Neither is reversible. *(This is engineering analysis, not legal advice — the same caveat LICENSING.md carries.)*

**Infrastructure is not the constraint** and is deliberately not detailed here, to avoid a vendor snapshot rotting inside a proposal. The shape that was examined on 2026-08-02: a European serverless-container product with a 60-minute request ceiling, HTTP/2, scale-to-zero, and single-digit-euro monthly cost at the expected load, whose provider is EU-owned and outside the US CLOUD Act, ISO 27001 and HDS certified, **and not SecNumCloud-qualified** (assessment ongoing as of that date). If phase 3 is ever taken up, re-measure limits, pricing, and certification status then; a figure recorded now would be stale by the time it mattered.

**If phase 3 happens**, model state moves to server-minted opaque handles passed as ordinary tool arguments (the specification's own non-normative "Stateful Tools" guidance), authorization becomes OAuth 2.1 resource-server, and the Java SDK becomes worth reconsidering for that transport only.

## 14. How this stands against decisions already recorded

Prose rather than a table, because a status board over other documents' decisions is exactly what [DOC-DISCIPLINE.md](DOC-DISCIPLINE.md) §5 forbids — it duplicates the owning surface and goes stale with no guard. Each owning document remains authoritative; what follows is only this proposal's argument for why it does not contradict them.

**[RELEASE-SPEC.md](RELEASE-SPEC.md) §6 and §12 rejected MCP, on grounds this proposal must answer rather than omit.** §6: *"not MCP — wrong model for a local offline CLI, and it would undercut the self-describing-CLI thesis."* §12: *"Rejected as the primary path (same reasoning as §6's vehicle choice): it adds a running process and undercuts the offline self-describing-CLI thesis."* On "adds a running process": true, and R1 is the concrete cost of it. On "undercuts the self-describing-CLI thesis": the strongest counter-argument this proposal has is that the tool's self-description is *derived* — `tools/list` and every resource are recomputed from the same picocli tree `manifest` walks, so the MCP surface cannot describe the tool differently than the CLI does. What MCP genuinely does not preserve is **offline zero-install simplicity**: it adds a process, a lifecycle, and a second name vocabulary (§6). Neither §6 nor §12 is repudiated here — the plugin remains the primary vehicle, and MCP is proposed as an additional surface, which is the shape §12 left on the table.

**[FEATURE-GAPS.md](FEATURE-GAPS.md) §4's `dmtool serve` won't-build** is addressed ground by ground in §3, and the residue is stated there rather than dismissed. R3 records the condition.

**[CLI-CONFORMANCE-GAPS.md](CLI-CONFORMANCE-GAPS.md) §2's demoted `sideEffect` axis** is *not* declared unfired by this proposal; §7 gives two reasons the demotion's basis may not transfer and routes the decision to §15 Q2. Its deferred **stdout-purity** entry now records the measured denominator rather than an absence of observation — **`[ASSUMED-FIXED: C2]`**, see §16. §5 explains why a wire protocol raises that bar regardless, which is why the dispatcher supplies its own sinks.

**[CLI-CONFORMANCE-GAPS.md](CLI-CONFORMANCE-GAPS.md) §3's remote/async cluster** stays void, contrary to an earlier draft. §3 scopes those recommendations to a *resident or remote* mode; a local, synchronous, stateless-per-call stdio server is the posture §3 already describes dmtool as having. Two specific corrections: pagination is **not** answered by `ttlMs`/`cacheScope` — the list endpoints remain cursor-based and the two mechanisms are orthogonal (§2); and trace context is not "free" — `_meta` carries client-supplied OpenTelemetry conventions, while the voided recommendation was `--trace-id` *plus a correlated audit log*, which MCP neither supplies nor obliges.

**[MODEL-REVIEW-GAPS.md](MODEL-REVIEW-GAPS.md) §2's MCP Apps widget** re-opens when dmtool *ships* as an MCP server. Phase 1 shipping fires it; the widget is phase 2 (§12).

## 15. Open for owner decision

**Q1 — which revision, given no evidence the host speaks `2026-07-28`?** Target `2026-07-28` only, implement a `2025-11-25` fallback alongside it, or verify the host first and decide after. Recommendation: **verify first** — it is one connection test against the real host once a skeleton exists, and a wrong answer means phase 1 does not work. This is R7 and it gates scheduling.

**Q2 — does this fire the `sideEffect` trigger?** §7 gives two reasons the 2026-07-21 demotion's basis may not transfer: the agent reads a tool description rather than per-verb `--help` with its flag set, and the trigger's own lower bar for a guardrail may be met by the one-approval-spans-read-and-remove observation. Recommendation: **not yet** — no consumer failure has been measured, and MCP's per-tool boolean could not carry a route-dependent fact even if the axis existed. But it is a judgement, and §7 declines to settle it by assertion.

**Q3 — is the invented root-verb grouping acceptable?** `dm.catalog` groups four root verbs that have no target, and `dm.apply`/`dm.batch`/`dm.export`/`dm.patterns`/`dm.where-used`/`dm.meta` are tools over verbs the op record gives no target. Alternatives: one tool per root verb (27 tools total), or a single generic `dm.run` for all root verbs (12 tools). Recommendation: **keep the grouping but drop `dm.catalog`'s invented op names**, exposing it as one tool with an argument-selected route like `dm.patterns`. This is the weakest-justified part of the design and the owner should see it named.

**Q4 — the skill over MCP, and the eval boundary.** Phase 1 excludes `dm:///skill` because the mechanism an earlier draft described does not work (§10). If serving it is wanted, the routes are bundling into the binary (colliding with RELEASE-SPEC §6's version-skew contract) or reading it from the plugin directory via an environment variable. Either choice also decides whether MCP has a bare-tool eval arm (R5). Recommendation: **stay excluded** until a bare-MCP host is an actual consumer.

## 16. Assumed-fixed CLI-side dependencies

Two statements above are written as if a CLI-side fix has landed that had **not** landed when this proposal was revised (2026-08-03). Both were surfaced by the same review that produced this revision, dispositioned as real gaps, and handed to the session that owns `cli/`. They are marked inline as **`[ASSUMED-FIXED: C<n>]`** so a reader never mistakes a forward assumption for a measured fact, and so verification is one `grep -n 'ASSUMED-FIXED' docs/MCP-SUPPORT-PROPOSAL.md` away.

**When the fix lands, verify and then delete the marker** — leaving it in place after the fact is the same rot as an unmarked assumption, in the other direction.

| marker | assumed to have landed | referenced at | how to verify | if it did **not** land |
|---|---|---|---|---|
| **C1** | `ManifestVerbTest.EXPECTED_VERBS` contains all **81** live leaves including `typedef modify`, asserted by **set equality** rather than containment; `CliSpecVerbTableTest` compares exact `(target, op)` pairs rather than a global op-token projection | §4 *Measured* first bullet; §6 note under the tool table | `grep -c '"' …/ManifestVerbTest.java` is not the check — read the assertion: it must be equality, and the list must hold 81 entries. Confirm `typedef modify` appears in it and in the `docs/CLI-SPEC.md` typedef row | §4's claim that `EXPECTED_VERBS` is a valid cross-check is false, and §6's op lists must be re-derived from the live tree rather than cross-checked against the pin. The tool table itself stays correct either way — it was derived from the tree, not the pin |
| **C2** | `docs/CLI-CONFORMANCE-GAPS.md` §2's stdout-purity entry records the measured denominator (all 81 leaves swept, 79 emitting exactly one JSON value, `schema` bare / `export card` / `export --out-dir` intentionally raw or empty) and notes that `--text` totality and the unexpected-exception route are already guarded | §5 framing consequence 3; §14 paragraph on `CLI-CONFORMANCE-GAPS.md` §2 | read the §2 entry: it must name the sweep and its exemptions, not rest on "no stdout contamination has been observed" | Both sentences overstate the ledger. The **design** is unaffected — §5 already requires the dispatcher to supply its own sinks rather than trust ambient purity — but the citation would need to say the entry is unamended and the sweep lives only in this proposal's §4 |

Neither assumption is load-bearing for the contract in §6–§12. C1 affects whether an independent cross-check exists for the tool table; C2 affects only how a ledger entry is characterised. **No third dependency exists** — the other four findings handed over (the `--output`/`--out-dir` split, the stale byte figure, the native-profile dead state, and a correction to an earlier lexical-probe record) were dispositioned in ways this proposal does not rely on, and the lexical-corpus correction is already applied in §4 with both corpora stated.
