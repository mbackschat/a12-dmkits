package io.github.mbackschat.a12.dm.adapter.review;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ChangeKind;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.Delta;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ElementChange;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ElementKind;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.Risk;

/** Locks the sparse, conservative candidate boundary before fuzzy relocation scoring. */
class RelocationMatcherCandidateTest {

    @Test
    void oversizedSiblingBatchIsRefusedBeforeQuadraticScoring() {
        var removed = new ArrayList<ElementChange>();
        var added = new ArrayList<ElementChange>();
        for (int i = 0; i < 100; i++) {
            removed.add(field("/Order/Legacy" + i, ChangeKind.REMOVED));
            added.add(field("/Order/Modern" + i, ChangeKind.ADDED));
        }

        assertEquals(List.of(), RelocationMatcher.candidates(removed, added),
                "an oversized ambiguous sibling bucket must stay remove+add without scoring its Cartesian product");
    }

    @Test
    void onlyRenameOrMoveLocalityProducesCandidates() {
        var removed = List.of(
                field("/Order/Fee", ChangeKind.REMOVED),
                field("/Order/Code", ChangeKind.REMOVED),
                field("/Archive/Orphan", ChangeKind.REMOVED));
        var added = List.of(
                field("/Order/Limit", ChangeKind.ADDED),
                field("/Archive/Code", ChangeKind.ADDED),
                field("/Other/Unrelated", ChangeKind.ADDED));

        assertEquals(List.of(
                new RelocationMatcher.Candidate(removed.get(0), added.get(0)),
                new RelocationMatcher.Candidate(removed.get(1), added.get(0)),
                new RelocationMatcher.Candidate(removed.get(2), added.get(1)),
                new RelocationMatcher.Candidate(removed.get(1), added.get(1))),
                RelocationMatcher.candidates(removed, added));
    }

    private static ElementChange field(String id, ChangeKind change) {
        String value = change == ChangeKind.REMOVED ? "NUMBER" : null;
        String after = change == ChangeKind.ADDED ? "NUMBER" : null;
        return new ElementChange(ElementKind.FIELD, id, change, Risk.MEDIUM,
                List.of(), List.of(new Delta("kind", value, after)));
    }
}
