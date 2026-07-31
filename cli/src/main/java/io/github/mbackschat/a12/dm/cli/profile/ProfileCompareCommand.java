package io.github.mbackschat.a12.dm.cli.profile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.adapter.profile.ModelProfiles;
import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ComparisonOutcome;
import io.github.mbackschat.a12.dm.cli.ParamKind;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code dmtool profile compare} — classify every current-profile fidelity axis and report whether the
 * synthesized model preserves its target workload shape.
 */
@Command(name = "compare", mixinStandardHelpOptions = true,
        description = "Compare target and actual current profiles. Every fidelity axis rides `data`; a mismatch "
                + "is a read result with `valid:false` and exit 1.")
final class ProfileCompareCommand extends BaseCommand {

    private static final ObjectMapper JSON = new ObjectMapper();

    @ParamKind(ParamKind.Kind.FILE)
    @Option(names = "--target", required = true, paramLabel = "<profile.json>",
            description = "target current profile, as a bare artifact or a profile-extract result envelope")
    String targetPath;

    @ParamKind(ParamKind.Kind.FILE)
    @Option(names = "--actual", required = true, paramLabel = "<profile.json>",
            description = "actual current profile, as a bare artifact or a profile-extract result envelope")
    String actualPath;

    @Override
    public Integer call() {
        String comparisonJson;
        try {
            comparisonJson = ModelProfiles.compare(
                    Files.readString(Path.of(targetPath)),
                    Files.readString(Path.of(actualPath)));
        } catch (IOException e) {
            return fail("cannot read profiles: " + e.getMessage());
        } catch (RuntimeException e) {
            return failOrRethrowDomainRejection("profile comparison failed: ", e);
        }

        JsonNode comparison;
        try {
            comparison = JSON.readTree(comparisonJson);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("the profile comparison is not valid JSON", e);
        }
        // Classify ONCE into the typed outcome, then let it own both the reported result and the exit code. The
        // published `data.matches` key is unchanged, and `differs` still exits 1 — this is a
        // reclassification of what the result is CALLED (profile equality was never model validity), not a change
        // to anything a caller observes.
        ComparisonOutcome outcome = ComparisonOutcome.of(comparison.path("matches").asBoolean());
        ResultEnvelope env = ResultEnvelope.comparisonResult("profile", "compare",
                outcome.matches() ? "profiles match" : "profiles differ", outcome, comparison);
        out().print(env.toJsonString());
        return env.exitCode();
    }
}
