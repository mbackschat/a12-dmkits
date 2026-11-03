package io.github.mbackschat.a12.dm.cli;

import java.io.PrintStream;

/**
 * Keeps one kernel thread's death off stderr, because the call that caused it already reports the failure.
 *
 * <p>Kernel 31.1.1's {@code SerializationUtils.copy} writes the model into a pipe on the calling thread while a
 * reader thread it starts deserializes the other end. The reader's {@code run} throws from exactly one place: its
 * {@code catch (JacksonException e)} arm, which first stores {@code e} and then rethrows it wrapped, uncaught. The
 * calling thread always reports that stored failure — through {@code getException()} after the join, through the
 * broken pipe's {@code IOException}, or, when it was itself already throwing and closing the pipe interrupted the
 * reader, through its own exception (kernel source, {@code SerializationUtils.java} at a12-kernel {@code eec9134}).
 * So the reader's trace would reach the user only as a raw stack trace after a well-formed envelope: for a model
 * the kernel reads and then cannot build (KERNEL-FINDINGS KF654), and for a required member the copy's own reader
 * finds absent.
 *
 * <p>The match is that arm and nothing wider: the top frame must be that reader's {@code run} and the direct
 * cause a {@code JacksonException}. Anything else the reader throws is never stored, so the caller cannot report
 * it, and it prints exactly as the JVM would, as does every other uncaught throwable.
 */
final class KernelCopyReaderTrace implements Thread.UncaughtExceptionHandler {

    private static final String COPY_READER =
            "com.mgmtp.a12.kernel.md.serializer.model.internal.service.serialization.SerializationUtils"
                    + "$PipedReaderExtended";
    private static final String JACKSON_FAILURE = "tools.jackson.core.JacksonException";

    private final Thread.UncaughtExceptionHandler fallback;
    private final PrintStream err;

    KernelCopyReaderTrace(Thread.UncaughtExceptionHandler fallback, PrintStream err) {
        this.fallback = fallback;
        this.err = err;
    }

    /** Installs the filter in front of whatever default handler the process already has. */
    static void install() {
        Thread.setDefaultUncaughtExceptionHandler(
                new KernelCopyReaderTrace(Thread.getDefaultUncaughtExceptionHandler(), System.err));
    }

    @Override
    public void uncaughtException(Thread thread, Throwable failure) {
        if (isReportedCopyReaderFailure(failure)) {
            return;
        }
        if (fallback != null) {
            fallback.uncaughtException(thread, failure);
            return;
        }
        // ThreadGroup's own default text; delegating to the group would call back into this handler.
        err.print("Exception in thread \"" + thread.getName() + "\" ");
        failure.printStackTrace(err);
    }

    static boolean isReportedCopyReaderFailure(Throwable failure) {
        StackTraceElement[] frames = failure.getStackTrace();
        if (frames.length == 0 || !COPY_READER.equals(frames[0].getClassName())
                || !"run".equals(frames[0].getMethodName())) {
            return false;
        }
        // By name: Jackson 3 reaches the CLI only through the kernel, never on its compile classpath.
        for (Class<?> type = failure.getCause() == null ? null : failure.getCause().getClass(); type != null;
                type = type.getSuperclass()) {
            if (JACKSON_FAILURE.equals(type.getName())) {
                return true;
            }
        }
        return false;
    }
}
