package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;

/**
 * The admitted temporal format vocabulary is DERIVED from the kernel and published, so no consumer has to
 * assemble it from the members it happens to have used.
 *
 * <p><b>The defect this exists to stop.</b> Three law tests independently ask the kernel "is this format
 * admitted?", each over its own hand-picked candidate list, and none publishes the answer — so the admitted set
 * was nobody's output and downstream sites copied members. A copied set is short in a way that reads complete,
 * and the copy's own test stays green because it asserts only what it lists. Measured twice: {@code MM-dd} was
 * missing from the incomplete-format whitelist until 2026-08-19 and three published schema descriptions
 * inherited a two-format DATE allowlist; {@code yyyyMM} was missing from the declared-format grid until
 * 2026-08-29 and reached an outbound handback to a peer project, who caught it. Neither needed new measurement
 * — both sets were already wrong when written.
 *
 * <p><b>Why derivation rather than a shared constant.</b> Sharing one list propagates a short list faithfully;
 * it cannot catch the case where the list was short at its origin, which is both incidents. The kernel is the
 * only thing that knows, so the set is recomputed from it and committed as a derived artifact, the same shape
 * {@code docs/OPERATOR-INDEX.md} already uses. Regenerate with {@code ./build.sh :dumpTemporalVocabulary}.
 *
 * <p><b>What the published claim actually is, stated because it is the whole subject.</b> Not "these are the
 * legal formats" — that is unknowable without enumerating an infinite string domain. It is "over THIS candidate
 * universe, the kernel admits exactly these", and the artifact prints the universe beside the verdicts so the
 * denominator travels with the answer. A format nobody imagined is still undiscoverable; what changes is that
 * the boundary is written down instead of implied.
 */
class TemporalFormatVocabularyGuardTest {

    private static final Path ARTIFACT = Path.of("docs/TEMPORAL-FORMAT-VOCABULARY.md");

    private enum Kind { DATE, TIME, DATE_TIME }

    /** Where a candidate lands once all three kinds have answered. */
    private enum Verdict { ADMITTED, REFUSED, SPLIT }

    // ── the candidate universe ───────────────────────────────────────────────────────────────────────────────

    /**
     * Every format string the estate's existing probes name, admitted and refused alike. Refused candidates earn
     * their place: without them "the kernel admits everything" and "the gate reads the string" are the same
     * observation.
     */
    private static final List<String> FROM_EXISTING_PROBES = List.of(
            // IncompleteDateFormatAdmissionLawsTest's eleven
            "yyyy", "yyyy-MM", "yyyyMM", "MM", "MM-dd", "yyyy-MM-dd", "dd.MM.yyyy",
            "MM.yyyy", "MM-yyyy", "yyyy/MM", "dd.MM",
            // TemporalFormatVocabularyLawsTest's witnesses and its nine refusals
            "HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss",
            "HH:mm", "H:mm:ss", "HH:mm:ss.SSS", "hh:mm:ss a", "HHmmss", "HH-mm-ss",
            "yyyy-MM-dd HH:mm:ss", "dd.MM.yyyy HH:mm:ss", "yyyy-MM-dd HH:mm");

    private static final List<String> DATE_COMPONENTS = List.of("yyyy", "MM", "dd");
    private static final List<String> TIME_COMPONENTS = List.of("HH", "mm", "ss");
    private static final List<String> DATE_SEPARATORS = List.of("", "-", ".", "/");
    private static final List<String> TIME_SEPARATORS = List.of("", ":", "-", ".");

    /**
     * The universe: the probes above plus every ordered non-empty selection of the date and time components at
     * each separator. The generated half exists because the two incidents were both a MISSING member, and a
     * hand-written candidate list cannot miss what it does not think of — the permutations do think of it. The
     * separator axis is generated rather than sampled because it is the one KF196 proved discriminating: the
     * same two components flip verdict on their separator alone.
     */
    private static Set<String> candidates() {
        Set<String> all = new LinkedHashSet<>(FROM_EXISTING_PROBES);
        all.addAll(permutations(DATE_COMPONENTS, DATE_SEPARATORS));
        all.addAll(permutations(TIME_COMPONENTS, TIME_SEPARATORS));
        for (String date : List.of("yyyy-MM-dd", "dd.MM.yyyy")) {
            for (String time : List.of("HH:mm:ss", "HH:mm")) {
                all.add(date + "'T'" + time);
                all.add(date + " " + time);
            }
        }
        return all;
    }

