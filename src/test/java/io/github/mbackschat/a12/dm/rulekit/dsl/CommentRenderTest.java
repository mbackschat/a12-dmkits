package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;

/**
 * Comments as a side-channel (carrier "c", the Phase-5 comment spike): comments live OFF the immutable
 * AST, in a {@link Comments} map keyed by <em>node identity</em>, and the renderer emits them as {@code ;;}
 * line comments at the node's position. This first increment proves the emit path — a leading (before)
 * comment on a condition renders as {@code ;; …} ahead of the canonical text. (Read-sourcing + authoring +
 * the round-trip come next.)
 */
class CommentRenderTest {

    @Test
    void rendersALeadingCommentBeforeACondition() {
        BoundModel m = Fixtures.subscription();
        NumberRef fee = m.num("/Subscription/Addons/MonthlyFee");
        Condition c = fee.filled().and(fee.gt(1000));

        Comments comments = Comments.none().before(c, "cap the monthly fee");
        String out = new DslRenderer().render(c, comments);

        // the side-channel comment is emitted as a ;; line ahead of the unchanged canonical render
        assertEquals(";; cap the monthly fee\n" + new DslRenderer().render(c), out);
    }

    @Test
    void rendersATrailingCommentNewlineSafelyAfterANode() {
        BoundModel m = Fixtures.subscription();
        NumberRef fee = m.num("/Subscription/Addons/MonthlyFee");
        Condition presence = fee.filled();
        Condition c = presence.and(fee.gt(1000));

        // a trailing (after) comment on the LEFT branch — the newline after ;; is what keeps the
        // following "And …" from being swallowed by the line comment (the newline-safety property)
        String out = new DslRenderer().render(c, Comments.none().after(presence, "must be present"));

        // the default renderer emits absolute paths (no baseGroup); the point here is the ;;-then-newline
        assertEquals("FieldFilled(/Subscription/Addons/MonthlyFee) ;; must be present\n"
                + " And [/Subscription/Addons/MonthlyFee] > 1000", out);
    }
}
