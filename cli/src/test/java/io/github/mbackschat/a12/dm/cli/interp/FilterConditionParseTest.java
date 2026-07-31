package io.github.mbackschat.a12.dm.cli.interp;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.interpreter.parse.ParserKt;

/**
 * Can the interpreter's condition parser read a starred operand's {@code Having} <b>filter</b>?
 *
 * <p>This exists because a test that was <em>meant</em> to lock the filter-descent fix passed with the fix disabled.
 * The green was real and the reason was not: the whole condition failed to parse, so the corrective stage's
 * {@code catch (RuntimeException) -> Map.of()} returned no comparisons at all and nothing could be pinned — the same
 * observable outcome as "counted both, therefore ambiguous", reached for an entirely different reason.
 *
 * <p>Two things follow, and the second is the one that matters. The descent fix is still right (an uncounted filter
 * comparison makes the uniqueness test unsound), but it is <b>unreachable</b> for any condition the parser cannot
 * read. And a parse failure on a legal, kernel-accepted condition silently disables the whole text-path corrective:
 * no diagnostic, no counter, nothing in the output distinguishes "no corrective applies" from "we could not read
 * your condition". The tolerant {@code catch} was justified as "never worse than today", which is true of the
 * corrective and false of the <em>visibility</em> — it converts a parser gap into silence.
 *
 * <p>So this class asks the narrow question directly, against the parser rather than through the corrective, where
 * the answer cannot be confounded.
 */
class FilterConditionParseTest {

    private static final String GROUP = "/Order";

    /** The plain form, as the control: whatever the filter case does, this must parse. */
    @Test
    void aComparisonWithoutAFilterParses() {
        assertNotNull(parse("[/Order/CustomerName] < [/Order/ProductCode]"));
    }

    /**
     * The filter form. If this throws, the corrective stage is silently inert for every condition containing a
     * {@code Having} clause — which is a gap in its own right, independent of what the walk counts.
     */
    @Test
    void aStarredOperandWithAHavingFilterParses() {
        assertNotNull(parse("MaxValue(/Order/Items*/Count Having [/Order/Items/Count] < 5) > 1"));
    }

    private static Object parse(String condition) {
        return ParserKt.parseCondition(condition, GROUP, ParserKt.getDEFAULT_RESOLVER());
    }
}
