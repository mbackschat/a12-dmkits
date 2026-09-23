package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.github.mbackschat.a12.dm.ModelDrafts;
import io.github.mbackschat.a12.dm.core.KernelCodeRefusal;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;
import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS — {@code NumberOfFilledGroups} DOES accept a root group, as the sole starred operand
 * (source {@code CheckFeldListenAnzahlOpImpl} {@code checkNoRootGroupWhenMoreThanOneParameter}). This
 * corrects the catalog's earlier "no root groups" constraint.
 *
 * <p>Also the accept half of the group-cardinality correction (KERNEL-FINDINGS §9 "group cardinality",
 * corrected 2026-07-09): a minItems-style rule over the starred single-group count is kernel-valid in both
 * anchorings — error field INSIDE the counted group (unguarded), or outside behind a {@code FieldFilled}
 * guard. The firing half (fires at 0 and 1 rows, silent at 2) is {@code adapter.laws.NumberOfFilledGroupsStarDiffTest}.
 *
 * <p>SPEC-2026-08-09-01 adds the computation-admission matrix. Fixed lists of two and three distinct,
 * disjoint non-repeatable groups are admitted, as are a sole starred repeatable group and a starred/fixed mix.
 * A fixed singleton, exact duplicate, ancestor/descendant overlap, and unstarred repeatable group remain four
 * distinct static rejections. These checks make no claim about starred computation runtime behavior.
 */
class RootGroupCountLawsTest {

    private static final RuleValidator VALIDATOR = new RuleValidator();
    private static final String MULTIFORM = ModelDrafts.repeatableRoot().toJson();
    private static final String COMPUTATION_MODEL = DocumentModelDraft.named("depot-count-admission").locale("en_US")
            .rootGroup("Depot", depot -> depot
                    .numberField("FilledCount", count -> count.fractionDigits(0))
                    .group("Alpha", alpha -> alpha.group("Nested", nested -> nested
                            .stringField("Value", value -> { })))
                    .group("Beta", beta -> beta.stringField("Value", value -> { }))
                    .group("Gamma", gamma -> gamma.stringField("Value", value -> { }))
                    .group("Lines", lines -> lines.repeatable(10)
                            .stringField("Value", value -> { })))
            .build().toJson();
    private static final BoundModel COMPUTATION = BoundModel.ofJson(COMPUTATION_MODEL);

    @Test
    void numberOfFilledGroupsAcceptsASoleStarredRootGroup() {
        KernelLaws.acceptsModel(MULTIFORM, "/Header", "../Title",
                "FieldFilled(Title) And NumberOfFilledGroups(/Persons*) > 0");
    }

    /**
     * The text above is its own record, so the nested-group count routes a sole repeatable root to it rather
     * than claiming a code: A12 admits the text, and a class cited here would be one A12 never gives.
     */
    @Test
    void theNestedGroupCountRoutesASoleRepeatableRootToTheRootGroupCount() {
        BoundModel multiform = BoundModel.ofJson(MULTIFORM);
        IllegalArgumentException routed = assertThrows(IllegalArgumentException.class,
                () -> Dsl.numberOfFilledGroups(multiform.group("/Persons")));
        assertFalse(routed instanceof KernelCodeRefusal, routed.getMessage());
        assertTrue(routed.getMessage().contains("numberOfFilledRootGroups"), routed.getMessage());
        assertEquals("NumberOfFilledGroups(/Persons*)",
                new DslRenderer().render(Dsl.numberOfFilledRootGroups(multiform.group("/Persons")).expression()));
    }

    @Test
    void aMinItemsRuleOverTheStarredCountIsKernelValidInBothAnchorings() {
        KernelLaws.acceptsOrder("/Order", "../Items/Sku", "NumberOfFilledGroups(Items*) < 2");
        KernelLaws.acceptsOrder("/Order", "../CustomerName",
                "FieldFilled(CustomerName) And NumberOfFilledGroups(Items*) < 2");
    }

