package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.ModelLoader;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * What kernel 31.1.1 asks of a document uniqueness criterion, the DM 28.6 {@code content.documentUniquenessCriteria}
 * member: a named set of fields whose values no two stored documents may share, with an error text per locale.
 *
 * <p>Every refusal row differs from the admitted control in one member of one criterion, on one fixture. The
 * criteria are written into the stored model as text, so the subject of every row is the stored shape A12 reads. Own domain (insurance policies).
 */
class DocumentUniquenessCriterionLawsTest {

    /**
     * {@code PolicyNo} and {@code Product} required, {@code Holder} optional, {@code Branch} required only when its
     * parent is, {@code Draft} transient, and {@code Claims[3] { ClaimRef }} with {@code ClaimRef} required.
     */
    private static final String POLICY = DocumentModelDraft.named("policy-uniqueness").locale("en_US")
            .rootGroup("Policy", p -> p
                    .stringField("PolicyNo", f -> f.required())
                    .stringField("Product", f -> f.required())
                    .stringField("Holder", f -> { })
                    .group("Office", o -> o.stringField("Branch", f -> f.requiredIfParentPresent()))
                    .stringField("Draft", f -> f.required().notPersisted())
                    .group("Claims", c -> c.repeatable(3).stringField("ClaimRef", f -> f.required())))
            .build().toJson();

    private static final String CONTROL = criterion("PolicyKey", "\"/Policy/PolicyNo\"",
            "Policy $/Policy/PolicyNo.value$ already exists");

    /** A criterion as A12 stores it; [fields] is the comma-joined quoted full names, a null [text] no message. */
    private static String criterion(String name, String fields, String text) {
        String fieldList = fields.isEmpty() ? "" : java.util.Arrays.stream(fields.split(","))
                .map(f -> "{\"fullName\":" + f + "}").collect(Collectors.joining(","));
        String message = text == null ? "" : "{\"locale\":\"en_US\",\"text\":\"" + text + "\"}";
        return "{\"name\":\"" + name + "\",\"fields\":[" + fieldList + "],\"errorMessage\":[" + message + "]}";
    }

    /** [POLICY] with these criteria, inserted as the content member A12 reads. */
    private static String withCriteria(String... criteria) {
        String member = "\"documentUniquenessCriteria\":[" + String.join(",", criteria) + "],";
        int at = POLICY.indexOf("\"modelRoot\"");
        return POLICY.substring(0, at) + member + POLICY.substring(at);
    }

    /** The verdict, with A12's notification text beside it so a codeless class stays readable. */
    private static String measured(String model) {
        try {
            ModelLoader.loadSelfContained(model);
            return KernelLaws.ADMITTED;
        } catch (ModelConsistencyException rejected) {
            return rejected.notifications().stream().map(n -> n.message().trim()).collect(Collectors.joining(" | "));
        } catch (RuntimeException raised) {
            return raised.getClass().getSimpleName() + ": " + raised.getMessage();
        }
    }

