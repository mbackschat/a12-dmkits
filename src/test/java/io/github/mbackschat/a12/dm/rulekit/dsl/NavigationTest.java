package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.core.ModelLoader;
import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.edit.EditSession;

/**
 * The Level-1 navigation surface (study finding 9, §6c — jOOQ-{@code Meta}-style): streams of rich
 * info handles, matching via plain {@code Stream.filter} (no query language), and strict narrowing
 * back to typed authoring handles. Safe by F0's snapshot semantics. Level 2 (globs, axes) is
 * deliberately absent — trigger-gated; annotation <em>reads</em> ({@code annotations()}/{@code annotation(name)})
 * landed (finding 37) as the minimum the annotation-query trigger demanded — enough for annotation-filtered streams.
 */
class NavigationTest {

    private static BoundModel m;

    @BeforeAll
    static void bind() throws Exception {
        try (Reader reader = fixture("models/subscription.dm.json")) {
            m = BoundModel.of(ModelIndex.of(ModelLoader.loadSelfContained(reader)));
        }
    }

    @Test
    void streamsAllFieldsInModelOrder() {
        List<String> paths = m.fields().map(FieldInfo::path).toList();
        assertEquals(List.of(
                "/Subscription/Tier",
                "/Subscription/AutoRenew",
                "/Subscription/TermsAccepted",
                "/Subscription/Addons/Name",
                "/Subscription/Addons/MonthlyFee",
                "/Subscription/Billing/InvoiceEmail",
                "/Subscription/Billing/BaseFee",
                "/Subscription/Billing/EffectiveFee"), paths);
    }

    @Test
    void streamsAllGroupsIncludingRootGroups() {
        List<String> paths = m.groups().map(GroupInfo::path).toList();
        assertEquals(List.of("/Subscription", "/Subscription/Addons", "/Subscription/Billing"), paths);
        assertTrue(m.groups().filter(g -> g.path().endsWith("Addons")).findFirst().orElseThrow().repeating());
    }

    @Test
    void matchingIsPlainStreamFilter() {
        // "all money fields" — kind + scale, no matcher API:
        List<FieldInfo> money = m.fields()
                .filter(f -> f.kind() == ValueKind.NUMBER && f.scale() == 2)
                .toList();
        assertEquals(3, money.size());
        assertEquals("/Subscription/Addons/MonthlyFee", money.getFirst().path());
        assertEquals("MonthlyFee", money.getFirst().name());
    }

    @Test
    void scopesFieldsToAGroup() {
        List<String> addonFields = m.fields(m.group("/Subscription/Addons")).map(FieldInfo::path).toList();
        assertEquals(List.of("/Subscription/Addons/Name", "/Subscription/Addons/MonthlyFee"), addonFields);
    }

    @Test
    void fieldAtIsTheNonThrowingPeek() {
        assertTrue(m.fieldAt("/Subscription/Tier").isPresent());
        assertTrue(m.fieldAt("/Subscription/Nope").isEmpty());
        assertTrue(m.fieldAt("/Subscription/Addons").isEmpty(), "a group is not a field");
    }

    @Test
    void pathsThroughAFieldCannotResolve() {
        // Structural guarantee made executable: the index is built by walking groups only, and a
        // field contributes no children — so a path whose intermediate segment is a field (Tier)
        // can never exist, and every resolvable path has all-group intermediates.
        assertTrue(m.fieldAt("/Subscription/Tier/Nope").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> m.num("/Subscription/Tier/Nope"));
        assertThrows(IllegalArgumentException.class, () -> m.group("/Subscription/Tier/Nope"));
    }

    @Test
    void infoHandlesNarrowStrictlyToTypedAuthoringHandles() {
        FieldInfo fee = m.fieldAt("/Subscription/Addons/MonthlyFee").orElseThrow();
        NumberRef num = fee.asNum();                       // lenient exploring → strict authoring
        assertEquals(2, num.scale());
        assertEquals("/Subscription/Addons", fee.group().path());

        FieldInfo name = m.fieldAt("/Subscription/Addons/Name").orElseThrow();
        assertThrows(IllegalArgumentException.class, name::asNum, "narrowing keeps the authoring guarantees");
    }

    @Test
    void enablesTheRuleTemplatePipeline() {
        // The §3.4 shape, condition-level (the Rule object is F2a): one condition per money field.
        List<String> rendered = m.fields()
                .filter(f -> f.kind() == ValueKind.NUMBER && f.scale() == 2)
                .map(f -> f.asNum().filled().and(f.asNum().lt(0)))
                .map(c -> new DslRenderer().render(c))
                .toList();
        assertEquals(List.of(
                "FieldFilled(/Subscription/Addons/MonthlyFee) And [/Subscription/Addons/MonthlyFee] < 0",
                "FieldFilled(/Subscription/Billing/BaseFee) And [/Subscription/Billing/BaseFee] < 0",
                "FieldFilled(/Subscription/Billing/EffectiveFee) And [/Subscription/Billing/EffectiveFee] < 0"),
                rendered);
    }

    @Test
    void readsFieldAnnotationsAsTypedDescriptors() {
        // author element-level annotations, write back, re-load through the kernel — the read side
        // of the surface we could already only WRITE (EditSession.setAnnotation).
        String json = EditSession.read(Fixtures.subscriptionJson())
                .setAnnotation("/Subscription/Billing/BaseFee", "audited", "true")
                .setAnnotation("/Subscription/Billing/BaseFee", "owner", "billing-team")
                .toJson();
        BoundModel bound = BoundModel.ofJson(json);

        FieldInfo fee = bound.fieldAt("/Subscription/Billing/BaseFee").orElseThrow();
        assertEquals(List.of(
                new FieldInfo.Annotation("audited", "true"),
                new FieldInfo.Annotation("owner", "billing-team")), fee.annotations());

        // convenience lookup by name
        assertEquals("true", fee.annotation("audited").orElseThrow().value());
        assertTrue(fee.annotation("absent").isEmpty());

        // a field with no annotations → empty list, never null
        assertTrue(bound.fieldAt("/Subscription/Tier").orElseThrow().annotations().isEmpty());
    }

    @Test
    void enablesAnnotationQueryByPlainStreamFilter() {
        // the use case: find fields carrying an annotation — ordinary Stream.filter, no query API.
        String json = EditSession.read(Fixtures.subscriptionJson())
                .setAnnotation("/Subscription/Billing/BaseFee", "audited", "true")
                .setAnnotation("/Subscription/Addons/MonthlyFee", "audited", "yes")
                .toJson();
        BoundModel bound = BoundModel.ofJson(json);

        List<String> audited = bound.fields()
                .filter(f -> f.annotation("audited").isPresent())
                .map(FieldInfo::path)
                .toList();
        assertEquals(List.of("/Subscription/Addons/MonthlyFee", "/Subscription/Billing/BaseFee"), audited);
    }

    private static Reader fixture(String classpathLocation) {
        InputStream in = NavigationTest.class.getClassLoader().getResourceAsStream(classpathLocation);
        assertNotNull(in, "fixture not found: " + classpathLocation);
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }
}
