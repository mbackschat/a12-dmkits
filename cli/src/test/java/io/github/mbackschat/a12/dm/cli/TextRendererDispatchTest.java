package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link TextRenderer} dispatches the {@code --text} {@code data} body on the envelope's
 * {@code (target, op)}: a registered verb ({@code model diff}/{@code model report}) renders its bespoke
 * compact view, and every other verb falls back to the generic indented tree (LEDGER MR5). Tested at the
 * boundary with crafted envelopes so no model files are needed.
 */
class TextRendererDispatchTest {

    @Test
    void modelDiffUsesTheRiskGroupedBespokeView() {
        String env = """
            {"outcome":"read","summary":"1 modified","target":"model","op":"diff",
             "data":{"changes":[{"element":"RULE","id":"/Order/A","change":"MODIFIED",
                "risk":"HIGH","riskReasons":["POLARITY_INVERTED"],"deltas":[]}]}}""";
        String out = TextRenderer.renderIfEnvelope(env);
        assertTrue(out.contains("HIGH (1)") && out.contains("/Order/A") && out.contains("POLARITY_INVERTED"),
                () -> "risk-grouped diff view:\n" + out);
        assertFalse(out.contains("riskReasons") || out.contains("deltas"),
                () -> "bespoke, not the generic JSON tree:\n" + out);
    }

    @Test
    void modelReportUsesTheGlossedCatalogBespokeView() {
        String env = """
            {"outcome":"read","summary":"1 rule(s)","target":"model","op":"report",
             "data":{"rules":[{"rule":"/Order/A","gloss":"Uses FieldFilled over X."}],
                "computations":[],"unreferenced":["/Order/Dead"]}}""";
        String out = TextRenderer.renderIfEnvelope(env);
        assertTrue(out.contains("rules:") && out.contains("/Order/A — Uses FieldFilled over X."),
                () -> "glossed catalog view:\n" + out);
        assertTrue(out.contains("unreferenced (1)") && out.contains("/Order/Dead"), () -> "dead-field set:\n" + out);
    }

    @Test
    void anUnregisteredVerbFallsBackToTheGenericTree() {
        String env = """
            {"outcome":"read","summary":"formatted","target":"rule","op":"format",
             "data":{"rule":"/Order/A","canonical":"FieldFilled(X)"}}""";
        String out = TextRenderer.renderIfEnvelope(env);
        assertTrue(out.contains("data:") && out.contains("canonical: FieldFilled(X)"),
                () -> "generic indented tree for an unregistered verb:\n" + out);
    }
}
