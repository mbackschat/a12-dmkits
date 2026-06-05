package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Locale-form regression (KERNEL-FINDINGS §13 supplement — <b>corrected</b> 2026-06-05). A bare,
 * language-only locale ({@code "en"}) — the <em>common</em> form in the A12 Tools workspaces — is a
 * valid Java locale and <b>deserializes</b> fine, but the kernel's <b>validation mode</b>
 * ({@code checkConsistency}, which {@link BoundModel#ofJson} runs) <b>rejects</b> it:
 * {@code IllegalArgumentException "… Sprache en wird nicht unterstützt"} ("language en is not
 * supported in validation mode"). The full form ({@code en_US}) is supported. So our validate/author
 * path requires a full locale code; a bare-locale model must have its locale normalized first.
 *
 * <p>This contradicts an earlier note ("rulekit reads codes verbatim, so bare-en just works") — that
 * held only for the deserialize tier, not validation. Locked here so the limitation can't silently
 * regress (and as the trigger for any future locale-normalization fix). Fixtures: {@code order-en.dm.json}
 * (bare {@code en}) vs {@code order.dm.json} (full {@code en_US}).
 */
class BareLocaleTest {

    @Test
    void aBareLanguageOnlyLocaleIsRejectedByValidation() {
        String bareEn = read("models/order-en.dm.json");   // header locales: [{"code":"en"}]
        // parses fine, but checkConsistency (run by ofJson) rejects the bare validation language
        assertThrows(RuntimeException.class, () -> BoundModel.ofJson(bareEn),
                "a bare-en model must be rejected by the kernel's validation mode (use the full en_US form)");
    }

    @Test
    void theFullLanguageCountryLocaleValidates() {
        String fullEnUs = read("models/order.dm.json");     // header locales: [{"code":"en_US"}]
        assertNotNull(BoundModel.ofJson(fullEnUs), "the full en_US form is validation-supported");
    }

    private static String read(String resource) {
        try (InputStream in = BareLocaleTest.class.getClassLoader().getResourceAsStream(resource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
