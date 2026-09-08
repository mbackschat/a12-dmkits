package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Locks purity gates to compiled dependencies, where comment and literal delimiters cannot hide code.
 *
 * <p>What this class can and cannot see, stated because a build-script text match is the weakest anchor in
 * the estate. It reads the build scripts, so it answers only <em>which mechanism is wired</em> — a task type
 * that reads class files rather than source text. It cannot see whether the task is reachable from any lane,
 * and it cannot see whether the task's verdicts are right. Those rest elsewhere: the verdicts are unit-tested
 * beside the code in {@code build-logic/src/test/.../CompiledPurityTest.kt} and proved to fire against a real
 * Gradle build in {@code BuildGuardsFunctionalTest.kt}; reachability rests on CI naming
 * {@code :verifyKitPurity} in its own invocation, so deleting that task fails the run outright. The bridge
 * gate has no equivalent — it hangs off {@code :interpreter-bridge:check}, and deleting it would make that
 * lane quietly stop checking.
 */
class PurityGuardMechanismTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    @Test
    void purityGatesDoNotApproximateEitherSourceLanguageWithCommentRegexes() throws IOException {
        for (String relative : new String[] {"build.gradle", "interpreter-bridge/build.gradle"}) {
            String build = Files.readString(CHECKOUT.resolve(relative));
            assertTrue(build.contains("VerifyCompiledPurityTask"), relative
                    + " must register the compiled-dependency task type; a purity gate that reads source "
                    + "text cannot model Java text blocks, Kotlin raw strings, or Kotlin's nested comments");
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
