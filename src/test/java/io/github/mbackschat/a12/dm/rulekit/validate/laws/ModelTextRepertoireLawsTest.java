package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.modelkit.FieldTypeSpec;
import io.github.mbackschat.a12.dm.modelkit.StringFieldBuilder;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

/**
 * Which stored texts of a model meet A12's codeless refusal of a supplementary-plane character, and how often
 * it is reported.
 *
 * <p>A12 runs its RUNTIME formal charset check over the model itself, read as a document of its metamodel
 * ([KERNEL-FINDINGS KF146]), and with no charset configured the legal set is the BMP ([KF32]). The refusal
 * carries no class, so each row renders a codeless finding by its TEXT; a bare marker would alias it with the
 * error code's and the field name's own codeless laws, which the {@code \u00E9} twins draw.
 *
 * <p><b>The carriers are measured, not transferred.</b> Every text of a rule or a computation is one, and so
 * are a field's annotation, enum value and enum label and a group's annotation name and value. The labels of
 * the model, a group and a field are NOT, and neither is a text written for a locale the model does not
 * declare. The unit is the ELEMENT: two defective texts of one rule or one field draw one finding. An
 * element's PATH is a carrier too, so a group name carrying the character is reported on the group and on
 * every element beneath it. A lone surrogate of either half is refused like a pair, and the message's own
 * {@code \p{C}} gate names it {@code INVALID_CHARACTERS} as well. The finding stands beside every coded class
 * the same element draws, a refused condition's included.
 *
 * <p>Own domain (insurance claims).
 */
class ModelTextRepertoireLawsTest {

    private static final String GRIN = "\uD83D\uDE00";
    private static final String ACUTE = "\u00E9";
    private static final String LONE_HIGH = "\uD83D";

    private static final RuleValidator VALIDATOR = new RuleValidator();

    private static final String READS = "FieldFilled(Reference) And FieldFilled(Note)";

    /** One text of the model, by carrier; every other text is plain ASCII. */
    private record Texts(String modelLabel, String groupLabel, String fieldLabel, String enumValue,
            String enumLabel, String annotation, String extraField) {
        static Texts plain() {
            return new Texts("Claims", "Claim", "Reference", "OPEN", "Open", "kept", "Extra");
        }
    }

    /** A second, nested group, for the carriers a group owns: its name, its annotation name and value. */
    private static String nested(String name, String annotationName, String annotationValue) {
        return DocumentModelDraft.named("claims-repertoire").locale("en_US")
                .rootGroup("Claim", c -> c
                        .stringField("Reference", f -> { })
                        .stringField("Note", f -> { })
                        .group(name, g -> g.annotation(annotationName, annotationValue).stringField("Detail", f -> { })))
                .build().toJson();
    }

    private static String model(Texts t) {
        return DocumentModelDraft.named("claims-repertoire").locale("en_US").label("en_US", t.modelLabel())
                .rootGroup("Claim", c -> c
                        .label("en_US", t.groupLabel())
                        .stringField("Reference", f -> f.label("en_US", t.fieldLabel()).annotation("origin", t.annotation()))
                        .stringField("Note", f -> { })
                        .stringField("Summary", f -> { })
                        .stringField(t.extraField(), f -> { })
                        .enumField("Status", e -> e.value(t.enumValue(), l -> l.label("en_US", t.enumLabel()))))
                .build().toJson();
    }

    private static final String PLAIN = model(Texts.plain());

    /** A patterned {@code Code} field and an enumeration carrying the given invalid-value message. */
    private static String fieldMessages(Consumer<StringFieldBuilder> code,
            String statusMessage) {
        return DocumentModelDraft.named("claims-repertoire").locale("en_US")
                .rootGroup("Claim", c -> c
                        .stringField("Reference", f -> { })
                        .stringField("Note", f -> { })
                        .stringField("Code", code)
                        .enumField("Status", e -> e.value("OPEN").value("CLOSED").invalidValueMessage(statusMessage)))
                .build().toJson();
    }

