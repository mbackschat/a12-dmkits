package io.github.mbackschat.a12.dm.cli;

import java.util.Locale;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorEntry;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The {@code operators} verb (CLI-SPEC §4) — exposes the DSL <b>operator catalog</b> so an agent can
 * explore the vocabulary it authors with, on demand, without the N1 skill (the self-explaining
 * requirement, CLI-SPEC §1a). Bare/filtered: a compact list ({@code id, keyword, kind, supported,
 * meaning}); with an id: that operator's full semantics (keywords EN/DE, operands, meaning,
 * constraints, gotchas, a valid + an invalid example). This is the proactive complement to
 * {@code explain}, which only surfaces operators a given rule already uses.
 */
@Command(name = "operators", mixinStandardHelpOptions = true,
        description = "Explore the DSL operator catalog: list all (filterable) or one operator's full semantics.")
final class OperatorsCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", arity = "0..1", paramLabel = "<operatorId>",
            description = "an operator id (e.g. DifferenceInDays) for its full semantics; omit to list all")
    String id;

    @Option(names = "--kind", paramLabel = "<kind>",
            description = "filter the list by kind (OPERATOR, PREDICATE, FUNCTION, …)")
    String kind;

    @Option(names = "--keyword", paramLabel = "<text>",
            description = "filter the list to operators whose EN/DE keyword contains this text")
    String keyword;

    @Override
    public Integer call() {
        OperatorCatalog catalog = OperatorCatalog.load();
        if (id != null) {
            return catalog.byId(id)
                    .map(e -> emit(full(e)))
                    .orElseGet(() -> fail("no operator '" + id + "' in the catalog (run `operators` to list them)"));
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("verifiedAgainst", catalog.verifiedAgainst());
        ArrayNode list = root.putArray("operators");
        catalog.entries().stream()
                .filter(e -> kind == null || e.kind().name().equalsIgnoreCase(kind))
                .filter(e -> keyword == null || containsKeyword(e, keyword))
                .forEach(e -> list.add(compact(e)));
        root.put("count", list.size());
        return emit(root);
    }

    private static boolean containsKeyword(OperatorEntry e, String needle) {
        String n = needle.toLowerCase(Locale.ROOT);
        String en = e.keywords() == null ? "" : String.valueOf(e.keywords().en()).toLowerCase(Locale.ROOT);
        String de = e.keywords() == null ? "" : String.valueOf(e.keywords().de()).toLowerCase(Locale.ROOT);
        return en.contains(n) || de.contains(n);
    }

    private ObjectNode compact(OperatorEntry e) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("id", e.id());
        n.put("keyword", e.keywords() == null ? null : e.keywords().en());
        n.put("kind", e.kind().name());
        n.put("supported", e.supported());
        n.put("meaning", e.meaning());
        return n;
    }

    private ObjectNode full(OperatorEntry e) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("id", e.id());
        n.put("kind", e.kind().name());
        ObjectNode kw = n.putObject("keyword");
        if (e.keywords() != null) {
            kw.put("en", e.keywords().en());
            kw.put("de", e.keywords().de());
        }
        n.put("supported", e.supported());
        if (e.returns() != null) {
            n.put("returns", e.returns());
        }
        ArrayNode operands = n.putArray("operands");
        e.operands().forEach(o -> operands.add(o.type()));
        n.put("meaning", e.meaning());
        if (!e.constraints().isEmpty()) {
            ArrayNode c = n.putArray("constraints");
            e.constraints().forEach(c::add);
        }
        if (!e.gotchas().isEmpty()) {
            ArrayNode g = n.putArray("gotchas");
            e.gotchas().forEach(x -> g.add(x.text()));
        }
        if (e.validExample() != null) {
            ObjectNode ex = n.putObject("validExample");
            ex.put("dsl", e.validExample().dsl());
            ex.put("note", e.validExample().note());
        }
        if (e.invalidExample() != null) {
            ObjectNode ex = n.putObject("invalidExample");
            ex.put("dsl", e.invalidExample().dsl());
            ex.put("error", e.invalidExample().error());
            ex.put("fix", e.invalidExample().fix());
        }
        if (e.sinceKernel() != null) {
            n.put("sinceKernel", e.sinceKernel());
        }
        return n;
    }

    private int emit(ObjectNode node) {
        try {
            out().print(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node) + "\n");
            return 0;
        } catch (JsonProcessingException e) {
            return fail("cannot serialize operators: " + e.getMessage());
        }
    }
}
