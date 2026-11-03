package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.adapter.UniquenessCriteriaJson.criterion;
import static io.github.mbackschat.a12.dm.adapter.UniquenessCriteriaJson.withCriteria;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.MountOptions;

/**
 * The adapter's criteria index reads the criteria of the model A12 expands (KERNEL-FINDINGS KF671): every field a
 * criterion names, by the criterion's name, so a field named only by a criterion is not mistaken for an unused one.
 * Own domain (insurance policies and their branch offices).
 */
class UniquenessCriterionIndexTest {

    private static final String POLICY = DocumentModelDraft.named("policy").locale("en_US")
            .rootGroup("Policy", p -> p
                    .stringField("PolicyNo", f -> f.required())
                    .stringField("Product", f -> f.required())
                    .group("Office", o -> o.stringField("Code", f -> f.required()))
                    .group("OfficeHistory", o -> o.stringField("Code", f -> f.required())))
            .build().toJson();

    @Test
    void indexesEveryFieldACriterionNamesByTheCriterionsName() {
        KernelAdapter adapter = KernelAdapter.ofJson(withCriteria(POLICY,
                criterion("PolicyKey", "Taken", "/Policy/PolicyNo", "/Policy/Product"),
                criterion("ProductKey", "Taken", "/Policy/Product"),
                criterion("OfficeKey", "Taken", "/Policy/Office/Code")));

        Map<String, Set<String>> expected = new LinkedHashMap<>();
        expected.put("/Policy/PolicyNo", Set.of("PolicyKey"));
        expected.put("/Policy/Product", Set.of("PolicyKey", "ProductKey"));
        expected.put("/Policy/Office/Code", Set.of("OfficeKey"));
        assertEquals(expected, adapter.uniquenessCriteria().byField());
    }

    /** A field is exact; a group covers its subtree segment-wise, so `/Policy/Office` is not `/Policy/OfficeHistory`. */
    @Test
    void theCriteriaWithinAnEntityAreItsOwnOrItsSubtrees() {
        UniquenessCriteria criteria = KernelAdapter.ofJson(withCriteria(POLICY,
                criterion("OfficeKey", "Taken", "/Policy/Office/Code"),
                criterion("HistoryKey", "Taken", "/Policy/OfficeHistory/Code"))).uniquenessCriteria();

        Map<String, Set<String>> actual = new LinkedHashMap<>();
        for (String entity : new String[] {"/Policy/Office/Code", "/Policy/Office", "/Policy", "/Policy/PolicyNo"}) {
            actual.put(entity, criteria.within(entity));
        }
        Map<String, Set<String>> expected = new LinkedHashMap<>();
        expected.put("/Policy/Office/Code", Set.of("OfficeKey"));
        expected.put("/Policy/Office", Set.of("OfficeKey"));
        expected.put("/Policy", Set.of("OfficeKey", "HistoryKey"));
        expected.put("/Policy/PolicyNo", Set.of());
        assertEquals(expected, actual);
    }

    /** A12's expansion keeps only the entry model's criteria (KF671), and so does the index. */
    @Test
    void anIncludedModelsOwnCriteriaAreNotIndexed() {
        String branch = withCriteria(DocumentModelDraft.named("branch").locale("en_US")
                .rootGroup("Branch", b -> b.stringField("Code", f -> f.required()))
                .build().toJson(), criterion("BranchKey", "Taken", "/Branch/Code"));
        String entry = withCriteria(DocumentModelDraft.named("policy").locale("en_US")
                .rootGroup("Policy", p -> p.stringField("PolicyNo", f -> f.required())
                        .mount("Office", "branch", MountOptions.single()))
                .build().toJson(), criterion("OfficeKey", "Taken", "/Policy/Office/Code"));

        assertEquals(Map.of("/Policy/Office/Code", Set.of("OfficeKey")),
                KernelAdapter.ofJson(entry, asked -> "branch".equals(asked) ? branch : null).uniquenessCriteria().byField());
    }
}
