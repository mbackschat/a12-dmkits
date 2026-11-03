package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * The filter silences exactly the failure the kernel copy reader stores for its caller and prints everything else
 * as the JVM would. The packaged half, which shows the trace really reaches stderr, is
 * {@code MalformedModelPublicContractTest.packagedUnbuildableModelIsReportedWithoutAStrayKernelThreadTrace}.
 */
class KernelCopyReaderTraceTest {

    private static final String READER =
            "com.mgmtp.a12.kernel.md.serializer.model.internal.service.serialization.SerializationUtils"
                    + "$PipedReaderExtended";

    @Test
    void onlyTheReadersStoredJacksonFailureIsSilenced() throws Exception {
        assertTrue(KernelCopyReaderTrace.isReportedCopyReaderFailure(thrownBy(READER, jacksonFailure())));
        assertFalse(KernelCopyReaderTrace.isReportedCopyReaderFailure(thrownBy(READER, new IllegalStateException())),
                "a reader failure the kernel never stores is one the caller cannot report");
        assertFalse(KernelCopyReaderTrace.isReportedCopyReaderFailure(thrownBy("com.example.Worker", jacksonFailure())),
                "another thread keeps its trace");
    }

    @Test
    void everythingElsePrintsTheJvmsDefaultText() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var err = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        var handler = new KernelCopyReaderTrace(null, err);
        handler.uncaughtException(new Thread("worker"), thrownBy("com.example.Worker", new IllegalStateException()));
        assertTrue(bytes.toString(StandardCharsets.UTF_8).startsWith("Exception in thread \"worker\" "),
                bytes::toString);

        bytes.reset();
        handler.uncaughtException(new Thread("Thread-0"), thrownBy(READER, jacksonFailure()));
        assertEquals("", bytes.toString(StandardCharsets.UTF_8));
    }

    /** A real Jackson 3 failure from the kernel's runtime classpath, which the CLI does not compile against. */
    private static Throwable jacksonFailure() throws Exception {
        Class<?> mapper = Class.forName("tools.jackson.databind.ObjectMapper");
        try {
            mapper.getMethod("readTree", String.class).invoke(mapper.getConstructor().newInstance(), "{");
        } catch (InvocationTargetException thrown) {
            return thrown.getCause();
        }
        throw new AssertionError("an unterminated object must fail to parse");
    }

    private static RuntimeException thrownBy(String owner, Throwable cause) {
        var failure = new RuntimeException("The given document model could not be deserialized.", cause);
        failure.setStackTrace(new StackTraceElement[] {new StackTraceElement(owner, "run", "X.java", 1)});
        return failure;
    }
}
