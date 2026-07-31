package io.github.mbackschat.a12.dm.cli;

import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;

/**
 * Shared assembly for the {@code rule format} / {@code computation format} verbs (FEATURE-GAPS): the
 * kernel's canonical condition text — optionally re-emitted in the other keyword language (EN↔DE) — rides the
 * read envelope's {@code data}. The two verbs differ only in their target kind and which element-kind names
 * they accept; the canonical-text round-trip is identical, so it lives here (one source, no drift).
 */
public final class FormatVerbs {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FormatVerbs() {
    }

    /**
     * The read envelope for formatting {@code fullName}: the kernel's canonical text — in {@code lang}'s
     * keywords, or the model's own language when {@code lang} is {@code null} — under {@code data.canonical}.
     */
    public static ResultEnvelope envelope(String target, KernelAdapter adapter, String fullName,
            ConditionLanguage lang, ConsistencyEvidence evidence) {
        String canonical = lang == null
                ? adapter.canonicalConditionText(fullName)
                : adapter.canonicalConditionText(fullName, lang);
        ObjectNode data = MAPPER.createObjectNode();
        data.put(target, fullName);
        data.put("lang", lang == null ? "model" : lang.name().toLowerCase(Locale.ROOT));
        data.put("canonical", canonical);
        // The evidence comes from the CALLER's binding, not from this adapter. Both format verbs bind with
        // BoundModel.ofJson (gated) and then open KernelAdapter.of — the deliberately UNCHECKED factory, whose
        // receipt is empty. A comment here previously claimed the adapter attested "through checkAndOpen", which was
        // simply the wrong factory: the gate had run one line earlier and its receipt was being thrown away.
        return ResultEnvelope.queryResult(target, "format", "formatted " + fullName, data, evidence);
    }
}
