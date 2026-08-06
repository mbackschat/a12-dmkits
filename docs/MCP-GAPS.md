# dmtool over MCP — gaps

> **What this is.** Open and deferred gaps on the **MCP server surface** that [`MCP-SPEC.md`](MCP-SPEC.md) owns — the wire, the tool projection, and the content rules of `dmtool mcp`. Rows carry `MG<n>` ids. The verb surface itself stays with [`CLI-SPEC.md`](CLI-SPEC.md) and the agent-facing conformance axis with [`CLI-CONFORMANCE-GAPS.md`](CLI-CONFORMANCE-GAPS.md); a gap that is really about a *verb* belongs there, not here.
>
> **Lifecycle.** A gap lives here only while open. When it ships its row is DELETED in the same commit and its rationale graduates to [`MCP-SPEC.md`](MCP-SPEC.md); git is the what/when. No shipped list, ever.
>
> **Current state.** The deferrals below were all decided while the design was being built, not discovered afterwards, and each names what would re-open it. The design record they were taken against is the archived [MCP-SUPPORT-PROPOSAL](archived/MCP-SUPPORT-PROPOSAL.md).

---

## 1. Open

_None open._

## 2. Deferred — with re-open triggers

- **MG1 — MCP resources (`resources/list`, `resources/templates/list`, `resources/read`).** The `dm:///` URI space (`dm:///help/{target}/{op}`, `dm:///schema/{target}/{op}`, `dm:///operators`, …) is designed but not served. It is a *second* implementation of content the tool surface already carries, and host support for `resources` has historically lagged `tools`, so the tool path is mandatory either way and the resource path buys host-side caching that nothing has measured a need for. **Re-open when** a target host is confirmed to read resources *and* a caller is re-fetching the same per-verb help often enough for caching to matter.

- **MG2 — MCP Apps (`io.modelcontextprotocol/ui`).** The in-chat HTML report widget that [`MODEL-REVIEW-GAPS.md`](MODEL-REVIEW-GAPS.md) §2 defers "when dmtool ships as an MCP server". Shipping the server fires that trigger; the widget itself is a separate extension, negotiated through capabilities and versioned independently. **Re-open when** the report surface is wanted in-chat rather than as a file.

- **MG3 — a hosted (Streamable HTTP) transport.** Everything hosting needs beyond the wire — the deployment shapes, the requirement set H1–H12 (never silently enter a paid tier; the bound must hold under hostile traffic; EU jurisdiction; a bounded request-payload cap), and the candidate assessments — is recorded in the [proposal](archived/MCP-SUPPORT-PROPOSAL.md) §13 and Appendix A, and none of it is built. **Decide the licensing first:** hosting counts as Distribution under EUPL Article 1, so the hosted combined work must be offered under EUPL-1.2 with a maintained source repository, an obligation the local case never carries. **Re-open when** a caller wants dmtool without installing it.

- **MG4 — an MCP arm for the self-sufficiency eval.** [`EVAL-SPEC.md`](EVAL-SPEC.md) §2a's *bare-tool* arm means the CLI without the skill; it is not an MCP arm, and the harness drives `${CLI}` command forms throughout. So how much judgment an MCP-only caller loses — the residue no registry can derive: polarity, the *is this even a rule?* gate, the per-row-versus-aggregate recasting trap — is **unmeasured**, and the tool descriptions' sufficiency is an assumption. **Re-open when** that question is worth the harness work and the run cost.

- **MG5 — concurrent dispatch.** Dispatch is serialised, which is the only posture whose thread-safety is established. A multi-caller shape needs that claim established rather than assumed, and `CustomJsWorker` — a single shared Node worker with `synchronized` methods — is a known cross-request bottleneck. A bounded queue returning an immediate busy error would be required, because this revision removed stream resumability, so a timed-out request is re-issued as a *new* one and unbounded queueing invites a retry storm. **Re-open with** MG3, or with any second concurrent caller.

- **MG6 — in-flight cancellation.** `notifications/cancelled` is accepted and produces no further messages for the request, which satisfies the MUST trivially under serialised dispatch: the reader is blocked inside the verb, so the notification is read only after the result was written. Actually *interrupting* work needs a reader thread and a cancellation-aware invoker. **Re-open with** MG5, or when a verb slow enough to be worth cancelling exists.

- **MG7 — the closed-world verbs, behind an authority assertion.** `model rename` and the four `workspace` verbs are not served (MCP-SPEC §5). The design that would reach them is a caller-declared **authority assertion**: the request states that the supplied tree is an authoritative complete workspace, and a verb whose correctness depends on input-set completeness runs only under it and is **refused** without it — never silently degraded. **Re-open when** a caller needs a workspace-scoped answer, or with MG3, where a carried resource tree makes the assertion natural.

- **MG8 — carried directory content.** A content parameter that takes a *directory* (the two custom-JS dirs) is refused: inline content for a tree is a different shape from inline content for a document. That refusal is currently also what keeps `CustomJsWorker`'s per-call shutdown hook and its long-lived `node` child unreachable from a session, so **serving directory content re-opens the residency work with it** — the hook must be removed and the worker closed per call before a resident server may reach it.

- **MG9 — the closed-world denominator is unaudited.** Five leaves are known to need input-set completeness. Whether any *other* verb makes a claim quantified over its input set has not been established by enumeration, so the exclusion list rests on the two mechanisms found rather than on a sweep. **Re-open** before MG7 is built, since the assertion needs the true denominator.

## 3. Out of scope

- **Any `2025-11-25` compatibility path.** No `initialize` handling beyond the corrective refusal, and no negotiated downgrade. A partly-served older client is the failure mode this project rejects outright; the refusal is legible and names where the supported set is published.

- **Multi round-trip requests (`resultType: "input_required"`), `subscriptions/listen`, sampling, roots, logging, and the tasks extension.** All are server-initiated or session-shaped interaction this surface does not need: every call is self-contained, and there is nothing to ask the caller mid-request.

- **Any new dependency.** Not an MCP SDK (none implements this revision), not a JSON-RPC library, not a runtime JSON-Schema validator. This also excludes wiring the MCP Inspector into CI: it is a per-method smoke tool, and the wire tests catch a broken frame or a missing method deterministically and in-process, so it would buy nothing and cost a Node dependency in the surface whose headline decision is zero new dependencies.

- **A local-convenience framing.** The positioning at the top of [`MCP-SPEC.md`](MCP-SPEC.md) forbids recommending MCP where the binary can be run. This is out of scope as a *documentation stance*, not merely unbuilt.
