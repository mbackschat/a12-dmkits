package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;

/**
 * {@code dmtool config read} — read the model's document configuration. A read op: the payload rides {@code
 * data} ({@code decimalSeparator}, {@code timeZone}, {@code conditionLanguage}, {@code fieldRefByShortNameAllowed},
 * and {@code locales} — the declared message locales that a rule/computation must each carry a message for).
 */
@Command(name = "read", mixinStandardHelpOptions = true,
        description = "Read the document configuration into `data`.")
final class ConfigReadCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Integer call() {
        String model = model();
        if (model == null) {
            return fail("pass -m <model.json> (before or after the verb)");
        }
        String json;
        try {
            json = Files.readString(Path.of(model));
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        }
        try {
            ModelDocument doc = ModelDocument.read(json);
            ModelDocument.DocConfig c = doc.config();
            ObjectNode data = MAPPER.createObjectNode();
            data.put("decimalSeparator", c.decimalSeparator());
            data.put("timeZone", c.timeZone());
            data.put("conditionLanguage", c.conditionLanguage());
            data.put("fieldRefByShortNameAllowed", c.fieldRefByShortNameAllowed());
            // The declared message locales (header.locales) drive the per-locale-message law
            // (MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING) — surface the full set so an agent can provision a
            // rule/computation message per locale up front. `conditionLanguage` alone doesn't reveal it.
            ArrayNode locales = data.putArray("locales");
            doc.locales().forEach(locales::add);
            ResultEnvelope env = ResultEnvelope.read("config", "read", "read document config", true, List.of(), data);
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (RuntimeException e) {
            return fail("read failed: " + e.getMessage());
        }
    }
}
