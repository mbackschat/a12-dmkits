package io.github.mbackschat.a12.dm.cli.interp;

import java.util.List;

import io.github.mbackschat.a12.dm.interpreter.A12PartiallyKnownMultiPointerFormat;
import io.github.mbackschat.a12.dm.interpreter.A12PointerFormat;

/**
 * The one seam where a runtime pointer becomes <b>published</b> output — and therefore the one place it takes A12's
 * canonical form (CLI-SPEC §5 "Runtime output addresses cells by canonical A12 pointer").
 *
 * <p><b>Two renderings exist on purpose, and only one of them is a contract.</b> The interpreter's own
 * {@code formatPointer} writes a house form — leading slash, {@code [1]} spelled out on every group segment:
 * {@code /Order[1]/Items[2]/Count}. That form is an <em>internal comparison key</em>: the conformance corpus pins it
 * (CONFORMANCE-CORPUS-SPEC §7), the differential renders the kernel's own pointer through it so both sides compare,
 * and the published interpreter library exposes it as {@code Message.errorPath} to its JVM/JS consumers. None of
 * that changes. What changes is what <em>dmtool</em> prints, which must be A12's canonical
 * {@code Order/Items[2]/Count} — no leading slash, {@code [1]} omitted — because a pointer a user copies out of our
 * output has to be one they can paste back into A12.
 *
 * <p><b>The conversion goes through the type, never through string surgery.</b> Both pointer types accept the
 * house form (a leading slash is stripped as redundant, an explicit {@code [1]} is legal), so the boundary is
 * {@code parse} → the type → {@code render}: the invariants live in the type, and a pointer that cannot be
 * represented fails loudly here rather than being half-rewritten by a regex.
 *
 * <p><b>Which type depends on the channel, and that is not a detail.</b> A <em>message</em> pointer is A12's
 * {@code PartiallyKnownDocumentMultiPointer} — the type {@code IMessage.getErrorFieldPointer()} and its two
 * sibling accessors actually return — so it may carry a wildcard at any level, or the unknown index a parallel
 * iteration leaves on a level that matched no row. A <em>computed cell</em> pointer is an exact
 * {@code DocumentPointer}: every level resolved to one row. Those are different A12 types with different
 * domains, so {@link #canonical} and {@link #canonicalInstance} go through different ones. Routing a message
 * pointer through the exact type is what once forced the exact type to admit {@code -5} — a value A12's own
 * pointer parser cannot even read — and the resulting union was a shape A12 has nowhere.
 */
public final class A12Pointers {

    private A12Pointers() {
    }

    /**
     * The canonical A12 rendering of a <b>message-channel</b> pointer the interpreter produced.
     *
     * <p>Total on every shape a report path can carry, including A12's unknown repetition
     * ({@link A12PartiallyKnownMultiPointerFormat#UNKNOWN}, the {@code -5} a parallel iteration leaves on a level
     * with no matching row). A {@code null} or blank pointer passes through unchanged — some message kinds
     * legitimately carry none, and inventing an empty pointer would be worse than reporting the absence.
     */
    public static String canonical(String runtimePointer) {
        if (runtimePointer == null || runtimePointer.isBlank()) {
            return runtimePointer;
        }
        return A12PartiallyKnownMultiPointerFormat.INSTANCE.render(
                A12PartiallyKnownMultiPointerFormat.INSTANCE.parse(runtimePointer));
    }

    /** {@link #canonical(String)} over a list — the {@code referenced} / {@code fillToFix} operand pointers. */
    public static List<String> canonical(List<String> runtimePointers) {
        return runtimePointers.stream().map(A12Pointers::canonical).toList();
    }

    /**
     * The canonical <b>instance</b> pointer for a computed cell: its model path plus the target row's repetition
     * indices ({@code Computed.getIndices()}, already one per path part).
     *
     * <p>Goes through the <b>exact</b> pointer type, unlike {@link #canonical}: a computed cell has every level
     * resolved to one row, so an unknown or wildcard index here would mean the engine produced a value for a row
     * it could not name. That would be a defect worth failing on, not a shape to render.</p>
     *
     * <p>This is the reported row-identity loss. {@code model compute} published the bare declaration path for every
     * outcome, so four rows of one repeated computed field came out as four entries with the <em>same</em>
     * {@code field} and different values — associable only by list position. The indices were never missing from the
     * evaluator; they were dropped by this projection, which is why the fix is here and not in the engine.
     *
     * <p>Empty indices mean a caller built the {@link io.github.mbackschat.a12.dm.interpreter.model.Computed} by
     * hand, or the target is not repeated: then the instance and the declaration coincide.
     */
    public static String canonicalInstance(String path, List<Integer> indices) {
        if (indices == null || indices.isEmpty()) {
            return declaration(path);
        }
        return A12PointerFormat.INSTANCE.format(path, indices);
    }

    /**
     * The canonical <b>declaration</b> pointer: the model path with no repetition at all — what the computation
     * declares, as opposed to which row it produced. Published alongside the instance so a consumer can group rows
     * by their declaration without parsing indices back out.
     */
    public static String declaration(String path) {
        return path == null ? null : path.startsWith("/") ? path.substring(1) : path;
    }
}
