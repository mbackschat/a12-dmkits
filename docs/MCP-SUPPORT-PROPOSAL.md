# dmtool over MCP — proposal

**Status:** PROPOSAL (2026-08-03, revised after cold review). Intent, rationale, and public contract; nothing built. Graduates into the owning specs as phases land, per [DOC-DISCIPLINE.md](DOC-DISCIPLINE.md) §5.

**Scope.** Expose the dmtool surface over the Model Context Protocol, revision **`2026-07-28`**, as a **stdio server inside the existing binary** (`dmtool mcp`). Hosted HTTP and MCP Apps are named as later phases with their own gates; they are not proposed here.

**Owner decisions already taken** (2026-08-02): stdio-first; target `2026-07-28` hand-rolled, with the official Java SDK kept as a possible later substitution; target-shaped tools rather than one-per-verb; filesystem paths for stdio, server-minted handles only if hosting ever happens. **Hosting requirements — with "never silently enter a paid tier" as the top-priority one — and the rejection of the first candidate against them are recorded 2026-08-03 in §13.5.** **§15 carries four questions that must be answered before implementation starts**, one of which (Q1) can invalidate the revision choice.

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

## 13. Hosting and deployment — requirements, shapes, and the assessment so far

**Why this is here at length.** EU-sovereign hosted access is where the idea started, and the assessment below is the part that took research rather than design. Phase 1 depends on none of it, but deleting it would mean re-deriving it later, which is the more expensive mistake. What is deliberately *not* here is a per-unit pricing table — those are the numbers that rot and they were never the decision input. The **free allowance** is a different matter: free hosting is a stated owner requirement (§13.5), so the allowance is a design constraint and is recorded as one.

**Two questions this section settles**, because they are asked of any hosted MCP surface and the protocol answers neither:

- **Tenancy is not an MCP concept, and cannot be.** The protocol is deliberately silent about who is calling — `2026-07-28` removed *sessions*, not *identity*. Identity can therefore only arrive as a **per-request credential from the transport**, which is not a workaround for statelessness but the only form identity takes in it, exactly as in any stateless HTTP API. With OAuth 2.1 the tenant is the access token's subject, re-established on every call. **Without authentication there are no tenants and there cannot be**, which leaves two coherent designs and no others: a read-only endpoint, or per-handle ephemeral workspaces where the handle *is* the capability. Per-shape resolution and the handle contract: §13.8.
- **Writing over a network endpoint is an authorization question, not a file-atomicity one.** §5's serialised dispatch already forbids two mutations interleaving inside one process, so the read-modify-write race belongs to two shells rather than to a server — an earlier draft of §13.7 had this wrong. What survives is that an **unauthenticated mutating endpoint is open by construction**, plus a narrower durability exposure on abrupt termination. Ordered by significance, with the source-verified basis: §13.7.

Both answers point the same way: **withholding mutation removes more risk than securing it adds** (§13.8), which is why S4 is specified read-only.

### 13.1 Three deployment shapes

| | shape | licensing delta | ops burden | who answers the sovereignty question |
|---|---|---|---|---|
| **S1** | `dmtool mcp` — stdio server inside the existing binary (**phase 1, what this proposal contracts**) | none; a local artifact under the posture [LICENSING.md](LICENSING.md) already sets | none | nobody has to — nothing leaves the machine |
| **S2** | Publish a **container image**; the customer runs it in their own tenancy | none; distribution, exactly as the binary is today | theirs | theirs, by construction |
| **S3** | **We host it** as a service | the hosted combined work must be offered under EUPL-1.2 with source (§13.2) | authentication, tenancy isolation, a DPA, retention, on-call | ours, and it is a business question rather than a technical one |
| **S4** | **A public demonstration endpoint**, preferably **read-only** (§13.8) — no customer data, no availability promise, existing to be tried rather than relied on | same clauses as S3 attach, but with no customer models there is no processor role | best-effort only | ours, but with nothing at stake |

**S4 was added 2026-08-03** because a candidate arrived that fits it and fits nothing else (§13.6). It is worth distinguishing from S3 precisely because the two have different *obligations*, not merely different scale: the moment an endpoint holds a customer's document model, §13.2's processor role attaches and platform durability becomes a contractual concern rather than an inconvenience.

