# IG implementation review

This ledger records read-only implementation-review verdicts for a12-rulekit. It lives with the project documentation so review findings travel across machines. Entries are self-contained so a new agent can verify and act on them without the originating conversation.

## Working rules for follow-up agents

### Reviewers who write chapters

Reviews are read-only unless the owner explicitly asks for changes. A reviewer writing a new chapter independently verifies every cited claim against the live code, makes the chapter self-contained, and records exact evidence, required corrections, and acceptance checks. The implementor rule below does not reduce that review obligation.

### Implementors who resolve chapters

Treat the completed chapter as the authoritative and complete work brief: it already contains the reviewed facts, evidence, scope, corrections, and acceptance checks. Follow the reviewer's explicit instructions exactly as written; do not restart or repeat the review, add to or question the instructions, reinterpret them, broaden the audit, reopen ratified decisions, redesign adjacent surfaces, or edit unrelated proposals/documents. Re-check only the live locations needed for the recorded fix and its direct guard, unless implementation exposes a concrete contradiction or genuine blocker.

Resolve the chapter immediately with the smallest clean root fix and its named acceptance checks; do not overthink it. Completion means clearing the associated IG gap: finish every named residual, graduate the durable result into its owning finding/specification, and delete the IG row rather than moving ordinary unfinished work to “deferred” or “ongoing-incremental.” Deferral is allowed only for an owner-ratified external re-open trigger or a genuine in-scope blocker that cannot be resolved, and that exception must be recorded plainly.

Keep every correction within the recorded findings, including the instruction not to broaden work into rewrites of unconditional “kernel-exact,” “full conformance,” “full fidelity,” or “linear” language. Verify and commit the fix, add the commit and verification evidence beneath every resolved issue, preserve the original verdict as review history, and leave the result ready for independent review. These rules bind the primary implementer and every delegated subagent for every current and future implementation chapter; the primary implementer must include them in delegated work and enforce them during integration.

---

## Review status

### Active chapters

None.

### Resolution index

None.

## Resolved chapter archive

None.
