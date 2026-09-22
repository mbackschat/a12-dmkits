package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

/**
 * PRECHECK-PROMOTION Partition C, item C3 — the client-side refusals the census cannot see and no law backs.
 *
 * <p>Each of these is a {@code Refusals.of} in a record or handle the inbound converters also call, so an
 * over-strict one does not merely block authoring: it makes a stored, kernel-LEGAL model unreadable. The
 * standing rule is that a refusal may never be wider than a MEASURED kernel basis, and all four are bucketed
 * {@code pre-checked} in the ledger, which asserts a basis none of them had.
 *
 * <p><b>The refused input cannot be authored through the typed surface</b> — that is what the refusal means —
 * so each row builds the LEGAL shape typed, renders it, and substitutes the one token that makes it illegal.
 * The substitution is asserted to have applied, because a pattern that silently matched nothing would leave a
 * legal condition being measured and every row would read as a false over-rejection. That guard earned its
 * place twice while this table was built: once on a token that renders differently than it reads, and once on
 * a path substitution that also rewrote the key and drew the broken-reference code instead of the verdict.
 *
 * <p><b>Outcome.</b> Three of the four refusals reproduce the kernel exactly. The fourth is two carriers with
 * two DIFFERENT codes — a single comparison and a value list — and the single one cited none at all, which is
 * why the census could not see it; it now cites the measured one.
 */
class UnbackedClientRefusalLawsTest {

    private static final String MODEL = DocumentModelDraft.named("unbacked-refusals").locale("en_US")
            .rootGroup("Order", o -> o
                    .field("Reference", FieldKind.STRING)
                    .enumField("Priority", e -> e
                            .value("STANDARD").value("EXPRESS")
                            .category("SpeedClass", "NORMAL", "FAST"))
                    .group("Fixed", f -> f.field("Note", FieldKind.STRING))
                    .group("Lines", l -> l.repeatable(20)
                            .field("Sku", FieldKind.STRING)
                            .field("Qty", FieldKind.NUMBER)))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(MODEL);

    /** The root-group locus most rows use: the rule sits at {@code /Order} on a non-repeating error field. */
    private static final String[] ROOT = {"/Order", "../Reference", "Reference"};
    /**
     * {@code RepetitionNotUnique} needs its rule group AT the repeated group — above it the key draws
     * {@code MVK_NO_WILDCARD} ({@code RepetitionNotUniqueKeyPathLawsTest}) — and the error field must then sit
     * in that group, or the out-of-group code pre-empts everything ([KF431]).
     */
    private static final String[] ROW = {"/Order/Lines", "../Sku", "Sku"};

    private static String verdict(String[] locus, String condition) {
        var result = KernelLaws.checkModel(MODEL, locus[0], locus[1],
                condition + " And FieldFilled(" + locus[2] + ")");
        if (result.ok()) {
            return KernelLaws.ADMITTED;
        }
        return result.diagnostics().stream()
                .map(d -> d.code().orElse("NO_CODE"))
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.joining("+"));
    }

    /** Renders {@code legal}, applies the one substitution that makes it illegal, and asks the kernel. */
    private static String verdictAfter(String[] locus, Condition legal, String from, String to) {
        String rendered = KernelLaws.render(legal);
        String corrupted = rendered.replace(from, to);
        if (corrupted.equals(rendered)) {
            return "SUBSTITUTION DID NOT APPLY to: " + rendered;
        }
        return verdict(locus, corrupted);
    }

    /** The legal counterpart of each row, so a refusal below is not the shape's own. */
    private static String verdictOf(String[] locus, Condition legal) {
        return verdict(locus, KernelLaws.render(legal));
    }

    /**
     * One table, four refusals plus a legal control each. A cell naming the code our gate cites means the
     * refusal has its basis; anything else means the gate refuses something the kernel does not, and the
     * refusal has to be corrected or deleted.
     */
    @Test
    void eachUnbackedRefusalMeasuredAgainstTheKernel() {
        var actual = new LinkedHashMap<String, String>();

        // MVK_CATEGORY_UNKOWN — EnumRef.category refuses a category the model does not declare.
        Condition categoryUse = M.enumOf("/Order/Priority").category("SpeedClass").is("FAST");
        actual.put("category: declared (control)", verdictOf(ROOT, categoryUse));
        actual.put("category: undeclared", verdictAfter(ROOT, categoryUse, "SpeedClass", "NoSuchCategory"));

        // MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION — EnumRef.checkedLiteral refuses an out-of-domain value.
        Condition enumValue = M.enumOf("/Order/Priority").is("STANDARD");
        actual.put("enum value: in domain (control)", verdictOf(ROOT, enumValue));
        actual.put("enum value: out of domain", verdictAfter(ROOT, enumValue, "STANDARD", "NOT_A_VALUE"));

        // The VALUE-LIST carrier of the same refusal: `checkedValues` feeds `in`/`notIn`, and the code the
        // kernel reports there need not be the one it reports for a single comparison. Measured before
        // touching either, or correcting one would break the other.
        Condition enumList = M.enumOf("/Order/Priority").in("STANDARD", "EXPRESS");
        actual.put("enum list: in domain (control)", verdictOf(ROOT, enumList));
        actual.put("enum list: out of domain", verdictAfter(ROOT, enumList, "EXPRESS", "NOT_A_VALUE"));

        // MVK_GROUP_NOT_REPEATABLE — RepetitionNotUniqueCondition.from refuses a non-repeatable @From group.
        Condition rnu = Dsl.repetitionNotUnique(M.field("/Order/Lines/Sku")).from(M.group("/Order/Lines"));
        actual.put("@From: repeatable (control)", verdictOf(ROW, rnu));
        // The substitution names the FromScope operand, not the bare path: replacing the path everywhere also
        // rewrites the KEY to a field that does not exist, and the kernel then reports the broken reference
        // instead of the repeatability verdict — a confound this row hit before it was narrowed.
        actual.put("@From: not repeatable",
                verdictAfter(ROW, rnu, "@From /Order/Lines", "@From /Order/Fixed"));

        // MVK_NO_FIELDS_ALLOWED — CountAggregate refuses a FIELD among NumberOfFilledGroups' operands.
        Condition groupCount = Dsl.numberOfFilledGroups(M.group("/Order/Fixed"), M.group("/Order/Lines"))
                .gt(0);
        actual.put("group count: groups only (control)", verdictOf(ROOT, groupCount));
        actual.put("group count: a field among them",
                verdictAfter(ROOT, groupCount, "/Order/Fixed", "/Order/Reference"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("category: declared (control)", KernelLaws.ADMITTED);
        expected.put("category: undeclared", "MVK_CATEGORY_UNKOWN");
        expected.put("enum value: in domain (control)", KernelLaws.ADMITTED);
        expected.put("enum value: out of domain", "MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON");
        expected.put("enum list: in domain (control)", KernelLaws.ADMITTED);
        expected.put("enum list: out of domain", "MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION");
        expected.put("@From: repeatable (control)", KernelLaws.ADMITTED);
        expected.put("@From: not repeatable", "MVK_GROUP_NOT_REPEATABLE");
        expected.put("group count: groups only (control)", KernelLaws.ADMITTED);
        expected.put("group count: a field among them", "MVK_NO_FIELDS_ALLOWED");

        assertEquals(expected, actual);
    }
}
