package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * <b>A MECHANISM claim must carry its basis</b> (CLAUDE.md "Before you STATE a finding: match the oracle to the
 * claim kind"). This guards the single most expensive class of defect the 2026-07-25 kernel exchange produced:
 * four wrong <em>mechanisms</em> shipped while every <em>measurement</em> was right, because a causal story
 * borrowed the confidence of the observation it explained. A wrong hypothesis that is labelled costs nothing; a
 * wrong mechanism dressed as a finding costs a full cross-project round-trip — twice, it did.
 *
 * <p><b>The rule.</b> A paragraph that asserts a mechanism <em>against a rival</em> — "the cause is X, not Y",
 * "not because …", "the reason is …" — must also name how it was established: a basis token from the closed
 * vocabulary below, or an explicit hypothesis/prediction marker that withdraws the claim from actionable status.
 * The contrast form is the target on purpose: it is where a causal story is doing real work, it is rare in prose
 * (4 paragraphs across both docs when this guard landed), and it is exactly the shape that bit twice.
 *
 * <p><b>What this deliberately does NOT guard.</b> A digit-count claim citing the glossary ("the seven digit
 * gates"): measured at 11 of 12 paragraphs failing, nearly all of them legitimate prose about unrelated digit
 * concepts (the scale-19 rescale, the 50-digit arithmetic cap, integer-digit serialization). A guard that fails
 * honest content gets disabled, which is worse than no guard — so the glossary stays a convention, not a check.
 *
 * <p>Runs in the dedicated {@code semanticsMapGuardTest} task, whose input set already declares exactly these two
 * docs, so a doc edit re-runs only this lane and can never restore from the build cache into a false green.
 */
class KernelDocsBasisContractTest {

    private static final List<Path> DOCS = List.of(
            Path.of("../docs/KERNEL-FINDINGS.md"), Path.of("../docs/KERNEL-SEMANTICS.md"));

    /** A mechanism asserted against a rival — where a causal story is load-bearing rather than incidental. */
    private static final Pattern CAUSAL_CONTRAST = Pattern.compile(
            "is not the reason|and not because|not because|rather than because|the cause is|the mechanism is",
            Pattern.CASE_INSENSITIVE);

    /**
     * How a claim may be established. Either an ORACLE was run (the first group) or the claim is explicitly
     * withdrawn from actionable status (the second) — a labelled hypothesis generates no churn even when wrong.
     */
    private static final Pattern BASIS = Pattern.compile(
            "measured|probed|probe|differential|source-read|source-verified|source \\+|codegen-verified"
                    + "|kernel-verified|locked|N7|experiment|fuzz|tri-check|tri-lock"
                    + "|hypothesis|prediction|unverified|not probe-locked",
            Pattern.CASE_INSENSITIVE);

    @Test
    void everyMechanismClaimNamesHowItWasEstablished() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path doc : DOCS) {
            for (String para : Files.readString(doc).split("\n\n")) {
                if (CAUSAL_CONTRAST.matcher(para).find() && !BASIS.matcher(para).find()) {
                    offenders.add(doc.getFileName() + ": " + oneLine(para));
                }
            }
        }
        assertTrue(offenders.isEmpty(), () -> """
                A mechanism claim is stated without naming how it was established.

                A causal claim made AGAINST a rival ("the cause is X, not Y") is not established by the
                observation it explains — that is what made this the costliest defect class of the 2026-07-25
                exchange. Either:
                  (a) name the oracle that settled it — the discriminating case a rival mechanism gets wrong
                      (measured / probed / differential / source-verified / locked / N7 / …), or
                  (b) mark it a hypothesis or prediction, which withdraws it from actionable status.

                Offending paragraph(s):
                """ + String.join("\n\n", offenders));
    }

    private static String oneLine(String para) {
        String flat = para.replaceAll("\\s+", " ").trim();
        return flat.length() > 220 ? flat.substring(0, 220) + " …" : flat;
    }
}
