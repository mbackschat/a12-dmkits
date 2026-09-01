package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Locks purity gates to compiled dependencies, where comment and literal delimiters cannot hide code. */
class PurityGuardMechanismTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    @Test
    void purityGatesDoNotApproximateEitherSourceLanguageWithCommentRegexes() throws IOException {
        for (String relative : new String[] {"build.gradle", "interpreter-bridge/build.gradle"}) {
            String build = Files.readString(CHECKOUT.resolve(relative));
            assertTrue(build.contains("verifyCompiledPurity"), relative);
            assertFalse(build.contains("replaceAll(/(?s)\\/\\*.*?\\*\\//"), relative);
        }
    }

    @Test
    void bridgePlantKeepsDelimiterTextNextToAForbiddenNameDecoy() throws IOException {
        Path plant = CHECKOUT.resolve(
                "interpreter-bridge/src/test/kotlin/io/github/mbackschat/a12/dm/bridge/PurityGuardPlant.kt");
        String source = Files.readString(plant);
        assertTrue(source.contains("/*"), "the regression plant must contain the delimiter that fooled the regex");
        assertTrue(source.contains("com.mgmtp.plant.Decoy"), "the plant must keep the forbidden-name decoy after it");
    }
}
