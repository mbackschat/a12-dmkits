package io.github.mbackschat.a12.dm.adapter.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ChangeKind;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.Delta;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ElementChange;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ElementKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionReferences;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

/** Conservative exact/fuzzy relocation matching, kept separate from model-diff orchestration. */
final class RelocationMatcher {

    static final double PAIR_MIN = 0.7;
    static final double PAIR_MARGIN = 0.2;
    /** Large ambiguous buckets stay remove+add instead of triggering quadratic fuzzy scoring. */
    static final int MAX_BUCKET_COMPARISONS = 4_096;
    private static final double NAME_FLOOR = 0.5;
    private static final double REF_FLOOR = 0.5;

    private RelocationMatcher() {}

    static void pair(List<ElementChange> changes, KernelAdapter base, KernelAdapter head) {
        pairExact(changes, base, head);
        pairSimilar(changes, base, head);
    }

    private static void pairExact(List<ElementChange> changes, KernelAdapter base, KernelAdapter head) {
        Map<FingerprintKey, List<ElementChange>> removed = new LinkedHashMap<>();
        Map<FingerprintKey, List<ElementChange>> added = new LinkedHashMap<>();
        for (ElementChange change : changes) {
            if (change.change() == ChangeKind.REMOVED) {
                removed.computeIfAbsent(fingerprintKey(change), ignored -> new ArrayList<>()).add(change);
            } else if (change.change() == ChangeKind.ADDED) {
                added.computeIfAbsent(fingerprintKey(change), ignored -> new ArrayList<>()).add(change);
            }
        }
        Map<ElementChange, ElementChange> replacements = new LinkedHashMap<>();
        List<ElementChange> consumed = new ArrayList<>();
        for (var entry : removed.entrySet()) {
            List<ElementChange> from = entry.getValue();
            List<ElementChange> to = added.getOrDefault(entry.getKey(), List.of());
            if (from.size() != 1 || to.size() != 1) {
                continue;
            }
            ChangeKind relocation = relocationKind(from.getFirst().id(), to.getFirst().id());
            if (relocation != null) {
                replacements.put(from.getFirst(), relocated(from.getFirst(), to.getFirst(), relocation, base, head));
                consumed.add(to.getFirst());
            }
        }
        apply(changes, replacements, consumed);
    }

    private static void pairSimilar(List<ElementChange> changes, KernelAdapter base, KernelAdapter head) {
        List<ElementChange> removed = changes.stream()
                .filter(change -> change.change() == ChangeKind.REMOVED).toList();
        List<ElementChange> added = changes.stream()
                .filter(change -> change.change() == ChangeKind.ADDED).toList();
        if (removed.isEmpty() || added.isEmpty()) {
            return;
        }
        Map<ElementChange, List<ScoredCandidate>> byRemoved = new LinkedHashMap<>();
        Map<ElementChange, List<ScoredCandidate>> byAdded = new LinkedHashMap<>();
        Map<ElementChange, Set<String>> references = new LinkedHashMap<>();
        for (Candidate candidate : candidates(removed, added)) {
            ElementChange from = candidate.removed();
            ElementChange to = candidate.added();
            Set<String> fromRefs = references.computeIfAbsent(from,
                    ignored -> from.element() == ElementKind.RULE ? refsOf(base, from.id()) : Set.of());
            Set<String> toRefs = references.computeIfAbsent(to,
                    ignored -> to.element() == ElementKind.RULE ? refsOf(head, to.id()) : Set.of());
            double score = similarityScore(from.element(), fingerprint(from), fingerprint(to), fromRefs, toRefs,
                    leafOf(from.id()), leafOf(to.id()));
            var scored = new ScoredCandidate(candidate, score);
            byRemoved.computeIfAbsent(from, ignored -> new ArrayList<>()).add(scored);
            byAdded.computeIfAbsent(to, ignored -> new ArrayList<>()).add(scored);
        }
        Map<ElementChange, ElementChange> replacements = new LinkedHashMap<>();
        List<ElementChange> consumed = new ArrayList<>();
        for (ElementChange from : removed) {
            BestCandidate best = best(byRemoved.getOrDefault(from, List.of()));
            if (best == null || best.score() < PAIR_MIN || best.margin() < PAIR_MARGIN) {
                continue;
            }
            ElementChange to = best.candidate().added();
            BestCandidate reverse = best(byAdded.getOrDefault(to, List.of()));
            if (reverse == null || !reverse.candidate().removed().equals(from)
                    || reverse.margin() < PAIR_MARGIN) {
                continue;
            }
            ChangeKind kind = ModelDiff.parentOf(from.id()).equals(ModelDiff.parentOf(to.id()))
                    ? ChangeKind.RENAMED : ChangeKind.MOVED;
            replacements.put(from, relocated(from, to, kind, base, head));
            consumed.add(to);
        }
        apply(changes, replacements, consumed);
    }

