package io.github.mbackschat.a12.dm.modelkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The one place this kit reads and writes A12's {@code youngerThan1900Check}, on all three carriers
 * ({@code DateType}, {@code DateFragmentType}, {@code DateRangeType}).
 *
 * <p>A12 stopped evaluating the attribute in kernel 31.2 (A12K-4073) and still reads and writes it, so the kit keeps
 * the declaration round-tripping and nothing more. A12 removes it with A12K-4439; deleting this class is then the
 * kit's half of that removal, and the compile errors it leaves name every caller. The public authoring surface is
 * deprecated for removal, so each consumer site outside this kit carries {@code @SuppressWarnings("removal")}, which
 * the {@code -Xlint:removal -Werror} ratchet of the consuming modules turns into a build failure when missing.
 */
final class YoungerThan1900Check {

    /** The DM-JSON member name, on each of the three carriers. */
    static final String MEMBER = "youngerThan1900Check";

    /** The key {@code dmtool}'s field and type-definition specs use for the same declaration. */
    static final String TOOL_KEY = "rejectBefore1900";

    private YoungerThan1900Check() {
    }

    /** Whether a carrier body declares the check, bound as A12 binds a primitive boolean member. */
    static boolean declaredIn(JsonNode body) {
        return A12Booleans.isSet(body.path(MEMBER));
    }

    /** Writes the member into a carrier body when declared; the absent member is A12's default. */
    static void writeInto(ObjectNode body, boolean declared) {
        if (declared) {
            body.put(MEMBER, true);
        }
    }

    /** Copies a declared check from a carrier body into a {@code dmtool} spec block. */
    static void copyToToolSpec(JsonNode body, ObjectNode spec) {
        if (declaredIn(body)) {
            spec.put(TOOL_KEY, true);
        }
    }
}
