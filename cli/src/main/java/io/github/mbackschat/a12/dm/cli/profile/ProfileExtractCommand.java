package io.github.mbackschat.a12.dm.cli.profile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.adapter.profile.ModelProfiles;
import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import io.github.mbackschat.a12.dm.cli.WorkspaceOption;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * {@code dmtool profile extract} — reduce a DM-JSON model to its deterministic, identifier-free
 * performance shape.
 *
 * <p>Without {@code -o}, the profile rides the normal result envelope's {@code data}. With {@code -o}, the
 * file is the bare profile artifact and stdout carries an applied write-confirmation envelope.
 */
@Command(name = "extract", mixinStandardHelpOptions = true,
        description = "Extract the -m model's deterministic, identifier-free performance profile into `data`; "
                + "-o writes the bare profile artifact and returns an applied envelope.")
final class ProfileExtractCommand extends BaseCommand {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mixin
    WorkspaceOption workspace = new WorkspaceOption();

    @Option(names = {"--output", "-o"}, paramLabel = "<profile.json>",
            description = "write the bare profile artifact here; stdout then carries an applied write-confirmation envelope")
    String outputPath;

    @Override
    public Integer call() {
        String modelPath = model();
        String modelJson = readModelJson(modelPath);
        if (modelJson == null) {
            return 2;
        }

        String profileJson;
        JsonNode profile;
        try {
            profileJson = ModelProfiles.extract(modelJson, workspace.resolverFor(modelPath));
            profile = JSON.readTree(profileJson);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("the extracted profile is not valid JSON", e);
        } catch (RuntimeException e) {
            return failOrRethrowDomainRejection("profile extraction failed: ", e);
        }

        if (outputPath == null) {
            // SynthesisProfile binds through BoundModel.ofJsonLenient — expand-only, no gate (measured).
            ResultEnvelope env = ResultEnvelope.queryResult(resultIdentity(), "extracted model performance profile",
                    profile, ConsistencyEvidence.none());
            out().print(env.toJsonString());
            return env.exitCode();
        }

        try {
            Files.writeString(Path.of(outputPath), profileJson);
        } catch (IOException e) {
            return refuseUnwritableOutput("-o <profile.json>", outputPath, e);
        }
        ObjectNode changed = JSON.createObjectNode();
        changed.put("artifact", "model-performance-profile");
        ResultEnvelope env = ResultEnvelope.applied(resultIdentity(), "extracted model performance profile", changed,
                outputPath, ConsistencyEvidence.none());
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
