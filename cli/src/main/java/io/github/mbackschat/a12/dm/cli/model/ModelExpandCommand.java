package io.github.mbackschat.a12.dm.cli.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;
import io.github.mbackschat.a12.dm.cli.interp.PortableModelPreparation;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * {@code dmtool model expand} — resolve includes and imported type definitions into one self-contained
 * DM-JSON model through the portable interpreter workspace assembler. Conversion stops before evaluator-only
 * time-zone/date-precision checks, so the artifact can remain Kernel-legal even when the CLI cannot evaluate it.
 *
 * <p>The primary stdout form is a model artifact rather than the result envelope and is therefore listed in
 * {@code SchemaCommand.CONTRACT_EXEMPT}. With {@code -o}, the artifact is written only after complete assembly
 * succeeds and stdout carries the normal write-confirmation envelope.
 */
@Command(name = "expand", mixinStandardHelpOptions = true,
        description = "Resolve includes and imported typedefs into one self-contained DM-JSON model. "
                + "Prints the artifact to stdout, or -o writes it to a file.")
final class ModelExpandCommand extends BaseCommand {

    @Option(names = {"--output", "-o"}, paramLabel = "<file>",
            description = "write the expanded model here instead of printing it to stdout")
    String outputPath;

    @Mixin
    WorkspaceOption workspace = new WorkspaceOption();

    @Override
    public Integer call() {
        String modelPath = model();
        String modelJson = readModelJson(modelPath);
        if (modelJson == null) {
            return 2;
        }
        String expanded = PortableModelPreparation.expand(
                modelJson,
                Path.of(modelPath).getFileName().toString(),
                workspace.resolverFor(modelPath));
        if (outputPath == null) {
            out().print(expanded);
            return 0;
        }
        try {
            Files.writeString(Path.of(outputPath), expanded);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }
        ObjectNode changed = JsonNodeFactory.instance.objectNode();
        changed.put("selfContained", true);
        ResultEnvelope envelope = ResultEnvelope.applied(
                "model", "expand", "expanded model into one self-contained artifact", changed, outputPath);
        out().print(envelope.toJsonString());
        return envelope.exitCode();
    }
}
