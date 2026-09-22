package io.github.mbackschat.a12.dm.adapter.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupListRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

/**
 * The {@code $}-correlated group operand in a MULTI-operand slot — the list carrier of the scalar case
 * {@link CorrelatedGroupPresenceReadTest} covers, and the half {@code DEV-API-GAPS} {@code DG49} was opened
 * for.
 *
 * <p><b>The direction that has no authoring call site to notice it.</b> The kernel admits
 * {@code AllGroupsFilled($A, $B)} inside a {@code Having} filter beside an in-scope conjunct — measured in
 * {@code validate.laws.HavingFilterConditionLeafLawsTest.aMarkedGroupOperandLISTObeysTheSameTwoPositions},
 * where the marker moves no verdict. Before {@link GroupListRef} could hold a marker, such a model was not
 * merely unauthorable: the converter's {@code verifyOuterPreserved} backstop counted source markers against
 * rendered ones and degraded the whole rule to a verbatim {@link ReadResult.Opaque}. A model a user already
 * has becomes unreadable, and no authoring path ever exercises that.
 *
 * <p><b>Three assertions on the quantifier carrier, because a render round-trip alone is satisfiable by two
 * wrongs.</b> The read must be {@code Converted}; the operands must actually carry their marker; and the
 * re-render must equal the authored condition. Dropping the marker on both sides would pass the third alone.
 * The unmarked control proves the flag tracks the source rather than being set unconditionally.
 *
 * <p>The group COUNT carrier is measured as a PAIR instead, and the reason is in its own javadoc: it is
 * opaque for a converter limit that has nothing to do with correlation, and only the unmarked row shows that.
 *
 * <p>Own domain: a policy renewal whose line items are filtered while two settlement groups' presence is read
 * from the captured outer environment.
 */
class CorrelatedGroupListReadTest {

    private static final String BILLING = "/Renewal/Billing";
    private static final String SHIPPING = "/Renewal/Shipping";

    @Test
    void aMarkedGroupQUANTIFIERListReadsBackWithBothMarkers() {
        BoundModel model = renewalModel();
        Condition authored = filtered(model,
                Dsl.allGroupsFilled(marked(model, BILLING), marked(model, SHIPPING)));

        Condition read = readCondition(model, "MarkedQuantifier", authored);

        assertTrue(markersOf(read).stream().allMatch(Boolean::booleanValue),
                "both $ markers must survive read-back, not be normalized away: " + RuntimeLaws.render(read));
        assertEquals(2, markersOf(read).size(), "both operands must come back as typed group scopes");
        assertEquals(RuntimeLaws.render(authored), RuntimeLaws.render(read));
    }

    /**
     * <b>The group COUNT carrier is opaque for a reason that is not the marker, and the control is what says
     * so.</b> {@code NumberOfFilledFields($A, $B)} comes back verbatim — and so does the identical rule with
     * the markers removed, on the same converter limit: {@code "does not yet handle aggregate
     * NumberOfFilledFields over a non-single-field scope"}. Both rules are kernel-ADMITTED, so this is a
     * genuine read-back residual; it is simply an older and wider one than {@code DG49}, which is about the
     * marker alone. Asserted as a PAIR rather than skipped, because without the unmarked row the marked row
     * would read as the marker's fault and send the next reader back into {@code groupScopeOf}.
     */
    @Test
    void theGroupCountCarrierIsOpaqueWithOrWithoutTheMarker() {
        BoundModel model = renewalModel();
        Condition marked = filtered(model,
                Dsl.numberOfFilledFields(marked(model, BILLING), marked(model, SHIPPING)).gt(0));
        Condition plain = filtered(model, Dsl.numberOfFilledFields(
                model.flattenGroup(BILLING), model.flattenGroup(SHIPPING)).gt(0));

        String markedReason = opaqueReason(model, "MarkedCount", marked);
        String plainReason = opaqueReason(model, "PlainCount", plain);

        assertEquals(plainReason, markedReason,
                "the marked and unmarked spellings must fail on the SAME limit, or the marker is the cause");
        assertTrue(markedReason.contains("non-single-field scope"),
                "the limit is the aggregate's scope shape, not the correlation: " + markedReason);
    }

