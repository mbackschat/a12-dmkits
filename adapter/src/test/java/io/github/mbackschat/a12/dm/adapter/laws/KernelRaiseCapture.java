package io.github.mbackschat.a12.dm.adapter.laws;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.NOPLogger;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

/**
 * The adapter test classpath's SLF4J provider, which hands the probe every exception A12 caught while evaluating a
 * rule and reported as that rule's VALUE message (a12-kernel-lean's instrument request, 2026-10-24 note 02).
 *
 * <p><b>Why logging is the only route.</b> The generated rule body catches any exception its condition raises and
 * calls {@code MainValidatorController.addValidatorMessage(field, idx, code, e)}, which publishes a message whose
 * text is a fixed prefix plus {@code e.getMessage()} and then calls {@code ValidationLogger.error(text, e)}
 * ({@code CodeGenRuleDefinition.st}, both the {@code java} and {@code groovy} dialects; kernel 30.8.1). The message
 * keeps neither the exception's class nor its cause, so the log call is the one place the throwable leaves the
 * kernel. It is the only Throwable-carrying error call in {@code kernel-core-runtime}'s main source.
 *
 * <p>Every other logger, and this one outside {@link #open()}/{@link #close()}, behaves as SLF4J's own no-provider
 * fallback did before this class existed, so no other test observes a difference. Capture is per thread because
 * validation runs on the caller's thread and an adapter test JVM may validate on several at once.
 */
public final class KernelRaiseCapture implements SLF4JServiceProvider {

    /** The logger A12's {@code ValidationLogger} obtains through {@code LoggerFactory.getLogger(Class)}. */
    static final String VALIDATION_LOGGER =
            "com.mgmtp.a12.kernel.core.rt._30_8.internal.logging.ValidationLogger";

    private static final ThreadLocal<List<Throwable>> SINK = new ThreadLocal<>();

    private final ILoggerFactory loggerFactory = new Factory();
    private final IMarkerFactory markerFactory = new BasicMarkerFactory();
    private final MDCAdapter mdcAdapter = new NOPMDCAdapter();

    /**
     * Refuse to observe when SLF4J bound some other provider: absent raises would then read as "A12 caught
     * none" rather than "nobody listened", the misreading the probe's absent-versus-empty rule exists to prevent.
     */
    static void requireInstalled() {
        if (!(LoggerFactory.getILoggerFactory() instanceof Factory)) {
            throw new IllegalStateException("kernel-probe needs " + KernelRaiseCapture.class.getName()
                    + " as the SLF4J provider to see the exception behind a raised rule, but SLF4J bound "
                    + LoggerFactory.getILoggerFactory().getClass().getName()
                    + "; remove the other provider from the adapter test classpath");
        }
    }

    /** Start collecting this thread's rule raises, discarding any left by an unclosed earlier window. */
    static void open() {
        SINK.set(new ArrayList<>());
    }

    /** Stop collecting and return what was caught since {@link #open()}, in the order A12 logged it. */
    static List<Throwable> close() {
        List<Throwable> caught = SINK.get();
        SINK.remove();
        return caught == null ? List.of() : List.copyOf(caught);
    }

    @Override
    public ILoggerFactory getLoggerFactory() {
        return loggerFactory;
    }

    @Override
    public IMarkerFactory getMarkerFactory() {
        return markerFactory;
    }

    @Override
    public MDCAdapter getMDCAdapter() {
        return mdcAdapter;
    }

    @Override
    public String getRequestedApiVersion() {
        return "2.0.99";
    }

    @Override
    public void initialize() {
    }

    private static final class Factory implements ILoggerFactory {

        @Override
        public org.slf4j.Logger getLogger(String name) {
            return VALIDATION_LOGGER.equals(name) ? new RaiseLogger() : NOPLogger.NOP_LOGGER;
        }
    }

    /** Collects the throwable of each error call while a window is open; drops everything else. */
    private static final class RaiseLogger extends LegacyAbstractLogger {

        private static final long serialVersionUID = 1L;

        RaiseLogger() {
            this.name = VALIDATION_LOGGER;
        }

        @Override
        protected String getFullyQualifiedCallerName() {
            return null;
        }

        @Override
        protected void handleNormalizedLoggingCall(Level level, Marker marker, String messagePattern,
                Object[] arguments, Throwable throwable) {
            List<Throwable> sink = SINK.get();
            // ValidationLogger.error also logs the rendered stack trace as a second, throwable-free call.
            if (sink != null && throwable != null) {
                sink.add(throwable);
            }
        }

        @Override
        public boolean isTraceEnabled() {
            return false;
        }

        @Override
        public boolean isDebugEnabled() {
            return false;
        }

        @Override
        public boolean isInfoEnabled() {
            return false;
        }

        @Override
        public boolean isWarnEnabled() {
            return false;
        }

        @Override
        public boolean isErrorEnabled() {
            return SINK.get() != null;
        }
    }
}
