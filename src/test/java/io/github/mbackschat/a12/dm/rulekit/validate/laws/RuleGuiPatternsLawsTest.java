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
 * A rule's {@code guiPatterns}, a stored map the serializer omits when empty and the portable checker never
 * reads. Its sibling {@code variantErrorMessages} is retired on kernel 31.1.1 ({@code RetiredVariantMembersLawsTest}).
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
class RuleGuiPatternsLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        // `Map<String, String>` takes a null value.
        expected.put("a null value", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "what A12 demands of a rule's guiPatterns");
    }

    private static String verdict(String member, String rawJson) {
        return KernelLaws.verdictOf(probe(member, rawJson));
    }

    /**
     * The probe model with [member] set to [rawJson], or left absent when null.
     *
     * <p>Unlike its sibling helpers this one ADDS the member, because the serializer omits it when empty and our
     * writer never authors it. The assertion pins that: if the writer ever starts emitting
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
