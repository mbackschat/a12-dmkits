package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.core.ModelIndex;

/**
 * The kits read a field declaration's primitive {@code boolean} members as A12's reader binds them — {@code 1} and
 * {@code " TRUE"} set a member, {@code 0} and the text {@code "null"} clear it (KERNEL-FINDINGS KF569, the
 * {@code :predicates} law that {@code core.ConfigFlag} carries) — on every read path a consumer reaches: the
 * typed {@link ModelDocument#authoredState()}, the {@code field read} block {@link ModelDocument#fieldConfigJson},
 * and {@link ModelIndex}. A JSON-boolean reader answers the default for both set spellings, which is the defect
 * this locks (IF404); the runtime consequence of the same reading is {@code adapter.laws.BooleanMemberBindingDiffTest}.
 */
class BooleanMemberReadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<Object> SET = List.of(1, " TRUE");
    private static final List<Object> CLEARED = List.of(0, "null");
    /**
     * Tokens A12 refuses the model for, so none is set (KF569): an exponent or a fraction, whatever the value. Written
     * verbatim, because the parsed decimal prints {@code 1e0} as {@code 1} and a reader judging that text sets it.
     */
    private static final List<Object> UNREAD = List.of(new Raw("1e0"), new Raw("1.0e1"), new Raw("0.1e1"));
    private static final String PLACEHOLDER = "boolean-member-token-placeholder";

    /** A number token written into the model text as it stands. */
    private record Raw(String token) {}

    /** Where one member lives, and what each read path answers for it ({@code null}: that path does not carry it). */
    private record Member(String type, String member, String field,
            Function<ModelDocument.FieldState, Boolean> authored, String block, String blockKey,
            Function<ModelIndex, Boolean> index) {}

    private static final List<Member> MEMBERS = List.of(
            new Member("StringType", "lineBreaksPermitted", "Note",
                    s -> ((FieldTypeSpec.StringSpec) s.type()).lineBreaksPermitted(), "string", "lineBreaks", null),
            new Member("StringType", "noValueValidation", "Note",
                    s -> ((FieldTypeSpec.StringSpec) s.type()).noValueValidation(), "string", "noValueValidation",
                    i -> i.stringNoValueValidation("/Claim/Note")),
            new Member("StringType", "alphabeticalSorting", "Note",
                    s -> ((FieldTypeSpec.StringSpec) s.type()).alphabeticalSorting(), "string", "alphabeticalSorting",
                    null),
            new Member("NumberType", "zeroNotAllowed", "Amount",
                    s -> ((FieldTypeSpec.NumberSpec) s.type()).zeroNotAllowed(), "number", "zeroNotAllowed", null),
            new Member("NumberType", "positivesOnly", "Amount",
                    s -> ((FieldTypeSpec.NumberSpec) s.type()).positivesOnly(), "number", "positivesOnly",
                    i -> i.numberPositivesOnly("/Claim/Amount")),
            new Member("NumberType", "leadingZerosAllowed", "Amount",
                    s -> ((FieldTypeSpec.NumberSpec) s.type()).leadingZerosAllowed(), "number", "leadingZerosAllowed",
                    null),
            new Member("DateType", "youngerThan1900Check", "Inception",
                    s -> ((FieldTypeSpec.DateSpec) s.type()).rejectBefore1900(), "date", "rejectBefore1900", null),
            new Member("DateRangeType", "youngerThan1900Check", "Cover",
                    s -> ((FieldTypeSpec.DateRangeSpec) s.type()).rejectBefore1900(), "dateRange", "rejectBefore1900",
                    null),
            new Member("DateFragmentType", "youngerThan1900Check", "Year",
                    s -> ((FieldTypeSpec.DateFragmentSpec) s.type()).rejectBefore1900(), "dateFragment",
                    "rejectBefore1900", null),
            new Member("EnumerationType", "alphabeticalSorting", "Tier",
                    s -> ((FieldTypeSpec.EnumSpec) s.type()).alphabeticalSorting(), "enum", "alphabeticalSorting",
                    null),
            new Member("Field", "global", "Note", s -> s.meta().global(), null, null, null),
            new Member("Field", "transient", "Note", s -> s.meta().notPersisted(), null, null, null));

    @Test
    void everyReadPathBindsTheMemberAsA12Does() {
        Map<String, Boolean> expected = new LinkedHashMap<>();
        Map<String, Boolean> observed = new LinkedHashMap<>();
        for (Member m : MEMBERS) {
            for (Object spelling : concat(concat(SET, CLEARED), UNREAD)) {
                boolean set = SET.contains(spelling);
                String json = model(m, spelling);
                String row = m.type() + "." + m.member() + "=" + text(spelling);
                ModelDocument document = ModelDocument.read(json);
                expected.put(row + " authoredState", set);
                observed.put(row + " authoredState", m.authored().apply(document.authoredState().fields()
                        .get("/Claim/" + m.field())));
                if (m.block() != null) {
                    expected.put(row + " field read", set);
                    observed.put(row + " field read", document.fieldConfigJson("/Claim/" + m.field())
                            .map(BooleanMemberReadTest::tree)
                            .map(c -> c.path(m.block()).path(m.blockKey()).asBoolean(false))
                            .orElse(false));
                }
                if (m.index() != null) {
                    expected.put(row + " ModelIndex", set);
                    observed.put(row + " ModelIndex", m.index().apply(ModelIndex.ofSelfContained(json)));
                }
            }
        }
        assertEquals(expected, observed);
    }

    private static String model(Member m, Object spelling) {
        ObjectNode root = (ObjectNode) tree(DocumentModelDraft.named("claim").locale("en_US")
                .rootGroup("Claim", g -> g.stringField("Note", s -> { })
                        .numberField("Amount", n -> { })
                        .dateField("Inception", d -> { })
                        .dateRangeField("Cover", r -> r.format("dd.MM.yyyy").separator("-"))
                        .dateFragmentField("Year", f -> f.fragmentFormat("yyyy"))
                        .enumField("Tier", e -> e.value("BASIC").value("PLUS")))
                .build().toJson());
        for (JsonNode element : root.path("content").path("modelRoot").path("rootGroups").get(0)
                .path("Group").path("elements")) {
            if (!element.path("name").asText().equals(m.field())) {
                continue;
            }
            ObjectNode field = (ObjectNode) element.path("Field");
            ObjectNode fieldType = (ObjectNode) field.path("fieldType");
            ObjectNode owner = m.type().equals("Field") ? field
                    : fieldType.get(m.type()) instanceof ObjectNode body ? body : fieldType.putObject(m.type());
            owner.set(m.member(), MAPPER.valueToTree(spelling instanceof Raw ? PLACEHOLDER : spelling));
            if (m.member().equals("noValueValidation")) {
                owner.put("lineBreaksPermitted", true); // A12's co-requirement, so the set rows stay buildable
            }
        }
        return spelling instanceof Raw raw ? root.toString().replace("\"" + PLACEHOLDER + "\"", raw.token()) : root.toString();
    }

    private static JsonNode tree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String text(Object spelling) {
        return spelling instanceof String s ? "\"" + s + "\"" : spelling instanceof Raw raw ? raw.token() : spelling.toString();
    }

    private static List<Object> concat(List<Object> a, List<Object> b) {
        List<Object> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}
