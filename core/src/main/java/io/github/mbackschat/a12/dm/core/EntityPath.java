package io.github.mbackschat.a12.dm.core;

import java.util.ArrayList;
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

    /**
     * The <b>relative reference from this path to {@code target}</b> — {@code ../} for each segment of this path
     * past the shared prefix, then the target's remaining segments.
     *
     * <p><b>A12 owns this operation and we match it rather than inventing one.</b> The counterpart is
     * {@code NodePath.relativize} → {@code ElementPathUtils.relPath(absPath, basePath)} ("the minimal relative
     * path from {@code basePath} to {@code absPath}"), and it is what PRODUCED every stored
     * {@code computedFieldRelPath} in existence: the {@code computedFieldId → computedFieldRelPath} migration
     * calls {@code absolutePathInDM.relativize(elem.path())} where {@code elem.path()} is the computation
     * ELEMENT's absolute path ({@code ReferenceViaPathStep.migrateComputedFields}), and the serializer writes
     * {@code getComputedField().getRelativePath()} back out ({@code ComputationSerializer}). Its algebra —
     * {@code ..} repeated {@code base.size() - commonAncestorLevel - 1} times, then the target's tail — agrees
     * with the code below segment for segment on well-formed absolute paths, cross-root pairs included (A12
     * returns {@code commonAncestorLevel = -1} there and emits the full walk; our leading {@code ""} segment
     * always matches, which yields the same count). <b>Trap: the argument order is mirrored.</b> A12 writes
     * {@code target.relativize(base)}; this writes {@code base.relativize(target)}, following
     * {@code java.nio.file.Path}, which is what a Java caller reaches for.
     *
     * <p><b>The base is an ELEMENT, not a directory.</b> A computation's {@code ../Field} names a field in the
     * computation's own group, because the first {@code ..} climbs off the computation element itself — which
     * is why A12's own producer passes the element path even though the comment beside it says "starting from
     * current group".
     *
     * <p><b>Round trip.</b> {@code ModelReader.resolveRelPath} is how a stored reference is read back, and it
     * inverts this for every path pair the callers can produce — pinned over an 11-path cross-product in
     * {@code EntityPathTest}. The inversion is NOT total over arbitrary text: a target carrying an empty segment
     * ({@code /A/}) relativizes to a reference the resolver then reads as {@code /A}. That input cannot arise
     * here, because both the CLI spec gate ({@code ModelPaths.isAbsoluteEntityPath}) and A12's own
     * {@code splitAbsPath} contract refuse an empty segment; it is stated rather than assumed away.
     *
     * <p>Identical paths relativize to the empty reference, which resolves back to the base. Nothing guards that
     * degenerate case in this class: a computation reaches it when its NAME equals its computed field's last
     * segment in the same group, and what refuses it is {@code RuleValidator.addPrecheck}'s
     * {@code RK_NAME_EXISTS} — so the raw {@code ModelWriter.addComputation} static, which skips that gate,
     * would persist an empty reference.
     */
    public String relativize(EntityPath target) {
        List<String> theirs = Objects.requireNonNull(target, "target").segments;
        int common = 0;
        while (common < segments.size() && common < theirs.size()
                && segments.get(common).equals(theirs.get(common))) {
            common++;
        }
        List<String> steps = new ArrayList<>();
        for (int i = common; i < segments.size(); i++) {
            steps.add("..");
        }
        steps.addAll(theirs.subList(common, theirs.size()));
        return String.join("/", steps);
    }

    /**
     * The absolute path a stored {@code reference} names, resolved against this base — the <b>inverse</b> of
     * {@link #relativize}. A reference starting with {@code /} is already absolute and passes through; each
     * {@code ..} pops one segment off the base; {@code .} and empty segments are skipped; anything else
     * descends.
     *
     * <p><b>The base is an ELEMENT, not a directory</b> — the mirror of {@link #relativize}'s own note. A rule's
     * or computation's reference is stored relative to that ELEMENT, which sits inside its group, so the first
     * {@code ..} climbs off the element and lands on the group: {@code ../Field} against {@code /Order/SomeRule}
     * is {@code /Order/Field}. The element's NAME never survives, which is why a caller holding only the group
     * can supply any name for the hop.
     *
     * <p><b>Why this is here rather than only in {@link ModelIndex#resolvePath}.</b> This direction already
     * existed, as a {@code static} on the model READER that never consults a model — pure path algebra filed
     * under the index. That filing is why the operation kept being rewritten by callers who went looking for it
     * in the path type: the interpreter carries its own {@code Paths.resolveErrorPath}, and the CLI's
     * {@code --field} corrective added another on 2026-09-04 which got the convention BACKWARDS — it read the
     * first {@code ..} as climbing ABOVE the group, so {@code ../CustomerName} under {@code /Order} named a
     * sibling of {@code /Order} instead of {@code /Order/CustomerName}. Nothing failed, because no test related
     * the two directions to each other. That is the defect class this record exists for (see the class doc): a
     * path relation spelled out at a call site instead of named on the value. {@code ModelReader.resolveRelPath}
     * now delegates here, so there is one implementation rather than an agreement to maintain.
     *
     * <p><b>The base is expected to be ABSOLUTE</b>, which every producer here is: a group path or element path
     * out of a model walk. On a relative base the result is still total but not meaningful — the first segment
     * is treated as the leading empty one and dropped — and it is pinned rather than left to discovery, because
     * this replaced a spelling that threw on one such input and produced a doubled separator on another.
     *
     * <p><b>Totality.</b> Deliberately total, matching the behaviour callers already depend on: a reference with
     * more {@code ..} than the base has segments clamps at the root level rather than failing, so
     * {@code /A/B} with {@code ../../../X} is {@code /X}. A caller that needs the difference — a corrective
     * proposing a value the author can use — tests the RESULT for the shape it needs
     * ({@code ModelPaths.isAbsoluteEntityPath}) rather than relying on this to refuse.
     * {@code EntityPathTest} pins the round trip against {@link #relativize} over a path cross-product.
     */
    public EntityPath resolve(String reference) {
        if (Objects.requireNonNull(reference, "reference").startsWith("/")) {
            return of(reference);
        }
        // Held without the leading empty segment so a climb to the root level is an empty list rather than a
        // special case, then re-anchored on the way out.
        List<String> resolved = new ArrayList<>(segments.subList(Math.min(1, segments.size()), segments.size()));
        for (String step : reference.split("/")) {
            if ("..".equals(step)) {
                if (!resolved.isEmpty()) {
                    resolved.remove(resolved.size() - 1);
                }
            } else if (!step.isEmpty() && !".".equals(step)) {
                resolved.add(step);
            }
        }
        return of("/" + String.join("/", resolved));
    }

    @Override
    public String toString() {
        return value();
    }
}
