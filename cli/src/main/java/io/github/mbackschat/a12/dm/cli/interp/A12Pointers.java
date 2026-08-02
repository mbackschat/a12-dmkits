package io.github.mbackschat.a12.dm.cli.interp;

import java.util.List;

import io.github.mbackschat.a12.dm.interpreter.A12PartiallyKnownMultiPointer;
import io.github.mbackschat.a12.dm.interpreter.A12PointerFormat;
import io.github.mbackschat.a12.dm.interpreter.MessagePointers;

/**
 * The one seam where a runtime pointer becomes <b>published</b> output, and therefore the one place it takes A12's
 * canonical form where A12 defines one (CLI-SPEC §5 "Runtime output addresses cells by canonical A12 pointer").
 *
 * <p><b>Two renderings exist on purpose, and only one of them is a contract.</b> The interpreter's own house form
 * writes a leading slash and spells {@code [1]} on every group segment: {@code /Order[1]/Items[2]/Count}. That form
 * is an <em>internal comparison key</em> — the conformance corpus pins it (CONFORMANCE-CORPUS-SPEC §7) and the
 * differential renders the kernel's own pointer through it so both sides compare. What <em>dmtool</em> prints must
 * be A12's canonical {@code Order/Items[2]/Count} — no leading slash, {@code [1]} omitted — because a pointer a user
 * copies out of our output has to be one they can paste back into A12.
 *
 * <p><b>Which A12 type a channel carries decides what can be rendered at all.</b> A <em>message</em> address is
 * {@code PartiallyKnownDocumentMultiPointer} — what {@code IMessage.getErrorFieldPointer()} and its two sibling
 * accessors return — so it may carry a wildcard at any level, or the unknown index a parallel iteration leaves on a
 * level that matched no row, and <em>A12 gives that type no pointer string</em>. A <em>computed cell</em> address is
 * an exact {@code DocumentPointer}, every level resolved to one row, and that one does have a canonical string. So
 * {@link #canonicalInstance} is always canonical A12, while {@link #canonical(A12PartiallyKnownMultiPointer)} is
 * canonical A12 exactly when the message address happens to be an exact pointer and the house form otherwise.
 *
 * <p><b>That decision is taken on the typed pointer, never by re-reading a string.</b> The house form omits the
 * terminal repetition, so {@code ("/Plan/Demand", [1, -5])} renders {@code /Plan[1]/Demand} — which parses
 * perfectly well as the <em>exact</em> pointer {@code Plan[1]/Demand}. A try-parse fallback therefore does not
 * detect an unresolved address; it silently promotes one to a resolved-looking pointer that names a different cell.
 * The engine retains the typed value precisely so this seam never has to guess.
 */
public final class A12Pointers {

    private A12Pointers() {
    }

    /**
     * The published rendering of a <b>message-channel</b> address.
     *
     * <p>Canonical A12 when the address names exactly one cell, and the interpreter's house form when it does not.
     * Both branches are decided by {@link MessagePointers}, from the pointer value itself.
     */
    public static String canonical(A12PartiallyKnownMultiPointer pointer) {
        if (pointer == null) {
            return null;
        }
        String exact = MessagePointers.INSTANCE.canonicalExactForm(pointer);
        return exact != null ? exact : MessagePointers.INSTANCE.houseForm(pointer);
    }

    /** {@link #canonical(A12PartiallyKnownMultiPointer)} over a list — the {@code referenced} / {@code fillToFix} channels. */
    public static List<String> canonical(List<A12PartiallyKnownMultiPointer> pointers) {
        return pointers.stream().map(A12Pointers::canonical).toList();
    }

    /**
     * The published rendering of a channel that is a <b>concrete cell address carried as text</b>: a rule
     * suppression's offending field, and an unsupported-construct entry whose subject is a {@code CELL}. Both name
     * one instantiated cell of the document under evaluation, so every level is resolved by construction and the
     * exact pointer type can read the string back.
     *
     * <p>Kept apart from {@link #canonical(A12PartiallyKnownMultiPointer)} deliberately: this one <em>does</em>
     * re-read text, which is only sound because no sentinel can reach it. A message address must not be routed
     * here — see the class note on why parsing the house form cannot detect an unresolved level.
     *
     * <p>A {@code null} or blank value passes through unchanged; inventing a pointer would be worse than
     * reporting the absence.
     */
    public static String canonicalCell(String cellPointer) {
        if (cellPointer == null || cellPointer.isBlank()) {
            return cellPointer;
        }
        try {
            return A12PointerFormat.INSTANCE.render(A12PointerFormat.INSTANCE.parse(cellPointer));
        } catch (IllegalArgumentException notAnExactPointer) {
            return cellPointer;
        }
    }

    /**
     * The canonical <b>instance</b> pointer for a computed cell: its model path plus the target row's repetition
     * indices ({@code Computed.getIndices()}, already one per path part).
     *
     * <p>Goes through the <b>exact</b> pointer type, unlike {@link #canonical(A12PartiallyKnownMultiPointer)}: a
     * computed cell has every level resolved to one row, so an unknown or wildcard index here would mean the engine
     * produced a value for a row it could not name. That would be a defect worth failing on, not a shape to
     * render.</p>
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
