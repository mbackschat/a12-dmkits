package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Locks the release check that keeps the source-reading guardians from agreeing with a stale kernel tree. */
class KernelSourceCheckoutTest {

    @TempDir
    Path tree;

    @Test
    void aCheckoutOfTheRunningReleaseIsAccepted() throws IOException {
        Files.writeString(tree.resolve("package.json"), "{\n  \"name\": \"a12-kernel\",\n  \"version\": \"31.3.1\"\n}");
        assertDoesNotThrow(() -> KernelSourceCheckout.requireRelease(tree, "31.3.1"));
    }

    @Test
    void aCheckoutOfAnotherReleaseIsRefusedNamingBoth() throws IOException {
        Files.writeString(tree.resolve("package.json"), "{ \"version\": \"31.1.1\" }");
        AssertionError refusal = assertThrows(AssertionError.class,
                () -> KernelSourceCheckout.requireRelease(tree, "31.3.1"));
        assertTrue(refusal.getMessage().contains("release 31.1.1") && refusal.getMessage().contains("kernel 31.3.1"),
                refusal.getMessage());
    }

    /** A tree that states no release cannot be shown to match, so it is refused rather than trusted. */
    @Test
    void aCheckoutThatStatesNoReleaseIsRefused() {
        assertThrows(AssertionError.class, () -> KernelSourceCheckout.requireRelease(tree, "31.3.1"));
    }
}