    /** Every ordered non-empty selection of [components], joined by each separator (singles joined by none). */
    private static Set<String> permutations(List<String> components, List<String> separators) {
        Set<String> out = new LinkedHashSet<>();
        for (List<String> sequence : orderedSelections(components)) {
            if (sequence.size() == 1) {
                out.add(sequence.get(0));
                continue;
            }
            for (String separator : separators) {
                out.add(String.join(separator, sequence));
            }
        }
        return out;
    }

    private static List<List<String>> orderedSelections(List<String> components) {
        List<List<String>> out = new ArrayList<>();
        permute(components, new ArrayList<>(), out);
        return out;
    }

    private static void permute(List<String> remaining, List<String> prefix, List<List<String>> out) {
        for (int i = 0; i < remaining.size(); i++) {
            List<String> next = new ArrayList<>(prefix);
            next.add(remaining.get(i));
            out.add(next);
            List<String> rest = new ArrayList<>(remaining);
            rest.remove(i);
            permute(rest, next, out);
        }
    }

    // ── the kernel probe ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Bind a one-field model through the kernel's consistency gate, which is what decides admission —
     * {@code build()} does not, since the draft serializes any string it is handed.
     *
     * <p>A client-side refusal is deliberately NOT caught: our own builder throwing would otherwise be published
     * as a kernel verdict, which is the exact confusion "a client-side refusal needs a measured kernel basis"
     * exists to prevent. If one ever fires here it fails the run, and that is the correct outcome.
     */
    private static boolean admits(Kind kind, String format) {
        String json = DocumentModelDraft.named("vocabulary").locale("en_US")
                .rootGroup("Doc", d -> {
                    switch (kind) {
                        case DATE -> d.dateField("F", f -> f.format(format));
                        case TIME -> d.timeField("F", f -> f.format(format));
                        case DATE_TIME -> d.dateTimeField("F", f -> f.format(format));
                    }
                })
                .build().toJson();
        try {
            BoundModel.ofJson(json);
            return true;
        } catch (ModelConsistencyException e) {
            return false;
        }
    }

    private static Map<String, Verdict> partition() {
        Map<String, Verdict> out = new LinkedHashMap<>();
        for (String format : candidates()) {
            int admitted = 0;
            for (Kind kind : Kind.values()) {
                if (admits(kind, format)) {
                    admitted++;
                }
            }
            out.put(format, switch (admitted) {
                case 3 -> Verdict.ADMITTED;
                case 0 -> Verdict.REFUSED;
                default -> Verdict.SPLIT;
            });
        }
        return out;
    }

    static Set<String> admittedFormats(Map<String, Verdict> partition) {
        Set<String> out = new TreeSet<>();
        partition.forEach((format, verdict) -> {
            if (verdict == Verdict.ADMITTED) {
                out.add(format);
            }
        });
        return out;
    }

    // ── the published artifact ───────────────────────────────────────────────────────────────────────────────

