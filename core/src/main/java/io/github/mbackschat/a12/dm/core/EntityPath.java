package io.github.mbackschat.a12.dm.core;

import java.util.List;
import java.util.Objects;

/**
 * A <b>model entity path, held as its segments</b> — {@code /Order/Shipping/Street} as
 * {@code ["", "Order", "Shipping", "Street"]} — so that "does this path lie inside that one" is a property of the
 * type rather than a rule every call site must remember.
 *
 * <p><b>Why the type and not just a helper.</b> The containment relation shipped wrong here twice in one week, in
 * opposite directions: {@code startsWith(ancestor)} crosses a segment boundary and puts
 * {@code /Order/BillingContact/Email} inside {@code /Order/Billing} ({@code CG16}), while
 * {@code startsWith(ancestor + "/")} fixes that end and excludes a subtree root from its own subtree
 * ({@code CG17}). Naming the two relations fixed the reported sites and a source census stops a third
 * hand-spelling from appearing — but that is a discipline, and the census admits a blind spot: a naive
 * {@code startsWith(ancestor)} is indistinguishable from any other string-prefix test. <b>A path parsed into
 * segments has no text-prefix operation to reach for</b>, so a call site holding an {@code EntityPath} cannot
 * spell either defect. (Credit where due: this is a12-kernel-lean's shape, offered as a reciprocal to our fix.)
 *
 * <p><b>The parse is deliberately permissive, and that is a contract, not an omission.</b> This type replaces a
 * string comparison that rejected nothing, so validating here would introduce a client-side refusal with no
 * measured basis — and because these paths also arrive when reading a kernel-authored model, an over-strict
 * parse would make a legal model unreadable rather than merely unauthorable. So {@code of} splits and never
 * judges: a relative path, a starred segment, a doubled separator and the {@code "/"} sentinel all pass through
 * and compare exactly as they did before. Adding validation is a separate, measured decision.
 *
 * @see EntityPaths for the string-typed relation this replaces at converted call sites
 * @see ModelIndex for resolving a path to the element it names — this class is pure path algebra
 */
public record EntityPath(List<String> segments) {

    public EntityPath {
        segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
    }

    /**
     * Split {@code path} on the separator, <b>keeping empty segments</b>.
     *
     * <p>The limit of {@code -1} is load-bearing rather than defensive. Java's default split discards trailing
     * empty strings, which collapses {@code "/"} and {@code ""} to the same single-empty-segment list — and those
     * two are not the same question: the relation being replaced reported {@code "/Order"} as inside {@code ""}
     * and <em>not</em> inside {@code "/"}. Keeping the empties preserves both answers.
     */
    public static EntityPath of(String path) {
        return new EntityPath(List.of(Objects.requireNonNull(path, "path").split("/", -1)));
    }

    /** The canonical string form — exactly what {@link #of} was given. */
    public String value() {
        return String.join("/", segments);
    }

    /**
     * Whether this path is {@code ancestor} itself, or lies below it.
     *
     * <p>The predicate a <b>safety gate</b> or a <b>reachability</b> question wants: "is this element within that
     * subtree", where the subtree root is part of its own subtree.
     */
    public boolean isInside(EntityPath ancestor) {
        return equals(ancestor) || isStrictlyInside(ancestor);
    }

    /**
     * Whether this path lies strictly <em>below</em> {@code ancestor} — the same comparison, excluding the
     * ancestor itself.
     *
     * <p>The predicate a <b>descendant</b> question wants: "is this field within that group", where a group is
     * not one of its own fields.
     */
    public boolean isStrictlyInside(EntityPath ancestor) {
        List<String> theirs = ancestor.segments;
        if (segments.size() <= theirs.size()) {
            return false;
        }
        return segments.subList(0, theirs.size()).equals(theirs);
    }

    @Override
    public String toString() {
        return value();
    }
}
