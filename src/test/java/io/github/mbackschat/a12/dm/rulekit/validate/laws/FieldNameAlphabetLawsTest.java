package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;

/**
 * The name alphabet on the FIELD carrier, and it is NOT the group carrier's — five of thirteen rows differ.
 *
 * <p>{@link GroupNameAlphabetLawsTest} established two gates on a GROUP's declared name: the path validator's
 * alphabet, and a second gate reserving a leading underscore for {@code __meta} / {@code __attachment_meta*}.
 * This grid is that one row for row on a field, written because applying a measured law to an unmeasured
 * carrier is the transfer that needs no new branch and therefore prompts no question. <b>It does not
 * transfer</b>, and one of the differences is in the direction this project forbids: a field named
 * {@code _Slots} is ADMITTED, so a checker inheriting the group's underscore gate would refuse a model A12
 * takes.
 *
 * <table><caption>where the two carriers part</caption>
 * <tr><th>name</th><th>as a GROUP</th><th>as a FIELD</th></tr>
 * <tr><td>{@code Slöts}</td><td>ADMITTED</td><td>{@code MVK_PATH_INVALID}</td></tr>
 * <tr><td>{@code _Slots}, {@code __other}</td><td>{@code NAME_STARTS_WITH_UNDERSCORE_AND_WRONG_PATTERN}</td>
 *     <td>ADMITTED</td></tr>
 * <tr><td>{@code 1Slots}</td><td>{@code MVK_PATH_INVALID}</td>
 *     <td>{@code MVK_PATH_INVALID} <b>and</b> {@code MVK_NAME_STARTS_WITH_DIGIT_A12_DV}</td></tr>
 * <tr><td>{@code Slot:List}</td><td>{@code MVK_PATH_INVALID}</td>
 *     <td>{@code MVK_NAME_INVALID_DOT_COLON_A12_DV} <b>instead</b></td></tr>
 * <tr><td>61 characters</td><td>refused, codeless</td><td>ADMITTED</td></tr>
 * </table>
 *
 * <p><b>So "the element name alphabet" is not one law, and the group carrier is the stricter one in both
 * directions that matter.</b> It refuses the leading underscore a field takes and admits the non-ASCII letter
 * a field refuses; the length limit is the group's alone. A checker has to ask the question per carrier, and
 * the {@code _A12_DV} codes say why: the field name answers to a second validator the group name does not
 * reach.
 *
 * <p>Own domain (a warehouse bin), matching its sibling's fixture shape.
 */
class FieldNameAlphabetLawsTest {

    /**
     * Every ASCII character outside the admitted alphabet, so the denominator is DERIVED rather than sampled.
     *
     * <p>A checker naming A12's code for a bad name may key on nothing wider than what has been measured, and
     * a hand-picked list of punctuation cannot miss what it does not think of — the same argument the temporal
     * format vocabulary's generator makes. So the sweep is every printable ASCII character that is not a
     * letter, a digit or an underscore, and the expectation is written per OUTCOME rather than per character.
     */
    private static final String ILLEGAL_ASCII =
            " !\"#$%&'()*+,-./:;<=>?@[\\]^`{|}~";

