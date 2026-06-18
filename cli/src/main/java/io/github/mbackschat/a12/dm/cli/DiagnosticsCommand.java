package io.github.mbackschat.a12.dm.cli;

import java.util.Locale;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The {@code diagnostics} verb (CLI-CONFORMANCE-SPEC) — the {@code operators} twin for <b>diagnostic
 * codes</b>. It makes the {@code RK_*} vocabulary explorable so an agent that receives
 * {@code code: RK_NO_SUCH_FIELD} can look up what it means, on demand, without the N1 skill. Bare/filtered:
 * a list of every code ({@code code, severity, source, meaning}); with a code: that code's full entry
 * (adds the canonical {@code fix}). All entries derive from the single-source {@link RkCode} registry —
 * never a second hand-authored copy. ({@code MVK_*} kernel codes are explained through the operator
 * catalog / {@code explain}; this verb covers the codes rulekit owns.)
 */
@Command(name = "diagnostics", mixinStandardHelpOptions = true,
        description = "Explore the diagnostic-code catalog: list all RK_* codes (filterable) or one code's full meaning.")
final class DiagnosticsCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", arity = "0..1", paramLabel = "<code>",
            description = "a code (e.g. RK_NO_SUCH_FIELD) for its full meaning + fix; omit to list all")
    String code;

    @Option(names = "--severity", paramLabel = "<severity>",
            description = "filter the list by severity (ERROR, WARNING, INFO)")
    String severity;

    @Option(names = "--source", paramLabel = "<source>",
            description = "filter the list by source (PRECHECK, KERNEL, LINT, ENVIRONMENT)")
    String source;

    @Override
    public Integer call() {
        if (code != null) {
            String wire = code.startsWith("RK_") ? code : "RK_" + code;
            return RkCode.byCode(wire)
                    .map(c -> emit(full(c)))
                    .orElseGet(() -> fail("no diagnostic code '" + code
                            + "' in the RK_* catalog (run `diagnostics` to list them). MVK_* codes are kernel codes "
                            + "— see `operators`/`explain`. A code you got back in a verb's `data.findings[]` (not the "
                            + "envelope `diagnostics[]`) is documented in that verb's output schema — run `schema "
                            + "<target> <op>` for the verb that produced it (e.g. `schema workspace roles`)."));
        }
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode list = root.putArray("diagnostics");
        for (RkCode c : RkCode.values()) {
            if (severity != null && !c.severity().name().equalsIgnoreCase(severity)) {
                continue;
            }
            if (source != null && !c.source().name().equalsIgnoreCase(source)) {
                continue;
            }
            list.add(compact(c));
        }
        root.put("count", list.size());
        return emit(root);
    }

    private ObjectNode compact(RkCode c) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("code", c.code());
        n.put("severity", c.severity().name());
        n.put("source", c.source().name());
        n.put("meaning", c.meaning());
        return n;
    }

    private ObjectNode full(RkCode c) {
        ObjectNode n = compact(c);
        n.put("fix", c.fix());
        return n;
    }

    private int emit(ObjectNode node) {
        try {
            out().print(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node) + "\n");
            return 0;
        } catch (JsonProcessingException e) {
            return fail("cannot serialize diagnostics: " + e.getMessage());
        }
    }
}
