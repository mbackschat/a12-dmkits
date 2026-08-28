package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * The <b>apply op-set parity guard</b> — the enforcement locus for a contract [`CLI-SPEC`] §7 <em>advertises</em>:
 * {@code apply}'s op-set is the <b>Local</b> class (add · read · modify · remove) plus the reference-rewriting
 * refactors, with {@code extract}/{@code inline} and the second-artifact verbs the named exceptions.
 *
 * <p>Nothing enforced that claim, and it was false in three places at once: {@code computation remove},
 * {@code computation read}, and {@code rule read} had no {@link ApplyCommand.Op}, so an agent that believed §7
 * hit {@code RK_UNKNOWN_OP} mid-sequence. An external probe found the first of the three, which is the tell that
 * a doc-only contract is not a contract. {@code CliCoverageParityTest} could not see it: its denominator is
 * {@code EditSession}'s mutators, and the <em>standalone</em> verb already satisfied each one.
 *
 * <p>The denominator here is the <b>live command tree</b> (via {@code manifest}, which picocli derives — so it
 * cannot drift): every leaf verb on a Local-class target must either have an apply op or be classified below
 * with its reason. An unclassified new verb fails the build, and so does a stale classification — the
 * allowlist cannot decay into a blanket exemption.
 */
class ApplyOpSetParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The element families {@code apply} transacts over — §7's "Local class". Cross-cutting verbs have no target here. */
    private static final Set<String> LOCAL_TARGETS =
            Set.of("field", "group", "rule", "computation", "typedef", "include", "config", "meta");

    /**
     * Live Local-class verbs that deliberately have NO apply op, each with the reason. A reason is required
     * because "not implemented" and "cannot be an op-record" are different facts, and only the second is a
     * contract.
     */
    private static final Map<String, String> STANDALONE_ONLY = Map.ofEntries(
            // ── produce or consume a SECOND artifact (an include file / an imported typedef source), which
            //    apply's one-model-one-write shape cannot represent (§7 names these explicitly) ──
            Map.entry("group extract", "writes a second artifact (the extracted include file)"),
            Map.entry("typedef extract", "writes a second artifact (the extracted typedef library)"),
            Map.entry("typedef inline", "consumes a second artifact (the imported typedef source)"),
            Map.entry("typedef import", "reads a second artifact (the typedef library to import from)"),
            Map.entry("typedef unimport", "detaches from a second artifact (the imported typedef source)"),
            Map.entry("include inline", "consumes a second artifact (the included model)"),
            // ── read-only ANALYSIS over one model: no session mutation to transact, and each already composes
            //    under `batch` (§7's decision rule: a fan-out of independent verbs goes to batch) ──
            Map.entry("rule check", "read-only analysis — no session mutation to transact"),
            Map.entry("rule deps", "read-only analysis — no session mutation to transact"),
            Map.entry("rule eval", "read-only evaluation — no session mutation to transact"),
            Map.entry("rule explain", "read-only analysis — no session mutation to transact"),
            Map.entry("rule format", "read-only rendering — no session mutation to transact"),
            Map.entry("computation explain", "read-only analysis — no session mutation to transact"),
            Map.entry("computation format", "read-only rendering — no session mutation to transact"),
            // ── interactive/bulk authoring aids whose input is a selection, not an op-record ──
            Map.entry("group multiselect", "bulk authoring aid over a selection, not a single-element op-record"),
            Map.entry("group attachment", "bulk authoring aid over a selection, not a single-element op-record"));

    private static Set<String> liveLocalVerbs() throws Exception {
        CliTestSupport.Result res = CliTestSupport.run("manifest");
        assertEquals(0, res.code(), res::err);
        Set<String> verbs = new TreeSet<>();
        for (JsonNode v : MAPPER.readTree(res.out()).path("verbs")) {
            if (LOCAL_TARGETS.contains(v.path("target").asText())) {
                verbs.add(v.path("verb").asText());
            }
        }
        assertTrue(verbs.size() > 20, () -> "the manifest yielded a plausible Local-class denominator: " + verbs);
        return verbs;
    }

    private static Set<String> applyOpLabels() {
        return Arrays.stream(ApplyCommand.Op.values())
                .map(ApplyCommand.Op::label)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void everyLocalClassVerbIsAnApplyOpOrClassifiedStandaloneOnly() throws Exception {
        Set<String> ops = applyOpLabels();
        Set<String> unclassified = new TreeSet<>();
        for (String verb : liveLocalVerbs()) {
            if (!ops.contains(verb) && !STANDALONE_ONLY.containsKey(verb)) {
                unclassified.add(verb);
            }
        }
        assertEquals(Set.of(), unclassified,
                () -> "these Local-class verbs have no apply op and no recorded reason — add the op to "
                        + "ApplyCommand.Op, or classify it in STANDALONE_ONLY with why it cannot be an "
                        + "op-record: " + unclassified);
    }

    @Test
    void noStandaloneOnlyClassificationIsStale() throws Exception {
        Set<String> live = liveLocalVerbs();
        Set<String> ops = applyOpLabels();
        Set<String> stale = new TreeSet<>();
        for (String verb : STANDALONE_ONLY.keySet()) {
            if (!live.contains(verb) || ops.contains(verb)) {
                stale.add(verb);
            }
        }
        assertEquals(Set.of(), stale,
                () -> "these STANDALONE_ONLY entries no longer describe reality (the verb is gone, or apply "
                        + "now has the op) — drop them so the exemption list cannot decay: " + stale);
    }

    /** The three ops an external probe's blocked transaction needed: a computation replaced in ONE session. */
    @Test
    void theLocalClassCoversComputationRemoveAndTheTargetedReads() {
        Set<String> ops = applyOpLabels();
        assertTrue(ops.containsAll(Set.of("computation remove", "computation read", "rule read")),
                () -> "apply covers the Local class for rules and computations: " + ops);
    }
}