**Recommendation: build S1, offer S2 as the answer to "can we have it hosted", and treat S3 as a separately justified product decision.** In order of weight:

1. **S1 is most of the code for S3 and none of the risk.** Statelessness is what makes that true — there is no session layer to build twice, so the transport is the only difference (§3, §5).
2. **S2 answers EU sovereignty better than S3 does.** A customer who cares about digital sovereignty most wants the thing running in *their* tenancy, under their own jurisdiction and their own certifications. Shipping an image is a build-pipeline change; hosting is an operation with a licensing and processor posture attached.
3. **S3's real cost is not infrastructure.** It is the licensing posture and the processor role in §13.2, neither of which has an undo.

**The owner requirements for S3 are recorded in §13.5, and they do not change this recommendation.** Note in particular that running for **free** still means *operating a service*, so EUPL-1.2 source availability and the GDPR processor role attach in full. Those obligations are indifferent to the invoice. A free tier removes the cheapest objection to S3 and leaves both expensive ones standing, which is worth being explicit about, because "it costs nothing" reads like "it commits to nothing" and does not.

**GitHub Actions is not a hosting option.** It appeared in the original framing as an alternative to a serverless-container product, and it is not one: CI jobs are ephemeral, with no inbound endpoint and no stable URL. It is the right place to *build the image and deploy it*, and the wrong place to *run* the server. Worth stating plainly so it is not re-proposed.

**The question that actually decides S2 versus S3 is "who is the consumer" — us and our team, BAs at customer sites, or a public service.** That is an owner question, but it is not a phase-1 blocker, which is why it is recorded here rather than in §15.

### 13.2 The gate is licensing, not infrastructure

EUPL-1.2 already applies to the shipped bundle: [LICENSING.md](LICENSING.md) sets the rule as *"EUPL-1.2 for any artifact in which we bundle the kernel"*, so distributing the binary — and distributing an S2 container image — changes nothing. What **hosting** adds is that Article 1 counts *"providing access to its essential functionalities"* as Distribution and/or Communication, so the copyleft and source-availability clauses attach to a service that hands out **no copies at all**, and the hosted combined work must be offered under EUPL-1.2 with a maintained source repository. Hosting also makes the operator a **GDPR processor** of customers' document models — their rules, field names, and domain structure are their business IP — which pulls in a DPA, a retention policy, and handle-scoped authorization. Neither consequence is reversible. *(Engineering analysis, not legal advice — the same caveat LICENSING.md carries.)*

Dropping to a kernel-free `STRUCTURAL_ONLY` backend to escape the clause is not a real option: it removes the consistency oracle, which is the most valuable thing dmtool does.

### 13.3 Sovereignty — the assessment, and the one place it does not reach

Scaleway was assessed because it was recommended on sovereignty grounds, and **it is out** — rejected on its billing model rather than on sovereignty, which it largely satisfies. The disposition and the requirements it produced are §13.5. This assessment is kept rather than deleted because it is dated research, because the requirements were derived from it, and because the sovereignty findings below apply to any European candidate.

Read from the vendor's own security-and-compliance page and a procurement announcement on **2026-08-02**:

- **Satisfied:** 100% European infrastructure operating under EU jurisdiction, stated by the vendor as *"free from the US CLOUD Act"*; **ISO/IEC 27001:2022** and **HDS** (health-data hosting) certified; selected in April 2026 under the European Commission's **Cloud III Dynamic Purchasing System**, a €180 M programme for EU institutions, after evaluation under the Commission's Cloud Sovereignty Framework.
- **Not satisfied: SecNumCloud is not held.** The vendor's own page says *"currently undergoing qualification."* If a customer's requirement is literally SecNumCloud, this provider does not meet it. **And the substitution is not like-for-like:** the providers that do hold the qualification offer no equivalent serverless-container product, so satisfying SecNumCloud changes the deployment shape to managed VMs — a different operational proposition, not a different vendor line-item.

