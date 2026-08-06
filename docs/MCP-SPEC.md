# dmtool over MCP — the spec

The `dmtool mcp` verb serves this tool over the [Model Context Protocol](https://modelcontextprotocol.io), revision **`2026-07-28`** — on stdin/stdout for a host that launches the binary, and over **Streamable HTTP** (`--http`) for a host reached across the network.

> ### Positioning — read this before anything else
>
> **MCP is for a caller that cannot execute the binary. Where an agent can run `dmtool` directly, the CLI is the better surface and should be used instead.**
>
> It is the *same executable*: the server is one more leaf in the same command tree, every tool routes to an existing verb, and no authoring capability exists here that the CLI lacks. For a local, shell-capable agent MCP is pure indirection — and under the content rules below it is actively worse, because it gives up in-place editing and pays a read-modify-write round trip the CLI does not.
>
> It earns its place in three situations: a **host that cannot spawn a subprocess** (a web client, a hosted agent platform, a sandboxed or mobile IDE integration), a **hosted deployment** where the caller installs nothing, and **host-enforced approval**, where allowlisting a read-only tool is a guarantee that granting shell access can never be.
>
> The positioning rides the verb's own `--help`, not only this document: that is where a cold agent meets the surface, and the wrong choice there is silent.

**Hosting is what this is for.** The design record is explicit that the *hosted* transport is the one carrying the value and that stdio is the development and testing vehicle for it; the owner's recorded sequencing was stdio first (2026-08-02), and both are now built. The content-carrying request model exists *because* the two were made to share one mechanism — §7 is a binding, not a second server. What remains for a deployment is packaging and a platform: [MCP-GAPS](MCP-GAPS.md) "Hosting" carries the container image (licensing-neutral) and hosting it ourselves (which attaches EUPL Article 1).

**How the design was reached** — the alternatives, the measurements, and the decisions taken while it was unbuilt — is the archived [MCP-SUPPORT-PROPOSAL](archived/MCP-SUPPORT-PROPOSAL.md). This file states what is *built*; what is deliberately not built is [MCP-GAPS](MCP-GAPS.md).

## 1. The revision, and the one it refuses

**`2026-07-28` only.** No `2025-11-25` compatibility path exists and no request from an older client is partly served. The revision is the enabling change rather than an incidental target: it removed sessions, the `initialize` handshake, and server-initiated requests, so a conforming server is now free to be a pure per-call transport — read one self-contained request, run the verb, write the answer, forget everything. That is already dmtool's own model.

Every refusal below is the specification's own mechanism, and each is locked by a case in `McpWireTest` asserting the code *and* that the message names the revision.

| what arrives | what the server answers | basis |
|---|---|---|
| `_meta.io.modelcontextprotocol/protocolVersion` present and not `2026-07-28` | `-32022` `Unsupported protocol version`, `data.supported` + `data.requested` | the version-negotiation MUST |
| either required `_meta` field absent (`protocolVersion`, `clientCapabilities`) | `-32602`, naming the missing field and the revision served | *"A request missing any required field is malformed; the server **MUST** reject it with JSON-RPC error code `-32602`"* |
| `initialize` | `-32601`, naming the removed method, the revision, and `server/discover` | the code is implementation-defined; a modern-only server **SHOULD** name its supported versions in any error to an `initialize`, because a legacy client has no fall-forward mechanism |
| any other unknown method | `-32601`, naming the method and listing what is served | JSON-RPC |
| an unparseable line | `-32700` with `id: null`, and **the session keeps serving** | JSON-RPC |

**The absent-field row corrects the parked proposal**, which specified `-32022` there on the reasoning that absence is the signature of a pre-revision client. The diagnosis is right and rides the message; the code is the one the specification mandates. `-32020`–`-32099` is reserved for the specification, so this server allocates nothing in that range and emits `-32022` only with its defined meaning.

## 2. The wire

Newline-delimited JSON-RPC. Three binding MUSTs are discharged in code rather than trusted:

- **One message per line, no embedded newlines.** Frames are serialised compactly, so a newline inside any string is escaped by the serializer and cannot break a frame. Locked by a case that forces a newline into a refusal message and asserts the output holds exactly one.
- **Nothing on stdout that is not an MCP message.** The framer owns the stream and every verb runs with its own sinks, checked by a case that drives a large-payload verb through a call and asserts the process stdout stayed empty. The residual is third-party code in the same JVM printing directly; dmtool's own sources are clean and no subprocess inherits stdout.
- **Never respond to a notification.** A message with no `id` is consumed silently — which is also what makes `notifications/cancelled` safe to accept, since a request gets exactly one response and it has already been written.

An explicit `"id": null` is neither a request nor a notification and is refused `-32600`; end of input is the graceful-shutdown signal, so the loop returns and the process exits.

Every result carries `resultType: "complete"` and `_meta.io.modelcontextprotocol/serverInfo`. With no session there is nowhere else for a server to say who it is.

**`--text` is refused on this verb.** The flag buffers stdout until the verb returns, and this verb returns when the session ends — so passing it would withhold every frame and hang the client on its first request. A protocol stream has no human rendering; the refusal is corrective, not silent.

## 3. The seam

```
McpStdioBinding  |  McpHttpBinding    the wire: framing/headers, the envelope, id correlation  ← outermost
              └──┴── McpDispatcher    method name -> handler, over OUR request/result records
                       └── McpToolRegistry / McpInvoker
```

Two bindings, one everything-else. That the hosted transport was a *binding* rather than a second server is the payoff of the content rules: no session, no server-side state, nothing per-transport to keep in step.

Only the binding may name a transport shape. Protocol *facts* live inside — `resultType`, `ttlMs`, and `cacheScope` are plain fields on `McpResult`, because which operation is cacheable and how fresh its answer is are semantics this server owns; a transport must not decide caching policy. What the rule bans is depending on someone else's classes to express them.

**Zero new dependencies.** The layer is Jackson tree plus the JDK, both already in the binary. The official Java SDK is not a trade-off here but unavailable: its latest release tracks `2025-11-25`, and Java is not in the revision's Tier-1 SDK set. Three things prepare for a substitution and nothing else does: the handler table is **data** (`McpDispatcher.handlers()`), handlers are **synchronous pure functions**, and the protocol tests assert **on the wire** — so they survive a substitution intact and become the SDK's acceptance suite rather than something to rewrite.

**Dispatch is serialised over stdio** — one request at a time, which is right for a single caller launching the binary. The hosted transport is **bounded-concurrent** instead (§7), because there a second caller must not wait behind the first.

## 4. `server/discover`

Mandatory in this revision, and cacheable (`ttlMs` one hour, `cacheScope: "public"` — the content is fixed for a given binary). It advertises `supportedVersions: ["2026-07-28"]`, the `tools` capability, and an `instructions` string that is a **router, never content**: it states that calls carry their own content, points at `tools/list`, and says to prefer the binary where it can be run. Duplicating the vocabulary or the per-verb detail here would be a second copy to keep true.

## 5. The tool surface — derived, never authored

`tools/list` is computed from the **running** command tree plus `CommandContracts`. Nothing is written per verb, so a verb added to the tree appears by itself and one whose effect changes moves to the truthful cell. Taking the *running* tree also means a verb the binary hides from itself (a native build's JVM-only set, empty today) is absent here too — a registry that rebuilt the tree would advertise verbs the same binary refuses.

**Why the grouping is not a matter of taste.** MCP lets a tool carry exactly two facts about what it does: `readOnlyHint`, true only if every op is read-only, and `outputSchema`, declarable only if every op always returns a conforming structured result. Both are per-`Tool`, so a tool spanning ops that disagree must drop the fact. The correct grouping is therefore the **partition of the ops by those two properties, inside each target** — a coarser one must lie, a finer one adds definitions without adding truth. The suffix names the cell, and a target with only one cell stays bare:

| cell | suffix | means |
|---|---|---|
| read-only, envelope | `.read` | inspects; returns the standard envelope |
| writes, envelope | `.edit` | changes the model; returns the envelope |
| read-only, raw | `.emit` | hands back a document or rendering; changes nothing |
| writes, raw | `.generate` | produces a document |

**28 tools over 76 ops, 12 truthfully read-only, 23 declaring `outputSchema`.** The `outputSchema` is the **served** `schema result` document, whose referents are embedded under `$defs`, so a client resolves it with no second call; publishing the resource file instead would hand over a `$ref` it cannot resolve.

`readOnlyHint` is what makes categorical approval safe: granting `dm.rule.read` cannot reach `rule remove`, which is the whole reason the effect axis is never collapsed. `openWorldHint` is `false` everywhere — not because nothing external is touched (`model eval` spawns `node`, `model diff --since` spawns `git`) but because there is no open world of external entities; both are local subprocesses under the caller's own account.

**Six live verbs are not served, each with its reason as data** (`McpToolRegistry.EXCLUDED_VERBS`), because an unexplained hole in a derived surface reads as a bug:

| not served | why |
|---|---|
| `mcp` | the server itself; routing to it would nest a session inside a session |
| `workspace list`/`graph`/`check`/`roles` | a **directory** is the input, and three of them answer over the whole set — served from a caller-declared tree they would silently answer about the fragment that was sent |
| `model rename` | its refusal must prove a sibling's **absence**: it scans the workspace inward and refuses if something includes the old id. Given only what a caller chose to send it would find nothing and permit the rename, turning "not supplied" into "proved absent" — a safety gate degrading silently rather than failing loudly ([MG7](MCP-GAPS.md)) |

**What a tool definition costs, and why it is what it is.** The whole catalogue is **85,624 bytes** (~21k tokens) for 28 tools over 76 ops — down from 336,910 (~84k) once two measurements were taken. On the wire it is cacheable and fetched about once an hour per client; in a prompt a host pays it on every model turn, which is the cost that actually binds. Two things got it there, both derived rather than trimmed by hand:

- **The declared `outputSchema` carries no annotations.** The served result schema is 12,777 bytes of which **81 % is `description` and `examples`**, and MCP puts `outputSchema` per tool, so that prose was repeated 23 times. `description`/`examples` are JSON Schema *annotations* — they assert nothing — so removing them **cannot change what validates**, and a test proves it by validating a real envelope against the shipped copy and still rejecting a malformed diagnostic through the embedded definition. One short description survives, naming the call that returns the full contract.
- **Only a tool whose ops consume a model asks for one.** `-m` is inherited by every leaf, so the obvious test ("does the option exist?") said yes even for a catalogue lookup and invited a caller to attach a document to `operators`. The signal is the `WorkspaceOption` **mixin** — the mechanism by which a verb gains model resolution at all — so `dm.catalog` no longer asks, while a mixed cell still does because three of its four ops read a model.

Reducing it further means dropping a published fact rather than a redundancy, so the remaining lever is the host's own progressive discovery.

**Input shape.** `{op?, args, model?, references?, files?}` — `op` only where a tool has more than one, and each `op` carries a **derived** description of that route's `Effect`, `FailurePersistence`, and `DryRun`, which is the route-level fact MCP annotations cannot express. `args` stays loosely typed on purpose: per-op validation is what this tool's corrective diagnostics already do well, and a large discriminated union degrades a model's tool-calling accuracy for no correctness gain. No property anywhere is a filesystem path.

## 6. `tools/call` — content in, content out, nothing kept

**Two rules, both hard.** The server never receives, resolves, or returns a client-side path; and nothing a call creates survives it. This is a simplification rather than a cost: with no caller-supplied path there is no traversal surface to sanitise, no ambient workspace to freeze into, and no relative path resolving against a directory the caller cannot see.

| direction | what crosses the wire |
|---|---|
| the subject model | its DM-JSON content, as `model` |
| models it references | the content of each, as `references[]` — **as many as the subject needs**; the server names every file, because a model's identity is its `header.id`, not its file name |
| a spec or document instance | the content, under `files.<parameterKey>` |
| an edited or generated model | an **embedded resource** in the response — the caller owns persistence |
| a read's answer | the envelope as `structuredContent`, the `--text` rendering as `content` |

**The mechanism, and why one is needed.** Seven verbs accept their content only as a file and `-m` is a path, so each request materialises a temporary tree under **server-generated** names, runs `Cli.run` against it, reads back everything the run created or changed, and deletes the tree. Content therefore *transiently* touches disk: rule 2 is a **lifetime** guarantee, not an absence of writes, and a test asserts no request tree survives a call. `-w` is passed explicitly whenever a model is carried — without it the workspace would fall back to `$DMTOOL_WORKSPACE` and let the server's own environment decide what a caller's references resolve against.

**Model content is never parsed by this layer.** Inbound bytes go to the tree verbatim and the CLI does the parsing it already does correctly; outbound bytes are read back and carried as text. Number fidelity is then unlosable by construction rather than dependent on configuring a mapper — which is why the layer's own mapper is allowlisted for *frames* only, with that invariant written into the allowlist row.

**Refusals, each corrective:** a caller-supplied path in `args` (including the inherited `-m`/`-w`) names what to send instead; a **directory-shaped** content parameter says it is a directory and that a carried tree is not served here; a content key the verb does not take lists the ones it does. The directory refusal also disposes of a residency question by construction: the only path to `CustomJsWorker` — which registers an unremoved shutdown hook per call and leaves its `node` child running until the process exits — is the two custom-JS directories, so it is unreachable from a session.

**`isError` keys on the outcome, not the exit code.** A `refused`/`rejected`/`error` envelope is actionable feedback a model can self-correct from; `model check` reporting an invalid model is a *successful* answer to the question asked, and marking it an error would tell the caller the tool failed. A malformed invocation — empty stdout, the whole corrective surface on stderr — comes back as a tool error carrying that stderr, because a design reading only stdout would drop the answer on the likeliest outcome of a generated call.

**One deliberate deviation, stated.** The specification SHOULDs that a tool returning structured content also return the serialized JSON in a text block, for a client that ignores `structuredContent`. That client is served here by the `--text` rendering, which carries the same information in the form a model reads better; emitting the JSON twice would double every response for a client that already has it. Artifacts come back as **embedded resources** (`dm:///out/<name>`), which the spec permits while SHOULDing the `resources` capability alongside — not declared here, because declaring a capability whose endpoints are not served ([MG1](MCP-GAPS.md)) would be the worse claim.

## 7. The hosted transport — Streamable HTTP

The **second binding over the same seam**: `dmtool mcp --http` reuses `McpDispatcher`, `McpToolRegistry`, and `McpInvoker` unchanged. That is what the content-carrying request model was built for — with no session and no server-side state, a transport swap touches one class. It runs on the JDK's own `com.sun.net.httpserver`, so hosting costs no new dependency.

```sh
dmtool mcp --http --host 0.0.0.0 --port 8080     # a hosted process; the endpoint is POST /mcp
```

Defaults are the safe ones: it binds **loopback** unless told otherwise (the specification SHOULDs that for a server running beside its caller), and **no browser origin is allowed** until one is listed. The startup line goes to **stderr**, because this route's stdout carries nothing — which is exactly what its `http` operational profile publishes as `EMPTY_STDOUT`.

**The status mapping is the specification's, not a convention.** JSON-RPC over HTTP is usually served as 200-with-an-error-body; this revision is explicit instead, and every row is a MUST:

| what happens | answer |
|---|---|
| a result | `200` · `application/json` (a single JSON object; SSE is permitted and not used) |
| a notification | `202` with **no body** — and header validation does not apply, since this revision defines no header rules for one |
| a required header missing, or disagreeing with the body | `400` + `HeaderMismatch` **-32020** |
| a protocol version this server does not implement | `400` + **-32022** with the supported set |
| an RPC method it does not implement | **`404`** + -32601 |
| `Origin` present and not allowlisted | `403` — the required DNS-rebinding defense |
| `GET` / `DELETE` (the removed standalone stream and session teardown) | `405`; `Mcp-Session-Id` and `Last-Event-ID` are ignored, never echoed |
| a body over the cap | `413` with a corrective naming the flag that raises it |

A **tool execution** error is none of these: it rides a `200` as `isError` inside the result, because the call reached the verb and the answer is actionable feedback.

**Why the mirrored headers are validated rather than trusted.** `MCP-Protocol-Version`, `Mcp-Method`, and — on `tools/call` — `Mcp-Name` must agree with the body. An intermediary may route on the header while this server executes the body, so a disagreement is a real vulnerability, which is why the specification makes rejecting it a MUST rather than a nicety. A name that is not header-safe arrives Base64-wrapped in the `=?base64?…?=` sentinel and is **decoded before comparison** — comparing first would reject every legitimate encoded name.

**The bind address comes from the environment; nothing else does.** `--host` and `--port` default to the platform's `IP`/`HOST` and `PORT` variables, because that is how a hosting platform *tells* a program where to listen — alwaysdata's user-program type states it outright ("you must use PORT and IP"), and it is the same convention on Cloud Run, Heroku, Fly, and Railway. An explicit flag still wins, and `--help` renders what the current environment resolves to.

That is deliberately the **only** thing this server reads from its environment, and the distinction is worth stating because the project treats ambient configuration as a hazard elsewhere: `$DMTOOL_WORKSPACE` is refused over MCP precisely so the server's own environment cannot decide what a caller's references resolve against (§6 passes `-w` explicitly to close it). The line is that **the environment may say where to listen; it may never say what an answer means.**

**Several callers at once.** The hosted endpoint runs a **bounded** worker pool (`--workers`, default the visible cores capped at 4) behind a **bounded** queue (`--queue-depth`, default 16). Past both a caller gets `503` with `Retry-After` and an error code allocated *outside* the JSON-RPC reserved range, as the specification directs for a purpose it does not define. Bounded on purpose: this revision removed stream resumability, so a timed-out request is re-issued as a **new** one, and a deep queue converts one slow call into a retry storm. The worker count is a **memory** dial more than a CPU one — authoring is flat at ~43 MiB while a large document evaluation has been measured at 519 MiB, so a host sized for authoring must not admit four evaluations at once.

**The thread-safety claim is established, not assumed** — the hosted endpoint is the first place two callers can be in flight together. What makes it hold: `Cli.run` builds a fresh command tree per invocation, so no `@Option` field is shared; each call gets its own temporary tree and its own output sinks; and the tool surface is derived **once** at startup rather than walked per request (which also stops `tools/list` rebuilding every bundled schema on every call). A test drives twelve concurrent calls carrying two different models and asserts each caller gets its own answer back.

**Not served, deliberately:** SSE response streams (a single JSON object is permitted and every answer here is one), `subscriptions/listen`, and authentication. The last is a specification SHOULD and a real exposure for a public endpoint: nothing is stored, so there is no data to reach, but there is CPU to burn. Put the endpoint behind the platform's own auth or a private network unless it is meant to be public.

**Deploying it.** The endpoint is built and deployed from CI, never from a laptop: [`deploy-mcp-endpoint.yml`](../.github/workflows/deploy-mcp-endpoint.yml) is `workflow_dispatch`-only, ships the **released** `dmtool-linux-x64` asset verified against `SHA256SUMS` (so production runs the byte-identical artifact a user downloads, not a second build), uploads it beside the live path and moves it into place, restarts the site, and then proves the endpoint with [`smoke-mcp-endpoint.sh`](../scripts/smoke-mcp-endpoint.sh) — bounded retries, so a dead endpoint fails the job instead of hanging it. The host's own start command pins the heap (`-Xmx`), which is not optional: a native image sizes its heap from *physical* host RAM, so an unpinned process on a small plan is killed and takes the endpoint down for everyone. Candidate assessments, and the requirements a host must meet, are in [MCP-GAPS](MCP-GAPS.md) "Hosting".

## 8. The verb's own contract

`mcp` publishes one operational profile, and every axis differs from an ordinary verb's:

| axis | value | why |
|---|---|---|
| `effect` | `DISPATCH` | its work is the child ops a client calls, exactly as `batch`'s is |
| `failurePersistence` | `NO_WRITE` | it accepts no destination: content crosses the wire both ways, and a call's temporary tree is deleted before the call answers, so no path the caller can name is written |
| `returns.kind` | `NDJSON_STREAM` | stdout is a stream of newline-delimited frames, **not one JSON value**. `RAW_JSON` would tell a consumer to parse stdout as a single document, which fails on the second frame — so the vocabulary gained a value rather than misusing one |
| `provenance.consistency` | `CHILD_DEFINED` | whatever the routed verb vouches for |

It is `CONTRACT_EXEMPT` from the directional `schema <verb>` surface, for a stated reason: it serves a protocol session rather than consuming and returning one document, and its own contract is published *over* that protocol by `server/discover` and `tools/list`.

Its demonstration is likewise structural rather than owed: a verb whose unit of use is a session on stdin cannot be shown by an argv, so it is exempt in the conformance matrix and demonstrated executably by the wire suite instead.

## 9. No skill ships with this surface — and none should

`skill/SKILL.md` is **not** given to an MCP caller, no second skill is written for one, and the server is not registered in either agent plugin. Four reasons, in order of how load-bearing they are:

- **The skill is invocation-shaped.** 167 lines carrying 20 `dmtool …` command forms, every one guarded to resolve against the live command tree. An agent holding `dm.rule.read` and no shell can execute **none** of it, so handing it over teaches the wrong surface.
- **Where both exist, the skill is right to win.** Under a plugin the binary *is* present, so an agent reading the skill will shell out and ignore the tools — which is the correct choice, per the positioning at the top of this file. A skill that steered such an agent to MCP would be steering it wrong.
- **Most of the skill's factual content is already MCP-reachable**, by the project's own placement rule: operator `gotchas` and semantics through `dm.catalog` op `operators`, field-level requiredness and co-requirements through the published schemas (`dm.catalog` op `schema`), and each error code's `fix`/`explain` through `dm.catalog` op `diagnostics` *and* the diagnostics riding every envelope. Those are three of the four placement lanes; only the fourth — the skill's own — is unreachable.
- **MCP has no skill concept in any revision.** Agent Skills is a separate portable standard; the two compose at the packaging layer, not in the protocol.

**What an MCP caller loses — and what it does not.** *(Corrected 2026-08-06: an earlier revision of this section said the judgment residue "has no home on this surface today". Measured, that was wrong in both directions.)*

- **Already data, reachable by a call:** operator `gotchas` and semantics (`dm.catalog` op `operators`), field-level requiredness and co-requirements (op `schema`), each code's `fix`/`explain` (op `diagnostics`, and the diagnostics riding every envelope) — and **polarity itself**, which `dm.rule.read` op `explain` publishes as `data.polarity.{firesOn, note}` for a stored rule. `rule check`'s polarity lint fires on the way in.
- **Genuinely prospective, and therefore in `instructions`:** the judgment that must arrive *before* the call. Rule polarity is the case that qualifies on its own — the kernel accepts both directions, so a `valid` result never means the polarity is right and **no diagnostic can catch an inverted rule**. A reactive surface cannot correct what it never sees. Beside it ride two more sentences: ask whether the constraint belongs in the *field* rather than a rule, and remember that a condition over a repeatable group is evaluated **per row** unless an aggregate makes it whole-column.

That is a deliberate narrowing of the "router, never content" rule, not an abandonment of it: `instructions` is defined by the specification as guidance for using the server effectively, and the test is whether a fact can be *looked up in time*. Everything that can stays behind a call, because a second copy is a second thing to keep true. Four sentences of the one trap that cannot is not a duplicated skill — and it reaches every MCP client with no packaging and no host skill support.

**A host-loaded skill remains available and is not ruled out.** MCP has no skill concept, but Agent Skills is a separate standard the *host* loads, so a caller's host can carry one beside the server registration. It would have to be a **different** skill from `skill/SKILL.md` — that one is invocation-shaped — and whether the residue above justifies writing one is unmeasured until an MCP eval arm exists ([MG4](MCP-GAPS.md)). Build it on a measurement, not on a hunch.

The cheap win available now is independent of MCP: relocate what the placement rule says was misfiled anyway. Two measured examples — `GroupFilled`'s "guard row existence with `GroupFilled`, not `FieldFilled`" is an operator-relation fact belonging in the catalog, and `export`'s "not an inspection tool" belongs in the verb description. Relocating them makes them MCP-reachable for free *and* shrinks the measured skill.

## 10. Guards

| claim | what fails when it is violated |
|---|---|
| every refusal in §1 fires, with its code and a revision-naming message | `McpWireTest` (one case per row) |
| framing: one line per frame, no embedded newline, silence for a notification, EOF ends the session | `McpWireTest` |
| the handler table is addressable data | `McpWireTest` |
| the verb is a live leaf and a session runs through `Cli.run` | `McpCommandTest` |
| `--text` is refused rather than hanging the client | `McpCommandTest` |
| the verb set, its description, params, operational profile, contract exemption | `ManifestVerbTest`, `CliConformanceMatrixTest`, `CommandContractsTest` |
| the op union is the live tree minus the stated exclusions, with no duplicate | `McpToolSurfaceTest` |
| every exclusion names a live verb and carries a reason | `McpToolSurfaceTest` |
| `readOnlyHint` and `outputSchema` are true of every op the tool covers, both directions | `McpToolSurfaceTest` (oracle: the tool's own `manifest`) |
| the surface's denominator (28 tools · 12 read-only · 23 with `outputSchema`) | `McpToolSurfaceTest` |
| tool names are unique, ordered, legal, and no input schema offers a path | `McpToolSurfaceTest` |
| the annotation-stripped `outputSchema` validates exactly what the served one does | `McpToolSurfaceTest` (a real 2020-12 validator, with a positive control) |
| only a tool whose ops consume a model asks for one | `McpToolSurfaceTest` |
| a read runs the verb on carried content and returns the envelope | `McpToolCallTest` |
| an edit returns the mutated model and leaves the caller's file untouched | `McpToolCallTest` |
| a raw route carries its payload as text and claims no structured content | `McpToolCallTest` |
| a caller-supplied path — including the inherited `-m`/`-w` — is refused with its reason | `McpToolCallTest` |
| a directory-shaped content parameter is refused as a directory | `McpToolCallTest` |
| a malformed invocation returns the stderr corrective as a tool error | `McpToolCallTest` |
| a domain refusal is a tool error that keeps its structured envelope | `McpToolCallTest` |
| a multi-model subject resolves from carried `references`, and fails legibly without them | `McpToolCallTest` |
| a variadic positional takes each element as its own token | `McpToolCallTest` |
| no request tree survives its call | `McpToolCallTest` |
| a call writes nothing to the process stdout, so no verb output can corrupt a frame | `McpToolCallTest` |
| every HTTP status and error code in §7, one case each | `McpHttpWireTest` |
| the `--http` route binds, announces on stderr, and leaves stdout empty | `McpCommandTest` |
| concurrent callers carrying different models each get their own answer | `McpHttpConcurrencyTest` |
| a full server answers `503` + `Retry-After` immediately instead of queueing | `McpHttpConcurrencyTest` |
| the derived tool surface is identical call to call | `McpHttpConcurrencyTest` |
| the published output-kind vocabulary names `NDJSON_STREAM` | `DmtoolPublicContractTest` |
