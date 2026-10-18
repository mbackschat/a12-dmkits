package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BigIntegerNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * How A12 reads the SPELLING of {@code content.modelConfig.fieldRefByShortNameAllowed} — measured, because a
 * reader that guesses wrong here refuses a model the Kernel accepts and nothing can detect it.
 *
 * <p>The flag decides whether a bare name the declaring group does not have may resolve to a unique model-wide
 * field ({@link ShortNameUniqueTierLawsTest} is the ON/OFF pair). So a reader that treats a spelling as OFF
 * where A12 treats it as ON reports {@code MVK_INVALID_ENTITY} for a reference A12 resolves — the direction
 * the Kernel is never asked about.
 *
 * <p><b>A12 states its own rule in the rejection it raises</b> for an unrecognized string: <em>only
 * "true"/"True"/"TRUE" or "false"/"False"/"FALSE" recognized</em>. That is Jackson's standard {@code boolean}
 * POJO binding, and it is NOT what {@code JsonNode.asBoolean} does — {@code asBoolean} accepts only the
 * lowercase {@code "true"} and returns false for {@code "True"}. So every reader here that uses
 * {@code asBoolean} reads a capitalized spelling as OFF where A12 reads it as ON
 * ({@code core.ModelReader:470}, {@code modelkit.ModelDocument:258}, the interpreter's
 * {@code load.ModelLoader:80}, the bridge's {@code InterpreterConditionReadBack:115}), and the interpreter's
 * {@code workspace.IncludeAssembly} refuses every string spelling outright. This table is the oracle they
 * answer to.
 *
 * <p><b>Why it is not the same question as the mount exclusions.</b> {@code excludeRules} and
 * {@code excludeComputations} reach A12 through {@code ElementDeserializer}'s {@code JsonNode.booleanValue()},
 * which is false for every non-Boolean ([KF3]); this member reaches a {@code boolean} field through the
 * standard POJO binding instead, which is a different Jackson path and need not agree. Assuming one rule for
 * both members is how the exclusion readers were wrong until 2026-09-12.
 *
 * <p>The verdict is read through the model-wide lookup rather than through any accessor of ours, so nothing
 * in the chain is our own reading of the flag.
 */
class ShortNameFlagSpellingLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The unique-tier model: one model-wide {@code Code}, in a group the rule is not declared in. */
    private static final String CATALOG = DocumentModelDraft.named("catalog-flag-spelling").locale("en_US")
            .rootGroup("Catalog", c -> c
                    .field("Note", FieldKind.STRING)
                    .group("Warehouse", w -> w.field("Code", FieldKind.STRING))
                    .group("Store", s -> s.field("Other", FieldKind.STRING)))
            .build().toJson();

    private static final String ON = "ON — the model-wide lookup ran";
    private static final String OFF = "OFF — MVK_INVALID_ENTITY";

    /**
     * Not a third value of the flag: the model does not LOAD. Jackson refuses the whole
     * {@code DocumentModelConfig} binding, so A12 never judges the model at all — which is why the row is
     * kept distinct from OFF rather than folded into it.
     */
    private static final String UNREADABLE = "UNREADABLE — the model does not deserialize";

    @Test
    void a12ReadsTheFlagThroughItsOwnPojoBindingRatherThanJacksonsCoercion() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("true", readAs(BooleanNode.TRUE));
        actual.put("false", readAs(BooleanNode.FALSE));
        actual.put("\"true\"", readAs(TextNode.valueOf("true")));
        actual.put("\"True\"", readAs(TextNode.valueOf("True")));
        actual.put("\"TRUE\"", readAs(TextNode.valueOf("TRUE")));
        actual.put("\"false\"", readAs(TextNode.valueOf("false")));
        actual.put("\"False\"", readAs(TextNode.valueOf("False")));
        actual.put("\"FALSE\"", readAs(TextNode.valueOf("FALSE")));
        actual.put("\"yes\"", readAs(TextNode.valueOf("yes")));
        actual.put("\"\"", readAs(TextNode.valueOf("")));
        actual.put("1", readAs(IntNode.valueOf(1)));
        actual.put("2", readAs(IntNode.valueOf(2)));
        actual.put("-1", readAs(IntNode.valueOf(-1)));
        actual.put("0", readAs(IntNode.valueOf(0)));
        actual.put("a 26-digit integer", readAs(BigIntegerNode.valueOf(new BigInteger("9".repeat(26)))));
        actual.put("0.5", readAs(DoubleNode.valueOf(0.5)));
        actual.put("1.5", readAs(DoubleNode.valueOf(1.5)));
        actual.put("\"1\"", readAs(TextNode.valueOf("1")));
        actual.put("{}", readAs(MAPPER.createObjectNode()));
        actual.put("[]", readAs(MAPPER.createArrayNode()));
        actual.put("null", readAs(NullNode.getInstance()));
        actual.put("absent", readAs(null));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("true", ON);
        expected.put("false", OFF);
        expected.put("\"true\"", ON);
        expected.put("\"True\"", ON);
        expected.put("\"TRUE\"", ON);
        expected.put("\"false\"", OFF);
        expected.put("\"False\"", OFF);
        expected.put("\"FALSE\"", OFF);
        expected.put("\"yes\"", UNREADABLE);
        // The EMPTY string is not an unrecognized spelling: Jackson's empty-string coercion supplies the
        // type's default before the recognized-values check runs, so it reads as OFF rather than refusing.
        expected.put("\"\"", OFF);
        expected.put("1", ON);
        expected.put("2", ON);
        expected.put("-1", ON);
        expected.put("0", OFF);
        // Wider than any machine integer and still read as one, which is the row a reader that CONVERTS
        // before deciding gets wrong: overflow sends it to the unreadable branch for a model A12 loads.
        expected.put("a 26-digit integer", ON);
        // The second divergence, and the sharper one: a FRACTIONAL number refuses the model outright, where
        // `asBoolean` truncates to an int and answers false for 0.5 and TRUE for 1.5 — so our readers invent
        // both verdicts for a model A12 will not load, one of them affirmative.
        expected.put("0.5", UNREADABLE);
        expected.put("1.5", UNREADABLE);
        // A numeric STRING is not a number: the recognized-values check runs on the text, so `"1"` refuses
        // where the bare `1` is ON. A reader that coerced a string through a numeric path would invert it.
        expected.put("\"1\"", UNREADABLE);
        expected.put("{}", UNREADABLE);
        expected.put("[]", UNREADABLE);
        expected.put("null", OFF);
        expected.put("absent", OFF);
        assertEquals(expected, actual, "how A12 reads each spelling of fieldRefByShortNameAllowed");
    }

    /**
     * The string arm TRIMS before it compares, and treats the text {@code null} as the type's default
     * ([KF569], first measured on {@code StringType.noValueValidation} and re-measured here on this carrier
     * rather than inherited). The trim is Java's {@code String.trim}, which strips U+0000 to U+0020 only, so a
     * no-break or em space before {@code true} still refuses; the {@code null} match is exact, so
     * {@code "NULL"} refuses while {@code " null"} trims to it.
     */
    @Test
    void theStringIsTrimmedAndTheTextNullReadsAsTheDefault() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("\" true\"", readAs(TextNode.valueOf(" true")));
        actual.put("\"\\ttrue\"", readAs(TextNode.valueOf("\ttrue")));
        actual.put("\"true\\n\"", readAs(TextNode.valueOf("true\n")));
        actual.put("\" TRUE\"", readAs(TextNode.valueOf(" TRUE")));
        actual.put("a no-break space before true", readAs(TextNode.valueOf("\u00A0true")));
        actual.put("an em space before true", readAs(TextNode.valueOf("\u2003true")));
        actual.put("\" \"", readAs(TextNode.valueOf(" ")));
        actual.put("\"null\"", readAs(TextNode.valueOf("null")));
        actual.put("\" null\"", readAs(TextNode.valueOf(" null")));
        actual.put("\"NULL\"", readAs(TextNode.valueOf("NULL")));
        actual.put("\"FaLsE\"", readAs(TextNode.valueOf("FaLsE")));
        actual.put("1e0", readAs(DoubleNode.valueOf(1.0)));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("\" true\"", ON);
        expected.put("\"\\ttrue\"", ON);
        expected.put("\"true\\n\"", ON);
        expected.put("\" TRUE\"", ON);
        expected.put("a no-break space before true", UNREADABLE);
        expected.put("an em space before true", UNREADABLE);
        expected.put("\" \"", OFF);
        expected.put("\"null\"", OFF);
        expected.put("\" null\"", OFF);
        expected.put("\"NULL\"", UNREADABLE);
        expected.put("\"FaLsE\"", UNREADABLE);
        expected.put("1e0", UNREADABLE);
        assertEquals(expected, actual, "how A12 reads a padded or null-text spelling of fieldRefByShortNameAllowed");
    }

    /**
     * Whether the model-wide fallback ran, asked through the Kernel's own verdict on a bare unique name.
     *
     * <p>A rejection carrying no {@code MVK_*} code is not a verdict about the reference: it is the model
     * failing to DESERIALIZE, a tier before any check. Folding that into OFF is the mistake this separation
     * exists to prevent — one spelling belongs there, and a reader that treats it as "flag off" carries on
     * analysing a model A12 will not load.
     */
    private static String readAs(JsonNode spelling) {
        ValidationResult r = KernelLaws.checkModel(withFlag(spelling), "/Catalog", "../Note",
                "FieldFilled(Note) And FieldFilled(Code)");
        if (r.ok()) {
            return ON;
        }
        return r.diagnostics().stream().anyMatch(d -> d.code().isEmpty()) ? UNREADABLE
                : "OFF — " + r.diagnostics().stream()
                        .map(d -> d.code().orElseThrow())
                        .distinct()
                        .reduce((a, b) -> a + "," + b)
                        .orElse("(no diagnostic)");
    }

    /** The model with the flag set to [spelling], or the member removed when it is null. */
    private static String withFlag(JsonNode spelling) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(CATALOG);
            ObjectNode config = (ObjectNode) root.path("content").path("modelConfig");
            if (!config.has("fieldRefByShortNameAllowed")) {
                throw new IllegalStateException("the draft no longer writes the member this varies");
            }
            if (spelling == null) {
                config.remove("fieldRefByShortNameAllowed");
            } else {
                config.set("fieldRefByShortNameAllowed", spelling);
            }
            return root.toString();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the draft's own output must be readable", unreadable);
        }
    }
}