**So the honest verdict: sound and cheap for GDPR and EU-jurisdiction sovereignty; not a choice for SecNumCloud-regulated workloads today.** Which of those two is the actual requirement is an owner question. Note that under S2 it stops being ours to answer at all — a further reason S2 is the better sovereignty story.

### 13.4 Technical fit, and the product choice inside the vendor

Serverless **Containers** fit the workload: the request ceiling is an hour, HTTP/2 is supported, instances scale to zero when idle, and the image budget is generous against a **33 MB / ~90 ms** native binary ([NATIVE-IMAGE-GRAALVM.md](NATIVE-IMAGE-GRAALVM.md)) — a near-ideal cold-start profile for a scale-to-zero product. Two things to check at the time rather than now: images must target `linux/amd64` only, so the release pipeline needs an amd64 build for this path even if the maintainer's machine is ARM; and the platform offers two sandbox generations, the stricter of which restricts syscalls, so the native binary must be tested on it rather than assumed to run.

Serverless **Functions** are the wrong product here — per-instance concurrency of one and a small request-payload cap make them event handlers, not a JSON-RPC endpoint. Recording the rejection because "serverless" reads as one choice when it is two.

Two facts here generalise past the rejected vendor and are worth carrying into any future assessment: **an amd64 image is needed** whatever the platform, since the maintainer's machine is ARM; and **process start is not container start**, so the 33 MB / ~90 ms figure is a floor for cold-start latency rather than a measurement of it. Everything else in this subsection is a dated snapshot — **re-measure against whatever candidate is actually chosen.**

### 13.5 Owner requirements for any hosted deployment, and why Scaleway is out

**Recorded 2026-08-03.** These govern any future hosting candidate. The assessment above is the worked example that produced them, not a shortlist.

| # | requirement | |
|---|---|---|
| **H1** | **Never silently enter a paid tier.** The maximum possible charge must be bounded **by construction** — either a platform-enforced spend ceiling, or a configuration whose worst-case consumption is provably inside a free allowance. Monitoring, alerting, and after-the-fact shutdown do **not** satisfy this. | **top priority** |
| **H2** | **The bound must hold under hostile traffic.** A flood must not be able to raise the bill, and the safe setting must not depend on the platform's defaults being sane. | **top priority** |
| **H3** | **Service unavailability is preferred over any charge.** On exhausting the allowance the service may stop; it may not bill. | required |
| **H4** | **Free at the expected load** — at most a few thousand calls per day, normally arriving within a 4–8 hour window. | required |
| **H5** | **EU jurisdiction and GDPR compliance**, which is the motivation the idea started from. SecNumCloud qualification only if a customer's requirement is literally SecNumCloud (§13.3). | required |
| **H6** | If S3 is taken up at all, **EUPL-1.2 source availability and the GDPR processor role are accepted** (§13.2). | gate |

The no-warm-model rule is deliberately **not** a row here. It is a design invariant that holds under every transport and phase, stated in §9 and conditioned on by R3; hosting neither creates nor relaxes it, and §13.6 records only that handles do not license a cache. Listing it as a hosting requirement would misattribute it.

**The acceptance test a candidate must pass: can the maximum possible monthly charge be stated as a number derived only from settings we control?** If it cannot, the candidate fails H1, whatever else it offers.

**Why Scaleway fails that test.** Verified 2026-08-03 against the vendor's billing-alerts and billing-FAQ pages:

- The free tier is an **allowance deducted on the invoice**, not a ceiling — the invoice carries a "Free Tier" section showing the cost it saved. Consumption beyond it is billed pay-as-you-go automatically.
- **Billing alerts only notify.** A monthly budget in euros plus a percentage threshold, delivered by SMS, e-mail, or webhook, up to ten alerts. Nothing in the documentation stops, throttles, or caps usage.
- There is **no spending limit and no prepaid mode** — prepaid cards are explicitly rejected as a payment method, and payment is auto-initiated from the registered card at the start of each month.

A cost-triggered webhook cannot close the gap either: inside the allowance the cost is €0, so a *cost* threshold necessarily fires **after** billing has begun. It bounds exposure to cents, not to zero, and it is a moving part that can fail.

