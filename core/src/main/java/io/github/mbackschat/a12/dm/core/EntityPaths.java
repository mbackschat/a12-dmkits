package io.github.mbackschat.a12.dm.core;

/**
 * The <b>containment relation between two model entity paths</b> — the one place in this repo that decides whether
 * {@code /Order/Shipping/Street} lies inside {@code /Order/Shipping}.
 *
 * <p><b>Why this is a class and not a one-liner at each call site.</b> The obvious spelling is wrong at two
 * different ends, and both ends have shipped here as defects, in opposite directions:
 *
 * <ul>
 *   <li>A bare {@code path.startsWith(ancestorPath)} compares STRING prefixes, not PATH prefixes, so
 *       {@code /Order/BillingContact/Email} comes out inside {@code /Order/Billing}. That reported a reference
 *       that does not exist ({@code CG16}).</li>
 *   <li>Adding the separator — {@code path.startsWith(ancestorPath + "/")} — fixes that end and breaks the other:
 *       a group is then not inside <em>itself</em>, because {@code "/Order/Shipping"} does not start with
 *       {@code "/Order/Shipping/"}. That is how {@code group extract} came to refuse a rule referencing the very
 *       group being extracted, an over-refusal naming a problem no author can fix ({@code CG17}).</li>
 * </ul>
 *
 * <p>Both predicates below are therefore segment-boundary correct, and they differ in exactly one case: whether a
 * path counts as inside itself. <b>Choosing the wrong one is the whole defect class</b>, which is why they are
 * named rather than spelled out — a reader of {@code isStrictlyInside} can see which question is being asked, and
 * a reader of {@code startsWith(x + "/")} cannot.
 *
 * <p>{@code "/"} is <em>not</em> special-cased. The root group is a named entity ({@code /Order}), so {@code "/"}
 * only ever appears as a caller-level "whole model" sentinel; a caller that means everything tests for its own
 * sentinel before asking about containment.
 *
 * @see ModelIndex for resolving a path to the element it names — this class is pure path algebra and never
 *      consults a model
 */
public final class EntityPaths {

    private EntityPaths() {
    }

    /**
     * Whether {@code path} is {@code ancestorPath} itself, or lies below it — compared at a path-segment boundary.
     *
     * <p>This is the predicate a <b>safety gate</b> or a <b>reachability</b> question wants: "is this element
     * within that subtree", where the subtree root is part of its own subtree.
     */
    public static boolean isInside(String path, String ancestorPath) {
        return path.equals(ancestorPath) || isStrictlyInside(path, ancestorPath);
    }

    /**
     * Whether {@code path} lies strictly <em>below</em> {@code ancestorPath} — the same comparison, excluding the
     * ancestor itself.
     *
     * <p>This is the predicate a <b>descendant</b> question wants: "is this field within that group", where a
     * group is not one of its own fields.
     */
    public static boolean isStrictlyInside(String path, String ancestorPath) {
        return path.startsWith(ancestorPath + "/");
    }
}