    private static String render(Map<String, Verdict> partition) {
        Set<String> admitted = admittedFormats(partition);
        List<String> refused = new ArrayList<>();
        List<String> split = new ArrayList<>();
        partition.forEach((format, verdict) -> {
            if (verdict == Verdict.REFUSED) {
                refused.add(format);
            } else if (verdict == Verdict.SPLIT) {
                split.add(format);
            }
        });

        StringBuilder out = new StringBuilder();
        out.append("""
                # Temporal format vocabulary — derived from the kernel

                > **Generated, never hand-edited.** Regenerate with `./build.sh :dumpTemporalVocabulary`;
                > `TemporalFormatVocabularyGuardTest` fails the build when this file and the kernel disagree.
                >
                > **What this claims.** Over the candidate universe listed at the bottom, the kernel admits exactly
                > the formats in the first table — on all three date-bearing kinds, since the vocabulary is
                > kind-independent ([KF206](KERNEL-FINDINGS.md#kf206)). It does **not** claim the admitted set is
                > closed: a format outside the universe is undiscovered, not refused. The universe is printed
                > precisely so that limit travels with the answer.
                >
                > **Cite this file** rather than re-listing formats. A site that legitimately covers a subset says
                > which subset and why, in the guard's registry.

                """);

        out.append("## Admitted — ").append(admitted.size()).append("\n\n");
        for (String format : admitted) {
            out.append("- `").append(format).append("`\n");
        }

        out.append("\n## Refused — ").append(refused.size()).append("\n\n");
        out.append("Refused on all three kinds. Listed because without them, \"the gate reads the string\" and\n");
        out.append("\"there is no gate\" are the same observation.\n\n");
        for (String format : refused) {
            out.append("- `").append(format).append("`\n");
        }

        out.append("\n## Kind-split — ").append(split.size()).append("\n\n");
        out.append(split.isEmpty()
                ? "None. Every candidate answers identically on DATE, TIME and DATE_TIME, which is KF206's\n"
                        + "kind-independence re-derived over the whole universe rather than over its witnesses.\n"
                : "**A candidate answered differently per kind, which contradicts KF206.** Investigate before\n"
                        + "trusting any row above.\n\n");
        for (String format : split) {
            out.append("- `").append(format).append("`\n");
        }

        out.append("\n## Candidate universe — ").append(partition.size()).append("\n\n");
        out.append("Every format string put to the kernel. It is the union of the estate's existing probes and\n");
        out.append("every ordered selection of the date and time components at each separator — the axis KF196\n");
        out.append("showed decides admission. **This list is the denominator of every count above.**\n\n");
        for (String format : partition.keySet()) {
            out.append("- `").append(format).append("`\n");
        }
        return out.toString();
    }

    // ── the guards ───────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The positive control for every other arm here.
     *
     * <p>Three of them are satisfied by an EMPTY probe — no missing format, no split, no lost round trip — so
     * without this a broken candidate generator or a probe that threw its way to nothing would read as green
     * across the class. The numbers are deliberate floors well under the live counts (113 candidates, 12
     * admitted, 101 refused at 2026-08-29): they answer "did the probe happen and does it discriminate", not
     * "how big is the set", and a floor that tracked the live count would need editing whenever the kernel moved.
     * Both verdicts must occur, because a probe that admitted everything would satisfy the containment arm too.
     */
    @Test
    void theProbeRanAndDiscriminates() {
        Map<String, Verdict> partition = partition();
        assertTrue(partition.size() >= 100, () -> "the candidate universe collapsed: " + partition.size());
        assertTrue(admittedFormats(partition).size() >= 10,
                () -> "too few admissions to be a real probe: " + admittedFormats(partition));
        assertTrue(partition.containsValue(Verdict.REFUSED),
                "nothing was refused, so this measures acceptance of anything rather than a vocabulary");
    }

    @Test
    void theCommittedVocabularyMatchesWhatTheKernelAdmits() throws IOException {
        Map<String, Verdict> partition = partition();
        String rendered = render(partition);

        if (Boolean.getBoolean("temporalVocabulary.fix")) {
            Files.writeString(ARTIFACT, rendered);
            return;
        }

        assertTrue(Files.exists(ARTIFACT), () -> ARTIFACT + " is missing — run ./build.sh :dumpTemporalVocabulary");
        assertEquals(rendered, Files.readString(ARTIFACT),
                "the committed vocabulary no longer matches the kernel. If a kernel upgrade moved it, "
                        + "regenerate with ./build.sh :dumpTemporalVocabulary and read the diff — a format "
                        + "entering or leaving this set is a finding, not a refresh");
    }

