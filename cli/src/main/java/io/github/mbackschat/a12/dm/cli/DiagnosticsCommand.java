package io.github.mbackschat.a12.dm.cli;

import java.util.Locale;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;
import io.github.mbackschat.a12.dm.rulekit.validate.StructuralKernelFix;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The {@code diagnostics} verb (CLI-CONFORMANCE-SPEC) — the {@code operators} twin for <b>diagnostic
 * codes</b>. It makes the {@code RK_*} vocabulary explorable so an agent that receives
 * {@code code: RK_NO_SUCH_FIELD} can look up what it means, on demand, without the N1 skill. Bare/filtered:
 * a list of every code ({@code code, severity, source, meaning}); with a code: that code's full entry
 * (adds the canonical {@code fix}). The list is the single-source {@link RkCode} registry — never a second
 * hand-authored copy. A single-code lookup ALSO resolves the <b>structural</b> {@code MVK_*} kernel codes
 * that no operator documents (the placement / iteration-scope family — {@code MVK_ERROR_FIELD_NOT_IN_RULEGROUP},
 * {@code MVK_NEG_CONDITION_IN_ITERATION}), via the shared {@link StructuralKernelFix} — a probe (P-mini-1)
 * found an agent that hit one naturally tries {@code diagnostics <code>} and used to dead-end. (Operator-owned
 * {@code MVK_*} codes still route to the operator catalog / {@code explain}; the bare list stays {@code RK_*}.)
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
            Optional<RkCode> rk = RkCode.byCode(wire);
            if (rk.isPresent()) {
                return emit(full(rk.get()));
            }
            // A structural KERNEL code no operator documents (placement / iteration-scope) — resolve it
            // here instead of dead-ending (P-mini-1), single-sourced from the same StructuralKernelFix
            // the diagnostic enricher uses. (Operator-owned MVK_* codes still route to operators/explain.)
            Optional<StructuralKernelFix.Hint> hint = StructuralKernelFix.forCode(code);
            if (hint.isPresent()) {
                return emit(structural(code, hint.get()));
            }
            return fail("no diagnostic code '" + code
                    + "' in the RK_* catalog (run `diagnostics` to list them). MVK_* codes are kernel codes "
                    + "— a structural/placement one (e.g. MVK_ERROR_FIELD_NOT_IN_RULEGROUP) is explained here; "
                    + "an operator one is explained via `operators`/`explain`. A code you got back in a verb's "
                    + "`data.findings[]` (not the envelope `diagnostics[]`) is documented in that verb's output "
                    + "schema — run `schema <target> <op>` for the verb that produced it (e.g. `schema workspace roles`).");
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

    /** A {@code diagnostics} entry for a structural KERNEL code (always KERNEL-origin, ERROR-severity). */
    private ObjectNode structural(String code, StructuralKernelFix.Hint hint) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("code", code);
        n.put("severity", "ERROR");
        n.put("source", "KERNEL");
        n.put("meaning", hint.explain());
        n.put("fix", hint.fix());
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
