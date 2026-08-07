# dmtool over MCP — the same tool, for a host that cannot run a binary

*2026-08-06T15:27:16Z by Showboat 0.6.1*
<!-- showboat-id: bafb9246-3f82-4693-ad37-0156910019b2 -->

`dmtool mcp` serves the whole verb surface over the **Model Context Protocol**, revision `2026-07-28`, as newline-delimited JSON-RPC on stdin/stdout. It exists for a caller that cannot execute the binary — a web client, a hosted agent platform, a sandboxed integration. **Where you can run the binary, run it:** the CLI edits models in place, while every MCP call carries the model's content in both directions.

A host normally launches it (`{"mcpServers":{"dmtool":{"command":"dmtool","args":["mcp"]}}}`); here it is driven by hand, one message per line, so every frame is visible.

**Discovery** is mandatory in this revision and cacheable — it names the revision served, what the server can do, and how long the answer stays fresh. There is no handshake: every request carries its own protocol version.

```bash
jq -nc '{jsonrpc:"2.0", id:1, method:"server/discover",
         params:{_meta:{"io.modelcontextprotocol/protocolVersion":"2026-07-28",
                        "io.modelcontextprotocol/clientCapabilities":{}}}}' \
  | dmtool mcp \
  | jq '.result | {resultType, supportedVersions, capabilities: (.capabilities|keys), cacheScope}'

```

```output
{
  "resultType": "complete",
  "supportedVersions": [
    "2026-07-28"
  ],
  "capabilities": [
    "tools"
  ],
  "cacheScope": "public"
}
```

**The tools are derived**, not authored: each is one cell of *(target × effect × return shape)* over the live command tree, so a tool is annotated read-only exactly when every op it covers is. That is what makes categorical approval safe — granting `dm.rule.read` cannot reach `rule remove`.

```bash
jq -nc '{jsonrpc:"2.0", id:2, method:"tools/list",
         params:{_meta:{"io.modelcontextprotocol/protocolVersion":"2026-07-28",
                        "io.modelcontextprotocol/clientCapabilities":{}}}}' \
  | dmtool mcp \
  | jq '{tools: (.result.tools|length),
         readOnly: [.result.tools[]|select(.annotations.readOnlyHint)|.name]}'

```

```output
{
  "tools": 30,
  "readOnly": [
    "dm.catalog",
    "dm.computation.read",
    "dm.config.read",
    "dm.field.read",
    "dm.group.read",
    "dm.include.read",
    "dm.model.emit",
    "dm.model.read",
    "dm.profile.read",
    "dm.rule.read",
    "dm.typedef.read",
    "dm.where-used",
    "dm.workspace.emit",
    "dm.workspace.read"
  ]
}
```

**A read carries its subject as content.** The model's DM-JSON goes in the request; the standard result envelope comes back as `structuredContent`, with the same answer rendered for a human in `content`. The server reads no file and keeps nothing.

```bash
jq -nc --rawfile model examples/models/order.dm.json \
  '{jsonrpc:"2.0", id:3, method:"tools/call",
    params:{name:"dm.model.read",
            arguments:{op:"check", model:$model},
            _meta:{"io.modelcontextprotocol/protocolVersion":"2026-07-28",
                   "io.modelcontextprotocol/clientCapabilities":{}}}}' \
  | dmtool mcp \
  | jq '.result | {isError, outcome: .structuredContent.outcome,
                   valid: .structuredContent.valid, text: .content[0].text}'

```

```output
{
  "isError": false,
  "outcome": "read",
  "valid": true,
  "text": "model is valid\n  (read · valid)\n"
}
```

**An edit returns the edited model.** The rule spec rides `files` under the parameter key the manifest publishes; the kernel checks the rule exactly as it would from the CLI, and the new DM-JSON comes back as an embedded resource. Persisting it is the caller's job — nothing was written anywhere.

```bash
jq -nc --rawfile model examples/models/order.dm.json \
  --arg spec '{
    "field": "/Order/DeliveryDate", "group": "/Order",
    "condition": "AllFieldsFilled(OrderDate, DeliveryDate) And DifferenceInDays(OrderDate, DeliveryDate) < 0",
    "code": "DELIVERY_BEFORE_ORDER",
    "messages": [ {"locale":"en_US","text":"Delivery date must not precede the order date."} ]
  }' \
  '{jsonrpc:"2.0", id:4, method:"tools/call",
    params:{name:"dm.rule.edit",
            arguments:{op:"add", model:$model, files:{"rule-spec.json":$spec}},
            _meta:{"io.modelcontextprotocol/protocolVersion":"2026-07-28",
                   "io.modelcontextprotocol/clientCapabilities":{}}}}' \
  | dmtool mcp \
  | jq '.result | {isError, outcome: .structuredContent.outcome,
                   changed: .structuredContent.changed,
                   returned: [.content[]|select(.type=="resource")
                              |{uri: .resource.uri, bytes: (.resource.text|length)}]}'

```

```output
{
  "isError": false,
  "outcome": "applied",
  "changed": {
    "rule": "/Order/DELIVERY_BEFORE_ORDER"
  },
  "returned": [
    {
      "uri": "dm:///out/subject.dm.json",
      "bytes": 11230
    }
  ]
}
```