**The exposure this creates is the H2 case, and it is worth quantifying.** Charges are allocated tier × run duration × instances, so a flood cannot spend more than the ceiling we configured — the bound comes from our settings, not from attacker volume. But the published ceilings are high: maximum scale runs to 50 instances, so 50 instances pinned for a month is on the order of **€1,900 at the default tier** and **€11,000 at the maximum tier**. Two details make this a real risk rather than a theoretical one: the **defaults are not safe**, so maximum scale must be lowered by hand; and **rejecting a request still consumes run duration**, so authentication narrows the blast radius without removing it. Combined with alert-only detection, an unnoticed weekend flood at default settings is a three-figure invoice.

**One configuration would have satisfied H1 and H2, and is recorded because it is the shape any candidate must offer.** At the platform floor — **70 mvCPU / 128 MB with maximum scale capped at one instance** — continuous operation for a 31-day month consumes ~187,000 vCPU-s and ~335,000 GB-s against a 200,000 / 400,000 allowance. Since consumption is exactly tier × time and time cannot exceed the month, exceeding the allowance is **arithmetically impossible** regardless of traffic, and a flood becomes cost-neutral: it degrades availability, which H3 already permits. The cap at one instance is the load-bearing half — parallel instances consume the allowance in parallel and void the bound.

It did not save the vendor, for three reasons: whether the allowance is scoped per container or account-wide — and therefore shared with any other workload — is not settled by the documentation; its guarantee is *our* arithmetic rather than the platform's promise, so a change to the allowance or the tier floor silently breaks it; and 70 mvCPU is ~7% of a core as a ceiling rather than a reservation, which makes every CPU-bound call take seconds.

*Corrected 2026-08-03:* this passage previously listed "whether the native binary runs inside 128 MB" as simply unsettled. It is now measured (§13.6) and the answer is split: **model-only** work peaks at ~43 MiB and would have fitted, while **document evaluation** can exceed 500 MiB on a sub-megabyte document, which 128 MB could not have held without a pinned `-Xmx` and a lowered input limit. So that floor tier was viable for authoring and inspection and not for evaluation. The billing-model failure above is unaffected either way.

**Decision (owner, 2026-08-03): Scaleway is out.** H1 outranks the sovereignty fit that motivated the candidate in the first place.

**What this implies for a future search**, stated as a criterion rather than a shortlist: the candidate classes that can satisfy H1 structurally are a platform that **suspends rather than bills** when a free tier is exhausted, and a **fixed-price plan whose ceiling is the price** — where "free" is traded for "bounded", which H1 permits and H4 would have to be revisited against. Usage-metered serverless with an uncapped invoice is disqualified as a class, not as a vendor. Note also that **S2 satisfies every requirement here trivially**, by moving the exposure to whoever runs it — a further argument for §13.1's recommendation.

### 13.6 Candidate: alwaysdata Free (forwarded 2026-08-03, partly re-verified)

Forwarded by the owner from independent research and re-verified against the vendor's own pricing, legal, and documentation pages on **2026-08-03**. It is the first candidate to pass H1 by construction, and the shape it fits is **S4**, not S3.

**The plan, from the vendor's pricing page:** €0/month for life, 1 GB SSD, **256 MB RAM**, **¼ CPU**, 3 days of backups, an `alwaysdata.net` hostname — offered "for personal needs". The publisher is **ALWAYSDATA SARL**, a French company (Paris RCS 492 893 490, registered office in the 8th arrondissement), with a CNIL processing declaration, which is what H5 asks for at the jurisdiction level. It carries **no ISO 27001, HDS, or SecNumCloud claim**, so it is weaker than the rejected candidate on certifications and cannot serve H5's second clause.

**Why it passes H1 — and it is a stronger mechanism than an arithmetic bound.** The plan is a fixed €0 subscription rather than a metered allowance inside a pay-as-you-go account, and the platform can be used with **no payment instrument registered at all**. Accounts with neither a card nor a prior payment sit in "restricted mode", and the documented way out is deliberate — *"fill in your credit card details … or make at least one payment"*, with the doc warning that adding a bank account explicitly does **not** lift it. So the maximum charge is €0 because there is no collection channel, and opening one requires an affirmative act by us. That satisfies the §13.5 acceptance test more robustly than a consumption bound, which depends on our arithmetic staying correct.

