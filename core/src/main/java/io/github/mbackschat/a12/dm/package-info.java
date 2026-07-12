/**
 * a12-dmkits — model-aware, typed, combinator-style kits for the A12 Kernel document model. The
 * <b>rulekit</b> authors validation- and computation-rule conditions through a typed builder, reads
 * existing rule text back into the same typed representation (via the kernel adapter, Phase 2),
 * modifies with reviewable diffs, and renders + validates against the real kernel; the <b>modelkit</b>
 * (its own module) authors document-model <em>structure</em> and emits DM-JSON the kernel accepts.
 *
 * <p>This is a personal exploration; it is <strong>not</strong> an official A12 artifact and is
 * not affiliated with or endorsed by mgm. Shipped code binds only to the kernel's public OSS
 * surface (see {@code docs/PROJECT-DESIGN.md} v2, N8/D15).
 *
 * <p>The package tree is filled in incrementally per the phased roadmap (PROJECT-DESIGN §16); this root
 * package currently only establishes the namespace.
 */
package io.github.mbackschat.a12.dm;
