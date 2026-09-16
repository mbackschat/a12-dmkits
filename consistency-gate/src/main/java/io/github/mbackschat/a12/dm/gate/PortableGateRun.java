package io.github.mbackschat.a12.dm.gate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.mbackschat.a12.dm.consistency.ConsistencyReport;
import io.github.mbackschat.a12.dm.consistency.Corrective;
import io.github.mbackschat.a12.dm.consistency.Diagnostic;
import io.github.mbackschat.a12.dm.consistency.Finding;
import io.github.mbackschat.a12.dm.consistency.FindingSeverity;
import io.github.mbackschat.a12.dm.consistency.FindingsKt;
import io.github.mbackschat.a12.dm.core.Severity;
import io.github.mbackschat.a12.dm.core.Verification;
import io.github.mbackschat.a12.dm.rulekit.validate.DiagnosticSource;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * A completed run of the portable gate — the checker's own report, plus that report in dmkits shape.
 *
 * <p><b>Why the two halves are both published rather than one derived from the other.</b>
 * {@link #validation()} is the shape a dmkits caller already handles, and it answers the question
 * {@code ok()} asks: did anything the checker ran reject this model. {@link #report()} answers the question
 * {@code ok()} CANNOT — how much ran at all. This checker reports no VALID verdict, so an accepted model
 * yields {@code ok() == true} beside a status of {@code Inconclusive}, and a consumer that reads only the
 * first has silently upgraded "nothing rejected it" into "it is valid". The coverage evidence is on the
 * report: {@code status}, {@code checksCompleted} and {@code gaps}.
 *
 * <p><b>Only {@link PortableConsistencyGate} can produce one.</b> The constructor is private and the factory
 * is package-private, for the reason {@code WitnessTypesAreUnforgeableTest} names: this type's existence is
 * the proof that the gate ran, and a publicly constructible witness inverts that. A {@code ConsistencyReport}
 * is a public Kotlin data class by design — consumers read and rebuild reports — so holding one proves
 * nothing about who produced it.
 */
public final class PortableGateRun {

    private final ConsistencyReport report;
    private final ValidationResult validation;

    private PortableGateRun(ConsistencyReport report, ValidationResult validation) {
        this.report = report;
        this.validation = validation;
    }

    static PortableGateRun of(ConsistencyReport report) {
        return new PortableGateRun(report, validationOf(report));
    }

    /** The checker's own report — the verdict, the findings, and the coverage evidence beside them. */
    public ConsistencyReport report() {
        return report;
    }

    /**
     * The same run as a dmkits {@link ValidationResult}: {@code ok()} false iff something rejected the model,
     * the checker's findings as {@code diagnostics()}, and a {@code PORTABLE_CHECKED} receipt.
     *
     * <p>The receipt is always present, because reaching this type means the gate ran. A source the checker
     * could not read is still a gate that ran and said so — it is reported as a diagnostic, never as an
     * absent receipt, which is what {@code beforeTheGate} is for and what this path never is.
     */
    public ValidationResult validation() {
        return validation;
    }

    /**
     * Map the report onto the dmkits shape, findings and source diagnostics alike.
     *
     * <p><b>Both lists have to travel.</b> A finding is what A12 says about a declaration; a source diagnostic
     * is what the checker could not read, and dropping those would present a model it never analysed as one
     * nothing was wrong with — a fabricated pass in the one forbidden direction. They arrive as ERROR because
     * an unread source is a failure to establish anything, which {@code ok()} must reflect.
     */
    private static ValidationResult validationOf(ConsistencyReport report) {
        List<io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic> diagnostics = new ArrayList<>();
        for (Finding finding : report.getFindings()) {
            diagnostics.add(diagnosticOf(finding));
        }
        for (Diagnostic diagnostic : report.getDiagnostics()) {
            diagnostics.add(diagnosticOf(diagnostic));
        }
        return ValidationResult.of(diagnostics, Verification.PORTABLE_CHECKED);
    }

    private static io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic diagnosticOf(Finding finding) {
        Corrective corrective = FindingsKt.getCorrective(finding);
        return new io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic(
                severityOf(finding.getSeverity()),
                DiagnosticSource.PORTABLE_CHECKER,
                Optional.of(finding.getCode()),
                finding.getExplain(),
                whereOf(finding.getAt()),
                Optional.ofNullable(corrective).map(Corrective::getFix),
                Optional.ofNullable(corrective).map(Corrective::getMeaning));
    }

    private static io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic diagnosticOf(Diagnostic diagnostic) {
        return new io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic(
                Severity.ERROR,
                DiagnosticSource.PORTABLE_CHECKER,
                Optional.of(diagnostic.getCode().name()),
                diagnostic.getExplain(),
                whereOf(diagnostic.getAt() != null ? diagnostic.getAt() : diagnostic.getDiagnosticLabel()),
                Optional.empty(),
                Optional.empty());
    }

    /**
     * A structure locus, which is the only family that fits: the checker names a DECLARED PATH, never a
     * rulekit {@code Rule} handle, and a {@code Where.rule} would claim a locus the caller can dereference
     * into a rulekit object. A finding with no path at all keeps an empty locus rather than borrowing one.
     */
    private static io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic.Where whereOf(String at) {
        return at == null
                ? new io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic.Where(
                        null, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())
                : io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic.Where.path(at);
    }

    /**
     * The checker's severity in {@code dm.core}'s vocabulary. Mirrored rather than shared: {@code :consistency}
     * keeps no project dependency, so its {@code FindingSeverity} is A12's three levels restated, and this is
     * the one place the two meet.
     */
    private static Severity severityOf(FindingSeverity severity) {
        return switch (severity) {
            case INFO -> Severity.INFO;
            case WARNING -> Severity.WARNING;
            case ERROR -> Severity.ERROR;
        };
    }
}