    @Test
    void aStarredRepeatableGroupCanBeMixedWithAFixedGroup() {
        KernelLaws.acceptsOrder("/Order", "../CustomerName",
                "FieldFilled(CustomerName) And NumberOfFilledGroups(Items*, ShippingAddress) < 2");
    }

    @Test
    void starringANonRepeatableRootGroupIsRejectedAsAnInvalidWildcard() {
        KernelLaws.rejectsOrder("/Order", "../CustomerName",
                "FieldFilled(CustomerName) And NumberOfFilledGroups(/Order*) < 2", "MVK_INVALID_WILDCARD");
    }

    @Test
    void computationsAdmitFixedPairsTriplesAndNestedTerminalsBesideDisjointGroups() {
        acceptsComputation(Dsl.numberOfFilledGroups(
                COMPUTATION.group("/Depot/Alpha"), COMPUTATION.group("/Depot/Beta")));
        acceptsComputation(Dsl.numberOfFilledGroups(
                COMPUTATION.group("/Depot/Alpha"), COMPUTATION.group("/Depot/Beta"),
                COMPUTATION.group("/Depot/Gamma")));
        acceptsComputation(Dsl.numberOfFilledGroups(
                COMPUTATION.group("/Depot/Alpha/Nested"), COMPUTATION.group("/Depot/Beta")));
    }

    @Test
    void computationsAdmitASoleStarAndAMixedStarredFixedList() {
        acceptsComputation(Dsl.numberOfFilledGroups(COMPUTATION.group("/Depot/Lines")));
        acceptsComputation(Dsl.numberOfFilledGroups(
                COMPUTATION.group("/Depot/Lines"), COMPUTATION.group("/Depot/Alpha")));
    }

    @Test
    void aFixedSingletonReportsTheGroupCountArityCode() {
        rejectsComputation("NumberOfFilledGroups(Alpha)", "MVK_PARAMSIZE_INVALIDGN");
    }

    @Test
    void anExactDuplicateReportsTheExactDuplicateCode() {
        rejectsComputation("NumberOfFilledGroups(Alpha, Alpha)", "MVK_DUPLICATE_PARAM1");
    }

    @Test
    void ancestorOverlapIncludingTheRootReportsTheOverlapCode() {
        rejectsComputation("NumberOfFilledGroups(Alpha, Alpha/Nested)", "MVK_DUPLICATE_PARAM2");
        rejectsComputation("NumberOfFilledGroups(/Depot, Alpha)", "MVK_DUPLICATE_PARAM2");
    }

    @Test
    void anUnstarredRepeatableGroupReportsTheMissingWildcardCode() {
        rejectsComputation("NumberOfFilledGroups(Lines, Alpha)", "MVK_NO_WILDCARD");
    }

    private static void acceptsComputation(NumberExpr operation) {
        var computation = COMPUTATION.computation(COMPUTATION.num("/Depot/FilledCount"))
                .name("CountAdmissionProbe")
                .calc(operation.expression())
                .message(Dsl.en("Filled group count admission probe."))
                .build();
        ValidationResult result = VALIDATOR.check(COMPUTATION_MODEL, computation.draft());
        assertTrue(result.ok(), () -> "expected computation acceptance of '" + operation + "', got: "
                + result.diagnostics());
    }

    private static void rejectsComputation(String operation, String code) {
        ValidationResult result = checkComputation(operation);
        assertFalse(result.ok(), () -> "expected computation rejection of '" + operation + "'");
        List<String> codes = result.diagnostics().stream().flatMap(diagnostic -> diagnostic.code().stream()).toList();
        assertEquals(List.of(code), codes,
                () -> "unexpected diagnostics for '" + operation + "': " + result.diagnostics());
    }

    private static ValidationResult checkComputation(String operation) {
        DraftComputation computation = new DraftComputation("/Depot", "CountAdmissionProbe", "../FilledCount",
                null, List.of(new DraftComputation.Alternative(null, operation)));
        return VALIDATOR.check(COMPUTATION_MODEL, computation);
    }
}