    /**
     * Every admitted format reaches the kernel as the string this file names it by.
     *
     * <p>Without this the whole artifact could be measuring a normalization: a draft builder that rewrote
     * {@code ddMM} into something else would report an admission for a format the kernel never saw, and the
     * published set would be fiction assembled by our own serializer. It is asserted for every admitted format
     * rather than sampled, because the surprising members are exactly the ones a sampler would omit — the three
     * separator-free forms this guard turned up on its first run were named nowhere in the estate.
     */
    @Test
    void everyAdmittedFormatSurvivesToTheKernelUnnormalized() {
        List<String> lost = new ArrayList<>();
        for (String format : admittedFormats(partition())) {
            String json = DocumentModelDraft.named("vocabulary").locale("en_US")
                    .rootGroup("Doc", d -> d.dateField("F", f -> f.format(format)))
                    .build().toJson();
            if (!json.contains("\"" + format + "\"")) {
                lost.add(format + " → " + json);
            }
        }
        assertEquals(List.of(), lost, "an admitted format must be carried verbatim, or the verdict is not about it");
    }

    /**
     * A site that claims to enumerate the whole admitted vocabulary, and the reason it makes that claim.
     *
     * <p>Only sites claiming COMPLETENESS belong here. A site that deliberately covers a subset — the
     * incomplete-format whitelist, a witness table — is not registered, because forcing it to name every format
     * would destroy the thing it is testing. That asymmetry is the point: completeness is the claim that goes
     * wrong silently, and a subset that says which subset is already honest.
     */
    private static final Map<String, String> COMPLETENESS_CLAIMANTS = Map.of(
            "adapter/src/test/kotlin/io/github/mbackschat/a12/dm/adapter/laws/"
                    + "TemporalDeclaredFormatGateDiffTest.kt",
            "its VOCABULARY grid asserts the kind does not move the classifier FOR EVERY admitted format, so a "
                    + "missing one silently narrows a published kernel claim",
            "interpreter/src/commonTest/kotlin/io/github/mbackschat/a12/dm/interpreter/"
                    + "TemporalDeclaredFormatFormalTest.kt",
            "the multiplatform half of the same grid, whose KDoc states the cell count as a denominator");

    /**
     * Every claimant names every admitted format.
     *
     * <p><b>Containment, deliberately, not parsing.</b> The guard asks whether the file mentions each admitted
     * format as a quoted literal — it does not try to understand the list. Parsing two languages' collection
     * literals would be brittle in exactly the direction that matters (a guard that fails on formatting gets
     * deleted), and containment is precise for the one failure mode with a record: a set that is SHORT. Its
     * stated limit is that it cannot see an extra or unreached entry; the kernel probe above covers extras,
     * because a format the kernel refuses never enters the admitted set to be claimed.
     */
    @Test
    void everySiteClaimingTheWholeVocabularyNamesEveryAdmittedFormat() throws IOException {
        Set<String> admitted = admittedFormats(partition());
        Map<String, List<String>> missing = new LinkedHashMap<>();
        for (Map.Entry<String, String> claimant : COMPLETENESS_CLAIMANTS.entrySet()) {
            Path source = Path.of(claimant.getKey());
            assertTrue(Files.exists(source),
                    () -> "a registered claimant no longer exists, so its entry is dead weight: " + source
                            + " — delete the row or repoint it. Reason on file: " + claimant.getValue());
            String text = Files.readString(source);
            List<String> absent = admitted.stream().filter(f -> !text.contains("\"" + f + "\"")).toList();
            if (!absent.isEmpty()) {
                missing.put(claimant.getKey(), absent);
            }
        }
        assertEquals(Map.of(), missing,
                "these sites claim to cover the whole admitted vocabulary and do not. Add the formats, or — if "
                        + "the site should no longer claim completeness — remove it from COMPLETENESS_CLAIMANTS "
                        + "with its reason. The admitted set is in " + ARTIFACT);
    }

    /**
     * A candidate that answers differently per kind would falsify KF206's kind-independence, so it is asserted
     * here rather than merely rendered: the artifact is read by consumers that key on one verdict per format.
     */
    @Test
    void noCandidateAnswersDifferentlyPerKind() {
        Map<String, Verdict> split = new LinkedHashMap<>();
        partition().forEach((format, verdict) -> {
            if (verdict == Verdict.SPLIT) {
                split.put(format, verdict);
            }
        });
        assertEquals(Map.of(), split,
                "KF206 says the temporal format gate reads the string, not the kind — a split contradicts it");
    }
}