    private static String rule(String json, String condition, String code, String message) {
        return render(VALIDATOR.check(json, new DraftRule("/Claim", "ProbeRule", "../Reference", condition, code,
                "ERROR", List.of(new LocalizedText("en_US", message)))).diagnostics());
    }

    private static String rule(String json) {
        return rule(json, READS, "PROBE", "see the reference");
    }

    private static String computation(String operation) {
        return computation(null, null, operation, "x");
    }

    private static String computation(String common, String precondition, String operation, String message) {
        return render(VALIDATOR.check(PLAIN, new DraftComputation("/Claim", "ProbeCalc", "../Summary", common,
                List.of(new DraftComputation.Alternative(precondition, operation)), List.of(), null,
                List.of(new LocalizedText("en_US", message)))).diagnostics());
    }

    private static final String ADMITTED = "admitted";

    /** A12's codeless charset refusal, as it names the element: a computation is a "Rule" there. */
    private static String unsupported(String kind, String path) {
        return "(no code: " + kind + " with path '" + path + "': The field contains one or several unsupported signs.)";
    }

    private static final String RULE = unsupported("Rule", "/Claim/ProbeRule");
    private static final String CALC = unsupported("Rule", "/Claim/ProbeCalc");
    private static final String STATUS = unsupported("Field", "/Claim/Status");
    private static final String PART = unsupported("Group", "/Claim/Part");

