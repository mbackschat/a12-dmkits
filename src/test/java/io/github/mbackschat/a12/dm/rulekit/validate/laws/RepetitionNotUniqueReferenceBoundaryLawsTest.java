package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * <b>Which references does a {@code RepetitionNotUnique} leaf contribute to the error-field-referenced rule?</b>
 * The BOUNDARY as a class, not an instance — answers a12-kernel-lean SG13, which is blocked on the shape because
 * their checked source stores only the RESOLVED reference group, present whether or not {@code @From} was authored.
 *
 * <p><b>The A/B that settles it.</b> On this fixture the derived reference group (the first repeatable group below
 * the rule group on the key path) is the SAME {@code Block/Lines} that {@code @From} names, so authoring the clause
 * changes nothing about scope — only whether the clause is present. If the two columns differ, the authored clause
 * contributes references and an authored-{@code @From} marker must be retained; if they agree, the resolved group
 * suffices.
 *
 * <pre>
 * /Catalog                     rule group
 *   Block                      non-repeatable — the @From group's PARENT
 *     BlockNote                position 2
 *     Lines        repeatable  — the @From group == the derived reference group
 *       LineKey                the key
 *       LineNote               position 1: in the @From group, not a key
 *       Extras     repeatable  — a DESCENDANT of the @From group
 *         ExtraNote            position 3
 * </pre>
 *
 * <p><b>Position 4 of the requested matrix is measured here only for one single terminal key and a distinct
 * non-key terminal error field.</b> In that fixture two wildcard laws close the door from both sides: a star in an
 * RNU key is refused ({@code MVK_NO_WILDCARDS_ALLOWED}) while an unstarred key whose inner repeatable level the
 * error field does not pin demands one ({@code MVK_NO_WILDCARD}). The remaining terminal-error shape is refused
 * {@code MVK_ERROR_FIELD_AND_ITERATION_INVALID} with {@code @From}, or
 * {@code MVK_ERROR_FIELD_NOT_REFERENCED} without it. This does not generalize to a composite ancestor and
 * descendant key whose descendant is the error field; {@code RepetitionNotUniqueKeyOrderDiffTest} locks that
 * admitted counterexample.
 */
class RepetitionNotUniqueReferenceBoundaryLawsTest {

    private static final String RULE_GROUP = "/Catalog";

    private static String oneLevelJson() {
        return DocumentModelDraft.named("catalog").locale("en_US")
                .rootGroup("Catalog", c -> c
                        .group("Block", b -> b
                                .field("BlockNote", FieldKind.STRING)
                                .group("Lines", l -> l
                                        .repeatable(20)
                                        .field("LineKey", FieldKind.STRING)
                                        .field("LineNote", FieldKind.STRING)
                                        .group("Extras", e -> e
                                                .repeatable(10)
                                                .field("ExtraNote", FieldKind.STRING)))))
                .build().toJson();
    }

    /** The measured single-key fixture for position 4. */
    private static String twoLevelJson() {
        return DocumentModelDraft.named("catalog2").locale("en_US")
                .rootGroup("Catalog", c -> c
                        .group("Block", b -> b
                                .group("Lines", l -> l
                                        .repeatable(20)
                                        .group("Parts", p -> p
                                                .repeatable(10)
                                                .field("PartKey", FieldKind.STRING)
                                                .field("PartNote", FieldKind.STRING)))))
                .build().toJson();
    }

    /** {@code admitted}, or the first MVK code the kernel reports. */
    private static String outcome(String json, String errorField, String condition) {
        ValidationResult r = new RuleValidator().check(json,
                new DraftRule(RULE_GROUP, "BoundaryRule", errorField, condition, "DUP", "ERROR"));
        return r.ok() ? "admitted" : r.diagnostics().stream()
                .flatMap(d -> d.code().stream()).findFirst().orElse("rejected (no MVK code)");
    }

    @Test
    void theFromScopeContributesTheWholeGroupButNotItsParentOrDescendants() {
        Map<String, String> positions = new LinkedHashMap<>();
        positions.put("1 in the @From group, not a key", "../Block/Lines/LineNote");
        positions.put("2 in the @From group's parent", "../Block/BlockNote");
        positions.put("3 in a descendant of the @From group", "../Block/Lines/Extras/ExtraNote");

        Map<String, String> actual = new LinkedHashMap<>();
        positions.forEach((label, errorField) -> {
            actual.put(label + " | @From",
                    outcome(oneLevelJson(), errorField, "RepetitionNotUnique(Block/Lines/LineKey @From Block/Lines)"));
            actual.put(label + " | no @From",
                    outcome(oneLevelJson(), errorField, "RepetitionNotUnique(Block/Lines/LineKey)"));
        });

        // position 2 never reaches the reference rule: an error field ABOVE the repeatable level leaves that
        // level unpinned, so the key is required to star it — and a star in an RNU key is refused outright.
        actual.put("2 starred, to show the demand cannot be met", outcome(oneLevelJson(), "../Block/BlockNote",
                "RepetitionNotUnique(Block/Lines*/LineKey)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("1 in the @From group, not a key | @From", "admitted");
        expected.put("1 in the @From group, not a key | no @From", "MVK_ERROR_FIELD_NOT_REFERENCED");
        expected.put("2 in the @From group's parent | @From", "MVK_NO_WILDCARD");
        expected.put("2 in the @From group's parent | no @From", "MVK_NO_WILDCARD");
        expected.put("3 in a descendant of the @From group | @From", "admitted");
        expected.put("3 in a descendant of the @From group | no @From", "MVK_ERROR_FIELD_NOT_REFERENCED");
        expected.put("2 starred, to show the demand cannot be met", "MVK_NO_WILDCARDS_ALLOWED");
        assertEquals(expected, actual, "the @From reference boundary");
    }

    @Test
    void aSingleTerminalKeyWithADistinctErrorFieldIsClosedFromBothSides() {
        String json = twoLevelJson();
        String bare = "RepetitionNotUnique(Block/Lines/Parts/PartKey)";
        String starred = "RepetitionNotUnique(Block/Lines/Parts*/PartKey)";

        Map<String, String> actual = new LinkedHashMap<>();
        // the error field IN the terminal key group: the only shape the wildcard laws leave open
        actual.put("in key group | bare | @From scope",
                outcome(json, "../Block/Lines/Parts/PartNote",
                        "RepetitionNotUnique(Block/Lines/Parts/PartKey @From Block/Lines)"));
        actual.put("in key group | bare | no @From", outcome(json, "../Block/Lines/Parts/PartNote", bare));
        // a star in an RNU key is refused outright, so the MVK_NO_WILDCARD demand cannot be satisfied
        actual.put("in key group | starred", outcome(json, "../Block/Lines/Parts/PartNote", starred));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("in key group | bare | @From scope", "MVK_ERROR_FIELD_AND_ITERATION_INVALID");
        expected.put("in key group | bare | no @From", "MVK_ERROR_FIELD_NOT_REFERENCED");
        expected.put("in key group | starred", "MVK_NO_WILDCARDS_ALLOWED");
        assertEquals(expected, actual, "the single terminal key with a distinct non-key error is closed from both sides");
    }
}
