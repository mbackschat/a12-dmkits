package io.github.mbackschat.a12.dm.cli.interp;

import java.util.List;

import io.github.mbackschat.a12.dm.interpreter.A12Pointer;
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
 * <p><b>The conversion goes through the type, never through string surgery.</b> {@link A12PointerFormat#parse}
 * already accepts the house form (a leading slash is stripped as redundant, an explicit {@code [1]} is legal), so
 * the boundary is {@code parse} → {@link A12Pointer} → {@code format}: the invariants live in the type, and a
 * pointer that cannot be represented fails loudly here rather than being half-rewritten by a regex.
 */
public final class A12Pointers {

    private A12Pointers() {
    }

    /**
     * The canonical A12 rendering of a runtime pointer the interpreter produced.
     *
     * <p>Total on every shape the interpreter emits, including A12's unknown repetition
     * ({@link A12PointerFormat#UNKNOWN_REPETITION}, the {@code -5} a parallel iteration leaves on a level with no
     * matching row). A {@code null} or blank pointer passes through unchanged — some message kinds legitimately
     * carry none, and inventing an empty pointer would be worse than reporting the absence.
     */
    public static String canonical(String runtimePointer) {
        if (runtimePointer == null || runtimePointer.isBlank()) {
            return runtimePointer;
        }
        A12Pointer parsed = A12PointerFormat.INSTANCE.parse(runtimePointer);
        return A12PointerFormat.INSTANCE.format(String.join("/", parsed.getNames()), parsed.getRepetitions());
    }

    /** {@link #canonical(String)} over a list — the {@code referenced} / {@code fillToFix} operand pointers. */
    public static List<String> canonical(List<String> runtimePointers) {
        return runtimePointers.stream().map(A12Pointers::canonical).toList();
    }

    /**
     * The canonical <b>instance</b> pointer for a computed cell: its model path plus the target row's repetition
     * indices ({@code Computed.getIndices()}, already one per path part).
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
