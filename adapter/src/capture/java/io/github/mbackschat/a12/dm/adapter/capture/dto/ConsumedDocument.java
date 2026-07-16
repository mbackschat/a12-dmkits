package io.github.mbackschat.a12.dm.adapter.capture.dto;

/**
 * The identity of the case/placement document a runner ACTUALLY consumed (the audit's Finding 3): the
 * count and canonical SHA-256 of the placement stream recorded at the runner's own document-building
 * boundary — each placement is recorded AS it is applied to the engine, never copied from the request —
 * so a placement changed or dropped during placement-to-document adaptation changes this identity. The
 * canonical form is owned by {@code schema.CaseDocumentIdentity} (the verifier recomputes the request-side
 * identity from the packet's copied request and requires equality: v1 adapts nothing, supplied ==
 * consumed).
 */
public record ConsumedDocument(int placements, String canonicalSha256) {
}
