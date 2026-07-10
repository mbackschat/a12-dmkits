package io.github.mbackschat.a12.dm.cli.config;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ReadPayloads;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;

/**
 * {@code dmtool config read} — read the model's document configuration. A read op: the payload rides {@code
 * data} ({@code decimalSeparator}, {@code timeZone}, {@code conditionLanguage}, {@code fieldRefByShortNameAllowed},
 * {@code supportedCharacters} — the allowed-character set, empty = all allowed — {@code locales}, the declared
 * message locales that a rule/computation must each carry a message for, and the model-level metadata:
 * {@code label} (per-locale, the read peer of {@code config modify --label}), {@code comment}, {@code baseYear},
 * and {@code annotations}).
 */
@Command(name = "read", mixinStandardHelpOptions = true,
        description = "Read the document configuration into `data` — incl. `locales`, the declared display-locale "
                + "registry (header.locales). Every rule/computation must carry a message for EACH declared "
                + "locale (the kernel enforces it on validate); register one with `config modify --add-locale`.")
final class ConfigReadCommand extends BaseCommand {

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
            ObjectNode data = ReadPayloads.config(doc);   // T3: the one shared shape, same as `apply` CONFIG_READ
            ResultEnvelope env = ResultEnvelope.read("config", "read", "read document config", true, List.of(), data);
            out().print(env.toJsonString());
            return env.exitCode();
        } catch (RuntimeException e) {
            return fail("read failed: " + e.getMessage());
        }
    }
}
