package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §8 — a semantic-index key ADDRESSES the repetition it keys, for every operand family's own
 * gate, and a defective key answers before that gate.
 *
 * <p>Each family row is a within-model pair. The KEYED read names {@code Claims/<field> For <key>} from a rule
 * at the root, so the key is the only thing that addresses the unstarred {@code Claims}. The LOCAL read names
 * the same field from a rule declared inside {@code Claims}, where the declaration's own iteration addresses
 * it. The account under test is that the key addresses exactly as that iteration does, so each family raises
 * the same class on both. The rival is that the keyed read stays unaddressed ({@code MVK_NO_WILDCARD}), draws a
 * class of the key's own, or is admitted. The unkeyed read from the root is each family's control that the rival
 * is observable: it is unaddressed in every family, so a keyed read raising the family's class is the key's doing.
 *
 * <p>{@code Riders} declares the fields of {@code Claims} and no index field, and {@code Endorsements} indexes
 * them on the enumeration {@code Status}, so each keyed read differs from a sound one in that declaration alone.
 *
 * <p>The probes are text because several deliberately carry a key the typed builders refuse.
 *
 * <p>Own domain (insurance claims).
 */
class KeyedOperandFamilyGateLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String POLICY = DocumentModelDraft.named("policy-keyed").locale("en_US")
            .rootGroup("Policy", p -> p
                    .stringField("Reference", f -> { })
                    .stringField("Code", f -> { })
                    .numberField("Limit", f -> f.fractionDigits(0))
                    .dateField("Opened", f -> f.format("yyyy-MM-dd"))
                    .enumField("Stage", e -> e
                            .value("OPEN", l -> l.label("en_US", "Open claim"))
                            .value("CLOSED", l -> l.label("en_US", "Closed claim")))
                    .enumField("Outcome", e -> e.value("OPEN").value("CLOSED"))
                    .group("Claims", g -> claimFields(g.repeatable(20).indexField("ClaimRef")))
                    .group("Riders", g -> claimFields(g.repeatable(5)))
                    .group("Endorsements", g -> claimFields(g.repeatable(5).indexField("Status")))
                    .group("Notes", g -> g.repeatable(5).stringField("Text", f -> { })))
            .build().toJson();

    /** The fields each repeatable group of the fixture declares, so its groups differ in their index alone. */
    private static DocumentModelDraft.GroupBuilder claimFields(DocumentModelDraft.GroupBuilder g) {
        return g.stringField("ClaimRef", f -> { })
                .numberField("Amount", f -> f.fractionDigits(2))
                .stringField("Memo", f -> { })
                .dateField("Filed", f -> f.format("yyyy-MM-dd"))
                .booleanField("Settled", f -> { })
                .enumField("Status", f -> f.value("OPEN").value("CLOSED"));
    }

    /** Each family's probe, with `%s` where the operand stands. */
    private static final Map<String, String> PROBES = new LinkedHashMap<>();

    static {
        PROBES.put("arity", "Sum(%s) > 0");
        PROBES.put("field-list all numbers", "Sum(%s, %s) > 0");
        PROBES.put("value-in-fields constant kind", "NumberOfValueInFields(5 In %s, %s) > 0");
        PROBES.put("range operand", "RangeAsString(%s, 1, 2) == \"a\"");
        PROBES.put("value-as-date operand", "ValueAsDate(%s, FirstDay) > \"01.01.2020\"");
        PROBES.put("field-value operand", "FieldValueAsString(%s) == \"a\"");
        PROBES.put("rounding operand", "RoundUpValue(%s, 0) > 0");
        PROBES.put("comparison against a constant", "[%s] == 5");
        PROBES.put("boolean comparison", "[%s] == \"x\"");
        PROBES.put("string/enum comparison", "[%s] == \"PENDING\"");
        PROBES.put("date-range list", "DateRangesOverlap(%s, %s)");
        PROBES.put("date arithmetic", "AddDays(%s, 1) < Today");
        PROBES.put("number scale comparison", "[%s] == [Limit]");
        PROBES.put("tolerance comparison", "[%s] DiffersWithToleranceRange1 5");
        PROBES.put("pattern comparison", "[%s] PatternMatched \"a+\"");
        PROBES.put("enum display comparison", "[%s] == [Stage]");
        PROBES.put("rounding a computed value", "RoundUp([%s], 0) > 0");
        PROBES.put("date arithmetic amount", "AddDays(Opened, [%s]) < Today");
        PROBES.put("date arithmetic precision", "[Opened] < AddHours(%s, 1)");
        PROBES.put("entity-list duplicate", "Sum(%s, %s) > 0");
    }

    /** The field each family's probe reads — the one its own gate refuses. */
    private static final Map<String, String[]> FIELDS = new LinkedHashMap<>();

    static {
        FIELDS.put("arity", new String[] {"Amount"});
        FIELDS.put("field-list all numbers", new String[] {"Memo", "Amount"});
        FIELDS.put("value-in-fields constant kind", new String[] {"Memo", "ClaimRef"});
        FIELDS.put("range operand", new String[] {"Amount"});
        FIELDS.put("value-as-date operand", new String[] {"Memo"});
        FIELDS.put("field-value operand", new String[] {"Memo"});
        FIELDS.put("rounding operand", new String[] {"Memo"});
        FIELDS.put("comparison against a constant", new String[] {"Filed"});
        FIELDS.put("boolean comparison", new String[] {"Settled"});
        FIELDS.put("string/enum comparison", new String[] {"Status"});
        FIELDS.put("date-range list", new String[] {"Memo", "ClaimRef"});
        FIELDS.put("date arithmetic", new String[] {"Memo"});
        FIELDS.put("number scale comparison", new String[] {"Amount"});
        FIELDS.put("tolerance comparison", new String[] {"Memo"});
        FIELDS.put("pattern comparison", new String[] {"Amount"});
        FIELDS.put("enum display comparison", new String[] {"Status"});
        FIELDS.put("rounding a computed value", new String[] {"Memo"});
        FIELDS.put("date arithmetic amount", new String[] {"Memo"});
        FIELDS.put("date arithmetic precision", new String[] {"Filed"});
        FIELDS.put("entity-list duplicate", new String[] {"Amount", "Amount"});
    }

    private static String keyed(String probe) {
        return KernelLaws.verdictModel(POLICY, "/Policy", "../Reference", "FieldFilled(Reference) And " + probe);
    }

    private static String local(String probe) {
        return local("Claims", probe);
    }

    private static String local(String group, String probe) {
        return KernelLaws.verdictModel(POLICY, "/Policy/" + group, "../ClaimRef", "FieldFilled(ClaimRef) And " + probe);
    }

    private static String probe(String family, String... operands) {
        return String.format(PROBES.get(family), (Object[]) operands);
    }

    private static String[] operands(String family, String form) {
        String[] fields = FIELDS.get(family);
        String[] out = new String[fields.length];
        for (int i = 0; i < fields.length; i++) {
            out[i] = form.formatted(fields[i]);
        }
        return out;
    }

    /** Each family's own class on its probe — what the three reads below must all raise. */
    private static final Map<String, String> CLASS = new LinkedHashMap<>();

    static {
        CLASS.put("arity", "MVK_PARAMSIZE_INVALIDN");
        CLASS.put("field-list all numbers", "MVK_NO_NUMBER");
        CLASS.put("value-in-fields constant kind", "MVK_NO_NUMBER");
        CLASS.put("range operand", "MVK_INVALID_TYPE");
        CLASS.put("value-as-date operand", "MVK_INVALID_DATE_TYPE_FOR_OP");
        CLASS.put("field-value operand", "MVK_NO_NUMBER");
        CLASS.put("rounding operand", "MVK_NO_NUMBER");
        CLASS.put("comparison against a constant", "MVK_INVALID_COMPARE_TO_DATE");
        CLASS.put("boolean comparison", "MVK_INVALID_COMPARE_TO_YESNO");
        CLASS.put("string/enum comparison", "MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON");
        CLASS.put("date-range list", "MVK_NO_DATE_RANGE");
        CLASS.put("date arithmetic", "MVK_NO_DATE");
        CLASS.put("number scale comparison", "MVK_INVALID_COMPARE_DEC_PLACES");
        CLASS.put("tolerance comparison", "MVK_INVALID_TYPE_FOR_TOLERANCE_OP");
        CLASS.put("pattern comparison", "MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON");
        CLASS.put("enum display comparison", "MVK_INVALID_COMPARE_ENUM_TO_STRING");
        CLASS.put("rounding a computed value", "MVK_NO_NUMBER");
        CLASS.put("date arithmetic amount", "MVK_NON_NUMERIC");
        CLASS.put("date arithmetic precision", "MVK_WRONG_DATE_FORMAT_FOR_OP");
        CLASS.put("entity-list duplicate", "MVK_DUPLICATE_PARAM1");
    }

    /**
     * A key addresses its level as the declaration's iteration does: each family raises its own class on the
     * string-keyed, the field-keyed and the local read alike.
     */
    @Test
    void aKeyAddressesItsLevelForEveryOperandFamily() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (String family : PROBES.keySet()) {
            actual.put(family + " — local", local(probe(family, operands(family, "%s"))));
            actual.put(family + " — string key", keyed(probe(family, operands(family, "Claims/%s For \"X\""))));
            actual.put(family + " — field key", keyed(probe(family, operands(family, "Claims/%s For Code"))));
            for (String read : new String[] {" — local", " — string key", " — field key"}) {
                expected.put(family + read, CLASS.get(family));
            }
        }
        assertEquals(expected, actual, "each family's class on the local read and on both keyed reads");
    }

    /** Without the key each family's read is unaddressed — the control that it is the key which addresses. */
    @Test
    void withoutItsKeyTheReadIsUnaddressed() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (String family : PROBES.keySet()) {
            actual.put(family, keyed(probe(family, operands(family, "Claims/%s"))));
            expected.put(family, "MVK_NO_WILDCARD");
        }
        assertEquals(expected, actual, "each family's unkeyed read across the unstarred Claims");
    }

    /** Each key defect, as the operand form it is spelled with, and the class it raises. */
    private static final Map<String, String[]> DEFECTS = new LinkedHashMap<>();

    static {
        DEFECTS.put("no index field on the keyed group", new String[] {"Riders/%s For \"X\"", "MVK_NO_INDEX_FIELD"});
        DEFECTS.put("a Number key over a String index", new String[] {"Claims/%s For Limit", "MVK_INDEX_TYPE_INCONSISTENT"});
        // The grammar's entity name admits a bare digit sequence (`nameOhnePunkt: … | ZiffernFolge`), so `5` is
        // read as an entity key named `5`, which resolves nothing, not as a constant key of the wrong kind.
        DEFECTS.put("a number literal key", new String[] {"Claims/%s For 5", "MVK_INVALID_ENTITY"});
        DEFECTS.put("an iterating key", new String[] {"Claims/%s For Notes/Text", "MVK_SEMANTIC_INDEX_INVALID_ITER"});
        DEFECTS.put("a key beside a star", new String[] {"Claims*/%s For \"X\"", "MVK_SEMANTIC_INDEX_AND_WILDCARD"});
        DEFECTS.put("a constant the enumeration index refuses", new String[] {"Endorsements/%s For \"PENDING\"", "MVK_INDEX_VALUE_INVALID"});
    }

    /**
     * A defective key answers before every family's gate, although each family's gate would refuse the operand
     * too. Each defective group's local read is the control that its fields draw the family's class.
     */
    @Test
    void aDefectiveKeyAnswersBeforeTheFamilysGate() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (String family : PROBES.keySet()) {
            for (String group : new String[] {"Riders", "Endorsements"}) {
                actual.put(family + " — " + group + " local", local(group, probe(family, operands(family, "%s"))));
                expected.put(family + " — " + group + " local", CLASS.get(family));
            }
            DEFECTS.forEach((defect, formAndClass) -> {
                actual.put(family + " — " + defect, keyed(probe(family, operands(family, formAndClass[0]))));
                expected.put(family + " — " + defect, formAndClass[1]);
            });
        }
        assertEquals(expected, actual, "a defective key against each family's gate");
    }

    /**
     * A key on the level the rule already iterates: sound, the family's gate answers as on the unkeyed read;
     * defective, the key answers first. So a reader that drops the key there reads past the key's class.
     */
    @Test
    void aKeyOnTheLevelTheRuleIteratesIsStillJudgedFirst() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (String family : PROBES.keySet()) {
            actual.put(family + " — sound", local(probe(family, operands(family, "%s For \"X\""))));
            expected.put(family + " — sound", CLASS.get(family));
            actual.put(family + " — number literal key", local(probe(family, operands(family, "%s For 5"))));
            expected.put(family + " — number literal key", "MVK_INVALID_ENTITY");
        }
        assertEquals(expected, actual, "a key on the rule's own iterated level");
    }

    /**
     * Inside a `Having` body a read is judged as outside one: the unkeyed read reaches the family's gate though
     * no star addresses the level it crosses, whether that is another level or the filtered one; the sound key
     * keeps it, and a defective key answers first.
     */
    @Test
    void aReadInsideAFilterBodyIsJudgedAsOutsideOne() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (String family : PROBES.keySet()) {
            for (String form : new String[] {"Claims/%s", "Claims/%s For \"X\"", "Claims/%s For 5"}) {
                actual.put(family + " — " + form, keyed("NumberOfFilledFields(Notes*/Text Having "
                        + probe(family, operands(family, form)) + ") > 0"));
                expected.put(family + " — " + form, form.endsWith("5") ? "MVK_INVALID_ENTITY" : CLASS.get(family));
            }
            actual.put(family + " — the filtered level's own body", keyed("NumberOfFilledFields(Claims*/Memo Having "
                    + probe(family, operands(family, "Claims/%s")) + ") > 0"));
            expected.put(family + " — the filtered level's own body", CLASS.get(family));
        }
        assertEquals(expected, actual, "a read inside a filter body");
    }

    /**
     * An enumeration-valued computation reading a text field: the gate that refuses it is the calculation
     * module's, not a condition operator's, so it is measured on its own carrier. The root's own text field
     * is the class's control, the unkeyed read the addressing control.
     */
    @Test
    void anEnumComputationSourceReadThroughAKey() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a root text field — the class's control", computingOutcomeFrom("[Reference]"));
        actual.put("unkeyed", computingOutcomeFrom("[Claims/Memo]"));
        actual.put("string key", computingOutcomeFrom("[Claims/Memo For \"X\"]"));
        actual.put("field key", computingOutcomeFrom("[Claims/Memo For Code]"));
        actual.put("number literal key", computingOutcomeFrom("[Claims/Memo For 5]"));
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a root text field — the class's control", "MVK_ERROR_INVALID_ENUM_CALCULATION");
        expected.put("unkeyed", "MVK_NO_WILDCARD");
        expected.put("string key", "MVK_ERROR_INVALID_ENUM_CALCULATION");
        expected.put("field key", "MVK_ERROR_INVALID_ENUM_CALCULATION");
        expected.put("number literal key", "MVK_INVALID_ENTITY");
        assertEquals(expected, actual, "an enum computation's source through a key");
    }

    /** The model with one computation writing [operation] into the root enumeration `Outcome`. */
    private static String computingOutcomeFrom(String operation) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(POLICY);
            ObjectNode computation = (ObjectNode) ((ArrayNode) root.path("content").path("modelRoot")
                    .path("rootGroups").get(0).path("Group").path("elements")).addObject();
            computation.put("type", "Computation").put("id", "cko").put("name", "DerivedOutcome");
            ObjectNode body = computation.putObject("Computation");
            body.put("computedFieldRelPath", "../Outcome");
            body.putArray("computationAlternatives").addObject().put("operation", operation);
            body.putArray("errorMessage").addObject().put("locale", "en_US").put("text", "the derived outcome");
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }
}
