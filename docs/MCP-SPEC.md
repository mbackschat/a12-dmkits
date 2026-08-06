# dmtool over MCP — the spec

The `dmtool mcp` verb serves this tool over the [Model Context Protocol](https://modelcontextprotocol.io), revision **`2026-07-28`**, on stdin/stdout.

> ### Positioning — read this before anything else
>
> **MCP is for a caller that cannot execute the binary. Where an agent can run `dmtool` directly, the CLI is the better surface and should be used instead.**
>
> It is the *same executable*: the server is one more leaf in the same command tree, every tool routes to an existing verb, and no authoring capability exists here that the CLI lacks. For a local, shell-capable agent MCP is pure indirection — and under the content rules below it is actively worse, because it gives up in-place editing and pays a read-modify-write round trip the CLI does not.
>
> It earns its place in three situations: a **host that cannot spawn a subprocess** (a web client, a hosted agent platform, a sandboxed or mobile IDE integration), a **hosted deployment** where the caller installs nothing, and **host-enforced approval**, where allowlisting a read-only tool is a guarantee that granting shell access can never be.
>
> The positioning rides the verb's own `--help`, not only this document: that is where a cold agent meets the surface, and the wrong choice there is silent.

**How the design was reached** — the alternatives, the measurements, and the decisions taken while it was unbuilt — is the [MCP-SUPPORT-PROPOSAL](MCP-SUPPORT-PROPOSAL.md). This file states what is *built*; what is deliberately not built is [MCP-GAPS](MCP-GAPS.md).

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
- **Nothing on stdout that is not an MCP message.** The framer owns the stream; no verb ever writes to it.
- **Never respond to a notification.** A message with no `id` is consumed silently — which is also what makes `notifications/cancelled` safe to accept, since a request gets exactly one response and it has already been written.

An explicit `"id": null` is neither a request nor a notification and is refused `-32600`; end of input is the graceful-shutdown signal, so the loop returns and the process exits.

Every result carries `resultType: "complete"` and `_meta.io.modelcontextprotocol/serverInfo`. With no session there is nowhere else for a server to say who it is.

**`--text` is refused on this verb.** The flag buffers stdout until the verb returns, and this verb returns when the session ends — so passing it would withhold every frame and hang the client on its first request. A protocol stream has no human rendering; the refusal is corrective, not silent.

## 3. The seam

```
McpStdioBinding     the wire: framing, the JSON-RPC envelope, id correlation      ← outermost
  └── McpDispatcher method name -> handler, over OUR request/result records
```

Only the binding may name a transport shape. Protocol *facts* live inside — `resultType`, `ttlMs`, and `cacheScope` are plain fields on `McpResult`, because which operation is cacheable and how fresh its answer is are semantics this server owns; a transport must not decide caching policy. What the rule bans is depending on someone else's classes to express them.

**Zero new dependencies.** The layer is Jackson tree plus the JDK, both already in the binary. The official Java SDK is not a trade-off here but unavailable: its latest release tracks `2025-11-25`, and Java is not in the revision's Tier-1 SDK set. Three things prepare for a substitution and nothing else does: the handler table is **data** (`McpDispatcher.handlers()`), handlers are **synchronous pure functions**, and the protocol tests assert **on the wire** — so they survive a substitution intact and become the SDK's acceptance suite rather than something to rewrite.

**Dispatch is serialised**, one request at a time. It is the only posture whose thread-safety is established, and no verb is long enough to make queueing costly for a single caller. That justification does not survive multiple callers.

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

**Input shape.** `{op?, args, model?, references?, files?}` — `op` only where a tool has more than one, and each `op` carries a **derived** description of that route's `Effect`, `FailurePersistence`, and `DryRun`, which is the route-level fact MCP annotations cannot express. `args` stays loosely typed on purpose: per-op validation is what this tool's corrective diagnostics already do well, and a large discriminated union degrades a model's tool-calling accuracy for no correctness gain. No property anywhere is a filesystem path.

## 6. The verb's own contract

`mcp` publishes one operational profile, and every axis differs from an ordinary verb's:

| axis | value | why |
|---|---|---|
| `effect` | `DISPATCH` | its work is the child ops a client calls, exactly as `batch`'s is |
| `failurePersistence` | `NO_WRITE` | it accepts no destination: content crosses the wire both ways, and a call's temporary tree is deleted before the call answers, so no path the caller can name is written |
| `returns.kind` | `NDJSON_STREAM` | stdout is a stream of newline-delimited frames, **not one JSON value**. `RAW_JSON` would tell a consumer to parse stdout as a single document, which fails on the second frame — so the vocabulary gained a value rather than misusing one |
| `provenance.consistency` | `CHILD_DEFINED` | whatever the routed verb vouches for |

It is `CONTRACT_EXEMPT` from the directional `schema <verb>` surface, for a stated reason: it serves a protocol session rather than consuming and returning one document, and its own contract is published *over* that protocol by `server/discover` and `tools/list`.

Its demonstration is likewise structural rather than owed: a verb whose unit of use is a session on stdin cannot be shown by an argv, so it is exempt in the conformance matrix and demonstrated executably by the wire suite instead.

## 6. Guards

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
| the published output-kind vocabulary names `NDJSON_STREAM` | `DmtoolPublicContractTest` |
