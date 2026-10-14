package io.github.mbackschat.a12.dm.cli;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * The evaluation instant {@code Today} and {@code Now} read, shared by {@code model eval}, {@code rule eval} and
 * {@code model compute}. Absent, it is the current instant: the kernel's {@code RuntimeController} samples a
 * calendar in the model's zone when {@code DocumentProcessingConfig.currentDateForTest} is unset. Pinned, it is
 * that slot. Either way it is an INSTANT, and the model's zone decides its day, which is why an offset-less
 * date-time is refused rather than guessed in some zone.
 */
@Command // a mixin must be a "command"
public final class EvalClockOption {

    /** The corrective for a malformed {@code --clock}. */
    public static final String FIX = "pass an ISO-8601 instant with a zone offset, e.g. --clock 2026-10-14T10:00:00Z "
            + "or --clock 2026-10-14T12:00:00+02:00; the model's timeZone then decides which day it is";

    @Option(names = "--clock", paramLabel = "<instant>",
            description = "pin the instant Today and Now read (the kernel's currentDateForTest): an ISO-8601 "
                    + "instant with a zone offset, e.g. 2026-10-14T10:00:00Z, read in the model's timeZone. "
                    + "Default: the current instant.")
    String clock;

    /** The evaluation instant — the current one when {@code --clock} is absent; empty when it is malformed. */
    public Optional<Instant> instant() {
        if (clock == null) {
            return Optional.of(Instant.now());
        }
        try {
            return Optional.of(Instant.parse(clock));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** The summary of the refusal a malformed {@code --clock} earns. */
    public String refusalSummary() {
        return "--clock must be an ISO-8601 instant with a zone offset, got: " + clock;
    }
}