**Restricted mode does not block the endpoint** — the decisive check, since restricted mode *is* the configuration that delivers H1. Its documented effects are that "HTTP: processing may take a bit longer as requests will be filtered using rules that are a little stricter" and that outgoing SMTP may be limited; the doc calls the restriction "not an inhibiting one". Unverified residual: those stricter filter rules are unspecified, and an endpoint receiving automated agent traffic is plausibly what they exist to catch. One deployment settles it.

**H2 and H3 pass by construction.** Resources are fixed rather than request-metered and no replicas are created automatically, so hostile traffic converts into queueing, throttling, or suspension rather than spend — and the vendor reserves the right to suspend free accounts running resource-intensive applications. That is precisely the H3 failure mode, chosen deliberately.

**H4: comfortable for the authoring workload, with a bounded caveat on document evaluation.** dmtool is primarily a model and rule *authoring* kit; evaluating document instances is a secondary use case, and the two have different memory profiles. Measured 2026-08-03 against the shipped native binary on macOS/arm64 — the right order but the wrong platform, so **re-measure on Linux/amd64**:

- **Authoring and inspection: flat at ~43 MiB.** `model check` — the kernel consistency gate, on a 63 KB model carrying 192 rules — costs the same as `operators list`, because the footprint is dominated by the mapped 41 MiB image rather than by the workload. **Model size is not a driver at any realistic scale.** Against 256 MB that is roughly six times the headroom, and it covers the verbs this surface is actually for.
- **Document evaluation: the one place the ceiling is reachable.** `model eval` peaks at 60 MiB on a small instance but **519 MiB on a 714 KB document with 100 nested rows**, because evaluation work scales with rules × rows rather than with input size. The fixture is a deliberate performance stress model, so treat that as an upper bound rather than a typical instance.

Three settings bound the caveat and none of them is new code: the decoder **already rejects oversized documents before parsing** (`document_code_units limit 32000000`), a limit calibrated for a workstation that a small deployment would simply lower; **pinning `-Xmx` below the host limit** turns an overrun into a well-formed error envelope rather than a process kill — measured at `-Xmx200m`, an over-budget evaluation returns `outcome: "error"` / `RK_INTERNAL_ERROR` with exit 0 and the server keeps serving, whereas an unpinned native image sizes its heap from *physical* host RAM and would be killed by the platform, taking the endpoint down for every caller; and §12's exclusion of concurrent dispatch holds peak at the worst single call.

**Parallel calls from different users raise three issues, and the memory one is the least of them.** *Throughput:* a fixed ¼ CPU is a hard ceiling, so concurrency redistributes latency rather than adding capacity, and a **bounded queue returning an immediate busy error** beats unbounded queueing — this revision removed stream resumability, so a timed-out request is re-issued as a *new* request and an unbounded queue invites a retry storm. *Isolation:* a shared-account platform gives one filesystem, while §9 addresses models by path, so concurrent callers have **no tenancy boundary** — one can name another's model. §13.8's handles fix addressing but not isolation; the server must scope them itself. *Writing:* not the interleaving it first appears to be — §5's serialised dispatch means one process cannot interleave two mutations — but an unauthenticated endpoint whose tools mutate models is an **open write surface**, which is the authorization question below rather than a file-write one (§13.7).

**Isolation and authorization deserve more weight than the memory ceiling, precisely because authoring is the primary workload** — authoring *mutates* models, so a multi-caller authoring endpoint meets the "who may write what" question on its main path rather than on an edge case, and a stateless protocol supplies no answer to it (§13.8). Both are design questions for any multi-caller hosting rather than defects of this platform, and both are why this candidate suits S4 rather than S3.

**The platform offers exactly the right deployment primitive.** Its **user program** site type takes a start command, a working directory, and environment variables, and expects the program to bind the IP and port it is given — with SSH available to test the program first, and Java named among the supported languages. A long-lived process serving Streamable HTTP is a first-class citizen here, which fits an MCP server better than a scale-to-zero container does.