    private static void apply(List<ElementChange> changes,
            Map<ElementChange, ElementChange> replacements, List<ElementChange> consumed) {
        for (int i = 0; i < changes.size(); i++) {
            ElementChange replacement = replacements.get(changes.get(i));
            if (replacement != null) {
                changes.set(i, replacement);
            }
        }
        changes.removeAll(consumed);
    }

    private static ElementChange relocated(ElementChange removed, ElementChange added, ChangeKind kind,
            KernelAdapter base, KernelAdapter head) {
        List<Delta> contentDeltas = ModelDiff.aspectDeltas(fingerprint(removed), fingerprint(added));
        List<Delta> deltas = new ArrayList<>();
        deltas.add(new Delta("path", removed.id(), added.id()));
        deltas.addAll(contentDeltas);
        List<String> reasons = new ArrayList<>();
        reasons.add(kind == ChangeKind.RENAMED ? "ELEMENT_RENAMED" : "ELEMENT_MOVED");
        for (Delta delta : contentDeltas) {
            reasons.add(contentReason(removed.element(), removed.id(), added.id(), delta, base, head));
        }
        List<String> distinctReasons = ModelDiff.distinctReasons(reasons);
        return new ElementChange(removed.element(), removed.id(), kind, ModelDiff.riskFor(distinctReasons),
                distinctReasons, List.copyOf(deltas));
    }

    private static String contentReason(ElementKind element, String oldId, String newId, Delta delta,
            KernelAdapter base, KernelAdapter head) {
        return switch (element) {
            case FIELD -> FieldDiffProjector.modifiedReason(delta);
            case GROUP -> GroupDiffProjector.modifiedReason(delta);
            case RULE -> delta.aspect().equals("condition")
                    ? ModelDiff.conditionReason(oldId, newId, base, head) : ModelDiff.modifiedReason(delta);
            case COMPUTATION -> ComputationDiffProjector.modifiedReason(delta);
            case TYPEDEF -> TypeDefinitionDiffProjector.modifiedReason(delta);
            case INCLUDE -> IncludeDiffProjector.modifiedReason(delta);
            case CONFIG -> ConfigurationDiffProjector.modifiedReason(delta);
        };
    }

    static Set<String> refsOf(KernelAdapter adapter, String ruleName) {
        return adapter.read(ruleName) instanceof ReadResult.Converted converted
                ? ConditionReferences.of(converted.condition()).fieldPaths()
                : Set.of();
    }

    static Set<String> refsOf(KernelAdapter adapter, ElementKind element, String name) {
        return switch (element) {
            case RULE -> refsOf(adapter, name);
            case COMPUTATION -> ComputationDiffProjector.references(adapter.readComputation(name));
            case FIELD, GROUP, TYPEDEF, INCLUDE, CONFIG -> Set.of();
        };
    }

    static String pathAfter(ElementChange relocation) {
        return relocation.deltas().stream().filter(delta -> delta.aspect().equals("path"))
                .map(Delta::after).findFirst().orElse(null);
    }

    static double similarityScore(ElementKind kind, Map<String, String> removed, Map<String, String> added,
            Set<String> removedRefs, Set<String> addedRefs, String removedLeaf, String addedLeaf) {
        double nameSimilarity = nameSimilarity(removedLeaf, addedLeaf);
        double fingerprintSimilarity = jaccard(entries(removed), entries(added));
        if (kind == ElementKind.RULE) {
            double referenceSimilarity = jaccard(removedRefs, addedRefs);
            if (nameSimilarity < NAME_FLOOR && referenceSimilarity < REF_FLOOR) {
                return 0.0;
            }
            return 0.5 * referenceSimilarity + 0.3 * fingerprintSimilarity + 0.2 * nameSimilarity;
        }
        if (nameSimilarity < NAME_FLOOR) {
            return 0.0;
        }
        return 0.8 * fingerprintSimilarity + 0.2 * nameSimilarity;
    }

    private static BestCandidate best(List<ScoredCandidate> candidates) {
        ScoredCandidate best = null;
        double second = Double.NEGATIVE_INFINITY;
        for (ScoredCandidate candidate : candidates) {
            if (best == null || candidate.score() > best.score()) {
                second = best == null ? second : best.score();
                best = candidate;
            } else if (candidate.score() > second) {
                second = candidate.score();
            }
        }
        return best == null ? null : new BestCandidate(best.candidate(), best.score(), best.score() - second);
    }

