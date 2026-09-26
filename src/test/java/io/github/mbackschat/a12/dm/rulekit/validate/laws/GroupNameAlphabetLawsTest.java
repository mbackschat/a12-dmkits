package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §13 — <b>the group-declaration alphabet answers to TWO gates, and the kernel's own prose
 * states only the first</b>; and, in the message {@code $#…$} position, <b>a quoted segment is lexed as a
 * unit for some illegal characters and not for others</b>.
 *
 * <p><b>The path validator's message is the trap.</b> It says a group name "may only contain letters, numbers
 * or underscores. Group names must begin with a letter or an underscore" — and a leading underscore is then
 * refused by a SECOND gate, {@code NAME_STARTS_WITH_UNDERSCORE_AND_WRONG_PATTERN}, which reserves that prefix
 * for {@code __meta} and {@code __attachment_meta*}. A consumer implementing the quoted sentence admits names
 * the kernel refuses, and the sentence is the kernel's own.
 *
 * <p><b>"Letters" is wider than ASCII</b> — {@code Slöts} is admitted — so an implementation validating
 * {@code [A-Za-z]} refuses legal models from the other direction. It is not Unicode either: the group's pattern
 * adds only the German umlauts and {@code ß}, and a group named {@code Parté} is refused ({@code KERNEL-FINDINGS
 * KF315}, corrected).
 *
 * <p><b>The colon is the exception that keeps the lexer claim honest.</b> A hyphen, a space and a digit-initial
 * spelling inside a QUOTED segment die at the lexer, {@code MVK_LEXER_STANDARD_ERROR}, where the identical
 * characters BARE reach the group lookup and draw {@code INVALID_GROUP}. A colon does not: quoted, it also
 * reaches the lookup. So the rule is not "an illegal character inside a quoted segment is a lexer error"; the
 * lexer's alphabet and the declaration's alphabet are different sets.
 *
 * <p>The control is an undeclared but WELL-FORMED name, which draws {@code INVALID_GROUP} in both spellings —
 * without it the lexer code and the lookup code read as two messages for one refusal rather than two layers.
 *
 * <p>Reported by a12-kernel-lean as {@code SPEC-2026-09-05-05}; the underscore gate and the colon exception
 * are corrections measured here.
 *
 * <p>Own domain (a warehouse bin sheet).
 */
class GroupNameAlphabetLawsTest {

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

    private static final String GROUP = "/Bin";
    private static final String ERROR_REL = "../Qty";
    private static final String CONDITION = "[/Bin/Slots/Qty] > 0";
    private static final String LEXER = "MVK_LEXER_STANDARD_ERROR";
    private static final String LOOKUP = "INVALID_GROUP";

    private static final String BIN = DocumentModelDraft.named("warehouse-bin").locale("en_US")
            .rootGroup("Bin", b -> b
                    .stringField("Ref", s -> { })
                    .group("Slots", g -> g.repeatable(20)
                            .numberField("Qty", n -> n.fractionDigits(0))))
            .build().toJson();

    /** The bin sheet with its nested group named [groupName], as A12 judges it. */
    private static String declared(String groupName) {
        return KernelLaws.verdictOf(DocumentModelDraft.named("warehouse-bin").locale("en_US")
                .rootGroup("Bin", b -> b
                        .stringField("Ref", s -> { })
                        .group(groupName, g -> g.numberField("Qty", n -> n.fractionDigits(0))))
                .build().toJson());
    }

    /** The same sheet with its ROOT group named [rootName], every other declaration held constant. */
    private static String declaredRoot(String rootName) {
        return KernelLaws.verdictOf(DocumentModelDraft.named("warehouse-bin").locale("en_US")
                .rootGroup(rootName, b -> b
                        .stringField("Ref", s -> { })
                        .group("Slots", g -> g.numberField("Qty", n -> n.fractionDigits(0))))
                .build().toJson());
    }

    /** …and with a FIELD of the nested group named [fieldName]. */
    private static String declaredField(String fieldName) {
        return KernelLaws.verdictOf(DocumentModelDraft.named("warehouse-bin").locale("en_US")
                .rootGroup("Bin", b -> b
                        .stringField("Ref", s -> { })
                        .group("Slots", g -> g.stringField(fieldName, n -> { })))
                .build().toJson());
    }

    private static String parameter(String messageText) {
        return KernelLaws.messageVerdict(BIN, "/Bin/Slots", ERROR_REL, CONDITION, "en_US", messageText);
    }

    /**
     * WHICH code each interior character draws, the group half of the pair.
     *
     * <p>{@link FieldNameAlphabetLawsTest} measures the same characters on a field and gets THREE answers;
     * this grid says what the group carrier does with them, because a checker naming A12's code has to pick
     * per carrier and per character rather than once. The admitted rows are the control and the
     * over-rejection guard at the same time: a checker refusing one of them rejects a legal model.
     */
    @Test
    void whichCodeEachInteriorCharacterDrawsOnAGroupName() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (String admitted : new String[] {"_", "0", "Z", "z"}) {
            actual.put("`" + admitted + "` — the control", declared("Slot" + admitted + "List"));
            expected.put("`" + admitted + "` — the control", KernelLaws.ADMITTED);
        }
        for (char c : ILLEGAL_ASCII.toCharArray()) {
            String key = "`" + c + "`";
            actual.put(key, declared("Slot" + c + "List"));
            // On THIS carrier the split is two-way, not the field's three: the separator makes the element's
            // own path disagree with the tree, and every other character is the path validator's alphabet.
            expected.put(key, c == '/' ? "MVK_PATHS_INCONSISTENT" : "MVK_PATH_INVALID");
        }
        assertEquals(expected, actual, "which code each interior character draws on a group name");
    }

    /**
     * The one NAME A12 reserves outright, and the carrier it reserves it on.
     *
     * <p>[MVK-LEDGER] carries {@code MVK_ROOT_GROUP_NAME_INVALID} as a structural law with no measurement.
     * It is the metamodel's only whole-name reservation — every other name law is about an alphabet — and it
     * sits on the ROOT-group carrier alone, which is the half a checker would get wrong by generalising.
     */
    @Test
    void theRootGroupNameThatA12Reserves() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("Bin — the control", declaredRoot("Bin"));
        actual.put("Id", declaredRoot("Id"));
        actual.put("ID", declaredRoot("ID"));
        actual.put("id", declaredRoot("id"));
        actual.put("iD", declaredRoot("iD"));
        actual.put("Identity — the reserved name as a PREFIX", declaredRoot("Identity"));
        actual.put("BinId — the reserved name as a SUFFIX", declaredRoot("BinId"));
        actual.put("a NESTED group of that name", declared("Id"));
        actual.put("a FIELD of that name", declaredField("Id"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Bin — the control", KernelLaws.ADMITTED);
        expected.put("Id", "MVK_ROOT_GROUP_NAME_INVALID");
        expected.put("ID", "MVK_ROOT_GROUP_NAME_INVALID");
        expected.put("id", "MVK_ROOT_GROUP_NAME_INVALID");
        expected.put("iD", "MVK_ROOT_GROUP_NAME_INVALID");
        // The match is WHOLE-name, so the reservation costs a modeller nothing but the two characters alone.
        expected.put("Identity — the reserved name as a PREFIX", KernelLaws.ADMITTED);
        expected.put("BinId — the reserved name as a SUFFIX", KernelLaws.ADMITTED);
        // …and it is the ROOT carrier's law alone: the same name one level down, and on a field, is fine.
        expected.put("a NESTED group of that name", KernelLaws.ADMITTED);
        expected.put("a FIELD of that name", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the root-group name A12 reserves");
    }

    /**
     * The dot and the colon on the ROOT carrier — the cell [KERNEL-FINDINGS KF316]'s carrier table leaves open.
     *
     * <p>It is not a curiosity about one more character: a field's FULL NAME is its path, so a root group
     * that admitted a dot would put one into every field beneath it, and the calculation module refuses a
     * computed field whose full name carries a dot or a colon
     * ({@code CalculationService.checkCalculationRule} → {@code MVK_ERROR_INVALID_FIELDNAME}). That class is
     * reachable from stored DM-JSON exactly if this row is ADMITTED, which is why the root carrier has to be
     * measured rather than inferred from the field carrier's own dot row above.
     */
    @Test
    void theDotAndColonOnTheRootCarrier() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("Bin — the control", declaredRoot("Bin"));
        actual.put("Bin.Sheet — a dot", declaredRoot("Bin.Sheet"));
        actual.put("Bin:Sheet — a colon", declaredRoot("Bin:Sheet"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Bin — the control", KernelLaws.ADMITTED);
        // The path validator answers on this carrier, not the name one the FIELD carrier draws — so the
        // dotted full name the calculation gate looks for cannot be built through a root group either.
        expected.put("Bin.Sheet — a dot", "MVK_PATH_INVALID");
        expected.put("Bin:Sheet — a colon", "MVK_PATH_INVALID");
        assertEquals(expected, actual, "the dot and the colon on the root-group carrier");
    }

    /** The declaration gate, and the second gate the kernel's own wording does not mention. */
    @Test
    void theGroupNameAlphabetIsNarrowerThanTheKernelsOwnSentence() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("Slots2 — letters and digits", declared("Slots2"));
        actual.put("Slot_List — an interior underscore", declared("Slot_List"));
        actual.put("Sloets with a non-ASCII letter", declared("Slöts"));
        actual.put("1Slots — digit-initial", declared("1Slots"));
        actual.put("Slot-List — a hyphen", declared("Slot-List"));
        actual.put("_Slots — the sentence says this is legal", declared("_Slots"));
        actual.put("__other — a reserved-looking prefix that is not reserved", declared("__other"));
        actual.put("__meta — the reserved exemption", declared("__meta"));
        actual.put("__attachment_meta_photo — the prefixed exemption", declared("__attachment_meta_photo"));
        actual.put("__attachment_meta — the bare prefixed exemption", declared("__attachment_meta"));
        actual.put("Slot List — a space", declared("Slot List"));
        actual.put("Slot:List — a colon", declared("Slot:List"));
        actual.put("60 characters — the stated limit", declared("A".repeat(60)));
        actual.put("61 characters — one over", declared("A".repeat(61)));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Slots2 — letters and digits", KernelLaws.ADMITTED);
        expected.put("Slot_List — an interior underscore", KernelLaws.ADMITTED);
        expected.put("Sloets with a non-ASCII letter", KernelLaws.ADMITTED);
        expected.put("1Slots — digit-initial", "MVK_PATH_INVALID");
        expected.put("Slot-List — a hyphen", "MVK_PATH_INVALID");
        expected.put("_Slots — the sentence says this is legal", "NAME_STARTS_WITH_UNDERSCORE_AND_WRONG_PATTERN");
        expected.put("__other — a reserved-looking prefix that is not reserved",
                "NAME_STARTS_WITH_UNDERSCORE_AND_WRONG_PATTERN");
        expected.put("__meta — the reserved exemption", KernelLaws.ADMITTED);
        expected.put("__attachment_meta_photo — the prefixed exemption", KernelLaws.ADMITTED);
        expected.put("__attachment_meta — the bare prefixed exemption", KernelLaws.ADMITTED);
        expected.put("Slot List — a space", "MVK_PATH_INVALID");
        expected.put("Slot:List — a colon", "MVK_PATH_INVALID");
        expected.put("60 characters — the stated limit", KernelLaws.ADMITTED);
        expected.put("61 characters — one over", KernelLaws.REFUSED_CODELESS);
        assertEquals(expected, actual, "a leading underscore is reserved, and 'letters' means Unicode letters");
    }

    /** Quoting a segment moves the refusal a layer earlier — for three characters out of four. */
    @Test
    void quotingASegmentMovesTheRefusalToTheLexerExceptForAColon() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("declared group, bare", parameter("row $#/Bin/Slots$ over limit"));
        actual.put("declared group, quoted", parameter("row $#/Bin/'Slots'$ over limit"));
        actual.put("undeclared well-formed, bare", parameter("row $#/Bin/Ghost$ over limit"));
        actual.put("undeclared well-formed, quoted", parameter("row $#/Bin/'Ghost'$ over limit"));
        actual.put("hyphen, bare", parameter("row $#/Bin/Slot-List$ over limit"));
        actual.put("hyphen, quoted", parameter("row $#/Bin/'Slot-List'$ over limit"));
        actual.put("space, bare", parameter("row $#/Bin/Slot List$ over limit"));
        actual.put("space, quoted", parameter("row $#/Bin/'Slot List'$ over limit"));
        actual.put("digit-initial, bare", parameter("row $#/Bin/1Slots$ over limit"));
        actual.put("digit-initial, quoted", parameter("row $#/Bin/'1Slots'$ over limit"));
        actual.put("colon, bare", parameter("row $#/Bin/Slot:List$ over limit"));
        actual.put("colon, quoted — the exception", parameter("row $#/Bin/'Slot:List'$ over limit"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("declared group, bare", KernelLaws.ADMITTED);
        expected.put("declared group, quoted", KernelLaws.ADMITTED);
        expected.put("undeclared well-formed, bare", LOOKUP);
        expected.put("undeclared well-formed, quoted", LOOKUP);
        expected.put("hyphen, bare", LOOKUP);
        expected.put("hyphen, quoted", LEXER);
        expected.put("space, bare", LOOKUP);
        expected.put("space, quoted", LEXER);
        expected.put("digit-initial, bare", LOOKUP);
        expected.put("digit-initial, quoted", LEXER);
        expected.put("colon, bare", LOOKUP);
        expected.put("colon, quoted — the exception", LOOKUP);
        assertEquals(expected, actual, "the lexer's alphabet is not the declaration's");
    }
}
