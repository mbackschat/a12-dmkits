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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <p><b>The third and fourth, both found by widening this artifact rather than by any consumer noticing
 * (2026-09-05).</b> Deriving the field vocabulary turned up {@code MMdd}, {@code ddMM} and {@code yyyyMMdd},
 * which no file in the estate had ever named — so the three shipped schema descriptions and the diagnostic
 * enricher were all quantifying the set ("Seven DATE-SHAPED patterns are admitted") three members short. Then
 * probing DATEFRAGMENT as its own domain found the opposite register: every surface named FOUR fragment
 * formats where the kernel admits all twelve, {@link #PROSE_CLAIMANTS} included, and {@link
 * #COMPLETENESS_CLAIMANTS} could not have caught either, because the guidance a consumer reads is prose and
 * has no delimitable enumeration to check.
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
            "yyyy-MM-dd HH:mm:ss", "dd.MM.yyyy HH:mm:ss", "yyyy-MM-dd HH:mm",
            // Deliberate probes of what the generator below structurally CANNOT reach, so the shape of the
            // limit is measured rather than reasoned about: a mixed separator within one string, a component
            // width the pools do not name, and instant spellings beyond the two joins. A cold review ran these
            // as a one-off and found none admitted (2026-08-29); they are kept here so that stays a published
            // verdict re-derived on every run rather than a claim resting on a probe nobody repeats.
            "yyyyMM-dd", "yyyy-MMdd", "yyyyMM.dd",
            "yy", "yyMM", "MMyy", "ddMMyy", "dd.MM.yy", "M", "d", "MMM", "MMMM",
            "yyyyMMddHHmmss", "yyyy-MM-ddHH:mm:ss", "yyyyMMdd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyyMMdd'T'HHmmss", "yyyyMMdd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ssZ", "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy/MM/dd HH:mm:ss", "MM/dd/yyyy HH:mm:ss",
            // The quoted-literal axis, which only the `'T'` and `'Z'` spellings above reach: a doubled `''` in
            // every position, and a quoted literal other than the ISO `'T'`. Added 2026-10-26 because the
            // interpreter's format reader treats `''` as empty text where SimpleDateFormat reads a quote, and
            // that is harmless only while no admitted format carries one.
            "yyyy-MM-dd''HH:mm:ss", "''yyyy-MM-dd", "yyyy-MM-dd'T'HH:mm:ss''", "yyyy''MM''dd", "HH''mm''ss",
            "yyyy-MM-dd'T''HH:mm:ss", "yyyy-MM-dd'X'HH:mm:ss", "yyyy-MM-dd' 'HH:mm:ss", "'T'HH:mm:ss");

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
        all.addAll(trailing(permutations(DATE_COMPONENTS, DATE_SEPARATORS), DATE_SEPARATORS));
        all.addAll(trailing(permutations(TIME_COMPONENTS, TIME_SEPARATORS), TIME_SEPARATORS));
        for (String date : List.of("yyyy-MM-dd", "dd.MM.yyyy")) {
            for (String time : List.of("HH:mm:ss", "HH:mm")) {
                all.add(date + "'T'" + time);
                all.add(date + " " + time);
            }
        }
        all.addAll(caseVariants(all));
        return all;
    }

    /**
     * The CASE axis, which every generator above holds fixed: components and separators vary, letter case never
     * does. That is why eleven published schema descriptions and the diagnostic enricher could all say a format
     * is "a LOWERCASE Java pattern" and that the editor's UPPERCASE labels are invalid, with neither claim
     * probed — a description asserting a rule no candidate exercises is the same defect as a short allowlist,
     * one layer up. Reported by a12-kernel-lean 2026-09-05, who read the description literally, wrote
     * {@code yyyy-mm-dd} and lost a round to it.
     *
     * <p><b>Both spellings of every candidate, not a hand-picked pair.</b> The lowercase reading is not merely
     * miscased — in a Java pattern {@code mm} is minutes where {@code MM} is months, so {@code yyyy-mm-dd} is a
     * different pattern rather than a sloppy one, and only the kernel can say which of the two the gate rejects
     * it for. Generating the axis rather than naming two members follows this file's own rule: a hand-written
     * list cannot miss what it does not think of.
     */
    private static Set<String> caseVariants(Set<String> base) {
        Set<String> variants = new LinkedHashSet<>();
        for (String format : base) {
            variants.add(format.toLowerCase(Locale.ROOT));
            variants.add(format.toUpperCase(Locale.ROOT));
        }
        variants.removeAll(base);
        return variants;
    }

    /**
     * The TRAILING-separator axis, which every generator above holds fixed by joining components and stopping.
     *
     * <p>It is here because a witness turned up outside the universe: {@code dd.MM.} — the German short date
     * without a year — is ADMITTED as a {@code formatOfFragment}, measured 2026-09-13 while checking what
     * member a {@code DateFragmentType} requires ({@link FieldTypeBodyMemberLawsTest}). No permutation can
     * produce it, so the doc's admitted count was short by whatever this axis holds, and its "outside the
     * universe is undiscovered, not refused" caveat had no witness. Generated rather than adding the one
     * member, per this file's own rule: a hand-written list cannot miss what it does not think of.
     */
    private static Set<String> trailing(Set<String> formats, List<String> separators) {
        Set<String> out = new LinkedHashSet<>();
        for (String format : formats) {
            for (String separator : separators) {
                if (!separator.isEmpty()) {
                    out.add(format + separator);
                }
            }
        }
        return out;
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

    // ── the second derived domain: the DATERANGE (format, separator) pair ────────────────────────────────────

    /**
     * The separators put to the kernel beside every candidate format. Four of these are the axis the existing
     * finding measured; the rest are here because a separator set is a denominator too, and this one had never
     * been widened.
     */
    private static final List<String> RANGE_SEPARATORS = List.of("/", "-", ".", "", " - ", ":", "_");

    /**
     * A DATERANGE declares a {@code format} and a {@code separator}, and the kernel allowlists the PAIR rather
     * than each half — so this is its own domain, not a projection of the field-format vocabulary. The two
     * axes genuinely differ in both directions: {@code dd.MM} is refused as a field format and admitted as a
     * range format, while {@code yyyyMM} is admitted as a field format and refused as a range one at every
     * separator.
     */
    private static boolean admitsRange(String format, String separator) {
        String json = DocumentModelDraft.named("vocabulary").locale("en_US")
                .rootGroup("Doc", d -> d.dateRangeField("F", r -> r.format(format).separator(separator)))
                .build().toJson();
        try {
            BoundModel.ofJson(json);
            return true;
        } catch (ModelConsistencyException e) {
            return false;
        }
    }

    /** Admitted {@code (format, separator)} pairs, rendered {@code format + "|" + separator}. */
    static Set<String> admittedRangePairs() {
        Set<String> out = new TreeSet<>();
        for (String format : candidates()) {
            for (String separator : RANGE_SEPARATORS) {
                if (admitsRange(format, separator)) {
                    out.add(format + "|" + separator);
                }
            }
        }
        return out;
    }

    // ── the third derived domain: the DATEFRAGMENT format ───────────────────────────────────────────────────

    /**
     * A DATEFRAGMENT declares its format under its own key ({@code formatOfFragment}) and is the one
     * date-bearing kind {@link Kind} does not cover, so its vocabulary is a separate domain rather than a
     * projection of the shared one — exactly like the DATERANGE pair above.
     *
     * <p><b>Why it is measured rather than reasoned from KF206.</b> Kind-independence was established over
     * DATE / TIME / DATE_TIME; a fragment is by definition a date that is NOT a full calendar date, so it is
     * the one kind where a narrower set is plausible. Extending it by analogy would be a scope transfer onto
     * an unmeasured carrier, and the estate's shipped guidance already carried a four-member fragment list
     * whose denominator nobody had measured.
     */
    private static boolean admitsFragment(String format) {
        String json = DocumentModelDraft.named("vocabulary").locale("en_US")
                .rootGroup("Doc", d -> d.dateFragmentField("F", f -> f.fragmentFormat(format)))
                .build().toJson();
        try {
            BoundModel.ofJson(json);
            return true;
        } catch (ModelConsistencyException e) {
            return false;
        }
    }

    static Set<String> admittedFragmentFormats() {
        Set<String> out = new TreeSet<>();
        for (String format : candidates()) {
            if (admitsFragment(format)) {
                out.add(format);
            }
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

        Set<String> pairs = admittedRangePairs();
        out.append("\n## DATERANGE `(format, separator)` pairs admitted — ").append(pairs.size()).append("\n\n");
        out.append("A DATERANGE allowlists the PAIR, so this is a separate domain from the table above and not a\n");
        out.append("projection of it — the two differ in both directions. Probed over the same candidate formats\n");
        out.append("× the separators `/` `-` `.` `` `` ` - ` `:` `_`.\n\n");
        out.append("| format | separator |\n|---|---|\n");
        for (String pair : pairs) {
            String[] halves = pair.split("\\|", -1);
            out.append("| `").append(halves[0]).append("` | ")
                    .append(halves[1].isEmpty() ? "*(empty)*" : "`" + halves[1] + "`").append(" |\n");
        }

        Set<String> fragments = admittedFragmentFormats();
        out.append("\n## DATEFRAGMENT formats admitted — ").append(fragments.size()).append("\n\n");
        out.append("A DATEFRAGMENT declares `formatOfFragment` and is the one date-bearing kind the table at the\n");
        out.append("top does not cover, so it is measured separately rather than inherited from KF206's\n");
        out.append("kind-independence — a fragment is by definition a date that is not a full calendar date, which\n");
        out.append("makes it the kind where a narrower set is plausible. Probed over the same candidate formats.\n\n");
        for (String format : fragments) {
            out.append("- `").append(format).append("`\n");
        }

        out.append("\n## Candidate universe — ").append(partition.size()).append("\n\n");
        out.append("Every format string put to the kernel, and **the denominator of every count above.** It is the\n");
        out.append("union of the estate's existing probes with a generated set: each ordered selection of the date\n");
        out.append("components `yyyy`/`MM`/`dd` joined by one of `` `` `-` `.` `/`, each ordered selection of the\n");
        out.append("time components `HH`/`mm`/`ss` joined by one of `` `` `:` `-` `.`, and the two complete date\n");
        out.append("forms joined to two clock forms by `'T'` or a space. Separators are generated because that is\n");
        out.append("the axis KF196 showed decides admission.\n\n");
        out.append("What it therefore does **not** reach, so a reader can judge the limit rather than infer it:\n");
        out.append("a string mixing the two component pools, a mixed separator within one string, a component\n");
        out.append("width this list does not name (`yy`, `M`, `MMM`), and any literal beyond `'T'`.\n\n");
        for (String format : partition.keySet()) {
            out.append("- `").append(format).append("`\n");
        }
        return out.toString();
    }

    // ── the guards ───────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The positive control for every other arm here.
     *
     * <p><b>What it actually protects, which is not what it looks like.</b> Three arms here are satisfied by an
     * EMPTY probe — no missing format, no split, no lost round trip — but the class would still fail, because the
     * artifact comparison would find a rendered set of zero against a committed twelve. The real exposure is the
     * REGENERATOR: {@code dumpTemporalVocabulary} runs this same class with the fixer flag, and there the
     * comparison does not run at all. A broken candidate generator would quietly rewrite the artifact to whatever
     * it produced, and every consumer would then be checked against that. These floors are the only thing
     * standing between a degraded probe and a codified one.
     *
     * <p>The numbers are deliberate floors well under the live counts (375 candidates, 12 admitted, 363 refused
     * at 2026-09-05, after the case axis landed): they answer "did the probe happen and does it discriminate",
     * not "how big is the set", and
     * a floor that tracked the live count would need editing whenever the kernel moved. Both verdicts must occur,
     * because a probe that admitted everything — a degraded backend, say — would satisfy the containment arm too.
     */
    @Test
    void theProbeRanAndDiscriminates() {
        Map<String, Verdict> partition = partition();
        assertTrue(partition.size() >= 100, () -> "the candidate universe collapsed: " + partition.size());
        assertTrue(admittedFormats(partition).size() >= 10,
                () -> "too few admissions to be a real probe: " + admittedFormats(partition));
        assertTrue(partition.containsValue(Verdict.REFUSED),
                "nothing was refused, so this measures acceptance of anything rather than a vocabulary");

        // The DATERANGE probe is a second backend call with its own failure mode: if `admitsRange` returned
        // false throughout, the pair table would render empty and the regenerator would codify that silently.
        Set<String> pairs = admittedRangePairs();
        assertTrue(pairs.size() >= 5 && pairs.size() < RANGE_SEPARATORS.size() * partition.size(),
                () -> "the range-pair probe did not discriminate: " + pairs.size() + " pairs");

        // Third backend call, same failure mode: an all-false `admitsFragment` would render an empty section
        // and the regenerator would codify it. Both verdicts must occur for the section to mean anything.
        Set<String> fragments = admittedFragmentFormats();
        assertTrue(!fragments.isEmpty() && fragments.size() < partition.size(),
                () -> "the fragment probe did not discriminate: " + fragments.size() + " admitted");
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
     *
     * <p><b>All three kinds, because they are three serializers, not one.</b> {@code DocumentModelDraft} writes
     * the {@code format} property from a separate site per temporal kind, each with its own default, so checking
     * DATE alone would leave a selective rewrite on TIME or DATE_TIME undetected while a verdict needs all three
     * kinds to admit. The value is matched as the {@code format} property rather than as a bare substring, so a
     * coincidental occurrence elsewhere in the model JSON cannot satisfy it.
     */
    @Test
    void everyAdmittedFormatSurvivesToTheKernelUnnormalized() {
        List<String> lost = new ArrayList<>();
        for (String format : admittedFormats(partition())) {
            for (Kind kind : Kind.values()) {
                String json = DocumentModelDraft.named("vocabulary").locale("en_US")
                        .rootGroup("Doc", d -> {
                            switch (kind) {
                                case DATE -> d.dateField("F", f -> f.format(format));
                                case TIME -> d.timeField("F", f -> f.format(format));
                                case DATE_TIME -> d.dateTimeField("F", f -> f.format(format));
                            }
                        })
                        .build().toJson();
                if (!json.replace(" ", "").contains("\"format\":\"" + format + "\"")) {
                    lost.add(kind + " " + format + " → " + json);
                }
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
            "the multiplatform half of the same grid, whose KDoc states the cell count as a denominator",
            "src/test/java/io/github/mbackschat/a12/dm/rulekit/validate/laws/"
                    + "TemporalDeclarationConfigAdmissionLawsTest.java",
            "its DateType-config grids are the measured BASIS a client-side refusal may key on, and both "
                    + "gates read the format's components — so a short axis would narrow that basis silently, "
                    + "in the one direction where over-rejection makes a legal model unauthorable",
            "src/test/java/io/github/mbackschat/a12/dm/rulekit/validate/laws/"
                    + "TemporalAdditionalCheckCarrierLawsTest.java",
            "its FRAGMENT grid carries the same year gate on a second carrier, and the claim that the two "
                    + "carriers AGREE is only as strong as its axis — a short one would make the agreement a "
                    + "coincidence of the formats that happened to be listed",
            "consistency/src/commonMain/kotlin/io/github/mbackschat/a12/dm/consistency/TemporalConfig.kt",
            "the portable checker PARTITIONS the admitted vocabulary into year-bearing and year-free halves "
                    + "and judges nothing outside their union, so a format missing from the grid is one the "
                    + "checker goes quiet on — the shipped half of the ddMM./KF313 incident, in a module that "
                    + "cannot see the kernel and so has no other way to notice");

    private static final String REGION_START = "VOCABULARY-GRID-START";
    private static final String REGION_END = "VOCABULARY-GRID-END";

    /**
     * The claimant's enumeration itself, with commented-out lines removed.
     *
     * <p><b>Why a marked region rather than the whole file.</b> Scanning the file was the first design and it was
     * unsound in the direction that matters: a claimant mentions formats outside its grid — a {@code const val
     * ISO}, a control field on a different model, a KDoc example — so a format DELETED from the grid still
     * appeared elsewhere and the check passed. Measured on this very slice: dropping the {@code HH:mm:ss} row
     * from one claimant, and commenting out the {@code ddMM} row in the other, both survived. Nine of twelve
     * formats were protected in one claimant and six of twelve in the other, so the arm was mostly decorative.
     * Delimiting the region binds the check to the thing the claim is about, and dropping {@code //} lines closes
     * the comment-out variant, without parsing either language's collection literals.
     */
    private static String enumerationRegion(Path source, String text) {
        return enumerationRegion(source, text, REGION_START, REGION_END);
    }

    private static String enumerationRegion(Path source, String text, String regionStart, String regionEnd) {
        int start = text.indexOf(regionStart);
        // Searched AFTER the opening marker: the first version scanned from position zero, so a start-marker
        // comment that merely NAMED the closing one ended the region before the list began, and every format
        // read as missing. Written down because the failure looked like a broken claimant, not a broken guard.
        int end = start < 0 ? -1 : text.indexOf(regionEnd, start + regionStart.length());
        assertTrue(start >= 0 && end > start,
                () -> "a registered claimant must delimit its enumeration with " + regionStart + " … "
                        + regionEnd + " comments so the check binds the grid rather than the file: " + source);
        return text.substring(start, end).lines()
                .filter(line -> !line.strip().startsWith("//"))
                .reduce("", (a, b) -> a + "\n" + b);
    }

    /**
     * Every claimant's enumeration names every admitted format.
     *
     * <p><b>Containment, deliberately, not parsing.</b> Within the marked region the guard asks whether each
     * admitted format appears as a quoted literal; it does not try to understand the list. Parsing two languages'
     * collection literals would be brittle in exactly the direction that matters — a guard that fails on
     * formatting gets deleted — and containment is precise for the one failure mode with a record: a set that is
     * SHORT. Its remaining limit is that it cannot see an entry that is present but unreached by any assertion;
     * extras need no check, because a format the kernel refuses never enters the admitted set to be claimed.
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
            String region = enumerationRegion(source, Files.readString(source));
            List<String> absent = admitted.stream().filter(f -> !region.contains("\"" + f + "\"")).toList();
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
     * PROSE surfaces that enumerate the admitted vocabulary for a human or an agent, and what each is for.
     *
     * <p>Separate from {@link #COMPLETENESS_CLAIMANTS} because these carry no delimitable enumeration — the set
     * is written into an English sentence inside a JSON string or a Java text block — so the marked-region
     * mechanism cannot apply. They are guarded all the same, and for the sharper reason: a test's short list
     * costs coverage, while these four are the guidance a consumer READS, so a short list here is advice that
     * a legal declaration is illegal. Both recorded incidents landed in exactly this family.
     */
    private static final Map<String, String> PROSE_CLAIMANTS = Map.of(
            "cli/src/main/resources/schema/field-add.schema.json",
            "the published `schema field add` description quantifies the admitted set for the agent authoring a "
                    + "field, so a short list reads as a closed allowlist",
            "cli/src/main/resources/schema/field-modify.schema.json",
            "the same claim on the post-creation surface",
            "cli/src/main/resources/schema/typedef-add.schema.json",
            "the same claim on the type-definition surface",
            "rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/validate/StructuralKernelFix.java",
            "the curated corrective for the kernel's code-free format rejection — the one place a consumer who "
                    + "already got the format wrong is told which ones are right");

    /** The characters that can occur INSIDE a format token, so a match at either edge is a different token. */
    private static final String FORMAT_CHAR = "[A-Za-z0-9'.:\\-]";

    /**
     * Every prose claimant names every admitted format, matched at token boundaries.
     *
     * <p><b>Plain containment is not enough here, measured against this guard's own two incidents.</b> Seven of
     * the twelve admitted formats are substrings of another — {@code MM-dd} sits inside {@code yyyy-MM-dd},
     * {@code MMdd} inside {@code yyyyMMdd}, {@code MM} and {@code yyyy} inside almost everything — so a
     * substring check is satisfied by a list that never mentions them. That is not hypothetical: {@code MM-dd}
     * is precisely the member missing from three published schema descriptions until 2026-08-19, and a
     * substring check would have passed the whole time. Requiring a non-format character on both sides makes
     * the match about the token rather than the letters.
     */
    @Test
    void everyProseSurfaceNamesEveryAdmittedFormat() throws IOException {
        Set<String> admitted = admittedFormats(partition());
        Map<String, List<String>> missing = new LinkedHashMap<>();
        for (Map.Entry<String, String> claimant : PROSE_CLAIMANTS.entrySet()) {
            Path source = Path.of(claimant.getKey());
            assertTrue(Files.exists(source),
                    () -> "a registered prose claimant no longer exists: " + source + " — delete the row or "
                            + "repoint it. Reason on file: " + claimant.getValue());
            String text = Files.readString(source);
            List<String> absent = admitted.stream().filter(format -> !namesAsToken(text, format)).toList();
            if (!absent.isEmpty()) {
                missing.put(claimant.getKey(), absent);
            }
        }
        assertEquals(Map.of(), missing,
                "these surfaces tell a consumer which formats are admitted and omit one the kernel accepts, so "
                        + "they advertise a legal declaration as illegal. Add the formats, or remove the file "
                        + "from PROSE_CLAIMANTS with its reason. The admitted set is in " + ARTIFACT);
    }

    private static boolean namesAsToken(String text, String format) {
        return Pattern
                .compile("(?<!" + FORMAT_CHAR + ")" + Pattern.quote(format)
                        + "(?!" + FORMAT_CHAR + ")")
                .matcher(text).find();
    }

    /**
     * The equal-set, different-spelling pairs the admitted vocabulary yields, re-derived here and compared
     * against the list {@link TemporalEqualSetPairDenominatorLawsTest} sweeps.
     *
     * <p><b>Why this belongs in this file rather than in that one.</b> An equal-set pair is the only kind of row
     * that separates the component-SET gate from the format-STRING one, so its count is a denominator, and this
     * is the only class that knows which formats the kernel actually admits. Stating the count where the sweep
     * lives is exactly how it came to say "two exist": the number was read off the pairs work had used. Here it
     * cannot be, because the pairs are computed from {@link #admittedFormats(Map)} — add a thirteenth admitted
     * spelling to any family and this fails until the sweep covers it.
     *
     * <p>The component set is read off the format's letters, which is sound only because each admitted format
     * names every component it carries in exactly ONE run — so the letter set and the component set coincide.
     * That premise is <em>asserted</em> below rather than left in this paragraph, and the classifier's own
     * output is pinned for the two time-bearing formats: nothing in the admitted vocabulary pairs two formats
     * that agree on their date letters and differ on a single time letter, so the corpus alone leaves
     * {@code H}, {@code m} and {@code s} individually unconstrained (mutants measured surviving 2026-09-07).
     */
    @Test
    void theEqualSetPairDenominatorMatchesTheAdmittedVocabulary() {
        Map<String, Set<String>> byComponents = new LinkedHashMap<>();
        for (String format : admittedFormats(partition())) {
            byComponents.computeIfAbsent(componentLettersOf(format), key -> new TreeSet<>()).add(format);
        }

        Set<List<String>> derived = new LinkedHashSet<>();
        byComponents.forEach((components, spellings) -> {
            List<String> ordered = List.copyOf(spellings);
            for (int i = 0; i < ordered.size(); i++) {
                for (int j = i + 1; j < ordered.size(); j++) {
                    derived.add(List.of(ordered.get(i), ordered.get(j)));
                }
            }
        });

        Set<List<String>> swept = TemporalEqualSetPairDenominatorLawsTest.EQUAL_SET_PAIRS.stream()
                .map(pair -> List.copyOf(new TreeSet<>(pair)))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        assertEquals(derived, swept,
                "the equal-set pair denominator the kernel's admitted vocabulary yields is not the one "
                        + TemporalEqualSetPairDenominatorLawsTest.class.getSimpleName() + " sweeps. That test "
                        + "carries the only rows separating the component-set gate from the format-string one, "
                        + "so an unswept pair is a hole in that separation, not a missing example.");
    }

    /**
     * The classifier's soundness premise, and its own output where the corpus cannot discriminate.
     *
     * <p>Grouping formats by their component LETTERS is only the same thing as grouping them by component SET
     * while every admitted format names each component in one run — {@code MMdd} is {MONTH, DAY}, but a
     * hypothetical {@code MM-dd-MM} would collapse to the same letters while carrying MONTH twice. The first
     * assertion holds that premise. The second pins the two time-bearing formats' classifications, because the
     * twelve admitted formats contain no pair that agrees on date letters and differs on one time letter, so
     * dropping {@code H}, {@code m} or {@code s} from the letter set changes no grouping and no derived pair.
     */
    @Test
    void theComponentLetterClassifierIsSoundAndItsTimeLettersAreLoadBearing() {
        for (String format : admittedFormats(partition())) {
            assertEquals(componentLettersOf(format).length() / 3, componentRunsOf(format),
                    () -> "grouping by letters equals grouping by components only while each component "
                            + "appears in ONE run; " + format + " breaks that premise");
        }
        assertEquals("[H, m, s]", componentLettersOf("HH:mm:ss"),
                "the clock format's three time components, each individually load-bearing here");
        assertEquals("[H, M, d, m, s, y]", componentLettersOf("yyyy-MM-dd'T'HH:mm:ss"),
                "and the stamp's six — the quoted 'T' is erased, not read as a component");
    }

    /** How many maximal runs of a component letter {@code format} contains. */
    private static int componentRunsOf(String format) {
        int runs = 0;
        char previous = 0;
        boolean quoted = false;
        for (char c : format.toCharArray()) {
            if (c == '\'') {
                quoted = !quoted;
                previous = 0;
                continue;
            }
            boolean component = !quoted && "yMdHms".indexOf(c) >= 0;
            if (component && c != previous) {
                runs++;
            }
            previous = component ? c : 0;
        }
        return runs;
    }

    /** The format's component letters as a canonical key — {@code dd.MM.yyyy} and {@code yyyyMMdd} agree. */
    private static String componentLettersOf(String format) {
        Set<Character> letters = new TreeSet<>();
        boolean quoted = false;
        for (char c : format.toCharArray()) {
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted && "yMdHms".indexOf(c) >= 0) {
                letters.add(c);
            }
        }
        return letters.toString();
    }

    /**
     * The one consumer that carries the PAIR domain, which needs an exact re-derivation rather than
     * containment.
     *
     * <p>{@link #COMPLETENESS_CLAIMANTS} asks whether every admitted format is NAMED, which is the right
     * question for a grid that must not be short. It is the wrong question here: the portable checker reports
     * a pair outside this table as unbuildable, so a table that is short REFUSES A MODEL A12 TAKES — the one
     * direction the checker may never take — and containment cannot see an entry that is present and wrong.
     * So this arm compares entry for entry, in both directions.
     */
    private static final String RANGE_PAIR_CLAIMANT =
            "consistency/src/commonMain/kotlin/io/github/mbackschat/a12/dm/consistency/FieldTypeBinding.kt";

    private static final String RANGE_REGION_START = "RANGE-PAIR-GRID-START";
    private static final String RANGE_REGION_END = "RANGE-PAIR-GRID-END";

    /** {@code "<format>" to setOf("a", "b")} or {@code "<format>" to emptySet()}, one entry per line. */
    private static final Pattern RANGE_ENTRY = Pattern.compile(
            "\"([^\"]*)\"\\s+to\\s+(?:setOf\\((.*)\\)|emptySet\\(\\))");

    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");

    /**
     * The kernel's own answer, shaped like the claimant's table: every admitted FIELD format plus every
     * format appearing in an admitted pair, each mapped to the separators it is admitted with.
     *
     * <p><b>The empty entries are measurements, not padding.</b> A field format with no admitted pair was put
     * to the kernel at all seven probed separators and refused at every one, so the checker may report it —
     * which is exactly why it has to appear here rather than being left out as "nothing to say".
     */
    private static Map<String, Set<String>> derivedRangeTable() {
        Map<String, Set<String>> out = new TreeMap<>();
        for (String format : admittedFormats(partition())) {
            out.put(format, new TreeSet<>());
        }
        for (String pair : admittedRangePairs()) {
            int split = pair.lastIndexOf('|');
            out.computeIfAbsent(pair.substring(0, split), f -> new TreeSet<>()).add(pair.substring(split + 1));
        }
        return out;
    }

    /** The claimant's own table, read out of its delimited region. */
    private static Map<String, Set<String>> carriedRangeTable() throws IOException {
        String region = enumerationRegion(Path.of(RANGE_PAIR_CLAIMANT),
                Files.readString(Path.of(RANGE_PAIR_CLAIMANT)), RANGE_REGION_START, RANGE_REGION_END);
        Map<String, Set<String>> out = new TreeMap<>();
        Matcher entries = RANGE_ENTRY.matcher(region);
        while (entries.find()) {
            Set<String> separators = new TreeSet<>();
            if (entries.group(2) != null) {
                Matcher quoted = QUOTED.matcher(entries.group(2));
                while (quoted.find()) {
                    separators.add(quoted.group(1));
                }
            }
            out.put(entries.group(1), separators);
        }
        return out;
    }

    private static final String PROBE_REGION_START = "RANGE-PROBE-GRID-START";
    private static final String PROBE_REGION_END = "RANGE-PROBE-GRID-END";

    /**
     * The checker's probed-separator axis is this sweep's own.
     *
     * <p><b>Why the silence boundary needs a guard of its own.</b> The pair table below says which pairs are
     * admitted; the probed set says which pairs the checker is entitled to speak about at all, and the checker
     * reports exactly the difference. So narrowing this sweep's axis without narrowing the checker's leaves it
     * reporting pairs that were never put to the kernel — the same over-rejection the pair table is guarded
     * against, reached through the half nothing was reading.
     */
    @Test
    void theCheckersProbedSeparatorSetIsThisSweepsOwnAxis() throws IOException {
        String region = enumerationRegion(Path.of(RANGE_PAIR_CLAIMANT),
                Files.readString(Path.of(RANGE_PAIR_CLAIMANT)), PROBE_REGION_START, PROBE_REGION_END);
        Set<String> carried = new TreeSet<>();
        Matcher quoted = QUOTED.matcher(region);
        while (quoted.find()) {
            carried.add(quoted.group(1));
        }
        assertEquals(new TreeSet<>(RANGE_SEPARATORS), carried,
                "the portable checker probes a different separator axis from the sweep that derived its pair "
                        + "table. A separator it keeps after this sweep drops it is reported on a pair nobody "
                        + "measured — edit " + RANGE_PAIR_CLAIMANT + " between " + PROBE_REGION_START + " and "
                        + PROBE_REGION_END);
    }

    /**
     * The checker's range table is the kernel's, entry for entry.
     *
     * <p>Its failure message has to carry the remedy, because the table is not regenerable by
     * {@code dumpTemporalVocabulary}: that fixer owns the published artifact, not a consumer's source.
     */
    @Test
    void theCheckersRangePairTableAgreesWithTheKernel() throws IOException {
        assertEquals(derivedRangeTable(), carriedRangeTable(),
                "the portable checker's ADMITTED_RANGE_SEPARATORS disagrees with the kernel. It REPORTS a "
                        + "pair outside its table, so a stale entry refuses a model A12 accepts — edit "
                        + RANGE_PAIR_CLAIMANT + " between " + RANGE_REGION_START + " and " + RANGE_REGION_END
                        + " to the expected map above");
    }

    /**
     * Every claimant is a declared Gradle input of the task that hosts this guard.
     *
     * <p>The registry above and {@code build.gradle}'s {@code vocabulary consumers} list are two copies of one
     * fact, and the copy that goes stale is silent: a claimant that is registered but undeclared can be edited
     * while the lane stays UP-TO-DATE, so the containment check simply does not run. That is the same staleness
     * this guard exists to catch, one level up, which is why it is asserted rather than trusted to a comment.
     */
    @Test
    void everyClaimantIsADeclaredInputOfItsOwnGuardTask() throws IOException {
        String build = Files.readString(Path.of("build.gradle"));
        Set<String> registered = new LinkedHashSet<>(COMPLETENESS_CLAIMANTS.keySet());
        registered.addAll(PROSE_CLAIMANTS.keySet());
        registered.add(RANGE_PAIR_CLAIMANT);
        List<String> undeclared = registered.stream()
                .filter(path -> !build.contains("'" + path + "'"))
                .toList();
        assertEquals(List.of(), undeclared,
                "registered as claimants but not declared as inputs of :vocabularyGuardTest — add each to the "
                        + "'vocabulary consumers' files(...) block in build.gradle, or this guard can be left "
                        + "unrun by the up-to-date check exactly when a claimant changes");
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
