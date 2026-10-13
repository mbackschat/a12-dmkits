package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.edit.GroupEditor;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.CategoryRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.EnumRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringRef;

/**
 * A text field concatenates off its {@link StringRef} directly, and every concatenation receiver takes one, the way
 * {@code NumberRef} carries its own arithmetic (API-FINDINGS AF188).
 *
 * <p><b>The friction this removes.</b> {@code EnumRef} and {@code CategoryRef} joined text directly, while a text
 * field had to leave its ref first — {@code FIRST.asString().plus(LAST.asString())} — and could not stand on the
 * right of any {@code plus} at all. Fourteen internal call sites carried that detour.
 *
 * <p><b>The oracle is render-equality with the {@code asString()} spelling.</b> Each row asserts the sugar renders
 * as the long form it replaces, so it is no second lowering; the long form is kernel-locked by the concatenation
 * laws, and one mixed chain is put to the kernel here as well.
 *
 * <p>Own domain (an insurance policyholder's name and tier).
 */
class StringRefConcatenationTest {

    private static final String POLICY = DocumentModelDraft.named("policyholder").locale("en_US")
            .rootGroup("Holder", h -> h
                    .stringField("First", s -> { })
                    .stringField("Last", s -> { })
                    .enumField("Tier", e -> e.value("BASIC").value("PLUS").category("Band", "BASIC", "PLUS"))
                    .enumField("PriorTier", e -> e.value("BASIC").value("PLUS"))
                    .stringField("Display", s -> { }))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(POLICY);
    private static final StringRef FIRST = M.str("/Holder/First");
    private static final StringRef LAST = M.str("/Holder/Last");
    private static final EnumRef TIER = M.enumOf("/Holder/Tier");
    private static final EnumRef PRIOR_TIER = M.enumOf("/Holder/PriorTier");
    private static final CategoryRef BAND = TIER.category("Band");

    private static String render(StringExpr expression) {
        return new DslRenderer().render(expression.expression());
    }

    @Test
    void aTextRefIsAReceiverLikeItsExpression() {
        assertEquals(render(FIRST.asString().plus(LAST.asString())), render(FIRST.plus(LAST)));
        assertEquals(render(FIRST.asString().plus(" ")), render(FIRST.plus(" ")));
        assertEquals(render(FIRST.asString().plus(Dsl.text(" ").plus(LAST.asString()))),
                render(FIRST.plus(Dsl.text(" ").plus(LAST))));
        assertEquals(render(FIRST.asString().plus(TIER)), render(FIRST.plus(TIER)));
        assertEquals(render(FIRST.asString().plus(BAND)), render(FIRST.plus(BAND)));
    }

    @Test
    void everyReceiverTakesATextRef() {
        assertEquals(render(FIRST.asString().plus(" ").plus(LAST.asString())), render(FIRST.plus(" ").plus(LAST)));
        assertEquals(render(TIER.plus(LAST.asString())), render(TIER.plus(LAST)));
        assertEquals(render(BAND.plus(LAST.asString())), render(BAND.plus(LAST)));
        assertEquals(render(Dsl.firstFilledEnum(TIER.field(), PRIOR_TIER.field()).plus(LAST.asString())),
                render(Dsl.firstFilledEnum(TIER.field(), PRIOR_TIER.field()).plus(LAST)));
    }

    @Test
    void aMixedChainIsAComputationTheKernelAdmits() {
        EditSession session = EditSession.read(POLICY);
        GroupEditor holder = session.group("/Holder");
        holder.addComputation(holder.str("Display"), computation -> computation.name("DisplayComp")
                .calc(holder.str("First").plus(" ").plus(holder.str("Last")).plus(" ").plus(holder.enumOf("Tier")))
                .message(Dsl.en("The holder's display name.")).build());
        session.validate();

        assertEquals(render(FIRST.asString().plus(" ").plus(LAST.asString()).plus(" ").plus(TIER)),
                render(FIRST.plus(" ").plus(LAST).plus(" ").plus(TIER)));
    }
}