    /** Every finding in order, repeats kept; a codeless one by its text, since a bare marker would alias two. */
    private static String render(List<Diagnostic> diagnostics) {
        return diagnostics.isEmpty() ? ADMITTED : diagnostics.stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")"))
                .collect(Collectors.joining(","));
    }

    @Test
    void eachStoredTextMeetsTheRepertoire() {
        Map<String, String> actual = new LinkedHashMap<>();
        for (String c : List.of(ACUTE, GRIN)) {
            Texts p = Texts.plain();
            actual.put(c + " message", rule(PLAIN, READS, "PROBE", "see " + c));
            actual.put(c + " condition literal", rule(PLAIN, READS + " And [Note] == \"a" + c + "\"", "PROBE", "x"));
            actual.put(c + " condition comment", rule(PLAIN, READS + " ;; " + c, "PROBE", "x"));
            actual.put(c + " condition outside a literal", rule(PLAIN, READS + " And " + c + " FieldFilled(Note)", "PROBE", "x"));
            actual.put(c + " error code", rule(PLAIN, READS, "PROBE" + c, "x"));
            actual.put(c + " computation literal", computation("\"a" + c + "\""));
            actual.put(c + " computation comment", computation("[Note] ;; " + c));
            actual.put(c + " model label", rule(model(new Texts("Claims " + c, p.groupLabel(), p.fieldLabel(), p.enumValue(), p.enumLabel(), p.annotation(), p.extraField()))));
            actual.put(c + " group label", rule(model(new Texts(p.modelLabel(), "Claim " + c, p.fieldLabel(), p.enumValue(), p.enumLabel(), p.annotation(), p.extraField()))));
            actual.put(c + " field label", rule(model(new Texts(p.modelLabel(), p.groupLabel(), "Reference " + c, p.enumValue(), p.enumLabel(), p.annotation(), p.extraField()))));
            actual.put(c + " enum value", rule(model(new Texts(p.modelLabel(), p.groupLabel(), p.fieldLabel(), "OPEN" + c, p.enumLabel(), p.annotation(), p.extraField()))));
            actual.put(c + " enum label", rule(model(new Texts(p.modelLabel(), p.groupLabel(), p.fieldLabel(), p.enumValue(), "Open " + c, p.annotation(), p.extraField()))));
            actual.put(c + " annotation value", rule(model(new Texts(p.modelLabel(), p.groupLabel(), p.fieldLabel(), p.enumValue(), p.enumLabel(), "kept " + c, p.extraField()))));
            actual.put(c + " field name", rule(model(new Texts(p.modelLabel(), p.groupLabel(), p.fieldLabel(), p.enumValue(), p.enumLabel(), p.annotation(), "Extra" + c))));
        }
        actual.put("two texts of one rule", rule(PLAIN, READS + " And [Note] == \"a" + GRIN + "\"", "PROBE", "see " + GRIN));
        actual.put("beside a refused condition", rule(PLAIN, READS + " And And FieldFilled(Note)", "PROBE", "see " + GRIN));
        actual.put("a lone high surrogate", rule(PLAIN, READS, "PROBE", "see " + LONE_HIGH));
        actual.put("a lone low surrogate", rule(PLAIN, READS, "PROBE", "see \uDE00"));
        actual.put("two texts of one field", rule(model(new Texts("Claims", "Claim", "Reference", "OPEN" + GRIN, "Open " + GRIN, "kept", "Extra"))));
        actual.put("computation common precondition", computation("FieldFilled(Note) ;; " + GRIN, null, "[Note]", "x"));
        actual.put("computation alternative precondition", computation(null, "FieldFilled(Note) ;; " + GRIN, "[Note]", "x"));
        actual.put("computation message", computation(null, null, "[Note]", "see " + GRIN));
        actual.put("rule name", render(VALIDATOR.check(PLAIN, new DraftRule("/Claim", "Probe" + GRIN, "../Reference", READS,
                "PROBE", "ERROR", List.of(new LocalizedText("en_US", "x")))).diagnostics()));
        actual.put("a message in an undeclared locale", render(VALIDATOR.check(PLAIN, new DraftRule("/Claim", "ProbeRule",
                "../Reference", READS, "PROBE", "ERROR", List.of(new LocalizedText("en_US", "x"),
                        new LocalizedText("de_DE", "see " + GRIN)))).diagnostics()));
        actual.put("nested group, plain", rule(nested("Part", "origin", "kept")));
        actual.put("nested group name", rule(nested("Part" + GRIN, "origin", "kept")));
        actual.put("group annotation value", rule(nested("Part", "origin", "kept " + GRIN)));
        actual.put("annotation name", rule(nested("Part", "origin" + GRIN, "kept")));
        actual.put("a rule inside a defective group", render(VALIDATOR.check(nested("Part" + GRIN, "origin", "kept"),
                new DraftRule("/Claim/Part" + GRIN, "InnerRule", "../Detail", "FieldFilled(Detail)", "PROBE", "ERROR",
                        List.of(new LocalizedText("en_US", "x")))).diagnostics()));
        actual.put("a field's annotation name", rule(DocumentModelDraft.named("claims-repertoire").locale("en_US")
                .rootGroup("Claim", c -> c
                        .stringField("Reference", f -> f.annotation("origin" + GRIN, "kept"))
                        .stringField("Note", f -> { }))
                .build().toJson()));
        actual.put("computation name", render(VALIDATOR.check(PLAIN, new DraftComputation("/Claim", "Calc" + GRIN,
                "../Summary", null, List.of(new DraftComputation.Alternative(null, "[Note]")), List.of(), null,
                List.of(new LocalizedText("en_US", "x")))).diagnostics()));
        actual.put("an enum label in an undeclared locale", rule(DocumentModelDraft.named("claims-repertoire").locale("en_US")
                .rootGroup("Claim", c -> c
                        .stringField("Reference", f -> { })
                        .stringField("Note", f -> { })
                        .enumField("Status", e -> e.value("OPEN", l -> l.label("en_US", "Open").label("de_DE", "Offen " + GRIN))))
                .build().toJson()));
        actual.put("a string type's pattern message", rule(fieldMessages(
                f -> f.pattern("[A-Z]+", "capitals only").patternMessage("en_US", "see " + GRIN), "OPEN only")));
        actual.put("an enumeration's invalid-value message", rule(fieldMessages(
                f -> f.pattern("[A-Z]+", "capitals only"), "see " + GRIN)));
        String undeclaredMessage = fieldMessages(f -> f.pattern("[A-Z]+", "capitals only")
                .patternMessage("de_DE", "see " + GRIN), "OPEN only");
        assertEquals("see " + GRIN, ((FieldTypeSpec.StringSpec) ModelDocument.read(undeclaredMessage)
                .authoredState().fields().get("/Claim/Code").type()).patternMessages().get("de_DE"));
        actual.put("a pattern message in an undeclared locale", rule(undeclaredMessage));
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put(ACUTE + " message", ADMITTED);
        expected.put(ACUTE + " condition literal", ADMITTED);
        expected.put(ACUTE + " condition comment", ADMITTED);
        expected.put(ACUTE + " condition outside a literal", "MVK_LEXER_STANDARD_ERROR");
        // The error code's own content law, not the charset: its letters stop short of this one.
        expected.put(ACUTE + " error code", "(no code: Rule with path '/Claim/ProbeRule': The error code is only "
                + "allowed to contain letters, digits, blanks and underscores.)");
        expected.put(ACUTE + " computation literal", ADMITTED);
        expected.put(ACUTE + " computation comment", ADMITTED);
        expected.put(ACUTE + " model label", ADMITTED);
        expected.put(ACUTE + " group label", ADMITTED);
        expected.put(ACUTE + " field label", ADMITTED);
        expected.put(ACUTE + " enum value", ADMITTED);
        expected.put(ACUTE + " enum label", ADMITTED);
        expected.put(ACUTE + " annotation value", ADMITTED);
        expected.put(ACUTE + " field name", "MVK_PATH_INVALID,(no code: Field with path '/Claim/Extra" + ACUTE + "': Extra"
                + ACUTE + ": A field name may only contain letters, digits or underscores. Field names must not "
                + "begin with a digit.)");
        expected.put(GRIN + " message", RULE);
        expected.put(GRIN + " condition literal", RULE);
        expected.put(GRIN + " condition comment", RULE);
        expected.put(GRIN + " condition outside a literal", RULE + ",MVK_LEXER_STANDARD_ERROR");
        // The charset answers where the twin draws the code's content law, so it pre-empts that one.
        expected.put(GRIN + " error code", RULE);
        expected.put(GRIN + " computation literal", CALC);
        expected.put(GRIN + " computation comment", CALC);
        expected.put(GRIN + " model label", ADMITTED);
        expected.put(GRIN + " group label", ADMITTED);
        expected.put(GRIN + " field label", ADMITTED);
        expected.put(GRIN + " enum value", STATUS);
        expected.put(GRIN + " enum label", STATUS);
        expected.put(GRIN + " annotation value", unsupported("Field", "/Claim/Reference"));
        expected.put(GRIN + " field name", "MVK_PATH_INVALID," + unsupported("Field", "/Claim/Extra" + GRIN));
        expected.put("two texts of one rule", RULE);
        expected.put("beside a refused condition", RULE + ",MVK_UNEXPECTED_TOKEN");
        expected.put("a lone high surrogate", RULE + ",INVALID_CHARACTERS");
        expected.put("a lone low surrogate", RULE + ",INVALID_CHARACTERS");
        expected.put("two texts of one field", STATUS);
        expected.put("computation common precondition", CALC);
        expected.put("computation alternative precondition", CALC);
        expected.put("computation message", CALC);
        expected.put("rule name", unsupported("Rule", "/Claim/Probe" + GRIN));
        expected.put("a message in an undeclared locale", ADMITTED);
        expected.put("nested group, plain", ADMITTED);
        // The field's own name is plain: its PATH carries the group's.
        expected.put("nested group name", "MVK_PATH_INVALID," + unsupported("Group", "/Claim/Part" + GRIN) + ","
                + unsupported("Field", "/Claim/Part" + GRIN + "/Detail"));
        expected.put("group annotation value", PART);
        expected.put("annotation name", PART);
        expected.put("a rule inside a defective group", "MVK_PATH_INVALID,"
                + unsupported("Rule", "/Claim/Part" + GRIN + "/InnerRule") + ","
                + unsupported("Group", "/Claim/Part" + GRIN) + "," + unsupported("Field", "/Claim/Part" + GRIN + "/Detail"));
        expected.put("a field's annotation name", unsupported("Field", "/Claim/Reference"));
        expected.put("computation name", unsupported("Rule", "/Claim/Calc" + GRIN));
        expected.put("an enum label in an undeclared locale", ADMITTED);
        // A field type's own message is a carrier too, on both types that declare one, and like every other
        // message only for a locale the model declares.
        expected.put("a string type's pattern message", unsupported("Field", "/Claim/Code"));
        expected.put("an enumeration's invalid-value message", STATUS);
        expected.put("a pattern message in an undeclared locale", ADMITTED);
        assertEquals(expected, actual, "the carriers of A12's charset refusal, and its unit");
    }

    /**
     * Whether the two things that stop A12's examination of a rule stop this refusal too: an error target
     * naming no field stops the condition and the message there (KERNEL-FINDINGS KF339) and leaves this one
     * standing, while an {@code ONLY} variant filter takes it away with the rest of the rule (KF299). Each row
     * has its plain twin, so the difference is the one character.
     */
    @Test
    void anErrorTargetDoesNotStopItAndAVariantFilterDoes() throws IOException {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a broken error target, plain", targeting("../NoSuchField", "see the reference"));
        actual.put("a broken error target", targeting("../NoSuchField", "see " + GRIN));
        actual.put("an unreferenced error target, plain", targeting("../Summary", "see the reference"));
        actual.put("an unreferenced error target", targeting("../Summary", "see " + GRIN));
        actual.put("an ONLY variant filter, plain", filtered("see the reference"));
        actual.put("an ONLY variant filter", filtered("see " + GRIN));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a broken error target, plain", "MVK_ERROR_FIELD_MISSING,MVK_MISSING_ERROR_FIELD");
        expected.put("a broken error target", RULE + ",MVK_ERROR_FIELD_MISSING,MVK_MISSING_ERROR_FIELD");
        expected.put("an unreferenced error target, plain", "MVK_ERROR_FIELD_NOT_REFERENCED");
        expected.put("an unreferenced error target", RULE + ",MVK_ERROR_FIELD_NOT_REFERENCED");
        expected.put("an ONLY variant filter, plain", KernelLaws.ADMITTED);
        expected.put("an ONLY variant filter", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "whether what stops A12's examination of a rule stops the charset check");
    }

    private static String targeting(String errorTarget, String message) {
        return render(VALIDATOR.check(PLAIN, new DraftRule("/Claim", "ProbeRule", errorTarget, READS, "PROBE",
                "ERROR", List.of(new LocalizedText("en_US", message)))).diagnostics());
    }

    /** The probe rule under {@code ONLY ["premium"]}, judged whole since the filter is not a draft's member. */
    private static String filtered(String message) throws IOException {
        ObjectNode root = (ObjectNode) MAPPER.readTree(VALIDATOR.inject(PLAIN, new DraftRule("/Claim", "ProbeRule",
                "../Reference", READS, "PROBE", "ERROR", List.of(new LocalizedText("en_US", message)))));
        for (JsonNode element : root.path("content").path("modelRoot").path("rootGroups").get(0)
                .path("Group").path("elements")) {
            if ("ProbeRule".equals(element.path("name").asText())) {
                ((ObjectNode) element.path("Rule")).set("variantFilter",
                        MAPPER.readTree("{\"filterType\":\"ONLY\",\"variants\":[\"premium\"]}"));
            }
        }
        return KernelLaws.verdictOf(root.toString());
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

}