    /**
     * A12 states every class but one as a sentence with no class key, so the rows read the sentence: the dollar
     * count is the one keyed class, raised by the error-text grammar the rule messages share. A parameter must
     * name a field of the criterion by its full path, with or without {@code .value}; a relative path is refused
     * both in the field list and in the text, and a group is no field.
     */
    @Test
    void whatACriterionAsksOfItsNameFieldsAndText() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("control: one required field, one text naming it", measured(withCriteria(CONTROL)));
        actual.put("control: two required fields", measured(withCriteria(criterion("PolicyKey",
                "\"/Policy/PolicyNo\",\"/Policy/Product\"", "Policy $/Policy/PolicyNo$ of $/Policy/Product.value$ exists"))));
        actual.put("no criteria at all", measured(withCriteria()));
        actual.put("an empty name", measured(withCriteria(criterion("", "\"/Policy/PolicyNo\"", "Taken"))));
        actual.put("no field", measured(withCriteria(criterion("PolicyKey", "", "Taken"))));
        actual.put("a field that does not exist", measured(withCriteria(criterion("PolicyKey", "\"/Policy/Missing\"", "Taken"))));
        actual.put("a group in the field slot", measured(withCriteria(criterion("PolicyKey", "\"/Policy/Office\"", "Taken"))));
        actual.put("a relative field path", measured(withCriteria(criterion("PolicyKey", "\"PolicyNo\"", "Taken"))));
        actual.put("an optional field", measured(withCriteria(criterion("PolicyKey", "\"/Policy/Holder\"", "Taken"))));
        actual.put("a field required only with its parent",
                measured(withCriteria(criterion("PolicyKey", "\"/Policy/Office/Branch\"", "Taken"))));
        actual.put("a field in a repeatable group",
                measured(withCriteria(criterion("PolicyKey", "\"/Policy/Claims/ClaimRef\"", "Taken"))));
        actual.put("a transient field", measured(withCriteria(criterion("PolicyKey", "\"/Policy/Draft\"", "Taken"))));
        actual.put("no error text", measured(withCriteria(criterion("PolicyKey", "\"/Policy/PolicyNo\"", null))));
        actual.put("two criteria of one name", measured(withCriteria(CONTROL,
                criterion("PolicyKey", "\"/Policy/Product\"", "Taken"))));
        actual.put("a text naming a field outside the criterion", measured(withCriteria(criterion("PolicyKey",
                "\"/Policy/PolicyNo\"", "Policy $/Policy/Product$ exists"))));
        actual.put("a text naming the field relatively", measured(withCriteria(criterion("PolicyKey",
                "\"/Policy/PolicyNo\"", "Policy $PolicyNo$ exists"))));
        actual.put("an unterminated parameter", measured(withCriteria(criterion("PolicyKey",
                "\"/Policy/PolicyNo\"", "Policy $/Policy/PolicyNo exists"))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("control: one required field, one text naming it", KernelLaws.ADMITTED);
        expected.put("control: two required fields", KernelLaws.ADMITTED);
        expected.put("no criteria at all", KernelLaws.ADMITTED);
        expected.put("an empty name", "A Document Uniqueness Criterion must have a name.");
        expected.put("no field", "Document Uniqueness Criterion 'PolicyKey': Must specify at least one field.");
        expected.put("a field that does not exist", "Document Uniqueness Criterion 'PolicyKey': The field '/Policy/Missing' does not exist in the Document Model.");
        expected.put("a group in the field slot", "Document Uniqueness Criterion 'PolicyKey': The field '/Policy/Office' does not exist in the Document Model.");
        expected.put("a relative field path", "Document Uniqueness Criterion 'PolicyKey': The field 'PolicyNo' does not exist in the Document Model.");
        expected.put("an optional field", "Document Uniqueness Criterion 'PolicyKey': The field '/Policy/Holder' is not set as required.");
        expected.put("a field required only with its parent", "Document Uniqueness Criterion 'PolicyKey': The field '/Policy/Office/Branch' is not set as required.");
        expected.put("a field in a repeatable group", "Document Uniqueness Criterion 'PolicyKey': The field '/Policy/Claims/ClaimRef' is repeatable.");
        expected.put("a transient field", "Document Uniqueness Criterion 'PolicyKey': The field '/Policy/Draft' is transient.");
        expected.put("no error text", "Document Uniqueness Criterion 'PolicyKey': No error text was specified for locale 'en_US'.");
        expected.put("two criteria of one name", "Document Uniqueness Criterion 'PolicyKey': Name is not unique in the Document Model. | Document Uniqueness Criterion 'PolicyKey': Name is not unique in the Document Model.");
        expected.put("a text naming a field outside the criterion", "Document Uniqueness Criterion 'PolicyKey': Invalid error text parameter for locale 'en_US'. Only fields referenced in the Criterion are allowed.");
        expected.put("a text naming the field relatively", "Document Uniqueness Criterion 'PolicyKey': Invalid error text parameter for locale 'en_US'. Only fields referenced in the Criterion are allowed.");
        expected.put("an unterminated parameter", "Document Uniqueness Criterion 'PolicyKey': Invalid error text for locale 'en_US'. The error text contains an incorrect number of $ characters. [WRONG_NUMBER_OF_DOLLARS]");
        assertEquals(expected, actual);
    }
}
