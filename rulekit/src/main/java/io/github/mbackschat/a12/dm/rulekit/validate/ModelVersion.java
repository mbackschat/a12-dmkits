package io.github.mbackschat.a12.dm.rulekit.validate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.mbackschat.a12.dm.core.Severity;

import io.github.mbackschat.a12.dm.core.ModelFormat;

/**
 * Document-model-version awareness (the model-format peer of {@link KernelVersion}). The <em>kernel is the
 * authority</em> on whether a loaded model's {@code header.modelVersion} is compatible — it accepts an
 * older same-major format and rejects a newer or different-major one with a {@code "Version mismatch …"}
 * ERROR (probed + locked in {@code KERNEL-FINDINGS}). rulekit only <b>classifies</b> that verdict so it is
 * machine-detectable and explicit:
 *
 * <ul>
 *   <li>the kernel's un-coded version-mismatch ERROR is <b>enriched</b> with the stable code
 *       {@code RK_MODEL_VERSION_INCOMPATIBLE} + an actionable fix — the <i>fail-fast</i> case;</li>
 *   <li>when the model loads but its version differs from {@link ModelFormat#VERSION}, a single
 *       INFO {@code RK_MODEL_VERSION_SKEW} is added — the <i>tolerant-but-explicit</i> case.</li>
 * </ul>
 *
 * <p>This never changes the {@code valid} verdict (the kernel already did); it only annotates, so an agent
 * can branch on a stable code instead of scraping a free-text summary. It also never bumps the version —
 * write-back preserves {@code header.modelVersion} verbatim (no implicit bump — locked by
 * {@code cli.ModelVersionPolicyTest}).
 */
public final class ModelVersion {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            // DM-JSON is parsed the way A12 parses it (`ObjectMapperProvider.createPreconfiguredObjectMapper`): a
            // number keeps the authored literal's exact scale, and a comment is legal. A DEFAULT mapper builds a
            // DoubleNode (0.50 -> 0.5) and refuses a comment outright. API-FINDINGS 103 / IF228.
            .enable(JsonParser.Feature.ALLOW_COMMENTS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    /** The model-format version this build authors/expects (the comparison reference). */
    public static final String REFERENCE = ModelFormat.VERSION;

    private ModelVersion() {
    }

    /** The {@code header.modelVersion} of a DM-JSON model, or empty when unreadable. */
    public static Optional<String> declared(String modelJson) {
        try {
            JsonNode v = MAPPER.readTree(modelJson).path("header").path("modelVersion");
            return v.isTextual() && !v.asText().isBlank() ? Optional.of(v.asText()) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** True when {@code summary} is the kernel's model-version-mismatch notification (locked in KERNEL-FINDINGS). */
    public static boolean isVersionMismatch(String summary) {
        return summary != null && summary.contains("Version mismatch")
                && summary.contains("Document model version");
    }

    /**
     * Classify version concerns over the kernel's diagnostics for a model: enrich a version-mismatch ERROR
     * with {@code RK_MODEL_VERSION_INCOMPATIBLE} + a fix, and (when the model's version differs from
     * {@link #REFERENCE} but the kernel did <em>not</em> flag a mismatch) append a tolerant INFO
     * {@code RK_MODEL_VERSION_SKEW}. Returns a new list; the input is unchanged.
     */
    public static List<Diagnostic> classify(String modelJson, List<Diagnostic> kernelDiagnostics) {
        String declared = declared(modelJson).orElse(null);
        List<Diagnostic> out = new ArrayList<>(kernelDiagnostics.size() + 1);
        boolean flaggedIncompatible = false;
        for (Diagnostic d : kernelDiagnostics) {
            if (d.code().isEmpty() && isVersionMismatch(d.summary())) {
                out.add(new Diagnostic(d.severity(), d.source(),
                        Optional.of("RK_MODEL_VERSION_INCOMPATIBLE"), d.summary(), d.where(),
                        Optional.of("the kernel implements model-format " + REFERENCE + " and cannot load a"
                                + " newer or different-major model — re-author the model against " + REFERENCE
                                + ", or run with a kernel that implements the model's version"),
                        d.explain()));
                flaggedIncompatible = true;
            } else {
                out.add(d);
            }
        }
        if (!flaggedIncompatible && declared != null && !declared.equals(REFERENCE)) {
            out.add(new Diagnostic(Severity.INFO, DiagnosticSource.ENVIRONMENT,
                    Optional.of("RK_MODEL_VERSION_SKEW"),
                    "the model declares version " + declared + " but this build references " + REFERENCE
                            + " — the kernel accepts it (older same-major formats are backward-compatible),"
                            + " so this is informational; write-back preserves " + declared + " unchanged",
                    new Diagnostic.Where("(model)", Optional.empty()),
                    Optional.empty(),
                    Optional.empty()));
        }
        return out;
    }
}
