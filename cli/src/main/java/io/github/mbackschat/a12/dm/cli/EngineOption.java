package io.github.mbackschat.a12.dm.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.Option;
import picocli.CommandLine.TypeConversionException;

/**
 * {@code --engine} — which implementation a checking verb asks for its verdict (CLI-SPEC §3b).
 *
 * <p><b>Absent means kernel, and that is a request rather than a gap.</b> The default is the real A12 kernel,
 * unchanged from before this option existed, and {@link #selected()} resolves the absence to it so the verb
 * records a request on every path. That is what makes the receipt assertable on all three request shapes —
 * default, explicit {@code kernel}, explicit {@code portable} — instead of only on the explicit ones.
 *
 * <p><b>There is no {@code auto}, and none may be added.</b> Nothing about availability, discovery order,
 * coverage, completeness, or an exception may pick an engine: an INCONCLUSIVE portable answer is not an
 * invitation to consult the kernel, and a kernel failure never becomes a portable answer. A caller who wants
 * the other engine's opinion asks for it, in a second invocation they can see.
 */
@Command // a mixin must be a "command"; this one carries exactly one option
public final class EngineOption {

    @Option(names = "--engine", paramLabel = "<engine>", converter = EngineConverter.class,
            defaultValue = "kernel",
            description = "Which implementation checks the model. `kernel` (the default) is the real A12 kernel, "
                    + "the consistency authority. `portable` is the bundled kernel-free checker, which reports "
                    + "INVALID or INCONCLUSIVE and cannot report a model VALID at all yet, so a model it finds "
                    + "nothing wrong with exits 3 (ran, concluded nothing) rather than 0. The engine you select "
                    + "is the engine that answers: there is no fallback in either direction.")
    CheckEngine engine;

    /** The requested engine — the kernel when the option is absent, which is the default request. */
    public CheckEngine selected() {
        return engine == null ? CheckEngine.A12_KERNEL : engine;
    }

    /**
     * Converts through {@link CheckEngine#ofWireName(String)} so a wrong value is a picocli <em>usage</em>
     * error (exit 2) carrying the admitted spellings, rather than an exception escaping the verb and being
     * reported as a tool failure. The vocabulary belongs in the refusal: an agent that guessed
     * {@code --engine=a12} learns the two names from the message it gets.
     */
    static final class EngineConverter implements ITypeConverter<CheckEngine> {

        @Override
        public CheckEngine convert(String value) {
            try {
                return CheckEngine.ofWireName(value);
            } catch (IllegalArgumentException e) {
                throw new TypeConversionException(e.getMessage());
            }
        }
    }
}
