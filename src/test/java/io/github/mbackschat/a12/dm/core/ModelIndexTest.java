package io.github.mbackschat.a12.dm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * F0 foundation: rulekit binds to a fully-expanded, consistency-checked DocumentModel and indexes
 * its group tree / field types / repetition structure from the public model API alone (PROJECT-DESIGN §5a).
 *
 * <p>Fixture {@code subscription.dm.json}: non-repeatable {@code /Subscription} with an enum field
 * {@code Tier}; a repeatable subgroup {@code /Subscription/Addons} (repeatability 20) with a string
 * {@code Name} and a 2-decimal number {@code MonthlyFee}.
 */
class ModelIndexTest {

    private static ModelIndex index;

    @BeforeAll
    static void loadIndex() {
        index = ModelIndex.ofSelfContained(fixture("models/subscription.dm.json"));
    }

    private static String fixture(String classpathLocation) {
        try (InputStream in = ModelIndexTest.class.getClassLoader().getResourceAsStream(classpathLocation)) {
            assertNotNull(in, "test fixture not found on classpath: " + classpathLocation);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void classifiesFieldKindsFromTheModel() {
        assertEquals(ValueKind.ENUMERATION, index.kindOf("/Subscription/Tier"));
        assertEquals(ValueKind.STRING, index.kindOf("/Subscription/Addons/Name"));
        assertEquals(ValueKind.NUMBER, index.kindOf("/Subscription/Addons/MonthlyFee"));
    }

    @Test
    void readsNumberScaleFromTheModel() {
        assertEquals(2, index.numberScale("/Subscription/Addons/MonthlyFee"));
    }

    @Test
    void knowsWhichGroupsRepeat() {
        assertTrue(index.isRepeating("/Subscription/Addons"), "Addons has repeatability 20");
        assertFalse(index.isRepeating("/Subscription"), "Subscription has repeatability 1");
    }

    @Test
    void readsAGroupUsageTypeThroughTheTypedIndex() {
        var attachment = DocumentModelDraft.named("attachment").locale("en_US")
                .attachmentGroup("Evidence", body -> { })
                .build()
                .toJson();

        var attachmentIndex = ModelIndex.ofSelfContained(attachment);

        assertEquals("attachment", attachmentIndex.usageTypeOfGroup("/Evidence").orElseThrow());
        assertTrue(index.usageTypeOfGroup("/Subscription").isEmpty());
    }

    @Test
    void readsTheStringNoValueValidationDeclaration() {
        String model = DocumentModelDraft.named("claim").locale("en_US")
                .rootGroup("Claim", root -> root
                        .stringField("Raw", field -> field.lineBreaks().noValueValidation())
                        .stringField("Ordinary", field -> { })
                        .numberField("Amount", field -> { }))
                .build().toJson();
        ModelIndex claim = ModelIndex.ofSelfContained(model);

        assertTrue(claim.stringNoValueValidation("/Claim/Raw"));
        assertFalse(claim.stringNoValueValidation("/Claim/Ordinary"));
        assertFalse(claim.stringNoValueValidation("/Claim/Amount"));
    }

    @Test
    void failsFastOnUnknownPath() {
        assertThrows(IllegalArgumentException.class, () -> index.kindOf("/Subscription/Nope"));
    }

    @Test
    void failsFastWhenAskingScaleOfNonNumber() {
        assertThrows(IllegalArgumentException.class, () -> index.numberScale("/Subscription/Tier"));
    }
}
