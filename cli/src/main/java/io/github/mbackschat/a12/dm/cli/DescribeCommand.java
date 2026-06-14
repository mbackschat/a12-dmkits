package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.github.mbackschat.a12.dm.rulekit.context.ModelContext;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

/**
 * The {@code describe} verb (CLI-SPEC §4) — emits the F9a model card as JSON, so an agent can orient
 * on a model (fields + kinds + enum domains + group repeatability) without reading raw DM-JSON.
 */
@Command(name = "describe", hidden = true, mixinStandardHelpOptions = true,
        description = "Emit the model card (fields + kinds + enum domains + group repeatability) as JSON.")
final class DescribeCommand extends BaseCommand {

    @Parameters(index = "0", paramLabel = "<model.json>", description = "the DM-JSON model file")
    String model;

    @Mixin
    WorkspaceOption workspace = new WorkspaceOption();

    @Override
    public Integer call() {
        try {
            BoundModel m = BoundModel.ofJson(Files.readString(Path.of(model)), workspace.resolverFor(model));
            out().print(new ModelContext(m).describe().toJson() + "\n");
            return 0;
        } catch (IOException e) {
            return fail("cannot read model: " + e.getMessage());
        } catch (RuntimeException e) {
            return fail("describe failed: " + e.getMessage());
        }
    }
}