    private static String opaqueReason(BoundModel model, String name, Condition authored) {
        Rule rule = model.rule(model.group("/Renewal")).name(name)
                .errorField(model.field("/Renewal/Reference")).when(authored)
                .code("RNW").severity(Severity.ERROR).message(en("renewal incomplete")).build();
        ReadResult result = KernelAdapter.of(model.add(rule)).read("/Renewal/" + name);
        return assertInstanceOf(ReadResult.Opaque.class, result,
                () -> "expected the pre-existing aggregate-scope limit, not a conversion: " + result).reason();
    }

    /** The control: an unmarked source must not acquire a marker on the way back. */
    @Test
    void theUnmarkedSpellingReadsBackUnmarked() {
        BoundModel model = renewalModel();
        Condition authored = filtered(model, Dsl.allGroupsFilled(
                model.flattenGroup(BILLING), model.flattenGroup(SHIPPING)));

        Condition read = readCondition(model, "PlainQuantifier", authored);

        assertTrue(markersOf(read).stream().noneMatch(Boolean::booleanValue),
                "an unmarked source must stay unmarked: " + RuntimeLaws.render(read));
        assertEquals(2, markersOf(read).size());
        assertEquals(RuntimeLaws.render(authored), RuntimeLaws.render(read));
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private static GroupListRef marked(BoundModel model, String group) {
        return model.flattenGroup(group).outer();
    }

    /**
     * Every {@code correlated()} flag on a group scope anywhere in the READ tree, read off the rendered text
     * rather than by navigating the AST: the two carriers put their operands at different depths, and a
     * shape-specific navigation would need one path per carrier while proving nothing extra.
     */
    private static List<Boolean> markersOf(Condition read) {
        String rendered = RuntimeLaws.render(read);
        return List.of(BILLING, SHIPPING).stream()
                .map(group -> rendered.contains("$" + group) || rendered.contains("$" + shortName(group)))
                .toList();
    }

    private static String shortName(String absolute) {
        return absolute.substring(absolute.lastIndexOf('/') + 1);
    }

    /**
     * The admitted shape: the group list sits beside an in-scope conjunct, which is what binds the filter's
     * iteration. As the SOLE condition the kernel refuses it ({@code MVK_NO_ITERATION_FOR_WILDCARD}) whichever
     * spelling is used, so it could not be added to a model here at all.
     */
    private static Condition filtered(BoundModel model, Condition groupList) {
        return Dsl.fieldFilled(model.field("/Renewal/Reference"))
                .and(Dsl.numberOfFilledFields(model.flatten("/Renewal/Items*/Code")
                        .having(model.num("/Renewal/Items/Amount").gt(1).and(groupList))).gt(0));
    }

    private static Condition readCondition(BoundModel model, String name, Condition authored) {
        Rule rule = model.rule(model.group("/Renewal")).name(name)
                .errorField(model.field("/Renewal/Reference")).when(authored)
                .code("RNW").severity(Severity.ERROR).message(en("renewal incomplete")).build();
        ReadResult result = KernelAdapter.of(model.add(rule)).read("/Renewal/" + name);
        return assertInstanceOf(ReadResult.Converted.class, result,
                () -> "a legal $-marked group LIST must not degrade to a verbatim read: " + result).condition();
    }

    private static BoundModel renewalModel() {
        return BoundModel.ofJson(DocumentModelDraft.named("renewal-correlated-list").locale("en_US")
                .rootGroup("Renewal", root -> root
                        .stringField("Reference", ignored -> { })
                        .group("Items", items -> items.repeatable(9)
                                .stringField("Code", ignored -> { })
                                .numberField("Amount", ignored -> { }))
                        .group("Billing", billing -> billing.stringField("Account", ignored -> { }))
                        .group("Shipping", shipping -> shipping.stringField("Carrier", ignored -> { })))
                .build().toJson());
    }
}
