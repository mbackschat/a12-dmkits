package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §8 — <b>everything the two SCALAR value-list predicates ask, and the order they ask it
 * in</b>.
 *
 * <p>{@code CheckFeldUndWerteListenBedingungImpl.check} judges the LEFT field first and completely
 * ({@code checkField}: is it a field, is it unstarred, is its declaration one of three kinds, does it waive
 * value validation) and only then looks at the list — which is itself two different checks depending on
 * whether the list is written out or is a list of {@code [field]} entries.
 *
 * <p><b>The head gates run on BOTH list forms</b>, which is what separates this method from a reader that
 * treats the constant list and the field-entry list as two constructs.
 *
 * <p><b>…yet a {@code [field]} entry is judged before the LEFT field.</b> Each entry is a field-value read,
 * whose own gates run as its bracket closes, during the parse and so ahead of this whole method (KF473).
 *
 * <p>Own domain (an insurance policy).
 */
class ScalarValueListGateLawsTest {

    private static final String ONLY_THREE_KINDS = "MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED";
    private static final String TYPES_FOR_COMPARISON = "MVK_INVALID_TYPES_FOR_COMPARISON";
    private static final String RAW_TYPE = "MVK_INVALID_RAW_TYPE";
    private static final String VARYING = "MVK_VARYING_TYPES_NOT_ALLOWED";

    private static final String POLICY = DocumentModelDraft.named("policy-scalar-value-lists").locale("en_US")
            .rootGroup("Policy", p -> p
                    .stringField("Reference", f -> { })
                    .stringField("Holder", f -> { })
                    .stringField("FreeNote", f -> f.lineBreaks().noValueValidation())
                    .numberField("Premium", f -> f.fractionDigits(2))
                    .enumField("Tier", e -> e.value("BASIC").value("PLUS").category("Band", "LOW", "HIGH"))
                    .enumField("Segment", e -> e.value("RETAIL").value("CORPORATE"))
                    .booleanField("Renewed", f -> { })
                    .confirmField("Accepted", f -> { })
                    .dateField("IssuedOn", f -> f.format("yyyy-MM-dd"))
                    .timeField("FiledAt", f -> f.format("HH:mm:ss"))
                    .dateTimeField("Stamped", f -> f.format("yyyy-MM-dd'T'HH:mm:ss"))
                    .dateFragmentField("CoverMonth", f -> f.fragmentFormat("yyyy-MM"))
                    .dateRangeField("CoverWindow", f -> f.format("yyyy-MM-dd").separator("/"))
                    .customField("Serial", f -> f.type("PolicySerial"))
                    .group("Broker", g -> g.stringField("BrokerName", f -> { }))
                    .group("Items", g -> g.repeatable(20).stringField("ItemCode", f -> { })))
            .build().toJson();

    private static String verdict(String condition) {
        return KernelLaws.verdictModel(POLICY, "/Policy", "../Reference",
                "FieldFilled(Reference) And " + condition);
    }

