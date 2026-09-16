package io.github.mbackschat.a12.dm.gate

import io.github.mbackschat.a12.dm.consistency.ConsistencyReport
import io.github.mbackschat.a12.dm.consistency.Diagnostic as CheckerDiagnostic
import io.github.mbackschat.a12.dm.consistency.Finding
import io.github.mbackschat.a12.dm.consistency.FindingSeverity
import io.github.mbackschat.a12.dm.consistency.corrective
import io.github.mbackschat.a12.dm.core.Severity
import io.github.mbackschat.a12.dm.core.Verification
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic
import io.github.mbackschat.a12.dm.rulekit.validate.DiagnosticSource
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult
import java.util.Optional

/**
 * A completed run of the portable gate — the checker's own report, plus that report in dmkits shape.
 *
 * **Why the two halves are both published rather than one derived from the other.** [validation] is the shape
 * a dmkits caller already handles, and it answers the question `ok()` asks: did anything the checker ran
 * reject this model. [report] answers the question `ok()` CANNOT — how much ran at all. This checker reports
 * no VALID verdict, so an accepted model yields `ok() == true` beside a status of `Inconclusive`, and a
 * consumer that reads only the first has silently upgraded "nothing rejected it" into "it is valid". The
 * coverage evidence is on the report: `status`, `checksCompleted` and `gaps`.
 *
 * **The private constructor keeps the two halves describing the same run, and claims nothing more than that.**
 * It is deliberately NOT a provenance witness in `ConsistencyEvidence`'s sense: `ValidationResult.of(…, mode)`
 * is public, so a `PORTABLE_CHECKED` receipt is already mintable by anyone and closing this type would protect
 * nothing. Kotlin's `internal` is a compiler boundary rather than a JVM one anyway — the compiled factory name
 * is reachable by someone who goes looking, exactly as `dmkits-interpreter-bridge`'s golden records for its own
 * `internal` members. What the private constructor does buy is that [report] and [validation] can never
 * disagree, because the second is derived from the first at construction.
 */
class PortableGateRun private constructor(private val report: ConsistencyReport) {

    private val validation: ValidationResult = validationOf(report)

    /** The checker's own report — the verdict, the findings, and the coverage evidence beside them. */
    fun report(): ConsistencyReport = report

    /**
     * The same run as a dmkits [ValidationResult]: `ok()` false iff something rejected the model, the
     * checker's findings as `diagnostics()`, and a `PORTABLE_CHECKED` receipt.
     *
     * The receipt is always present, because reaching this type means the gate ran. A source the checker could
     * not read is still a gate that ran and said so — it is reported as a diagnostic, never as an absent
     * receipt, which is what `beforeTheGate` is for and what this path never is.
     */
    fun validation(): ValidationResult = validation

    internal companion object {
        internal fun of(report: ConsistencyReport) = PortableGateRun(report)

        /**
         * Map the report onto the dmkits shape, findings and source diagnostics alike.
         *
         * **Both lists have to travel.** A finding is what A12 says about a declaration; a source diagnostic
         * is what the checker could not read, and dropping those would present a model it never analysed as
         * one nothing was wrong with — a fabricated pass in the one forbidden direction. They arrive as ERROR
         * because an unread source is a failure to establish anything, which `ok()` must reflect.
         */
        private fun validationOf(report: ConsistencyReport): ValidationResult =
            ValidationResult.of(
                report.findings.map(::diagnosticOf) + report.diagnostics.map(::diagnosticOf),
                Verification.PORTABLE_CHECKED,
            )

        private fun diagnosticOf(finding: Finding): Diagnostic {
            val corrective = finding.corrective
            return Diagnostic(
                severityOf(finding.severity),
                DiagnosticSource.PORTABLE_CHECKER,
                Optional.of(finding.code),
                finding.explain,
                whereOf(finding.at),
                Optional.ofNullable(corrective?.fix),
                Optional.ofNullable(corrective?.meaning),
            )
        }

        private fun diagnosticOf(diagnostic: CheckerDiagnostic): Diagnostic = Diagnostic(
            Severity.ERROR,
            DiagnosticSource.PORTABLE_CHECKER,
            Optional.of(diagnostic.code.name),
            diagnostic.explain,
            whereOf(diagnostic.at ?: diagnostic.diagnosticLabel),
            Optional.empty(),
            Optional.empty(),
        )

        /**
         * A structure locus, which is the only family that fits: the checker names a DECLARED PATH, never a
         * rulekit `Rule` handle, and a `Where.rule` would claim a locus the caller can dereference into a
         * rulekit object. A finding with no path at all keeps an empty locus rather than borrowing one.
         */
        private fun whereOf(at: String?): Diagnostic.Where =
            at?.let { Diagnostic.Where.path(it) }
                ?: Diagnostic.Where(null, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())

        /**
         * The checker's severity in `dm.core`'s vocabulary. Mirrored rather than shared: `:consistency` keeps
         * no project dependency, so its [FindingSeverity] is A12's three levels restated, and this is the one
         * place the two meet.
         */
        private fun severityOf(severity: FindingSeverity): Severity = when (severity) {
            FindingSeverity.INFO -> Severity.INFO
            FindingSeverity.WARNING -> Severity.WARNING
            FindingSeverity.ERROR -> Severity.ERROR
        }
    }
}
