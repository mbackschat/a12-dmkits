package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.Optional;

import io.github.mbackschat.a12.dm.core.EntityPath;
import io.github.mbackschat.a12.dm.core.HasEntityPath;

/**
 * An operand that can repeat or overlap another in the same list — the single place the kernel's two
 * parameter-duplicate arms are modelled, so every carrier asks one question instead of each rolling its own.
 *
 * <p><b>Why one type rather than a check per carrier.</b> The kernel raises {@code MVK_DUPLICATE_PARAM1} and
 * {@code MVK_DUPLICATE_PARAM2} from two private lines with a single shared caller that invokes both arms in
 * sequence. So the predicate is uniform by construction and the carriers differ only in which operands their
 * list can hold; the call-site and operator denominators are in KERNEL-FINDINGS {@code kf1015}.
 * Modelling it once also removes the reason two hand-written mirrors could drift apart unnoticed: starredness
 * had no accessor on any operand union, so each gate re-derived it by {@code instanceof} inside its own switch.
 *
 * <p><b>The identity is exactly four components</b> — resolved path, category projection, semantic index, and
 * the {@code $} outer-iteration flag. The kernel's {@code EntityRef.equals} compares seven; the other three
 * cannot discriminate in an A12 model and were each measured out rather than argued out, so four is exact
 * rather than lenient. Two consequences worth stating because a naive key gets them wrong: the path must be the
 * RESOLVED one, since the kernel normalizes relative to absolute before comparing, and the diagnostic message
 * renders fewer components than the predicate compares, so message text is never the key.
 */
public interface DuplicateOperand extends HasEntityPath {

    /** The rendered path — {@code *} on flattened segments, {@code field -> Cat} for a category projection,
     *  {@code base For "key"} for an index selection. Diagnostics only; never an identity. */
    String renderedPath();

    /**
     * The base entity's path — no stars, no category, no index — for F9/error-field checks and for the
     * containment relation. For a group-scope arm this is the GROUP's path: the operand references the group,
     * and the fields it contributes are the kernel's expansion of it.
     */
    String fieldPath();

    @Override
    default EntityPath entityPath() {
        return EntityPath.of(fieldPath());
    }

    /**
     * The exact-reference identity, or EMPTY for a WILDCARDED operand.
     *
     * <p>Empty is not "unknown" — it is the kernel's own exemption. Its direct arm skips a ref carrying a
     * wildcard ({@code ref.hasWildCards() && !parser.isHModel()}), so the same starred list may occupy several
     * authored slots and is expanded and consumed again at each. The exemption is A12-only; in an H model
     * there is no skip at all.
     */
    Optional<String> exactIdentity();

    /**
     * The modifiers the CONTAINMENT arm compares alongside the path relation — the category projection and the
     * semantic index, empty when the operand carries neither.
     *
     * <p>Containment is not a bare path-prefix test: one variable apart, a starred group beside its starred
     * descendant is {@code MVK_DUPLICATE_PARAM2} while the same group INDEX-SELECTED instead is not a
     * duplicate at all. Unlike the exact arm, containment does <b>not</b> skip a wildcard — a starred ancestor
     * overlaps its starred descendant — which is why every arm must answer this, wildcarded ones included.
     */
    default String overlapModifier() {
        return "";
    }
}
