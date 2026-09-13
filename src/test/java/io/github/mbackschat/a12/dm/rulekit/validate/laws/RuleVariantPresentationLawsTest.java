package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

/**
 * A rule's {@code guiPatterns} and {@code variantErrorMessages} — the last two stored members no lock covered
 * and the portable checker never read.
 *
 * <p><b>They are measured together because they are one topic.</b> Both are maps the serializer omits when
 * empty, both are the variant family's PRESENTATION half rather than its behaviour, and both look from the
 * outside like constrained vocabularies that a checker ought to police. Measuring one at a time is how the
 * rounding pair nearly shipped a law that was false of its sibling.
 *
 * <p><b>{@code guiPatterns} carries a closed vocabulary in its own javadoc and A12 enforces none of it.</b>
 * {@code Rule.guiPatterns} documents "At the moment only following patterns are possible: key=
 * "dynamicEnumeration", value="true"|"false"" — which reads as exactly the kind of constraint a checker
 * should translate. It is not enforced anywhere: the only reader of {@code getGuiPatterns()} in the whole
 * kernel is {@code md.serializer … RuleSerializer}, which writes it back out. <i>Searched:</i>
 * {@code grep -rn 'getGuiPatterns' ../a12-kernel --include='*.java' | grep -v delombok} — six matches
 * (serializer ×2, the delegating {@code RuleWrapper} ×2, the {@code Rule} javadoc, the {@code IRule}
 * declaration), all six read. In particular the dynamic-enumeration codegen does NOT consult it: both
 * {@code CodeGenMetaRulesBuilder} and {@code NoMetaModelChecks.checkDynamicEnumerations} take their
 * information from {@code data.getParams().getDynamicEnumerationInformation()}, a codegen parameter, not from
 * the rule member. The grid below is what settles it, because a source read of who calls an accessor is a
 * reachability claim and this project does not ship those unmeasured.
 *
 * <p><b>So the finding is a NON-finding, and that is the useful half.</b> A checker that refused an
 * undocumented key — the obvious translation of that javadoc — would reject models A12 admits, in the one
 * direction it may not take. The rows naming an unknown key and an unknown value exist to state that
 * explicitly rather than leave it to be re-derived.
 *
 * <p>Own domain (the shared subscription fixture).
 */
class RuleVariantPresentationLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A well-formed {@code ILocalizedTextMap}, which serializes as an ARRAY of locale/text pairs. */
    private static final String TEXT_MAP = "[{\"locale\":\"en_US\",\"text\":\"premium wording\"}]";

    @Test
    void whatA12DemandsOfARulesGuiPatterns() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("absent — the control", verdict("guiPatterns", null));
        actual.put("an empty map", verdict("guiPatterns", "{}"));
        actual.put("the one documented pattern, true", verdict("guiPatterns", "{\"dynamicEnumeration\":\"true\"}"));
        actual.put("the one documented pattern, false", verdict("guiPatterns", "{\"dynamicEnumeration\":\"false\"}"));
        // The two rows a checker translating the javadoc would refuse.
        actual.put("the documented key, a value outside the documented two",
                verdict("guiPatterns", "{\"dynamicEnumeration\":\"maybe\"}"));
        actual.put("a key outside the documented one", verdict("guiPatterns", "{\"notAPattern\":\"true\"}"));
        // The shapes that ask about the BINDING rather than the vocabulary.
        actual.put("an explicit null", verdict("guiPatterns", "null"));
        actual.put("a JSON boolean where the value type is String",
                verdict("guiPatterns", "{\"dynamicEnumeration\":true}"));
        actual.put("a JSON array instead of a map", verdict("guiPatterns", "[]"));
        actual.put("a bare string instead of a map", verdict("guiPatterns", "\"dynamicEnumeration\""));
        // The rows that separate "the member must be an object" from "its VALUES must bind to String".
        actual.put("an object where the value type is String",
                verdict("guiPatterns", "{\"dynamicEnumeration\":{\"a\":\"b\"}}"));
        actual.put("an array where the value type is String", verdict("guiPatterns", "{\"dynamicEnumeration\":[1]}"));
        actual.put("a null value", verdict("guiPatterns", "{\"dynamicEnumeration\":null}"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("absent — the control", KernelLaws.ADMITTED);
        expected.put("an empty map", KernelLaws.ADMITTED);
        expected.put("the one documented pattern, true", KernelLaws.ADMITTED);
        expected.put("the one documented pattern, false", KernelLaws.ADMITTED);
        // Nothing in A12 reads the vocabulary, so neither of these is refused — the whole reason this
        // member gets a disposition rather than a check family.
        expected.put("the documented key, a value outside the documented two", KernelLaws.ADMITTED);
        expected.put("a key outside the documented one", KernelLaws.ADMITTED);
        expected.put("an explicit null", KernelLaws.UNREADABLE);
        expected.put("a JSON boolean where the value type is String", KernelLaws.ADMITTED);
        expected.put("a JSON array instead of a map", KernelLaws.UNREADABLE);
        expected.put("a bare string instead of a map", KernelLaws.UNREADABLE);
        // So the leniency has a shape: a scalar of any JSON type is coerced into the String value, and a
        // CONTAINER is refused. "Must be an object" is therefore not the whole law, and a checker that
        // stopped at the member's own type would miss both of these.
        expected.put("an object where the value type is String", KernelLaws.UNREADABLE);
        expected.put("an array where the value type is String", KernelLaws.UNREADABLE);
        // ADMITTED — and this is the row that does NOT transfer to the sibling member below, which is the
        // whole argument for measuring the two together. `Map<String, String>` takes a null value.
        expected.put("a null value", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "what A12 demands of a rule's guiPatterns");
    }

    @Test
    void whatA12DemandsOfARulesVariantErrorMessages() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("absent — the control", verdict("variantErrorMessages", null));
        actual.put("an empty map", verdict("variantErrorMessages", "{}"));
        actual.put("a message for a variant no filter names",
                verdict("variantErrorMessages", "{\"premium\":" + TEXT_MAP + "}"));
        actual.put("a message for the default variant, named by empty string",
                verdict("variantErrorMessages", "{\"\":" + TEXT_MAP + "}"));
        actual.put("an empty text map for a variant", verdict("variantErrorMessages", "{\"premium\":[]}"));
        // The shapes that ask about the BINDING rather than the vocabulary.
        actual.put("an explicit null", verdict("variantErrorMessages", "null"));
        actual.put("a plain string where a text map belongs",
                verdict("variantErrorMessages", "{\"premium\":\"premium wording\"}"));
        actual.put("a text entry with no locale",
                verdict("variantErrorMessages", "{\"premium\":[{\"text\":\"x\"}]}"));
        actual.put("a JSON array instead of a map", verdict("variantErrorMessages", "[]"));
        // The rows that say how far the container leniency reaches inside one variant's value.
        actual.put("a null value", verdict("variantErrorMessages", "{\"premium\":null}"));
        actual.put("a number where a text map belongs", verdict("variantErrorMessages", "{\"premium\":7}"));
        actual.put("a single entry object rather than an array of them",
                verdict("variantErrorMessages", "{\"premium\":{\"locale\":\"en_US\",\"text\":\"x\"}}"));
        actual.put("a text entry with no text", verdict("variantErrorMessages", "{\"premium\":[{\"locale\":\"en_US\"}]}"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("absent — the control", KernelLaws.ADMITTED);
        expected.put("an empty map", KernelLaws.ADMITTED);
        expected.put("a message for a variant no filter names", KernelLaws.ADMITTED);
        expected.put("a message for the default variant, named by empty string", KernelLaws.ADMITTED);
        expected.put("an empty text map for a variant", KernelLaws.ADMITTED);
        expected.put("an explicit null", KernelLaws.UNREADABLE);
        // ADMITTED, and it was predicted UNREADABLE. A bare string binds into the text-map slot where a
        // whole ARRAY of locale/text pairs is declared — the same scalar coercion the sibling grid's boolean
        // row shows, reaching one level deeper. What the reader makes of the string is a serialization
        // question this grid does not ask; what it settles is that A12 does not refuse the model.
        expected.put("a plain string where a text map belongs", KernelLaws.ADMITTED);
        // The array form is NOT coerced the same way: drop the locale from an entry and the reader refuses,
        // so the leniency is about the container's shape rather than about its contents.
        expected.put("a text entry with no locale", KernelLaws.UNREADABLE);
        expected.put("a JSON array instead of a map", KernelLaws.UNREADABLE);
        // The row the sibling member predicts wrongly, and it lands in a THIRD tier rather than either of
        // the two this grid otherwise uses: a null value here escapes every typed kernel channel, where the
        // identical shape under `guiPatterns` is simply ADMITTED. The difference is the value type —
        // `ILocalizedTextMap` is `@NonNull`-bound where `String` is not — so neither member's null row may
        // be read off the other, and a checker sharing one predicate between them would be wrong here.
        expected.put("a null value", KernelLaws.RAISED_UNTYPED);
        // A number coerces the same way the plain string does, so the container leniency is about ANY scalar
        // rather than about strings specifically.
        expected.put("a number where a text map belongs", KernelLaws.ADMITTED);
        // But an OBJECT does not: one well-formed entry is refused where an array holding that same entry is
        // admitted, so the array is required as the container even when it would hold one element.
        expected.put("a single entry object rather than an array of them", KernelLaws.UNREADABLE);
        // Both halves of an entry are required — the `locale` row above and this one together, so neither
        // alone could show it is the ENTRY shape rather than one of its keys.
        expected.put("a text entry with no text", KernelLaws.UNREADABLE);
        assertEquals(expected, actual, "what A12 demands of a rule's variantErrorMessages");
    }

    private static String verdict(String member, String rawJson) {
        return KernelLaws.verdictOf(probe(member, rawJson));
    }

    /**
     * The probe model with [member] set to [rawJson], or left absent when null.
     *
     * <p>Unlike its sibling helpers this one ADDS the member, because the serializer omits both of these when
     * empty and our writer never authors either. The assertion pins that: if the writer ever starts emitting
     * one, the "absent" control would silently stop being a control.
     */
    private static String probe(String member, String rawJson) {
        String written = ModelWriter.addRule(Fixtures.subscriptionJson(), "/Subscription", "PresentationProbe",
                "../Tier", "PROBE", "FieldNotFilled(Tier)", "ERROR", List.of(en("x"), de("y")));
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(written);
            ObjectNode rule = ruleBody(root);
            assertFalse(rule.has(member), () -> "the writer now emits `" + member + "`, so absent is no control");
            if (rawJson != null) {
                rule.set(member, MAPPER.readTree(rawJson));
            }
            return root.toString();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the writer's own output and the raw fragment must parse", unreadable);
        }
    }

    /** The probe rule's body, refusing rather than silently returning a model the substitution never reached. */
    private static ObjectNode ruleBody(ObjectNode root) {
        for (var element : root.path("content").path("modelRoot").path("rootGroups").get(0)
                .path("Group").path("elements")) {
            if ("PresentationProbe".equals(element.path("name").asText())) {
                return (ObjectNode) element.path("Rule");
            }
        }
        throw new IllegalStateException("the probe rule is no longer where this test writes it");
    }
}
