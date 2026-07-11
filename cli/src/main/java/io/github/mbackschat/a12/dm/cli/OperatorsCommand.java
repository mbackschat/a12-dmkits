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
        // the per-kind empty-operand defaults (CATALOG-SPEC §4) — what an empty operand of each value
        // kind does unless an operator's own `semantics.emptyOperand` facet deviates
        if (!catalog.emptyOperandDefaults().isEmpty()) {
            ObjectNode defaults = root.putObject("emptyOperandDefaults");
            catalog.emptyOperandDefaults().forEach((kind, d) -> defaults.put(kind, d.behavior().name()));
        }
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
        // the DSL authoring entry point(s) — the Java method(s) that render this operator (G3), so an
        // agent driving rulekit can cross from "which operator" to "which call". The constants with no
        // standalone factory (True/False/FirstDay/LastDay) serve an explicit empty list + a dslEntryNote
        // reason — "we checked; here is why there is no factory" — never a silent absence.
        if (!e.dslEntry().isEmpty() || e.dslEntryNote() != null) {
            ArrayNode de = n.putArray("dslEntry");
            e.dslEntry().forEach(de::add);
        }
        if (e.dslEntryNote() != null) {
            n.put("dslEntryNote", e.dslEntryNote());
        }
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
            // each gotcha travels WITH its kind (SURPRISE/AUTHORING/RENDERING) — the kind was
            // previously dropped, flattening the author-relevant/trivia classification
            e.gotchas().forEach(x -> {
                ObjectNode gn = g.addObject();
                gn.put("text", x.text());
                gn.put("kind", x.kind().name());
            });
        }
        // the semantics facets (CATALOG-SPEC §3–§4/§7) — the machine-readable per-operator semantics
        // across every dimension; the CI-executed probes are elided (verification detail, not payload)
        putSemantics(n, e);
        // the operand-conditional correctives (CATALOG-SPEC §6) — per operand shape, the fix for a code
        if (!e.correctives().isEmpty()) {
            ArrayNode corr = n.putArray("correctives");
            e.correctives().forEach(c -> {
                ObjectNode cn = corr.addObject();
                cn.put("code", c.code());
                ArrayNode when = cn.putArray("whenOperands");
                c.whenOperands().forEach(when::add);
                cn.put("fix", c.fix());
            });
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

    /**
     * Emit the {@code semantics} facet block (CATALOG-SPEC §3) — every populated dimension, probes
     * elided. The block is created only if at least one dimension speaks, so an operator with no facet
     * carries no empty {@code semantics} node.
     */
    private static void putSemantics(ObjectNode n, OperatorEntry e) {
        boolean any = !e.emptyOperandClaims().isEmpty() || e.boundaryClaim() != null
                || e.iterationRangeClaim() != null || e.aggregateIdentityClaim() != null
                || e.polarityClaim() != null;
        if (!any) {
            return;
        }
        ObjectNode sem = n.putObject("semantics");
        if (!e.emptyOperandClaims().isEmpty()) {
            ArrayNode claims = sem.putArray("emptyOperand");
            e.emptyOperandClaims().forEach(c -> {
                ObjectNode cn = claims.addObject();
                cn.put("operand", c.operand());
                if (c.behavior() != null) {
                    cn.put("behavior", c.behavior().name());
                }
                if (!c.perKind().isEmpty()) {
                    ObjectNode pk = cn.putObject("perKind");
                    c.perKind().forEach((kind, b) -> pk.put(kind, b.name()));
                }
                cn.put("basis", c.basis());
                if (c.note() != null) {
                    cn.put("note", c.note());
                }
            });
        }
        if (e.boundaryClaim() != null) {
            ObjectNode b = sem.putObject("boundary");
            b.put("value", e.boundaryClaim().value().name());
            b.put("basis", e.boundaryClaim().basis());
            if (e.boundaryClaim().note() != null) {
                b.put("note", e.boundaryClaim().note());
            }
        }
        if (e.iterationRangeClaim() != null) {
            ObjectNode ir = sem.putObject("iterationRange");
            ir.put("value", e.iterationRangeClaim().value().name());
            ir.put("basis", e.iterationRangeClaim().basis());
            if (e.iterationRangeClaim().note() != null) {
                ir.put("note", e.iterationRangeClaim().note());
            }
        }
        if (e.aggregateIdentityClaim() != null) {
            ObjectNode ai = sem.putObject("aggregateIdentity");
            if (e.aggregateIdentityClaim().value() != null) {
                ai.put("value", e.aggregateIdentityClaim().value().name());
            }
            if (!e.aggregateIdentityClaim().perKind().isEmpty()) {
                ObjectNode pk = ai.putObject("perKind");
                e.aggregateIdentityClaim().perKind().forEach((kind, v) -> pk.put(kind, v.name()));
            }
            ai.put("basis", e.aggregateIdentityClaim().basis());
            if (e.aggregateIdentityClaim().note() != null) {
                ai.put("note", e.aggregateIdentityClaim().note());
            }
        }
        if (e.polarityClaim() != null) {
            ObjectNode p = sem.putObject("polarity");
            p.put("value", e.polarityClaim().value().name());
            p.put("basis", e.polarityClaim().basis());
            if (e.polarityClaim().note() != null) {
                p.put("note", e.polarityClaim().note());
            }
        }
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