**One revision, and anything else is refused legibly.** A client on the previous revision gets the protocol's own error with the supported set, not a mystery failure — so it can act on the answer instead of guessing.

```bash
jq -nc '{jsonrpc:"2.0", id:5, method:"tools/list",
         params:{_meta:{"io.modelcontextprotocol/protocolVersion":"2025-11-25",
                        "io.modelcontextprotocol/clientCapabilities":{}}}}' \
  | dmtool mcp \
  | jq '.error'

```

```output
{
  "code": -32022,
  "message": "Unsupported protocol version",
  "data": {
    "supported": [
      "2026-07-28"
    ],
    "requested": "2025-11-25"
  }
}
```

**No caller-supplied path, of any kind.** A path argument is refused rather than sanitised, and the refusal says what to send instead. That is why two callers cannot reach each other's models: the server holds none.

```bash
jq -nc --rawfile model examples/models/order.dm.json \
  '{jsonrpc:"2.0", id:6, method:"tools/call",
    params:{name:"dm.model.read",
            arguments:{op:"check", model:$model, args:{workspace:"/etc"}},
            _meta:{"io.modelcontextprotocol/protocolVersion":"2026-07-28",
                   "io.modelcontextprotocol/clientCapabilities":{}}}}' \
  | dmtool mcp \
  | jq -r '.error.message'

```

```output
`workspace` names a filesystem path (a directory searched to resolve a model's references), and this server accepts no caller-supplied path. Send content instead — a model as `model`, a referenced model in `references`, any other file under `files` — and read the result back out of the response.
```

**A model rarely stands alone.** A subject that includes another model, or imports its type definitions, needs those models too — so a request carries them in `references`. The server names every file itself, because a model's identity is its `header.id` rather than its filename.

```bash
jq -nc --rawfile app examples/models/multifile/app/storefront.dm.json \
       --rawfile lib examples/models/multifile/lib/catalog.dm.json \
  '{jsonrpc:"2.0", id:5, method:"tools/call",
    params:{name:"dm.model.read",
            arguments:{op:"check", model:$app, references:[$lib]},
            _meta:{"io.modelcontextprotocol/protocolVersion":"2026-07-28",
                   "io.modelcontextprotocol/clientCapabilities":{}}}}' \
  | dmtool mcp \
  | jq '.result | {isError, valid: .structuredContent.valid,
                   summary: .structuredContent.summary}'

```

```output
{
  "isError": false,
  "valid": true,
  "summary": "model is valid"
}
```

**Leave one out and it fails loudly, never quietly.** An absent reference is a rejection with a code, not a model that silently validates against half of itself.

```bash
jq -nc --rawfile app examples/models/multifile/app/storefront.dm.json \
  '{jsonrpc:"2.0", id:6, method:"tools/call",
    params:{name:"dm.model.read",
            arguments:{op:"check", model:$app},
            _meta:{"io.modelcontextprotocol/protocolVersion":"2026-07-28",
                   "io.modelcontextprotocol/clientCapabilities":{}}}}' \
  | dmtool mcp \
  | jq -c '.result.structuredContent | {outcome, code: .diagnostics[0].code}'

```

```output
{"outcome":"rejected","code":"RK_UNRESOLVED_REFERENCE"}
```

Because a request carries models rather than a closure, an edit may also **introduce** a reference: `typedef import`, `include add --reference` and `include set-reference` mount a model the subject does not reference yet, and it simply rides along in `references`. A refactor that *creates* a model — `group extract` — returns both files, the new sub-model and the rewritten subject.

## The hosted form

Everything above is the stdio transport, which a host launches. The same binary also binds a port and speaks Streamable HTTP, for callers that reach it over the network — upload the binary, start it, hand out the URL:

    dmtool mcp --http --host 0.0.0.0 --port 8080     # clients POST to https://your-host/mcp

*(Indented rather than fenced on purpose: `showboat verify` executes every fenced block, and a server that never returns would hang the demo gate forever. It only ever passed because port 8080 happened to be busy.)*

It is the *same* server: the dispatcher, the tool registry, and the invoker are reused unchanged, so every behaviour shown above holds identically. What the HTTP layer adds is the transport's own rules — the mirrored `MCP-Protocol-Version` / `Mcp-Method` / `Mcp-Name` headers must agree with the body (`400` + `-32020` if they do not), an unknown method is `404`, an unlisted browser `Origin` is `403`, the removed `GET`/`DELETE` session mechanics are `405`, and a body over the cap is `413`. Several callers are served by a bounded pool; past it the answer is an immediate `503` with `Retry-After`. The full contract is [`MCP-SPEC.md`](../docs/MCP-SPEC.md) §7.

A few verbs cannot answer honestly from a fragment, and the surface says so rather than hiding them: the `workspace` family reports over a whole directory, and `model rename` refuses on an *inward* scan for a sibling that includes the old id, so on a partial tree it would find nothing and permit the rename. Those ops run **only** under `authoritativeWorkspace: true` — the argument by which a caller takes responsibility for the completeness of what it sent — and are refused without it.

Standing one of these up for real is [`MCP-HOSTING.md`](../docs/MCP-HOSTING.md): the site configuration, the credentials, the manual deploy pipeline, and what the public endpoint deliberately is not.
