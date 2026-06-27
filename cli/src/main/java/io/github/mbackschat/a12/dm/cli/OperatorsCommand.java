package io.github.mbackschat.a12.dm.cli;

import java.util.Locale;
import java.util.Optional;

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
 * meaning}) — narrowed by a positional substring (matched over id/keyword/meaning, the agent's natural
 * reach), {@code --kind}, or {@code --keyword}; with an exact id: that operator's full semantics (keywords EN/DE, operands, meaning,
 * constraints, gotchas, a valid + an invalid example). This is the proactive complement to
 * {@code explain}, which only surfaces operators a given rule already uses.
 */
@Command(name = "operators", mixinStandardHelpOptions = true,
        description = "Explore the DSL operator catalog: list all (filter by a substring, --kind, or --keyword) "
                + "or pass an exact operator id for its full semantics.")
final class OperatorsCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", arity = "0..1", paramLabel = "<operatorIdOrSubstring>",
            description = "an exact operator id (e.g. DifferenceInDays) for its full semantics; a non-id "
                    + "substring filters the list (e.g. `operators date`); omit to list all")
    String id;

    @Option(names = "--kind", paramLabel = "<kind>",
            description = "filter the list by kind (OPERATOR, PREDICATE, FUNCTION, …)")
    String kind;

    @Option(names = "--keyword", paramLabel = "<text>",
            description = "filter the list to operators whose EN/DE keyword OR meaning contains this text — a "
                    + "TOPICAL filter, so `--keyword date` finds the date-arithmetic operators (DifferenceInDays, "
                    + "AddYears, Today, …) too, not only those with 'date' in the keyword")
    String keyword;

    @Override
    public Integer call() {
        OperatorCatalog catalog = OperatorCatalog.load();
        if (id != null) {
            Optional<OperatorEntry> exact = catalog.byId(id);
            if (exact.isPresent()) {
                return emit(full(exact.get()));   // an exact id → that operator's full semantics
            }
            // G13: a non-id positional is the agent's natural reach for a filter — treat it as a substring
            // needle over the list (composes with --kind/--keyword below), instead of failing outright
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("verifiedAgainst", catalog.verifiedAgainst());
        ArrayNode list = root.putArray("operators");
        catalog.entries().stream()
                .filter(e -> kind == null || e.kind().name().equalsIgnoreCase(kind))
                .filter(e -> keyword == null || containsKeyword(e, keyword))
                .filter(e -> id == null || matchesSubstring(e, id))
                .forEach(e -> list.add(compact(e)));
        root.put("count", list.size());
        if (id != null && list.isEmpty()) {
            // neither an exact id nor a substring match → keep the corrective failure (a genuine typo)
            return fail("no operator '" + id + "' — not an id, and nothing matched it as a substring "
                    + "(run `operators` to list all, or try a shorter substring)");
        }
        return emit(root);
    }

    private static boolean containsKeyword(OperatorEntry e, String needle) {
        String n = needle.toLowerCase(Locale.ROOT);
        String en = e.keywords() == null ? "" : String.valueOf(e.keywords().en()).toLowerCase(Locale.ROOT);
        String de = e.keywords() == null ? "" : String.valueOf(e.keywords().de()).toLowerCase(Locale.ROOT);
        // Also match the MEANING so --keyword is a TOPICAL filter: an agent's `--keyword date` must surface the
        // date-arithmetic ops (DifferenceInDays/AddYears/Today) whose keyword has no "date" but whose meaning
        // does (eval finding 01b — the keyword-only filter missed the family the agent was after).
        String meaning = e.meaning() == null ? "" : e.meaning().toLowerCase(Locale.ROOT);
        return en.contains(n) || de.contains(n) || meaning.contains(n);
    }

    /** The positional substring filter (G13): match the needle against the id, EN/DE keyword, or meaning. */
    private static boolean matchesSubstring(OperatorEntry e, String needle) {
        String n = needle.toLowerCase(Locale.ROOT);
        StringBuilder hay = new StringBuilder(e.id());
        if (e.keywords() != null) {
            hay.append(' ').append(e.keywords().en()).append(' ').append(e.keywords().de());
        }
        if (e.meaning() != null) {
            hay.append(' ').append(e.meaning());
        }
        return hay.toString().toLowerCase(Locale.ROOT).contains(n);
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
        if (!e.opposite().isEmpty()) {
            ArrayNode opp = n.putArray("opposite");
            e.opposite().forEach(opp::add);
        }
        // seeAlso: the field-config constraint(s) that express a related restriction (G7) — a factual,
        // bidirectional bridge to the `schema field add` config knobs; choosing field-config vs a rule is
        // modeling judgment (the skill's job), not the tool's
        if (!e.seeAlso().isEmpty()) {
            ArrayNode sa = n.putArray("seeAlso");
            e.seeAlso().forEach(sa::add);
        }
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
