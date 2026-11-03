package io.github.mbackschat.a12.dm.adapter.laws;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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
 * ({@code CodeGenRuleDefinition.st}, both the {@code java} and {@code groovy} dialects; kernel 30.8.1 and 31.1.1). The message
 * keeps neither the exception's class nor its cause, so the log call is the one place the throwable leaves the
 * kernel. It is the only Throwable-carrying error call in {@code kernel-core-runtime}'s main source.
 *
 * <p>Every other logger, and this one outside {@link #open()}/{@link #close()}, behaves as SLF4J's own no-provider
 * fallback did before this class existed, so no other test observes a difference. Capture is per thread because
 * validation runs on the caller's thread and an adapter test JVM may validate on several at once.
 */
public final class KernelRaiseCapture implements SLF4JServiceProvider {

    /**
     * The logger A12's {@code ValidationLogger} obtains through {@code LoggerFactory.getLogger(Class)}, whatever
     * runtime release it sits in. The runtime's package embeds the kernel's major.minor ({@code rt._31_1} on kernel
     * 31.1.1, {@code rt._30_8} before it), and a name pinned to one release silently captured nothing after the bump
     * ({@code KernelProbeCustomConditionTest#anUndeclaredNameStaysUnregisteredBesideADeclaredOne} found it).
     */
    static final Pattern VALIDATION_LOGGER =
            Pattern.compile("com\\.mgmtp\\.a12\\.kernel\\.core\\.rt\\._\\d+_\\d+\\.internal\\.logging\\.ValidationLogger");

    private static final ThreadLocal<List<Throwable>> SINK = new ThreadLocal<>();

    private final ILoggerFactory loggerFactory = new Factory();
    private final IMarkerFactory markerFactory = new BasicMarkerFactory();
    private final MDCAdapter mdcAdapter = new NOPMDCAdapter();

    /**
     * Refuse to observe when nobody would listen: when SLF4J bound some other provider, or when no class on the
     * classpath carries the logger name {@link #VALIDATION_LOGGER} matches. Either way absent raises would read as "A12
     * caught none" rather than "nobody listened", the misreading the probe's absent-versus-empty rule exists to
     * prevent. The second arm is the one the kernel 31.1.1 bump got past, with the provider bound and the name stale.
     */
    static void requireInstalled() {
        if (!(LoggerFactory.getILoggerFactory() instanceof Factory)) {
            throw new IllegalStateException("kernel-probe needs " + KernelRaiseCapture.class.getName()
                    + " as the SLF4J provider to see the exception behind a raised rule, but SLF4J bound "
                    + LoggerFactory.getILoggerFactory().getClass().getName()
                    + "; remove the other provider from the adapter test classpath");
        }
        if (!LOGGER_CLASS_PRESENT) {
            throw new IllegalStateException("kernel-probe listens for A12's rule raises on a logger matching "
                    + VALIDATION_LOGGER.pattern() + ", but no class on the classpath has that name; the runtime moved, "
                    + "so update the pattern before trusting an absent raise");
        }
    }

    /** Whether some classpath jar holds the class {@link #VALIDATION_LOGGER} names, scanned once. */
    private static final boolean LOGGER_CLASS_PRESENT = loggerClassPresent();

    private static boolean loggerClassPresent() {
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (!entry.endsWith(".jar")) {
                continue;
            }
            try (ZipFile jar = new ZipFile(entry)) {
                boolean found = jar.stream().map(ZipEntry::getName)
                        .filter(name -> name.endsWith(".class"))
                        .map(name -> name.substring(0, name.length() - ".class".length()).replace('/', '.'))
                        .anyMatch(name -> VALIDATION_LOGGER.matcher(name).matches());
                if (found) {
                    return true;
                }
            } catch (IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
        }
        return false;
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
            return VALIDATION_LOGGER.matcher(name).matches() ? new RaiseLogger(name) : NOPLogger.NOP_LOGGER;
        }
    }

    /** Collects the throwable of each error call while a window is open; drops everything else. */
    private static final class RaiseLogger extends LegacyAbstractLogger {

        private static final long serialVersionUID = 1L;

        RaiseLogger(String name) {
            this.name = name;
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