    /** What the LEFT operand must be, before the list is looked at at all. */
    @Test
    void whatTheSubjectFieldMustBe() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a string field — the control", verdict("FieldValueIncludedInValueList(Holder, \"x\")"));
        actual.put("a group", verdict("FieldValueIncludedInValueList(Broker, \"x\")"));
        actual.put("a starred field", verdict("FieldValueIncludedInValueList(Items*/ItemCode, \"x\")"));
        actual.put("a star on the field, not on its level",
                verdict("FieldValueIncludedInValueList(Items/ItemCode*, \"x\")"));
        actual.put("a star on a fixed group above the field",
                verdict("FieldValueIncludedInValueList(Broker*/BrokerName, \"x\")"));
        actual.put("the same without the star — the twin",
                verdict("FieldValueIncludedInValueList(Broker/BrokerName, \"x\")"));
        actual.put("a boolean field", verdict("FieldValueIncludedInValueList(Renewed, \"x\")"));
        actual.put("a confirm field", verdict("FieldValueIncludedInValueList(Accepted, \"x\")"));
        actual.put("a date field", verdict("FieldValueIncludedInValueList(IssuedOn, \"x\")"));
        actual.put("a time field", verdict("FieldValueIncludedInValueList(FiledAt, \"x\")"));
        actual.put("a date-time field", verdict("FieldValueIncludedInValueList(Stamped, \"x\")"));
        actual.put("a date-fragment field", verdict("FieldValueIncludedInValueList(CoverMonth, \"x\")"));
        actual.put("a date-range field", verdict("FieldValueIncludedInValueList(CoverWindow, \"x\")"));
        actual.put("a CUSTOM-typed field", verdict("FieldValueIncludedInValueList(Serial, \"x\")"));
        actual.put("a string field waiving value validation",
                verdict("FieldValueIncludedInValueList(FreeNote, \"x\")"));
        actual.put("the negated spelling, same subject gate",
                verdict("FieldValueNotIncludedInValueList(Renewed, \"x\")"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a string field — the control", KernelLaws.ADMITTED);
        expected.put("a group", "MVK_NO_GROUPS_ALLOWED");
        expected.put("a starred field", "MVK_NO_WILDCARDS_ALLOWED");
        // The resolver's wildcard gate answers before the subject is judged at all.
        expected.put("a star on the field, not on its level", "MVK_INVALID_WILDCARD");
        expected.put("a star on a fixed group above the field", "MVK_INVALID_WILDCARD");
        expected.put("the same without the star — the twin", KernelLaws.ADMITTED);
        expected.put("a boolean field", ONLY_THREE_KINDS);
        expected.put("a confirm field", ONLY_THREE_KINDS);
        expected.put("a date field", ONLY_THREE_KINDS);
        // The four temporal kinds are measured one by one rather than transferred from the date row: this
        // gate reads three POSITIVE predicates, so what it refuses is the complement, and a complement is
        // exactly what a checker may not infer.
        expected.put("a time field", ONLY_THREE_KINDS);
        expected.put("a date-time field", ONLY_THREE_KINDS);
        expected.put("a date-fragment field", ONLY_THREE_KINDS);
        expected.put("a date-range field", ONLY_THREE_KINDS);
        // `isLikeString` admits a PREDEFINED type as well as a string, so the custom declaration passes a
        // gate whose name lists neither.
        expected.put("a CUSTOM-typed field", KernelLaws.ADMITTED);
        expected.put("a string field waiving value validation", RAW_TYPE);
        expected.put("the negated spelling, same subject gate", ONLY_THREE_KINDS);
        assertEquals(expected, actual, "what the subject field must be");
    }

    /** The written-out list: its KIND must match the subject's, and only then is a domain consulted. */
    @Test
    void whatTheWrittenOutListMustBe() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a number field, numbers", verdict("FieldValueIncludedInValueList(Premium, 1, 2)"));
        actual.put("a number field, strings", verdict("FieldValueIncludedInValueList(Premium, \"1\")"));
        actual.put("a string field, numbers", verdict("FieldValueIncludedInValueList(Holder, 1)"));
        actual.put("an enumeration, numbers", verdict("FieldValueIncludedInValueList(Tier, 1)"));
        actual.put("a custom-typed field, numbers", verdict("FieldValueIncludedInValueList(Serial, 1)"));
        actual.put("a categorized enumeration, numbers",
                verdict("FieldValueIncludedInValueList(Tier -> Band, 1)"));
        actual.put("a categorized enumeration, a classification",
                verdict("FieldValueIncludedInValueList(Tier -> Band, \"LOW\")"));
        actual.put("a categorized enumeration, the enumeration's OWN value",
                verdict("FieldValueIncludedInValueList(Tier -> Band, \"BASIC\")"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a number field, numbers", KernelLaws.ADMITTED);
        expected.put("a number field, strings", TYPES_FOR_COMPARISON);
        expected.put("a string field, numbers", TYPES_FOR_COMPARISON);
        expected.put("an enumeration, numbers", TYPES_FOR_COMPARISON);
        expected.put("a custom-typed field, numbers", TYPES_FOR_COMPARISON);
        // The kind gate is asked ahead of the domain one, so a number list under a categorized enumeration
        // never reaches the category at all.
        expected.put("a categorized enumeration, numbers", TYPES_FOR_COMPARISON);
        expected.put("a categorized enumeration, a classification", KernelLaws.ADMITTED);
        // The access SWAPS the domain rather than widening it — the same asymmetry the quantifier carrier
        // shows, measured here on the scalar one.
        expected.put("a categorized enumeration, the enumeration's OWN value",
                "MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION_CATEGORY");
        assertEquals(expected, actual, "what the written-out list must be");
    }

    /** The other list form — {@code [field]} entries — and what the method and each entry's bracket check. */
    @Test
    void whatAListOfFieldEntriesMustBe() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("another string field", verdict("FieldValueIncludedInValueList(Holder, [Reference])"));
        actual.put("a number against a string", verdict("FieldValueIncludedInValueList(Holder, [Premium])"));
        actual.put("an enumeration against a string",
                verdict("FieldValueIncludedInValueList(Holder, [Tier])"));
        actual.put("a custom-typed field against a string",
                verdict("FieldValueIncludedInValueList(Holder, [Serial])"));
        actual.put("another enumeration against an enumeration",
                verdict("FieldValueIncludedInValueList(Tier, [Segment])"));
        actual.put("the SUBJECT itself in its own list",
                verdict("FieldValueIncludedInValueList(Holder, [Holder])"));
        actual.put("one entry written twice",
                verdict("FieldValueIncludedInValueList(Holder, [Reference], [Reference])"));
        actual.put("two entries disagreeing with each other",
                verdict("FieldValueIncludedInValueList(Holder, [Reference], [Premium])"));
        actual.put("a subject the head gate refuses", verdict("FieldValueIncludedInValueList(Renewed, [Tier])"));
        actual.put("an entry waiving value validation",
                verdict("FieldValueIncludedInValueList(Holder, [FreeNote])"));
        actual.put("an entry waiving value validation after one of another base type",
                verdict("FieldValueIncludedInValueList(Holder, [Premium], [FreeNote])"));
        actual.put("an entry waiving value validation after a duplicate",
                verdict("FieldValueIncludedInValueList(Holder, [Reference], [Reference], [FreeNote])"));
        actual.put("an entry waiving value validation under a subject the head gate refuses",
                verdict("FieldValueIncludedInValueList(Renewed, [FreeNote])"));
        actual.put("a group entry", verdict("FieldValueIncludedInValueList(Holder, [Broker])"));
        actual.put("a starred entry", verdict("FieldValueIncludedInValueList(Holder, [Items*/ItemCode])"));
        actual.put("a group entry under a subject the head gate refuses",
                verdict("FieldValueIncludedInValueList(Renewed, [Broker])"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("another string field", KernelLaws.ADMITTED);
        expected.put("a number against a string", VARYING);
        expected.put("an enumeration against a string", VARYING);
        // A CUSTOM declaration shares the string's BASE type, so it matches where an enumeration does not —
        // the same grouping `isLikeString` makes one gate earlier, here reached by a different predicate.
        expected.put("a custom-typed field against a string", KernelLaws.ADMITTED);
        expected.put("another enumeration against an enumeration", KernelLaws.ADMITTED);
        // The subject rides in the duplicate check together with the entries, so naming it twice is a
        // duplicate rather than a self-comparison A12 would admit.
        expected.put("the SUBJECT itself in its own list", "MVK_DUPLICATE_PARAM1");
        expected.put("one entry written twice", "MVK_DUPLICATE_PARAM1");
        expected.put("two entries disagreeing with each other", VARYING);
        // The head gate runs on this list form too, which is the row that makes the two forms one method.
        expected.put("a subject the head gate refuses", ONLY_THREE_KINDS);
        expected.put("an entry waiving value validation", RAW_TYPE);
        // Each entry is a field-value read, whose own gates run as its bracket closes — during the parse, so
        // ahead of every gate of the predicate, the subject's included (KF473).
        expected.put("an entry waiving value validation after one of another base type", RAW_TYPE);
        expected.put("an entry waiving value validation after a duplicate", RAW_TYPE);
        expected.put("an entry waiving value validation under a subject the head gate refuses", RAW_TYPE);
        expected.put("a group entry", "MVK_NO_GROUPS_ALLOWED");
        expected.put("a starred entry", "MVK_NO_WILDCARDS_ALLOWED");
        expected.put("a group entry under a subject the head gate refuses", "MVK_NO_GROUPS_ALLOWED");
        assertEquals(expected, actual, "what a list of field entries must be");
    }
}
