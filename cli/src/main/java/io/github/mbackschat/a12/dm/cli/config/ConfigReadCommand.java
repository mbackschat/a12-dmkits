package io.github.mbackschat.a12.dm.cli.config;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;

/**
 * {@code dmtool config read} — read the model's document configuration. A read op: the payload rides {@code
 * data} ({@code decimalSeparator}, {@code timeZone}, {@code conditionLanguage}, {@code fieldRefByShortNameAllowed},
 * {@code supportedCharacters} — the allowed-character set, empty = all allowed — and {@code locales}, the
 * declared message locales that a rule/computation must each carry a message for).
 */
@Command(name = "read", mixinStandardHelpOptions = true,
        description = "Read the document configuration into `data` — incl. `locales`, the declared display-locale "
                + "registry (header.locales). Every rule/computation must carry a message for EACH declared "
                + "locale (the kernel enforces it on validate); register one with `config modify --add-locale`.")
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
            // The allowed-character set (each entry a char or a range like " -~"); empty = all characters allowed.
            ArrayNode supported = data.putArray("supportedCharacters");
            c.supportedCharacters().forEach(supported::add);
            // the model-level comment (modelInfo.comment), if set
            doc.modelComment().ifPresent(comment -> data.put("comment", comment));
            // the model's base year (modelInfo.baseYear), if set — the read peer of `config modify --base-year`
            doc.baseYear().ifPresent(year -> data.put("baseYear", year));
            // the model-level header annotations (incl. the access-control `roles`, G26) — the read peer of
            // `config modify --annotation`/`--role`; surfaced so a roles edit round-trips
            Map<String, String> modelAnnotations = doc.modelAnnotations();
            if (!modelAnnotations.isEmpty()) {
                ObjectNode annotations = data.putObject("annotations");
                modelAnnotations.forEach(annotations::put);
            }
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