    @Test
    void theFieldNameAlphabetIsNotTheGroupCarriersAndFiveRowsSayWhere() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("Slots2 — letters and digits", declared("Slots2"));
        actual.put("Slot_List — an interior underscore", declared("Slot_List"));
        actual.put("Sloets with a non-ASCII letter", declared("Slöts"));
        actual.put("1Slots — digit-initial", declared("1Slots"));
        actual.put("Slot-List — a hyphen", declared("Slot-List"));
        actual.put("_Slots — a leading underscore", declared("_Slots"));
        actual.put("__other — a reserved-looking prefix that is not reserved", declared("__other"));
        actual.put("__meta — the group carrier's reserved exemption", declared("__meta"));
        actual.put("__attachment_meta_photo — the prefixed exemption", declared("__attachment_meta_photo"));
        actual.put("Slot List — a space", declared("Slot List"));
        actual.put("Slot:List — a colon", declared("Slot:List"));
        actual.put("60 characters — the group carrier's stated limit", declared("A".repeat(60)));
        actual.put("61 characters — one over", declared("A".repeat(61)));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Slots2 — letters and digits", KernelLaws.ADMITTED);
        expected.put("Slot_List — an interior underscore", KernelLaws.ADMITTED);
        // KF315 admits the sampled German letter on the group but refuses it on the field — the reverse of the underscore row below,
        // so neither carrier is simply the stricter one.
        expected.put("Sloets with a non-ASCII letter", "MVK_PATH_INVALID");
        expected.put("1Slots — digit-initial", "MVK_PATH_INVALID,MVK_NAME_STARTS_WITH_DIGIT_A12_DV");
        expected.put("Slot-List — a hyphen", "MVK_PATH_INVALID");
        // The row in the forbidden direction: a checker inheriting the group's underscore gate would refuse
        // these two, and A12 admits both.
        expected.put("_Slots — a leading underscore", KernelLaws.ADMITTED);
        expected.put("__other — a reserved-looking prefix that is not reserved", KernelLaws.ADMITTED);
        expected.put("__meta — the group carrier's reserved exemption", KernelLaws.ADMITTED);
        expected.put("__attachment_meta_photo — the prefixed exemption", KernelLaws.ADMITTED);
        expected.put("Slot List — a space", "MVK_PATH_INVALID");
        // A different code entirely, from the validator whose name says which characters it owns.
        expected.put("Slot:List — a colon", "MVK_NAME_INVALID_DOT_COLON_A12_DV");
        expected.put("60 characters — the group carrier's stated limit", KernelLaws.ADMITTED);
        // The length limit is the GROUP carrier's alone.
        expected.put("61 characters — one over", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the field-name alphabet against the group carrier's");
    }

    /**
     * WHICH code each interior character draws, because the rows above show a field has more than one.
     *
     * <p>A checker reporting a bad name has to name A12's code, and on this carrier the code depends on the
     * CHARACTER — three ways. The dot and the colon answer to {@code MVK_NAME_INVALID_DOT_COLON_A12_DV}; the
     * path separator {@code /} answers to {@code MVK_PATHS_INCONSISTENT}, because a name carrying it makes the
     * element's own path disagree with the tree it sits in rather than being a bad name at all; everything
     * else the path validator refuses answers to {@code MVK_PATH_INVALID}. One rule for "an illegal character"
     * would be right about most of them and wrong about three, and which three is not guessable from the
     * validator's name alone.
     *
     * <p>The admitted rows are the control: without them the grid measures the slot rather than the character,
     * and they are also the over-rejection guard, since a checker refusing one of them rejects a legal model.
     */
    @Test
    void whichCodeEachInteriorCharacterDrawsOnAFieldName() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (String admitted : new String[] {"_", "0", "Z", "z"}) {
            actual.put("`" + admitted + "` — the control", declared("Slot" + admitted + "List"));
            expected.put("`" + admitted + "` — the control", KernelLaws.ADMITTED);
        }
        for (char c : ILLEGAL_ASCII.toCharArray()) {
            String key = "`" + c + "`";
            actual.put(key, declared("Slot" + c + "List"));
            // The dot and the colon answer to their own validator; the separator makes the element's own path
            // disagree with the tree rather than being a bad name at all; everything else is the path
            // validator's alphabet.
            expected.put(key, switch (c) {
                case '.', ':' -> "MVK_NAME_INVALID_DOT_COLON_A12_DV";
                case '/' -> "MVK_PATHS_INCONSISTENT";
                default -> "MVK_PATH_INVALID";
            });
        }
        assertEquals(expected, actual, "which code each interior character draws on a field name");
    }

    /**
     * The notification CLASSES a refusal carries, or {@code (codeless)} — the sibling grid's helper, on a
     * field.
     *
     * <p>Codeless prose is dropped for the reason stated there: one malformed name re-reports the path
     * validator's sentence once per element beneath it, so a raw collect measures the fixture's shape rather
     * than the law.
     */
    private static String declared(String fieldName) {
        String json = DocumentModelDraft.named("warehouse-bin").locale("en_US")
                .rootGroup("Bin", b -> b
                        .stringField("Ref", s -> { })
                        .stringField(fieldName, s -> { }))
                .build().toJson();
        try {
            BoundModel.ofJson(json);
            return KernelLaws.ADMITTED;
        } catch (ModelConsistencyException rejected) {
            String classes = rejected.notifications().stream()
                    .map(n -> n.message().trim())
                    .filter(m -> m.matches(".*\\[\\w+]$"))
                    .map(m -> m.replaceAll(".*\\[(\\w+)]$", "$1"))
                    .distinct()
                    .collect(Collectors.joining(","));
            return classes.isEmpty() ? "(codeless)" : classes;
        }
    }
}
