package io.github.mbackschat.a12.dm.cli.profile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.adapter.profile.ModelSynthesizer;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code dmtool profile synthesize} — materialize a current profile as an own-domain DM-JSON model. */
@Command(name = "synthesize", mixinStandardHelpOptions = true,
        description = "Synthesize a deterministic own-domain DM-JSON model from one current performance "
                + "profile. Repeatable groups must be bounded; synthesis refuses an unbounded profile instead "
                + "of silently making a cheaper model. The model goes to stdout, or -o writes it and returns "
                + "an applied receipt.")
final class ProfileSynthesizeCommand extends BaseCommand {

    private static final ObjectMapper JSON = new ObjectMapper();

    @ParamKind(ParamKind.Kind.FILE)
    @Option(names = "--input", required = true, paramLabel = "<profile.json>",
            description = "current profile artifact, either bare JSON or a profile-extract result envelope")
    String inputPath;

    @ParamKind(ParamKind.Kind.SCALAR)
    @Option(names = "--seed", paramLabel = "<n>", defaultValue = "1",
            description = "deterministic seed; the same current profile and seed reproduce the same model")
    long seed;

    @ParamKind(ParamKind.Kind.FILE)
    @Option(names = {"--output", "-o"}, paramLabel = "<model.json>",
            description = "write the synthesized DM-JSON model here instead of stdout")
    String outputPath;

    @Override
    public Integer call() {
        String modelJson;
        ModelSynthesizer.Synthesized synthesized;
        try {
            synthesized = ModelSynthesizer.synthesized(
                    Files.readString(Path.of(inputPath)), seed);
            modelJson = synthesized.dmJson();
        } catch (IOException e) {
            return fail("cannot read input: " + e.getMessage());
        } catch (RuntimeException e) {
            return failOrRethrowDomainRejection("profile synthesis failed: ", e);
        }

        if (outputPath == null) {
            out().print(modelJson);
            return 0;
        }
        String modelId = modelId(modelJson);
        try {
            Files.writeString(Path.of(outputPath), modelJson);
        } catch (IOException e) {
            return fail("cannot write output: " + e.getMessage());
        }

        ObjectNode changed = JSON.createObjectNode();
        changed.put("artifact", "profile-synthesized-model");
        changed.put("id", modelId);
        changed.put("seed", seed);
        ResultEnvelope envelope = ResultEnvelope.applied(
                "profile", "synthesize", "synthesized model from performance profile", changed, outputPath,
                ConsistencyEvidence.of(synthesized));
        out().print(envelope.toJsonString());
        return envelope.exitCode();
    }

    private static String modelId(String modelJson) {
        try {
            return ModelDocument.read(modelJson).id();
        } catch (RuntimeException e) {
            throw new IllegalStateException("the synthesized model is not valid JSON", e);
        }
    }
}