**What disqualifies it for S3, and the correction to the forwarded assessment.** The research reported that commercial use is explicitly prohibited. Re-reading the vendor's own pages, **no such contractual clause was found**. What exists is softer and, for a production endpoint, worse: the pricing card frames the plan as "for personal needs", the documentation describes the free offer as being for low-resource needs or for evaluating the platform, and the legal page states that **"alwaysdata reserves the right to suspend or delete without notice a free account if its legitimacy is contested."** That is unbounded discretion rather than a bright line — there is no clause to comply with, and therefore no way to make the arrangement safe by conforming to it.

Note the verb: **delete**, not only suspend. For H3 that is acceptable, because H3 trades availability away deliberately. Durability is a different matter, and it collides with §13.2: hosting customers' document models makes us a GDPR processor, and a platform that may delete the account without notice cannot carry a retention obligation. The forwarded research also correctly flagged that three days of rolling backups mean payloads written to disk persist beyond the request — and drew the right conclusion, *do not write request bodies to the filesystem*. **For dmtool that mitigation is not available:** its state *is* files, models and documents are read and written on disk, and §13.8 puts hosted model bytes behind handles precisely because the filesystem is the store. On a 1 GB local disk with rolling backups, the natural implementation retains exactly what a processor role obliges us to control.

**Assessment: a strong S4 candidate, not an S3 one.** As a public demonstration endpoint holding no customer models — where deletion without notice costs a redeploy and there is no processor role to breach — it satisfies **H1 through H3 outright**, better than anything else assessed, and H5 at the jurisdiction level. **H4 holds for the authoring workload it exists to serve**, where 256 MB carries about six times the headroom; only the secondary document-evaluation path needs the three settings above, and a pinned `-Xmx` reduces even that to a per-call error. As infrastructure serving customers or the company it fails — not on resources, but on the absence of any contractual floor, and on the isolation and write-atomicity gaps above. Which of those it is depends on which consumer §13.1 is serving, still the open question there.

### 13.7 What a network endpoint changes about writing

*Revised 2026-08-03.* An earlier version of this subsection presented concurrent read-modify-write interleaving as the substantive multi-user gap. **That overstated it**, because §5 already specifies **serialised dispatch** — one request at a time — so a single port-bound process cannot interleave two mutations. The race needs two concurrent read-modify-write sequences, which is the *CLI's* situation (two shells), not the server's. What the network deployment actually changes is set out below, and it is mostly not about concurrency.

**Source-verified 2026-08-03, and worth recording regardless of what it costs:**

- **Model writes are not atomic.** Every mutating verb funnels through one shared path, `Files.writeString(Path.of(write.destination()), write.json())` in `cli/.../BaseCommand.java` — truncate-and-write, no temporary file, no `ATOMIC_MOVE`.
- **Nothing locks a model.** No `FileLock`, no `tryLock` on the model path; the only `synchronized` members belong to the Node custom-type bridge and a lazy resolver.
- **`group extract` writes two files** — the extracted sub-model, then the rebased base — with no atomicity between them.

**What survives serialised dispatch, in descending order of significance:**

1. **Authorization, not atomicity, is the real write problem on a network endpoint.** A reachable port whose tools mutate models is an **open write surface**: anyone who can address a workspace can add or remove rules in it. This dwarfs a torn file, it is not fixed by any write primitive, and it is the same question as tenancy (§13.8).
2. **Truncation on abrupt termination.** A process killed mid-write — by a platform memory kill, a redeploy, or a crash — leaves the model truncated rather than unchanged. This is durability, not concurrency, and it is a property the CLI already has. The pinned `-Xmx` of §13.6 converts the memory case into a clean error, but not a platform kill or a redeploy.
3. **Writers the server does not mediate.** Shell access to the same account, or a deployment that rewrites files, can still interleave with a running server. A fixed single-instance plan removes the second-instance case; it does not remove this one.
4. **Object storage replaces the question rather than inheriting it.** Once model bytes live behind handles in an object store (§13.8), local file atomicity is moot and the correct primitive is a **conditional write on a content version** — which is also the mechanism that protects against an unmediated writer, since it detects the conflict instead of preventing it.