    private static Set<Map.Entry<String, String>> entries(Map<String, String> fingerprint) {
        return new LinkedHashSet<>(fingerprint.entrySet());
    }

    private static <T> double jaccard(Set<T> left, Set<T> right) {
        if (left.isEmpty() && right.isEmpty()) {
            return 1.0;
        }
        Set<T> intersection = new LinkedHashSet<>(left);
        intersection.retainAll(right);
        Set<T> union = new LinkedHashSet<>(left);
        union.addAll(right);
        return (double) intersection.size() / union.size();
    }

    private static double nameSimilarity(String left, String right) {
        int max = Math.max(left.length(), right.length());
        return max == 0 ? 1.0 : 1.0 - (double) levenshtein(left, right) / max;
    }

    private static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            previous = current;
        }
        return previous[right.length()];
    }

    private static FingerprintKey fingerprintKey(ElementChange change) {
        return new FingerprintKey(change.element(), fingerprint(change));
    }

    private static Map<String, String> fingerprint(ElementChange change) {
        Map<String, String> fingerprint = new LinkedHashMap<>();
        for (Delta delta : change.deltas()) {
            if (!delta.aspect().equals("path")) {
                fingerprint.put(delta.aspect(),
                        change.change() == ChangeKind.REMOVED ? delta.before() : delta.after());
            }
        }
        return fingerprint;
    }

    private static ChangeKind relocationKind(String base, String head) {
        boolean sameParent = ModelDiff.parentOf(base).equals(ModelDiff.parentOf(head));
        boolean sameLeaf = leafOf(base).equals(leafOf(head));
        if (sameParent && !sameLeaf) {
            return ChangeKind.RENAMED;
        }
        if (!sameParent && sameLeaf) {
            return ChangeKind.MOVED;
        }
        return null;
    }

    record Candidate(ElementChange removed, ElementChange added) {}

    /**
     * Sparse fuzzy candidates. A supported element can be renamed in place (same parent) or moved without a
     * rename (same leaf). Oversized locality buckets are conservatively excluded in full: retaining candidates
     * from another bucket could fabricate a winner while an unscored candidate was actually better.
     */
    static List<Candidate> candidates(List<ElementChange> removed, List<ElementChange> added) {
        var candidates = new LinkedHashSet<Candidate>();
        var blockedRemoved = new LinkedHashSet<ElementChange>();
        var blockedAdded = new LinkedHashSet<ElementChange>();
        addBucketCandidates(removed, added, ModelDiff::parentOf,
                candidates, blockedRemoved, blockedAdded);
        addBucketCandidates(removed, added, RelocationMatcher::leafOf,
                candidates, blockedRemoved, blockedAdded);
        candidates.removeIf(candidate -> blockedRemoved.contains(candidate.removed())
                || blockedAdded.contains(candidate.added()));
        return List.copyOf(candidates);
    }

    private static void addBucketCandidates(List<ElementChange> removed, List<ElementChange> added,
            Function<String, String> locality, Set<Candidate> candidates,
            Set<ElementChange> blockedRemoved, Set<ElementChange> blockedAdded) {
        Map<Bucket, List<ElementChange>> removedByBucket = buckets(removed, locality);
        Map<Bucket, List<ElementChange>> addedByBucket = buckets(added, locality);
        for (var entry : removedByBucket.entrySet()) {
            List<ElementChange> from = entry.getValue();
            List<ElementChange> to = addedByBucket.getOrDefault(entry.getKey(), List.of());
            if ((long) from.size() * to.size() > MAX_BUCKET_COMPARISONS) {
                blockedRemoved.addAll(from);
                blockedAdded.addAll(to);
                continue;
            }
            for (ElementChange fromChange : from) {
                for (ElementChange toChange : to) {
                    candidates.add(new Candidate(fromChange, toChange));
                }
            }
        }
    }

    private static Map<Bucket, List<ElementChange>> buckets(
            List<ElementChange> changes, Function<String, String> locality) {
        Map<Bucket, List<ElementChange>> buckets = new LinkedHashMap<>();
        for (ElementChange change : changes) {
            if (supports(change.element())) {
                var bucket = new Bucket(change.element(), locality.apply(change.id()));
                buckets.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(change);
            }
        }
        return buckets;
    }

    private static boolean supports(ElementKind element) {
        return element == ElementKind.FIELD || element == ElementKind.RULE;
    }

    private static String leafOf(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private record FingerprintKey(ElementKind kind, Map<String, String> fingerprint) {}

    private record ScoredCandidate(Candidate candidate, double score) {}

    private record BestCandidate(Candidate candidate, double score, double margin) {}

    private record Bucket(ElementKind kind, String locality) {}
}
