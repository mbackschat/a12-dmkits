package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Proves the release smoke distinguishes a native executable from a Java-dependent launcher. */
class NativeNoJavaSmokeTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    @Test
    void theNoJavaSmokeRejectsAJavaDependentLauncher() throws IOException, InterruptedException {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"),
                "POSIX-only shell discriminator; the real Windows artifact runs it under Git Bash");
        Path script = CHECKOUT.requireFile("scripts/native-no-java-smoke.sh");
        Process process = new ProcessBuilder("/bin/bash", script.toString(), "--self-test")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int code = process.waitFor();
        assertEquals(0, code, () -> "native no-Java discriminator did not fire correctly:\n" + output);
    }
}