**So the atomic-write fix is optional even for phase 3, and is deliberately not filed as a standing CLI gap.** The remedy is small and single-sourced — a sibling temporary file and an `ATOMIC_MOVE` inside that one shared method, guarded by a test proving a killed write leaves the original intact — but serialised dispatch removes the concurrency argument for it, and a store-backed deployment removes the file argument. It is worth doing for durability on its own terms, and nothing in the CLI's usage model demands it today: one caller, one shell, serial invocations. Recorded here because this is where the question arose, not because this proposal creates the obligation.

### 13.8 What phase 3 changes in the design

Model state moves from filesystem paths to **server-minted opaque handles passed as ordinary tool arguments** — the specification's own non-normative "Stateful Tools" guidance, and the only shape available now that protocol sessions are gone (§3). Model bytes then live in object storage behind the handle.

**A handle scopes to a private per-tenant workspace, never into a shared tree.** This is part of the contract rather than an implementation detail: it is what closes the isolation gap of §13.6, and by making every model single-writer it also removes the unmediated-writer exposure of §13.7 item 3.

**But what is a tenant, when the protocol has no sessions?** Statelessness removed *sessions*, not *identity* — a stateless server simply cannot remember who called, so every request must carry its own proof, which is the ordinary bearer-credential pattern. Of the three signals available, only one is an identity:

- **`_meta.io.modelcontextprotocol/clientInfo`** is client-declared and unauthenticated, and it names the client *software* rather than a principal. It is useful for logging and useless as a tenant key.
- **A handle is a capability, not an identity** — whoever presents it reaches what it addresses.
- **The OAuth 2.1 access token** carried on every Streamable HTTP request is the real principal, and per-request credentials are precisely how stateless systems authenticate.

So the term resolves per shape, and the differences are load-bearing:

| shape | what a tenant is | isolation boundary |
|---|---|---|
| **S1** stdio | nothing — one process, one OS user | the OS account; filesystem paths are appropriate (§9) |
| **S4** public demonstration | **no identity at all** — each handle mints its own ephemeral workspace | per *handle*, capability-only; acceptable precisely because S4 holds no customer models |
| **S3** hosted service | the **authenticated principal** of the request's access token | per principal, with handles bound to it |

**Under S3 a handle must be minted for a subject and rejected when presented by another.** If the handle is the only boundary, the security model is an unguessable string and a leaked handle is a breach — a defensible choice for S4, never for a service holding customer models under the §13.2 processor role.

**Two non-negotiables follow, and the second is the cleaner answer.**

1. **An endpoint without authentication must not expose mutating tools.** Where there is no principal there is no answer to "who may write what", so a reachable mutating surface is simply open (§13.7 item 1). This is not a policy preference; it is what the absence of identity means.
2. **Prefer making the demonstration endpoint read-only, rather than isolating writes on it.** A read-only S4 exposes the catalogs, the published schemas, per-verb help, and the consistency oracle over caller-supplied content — `model check`, `rule check`, `model describe`, `where-used`, the whole inspection surface — which is most of what there is to demonstrate. **It then needs no tenant, no authorization, no isolation, no handles, and no write path at all**, and every concern in §13.7 evaporates rather than being mitigated. A demonstration endpoint that cannot be made worse by its callers is also the only one that can be left running unattended, which is what "no availability promise" (§13.1) is really trading for. Mutating tools stay a phase-3 question, gated on the authentication of requirement 1. Inlining a model in every call stays available for one-shot use and is token-fatal on real models; a client-supplied storage URI is the third option and the least attractive, since it makes us fetch from a location the model chose.

Authorization becomes OAuth 2.1 resource-server, with the revision's `iss`-validation requirement and Client ID Metadata Documents now preferred over Dynamic Client Registration (§2). The official Java SDK becomes worth reconsidering **for that transport only** (§5.1): Streamable HTTP, SSE, and OAuth plumbing would justify the dependency where native-image is no longer a constraint.

**§9's no-warm-model rule still holds under hosting.** Handles address storage, not a resident parsed model; caching one re-opens the cache-coherence objection that R3 records.

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
