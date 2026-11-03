package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.kernel.md.model.api.IDocumentUniquenessCriterion;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.ModelLoader;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.modelkit.MountOptions;

/**
 * What kernel 31.1.1 does with a document uniqueness criterion when the structure around it changes: a field or
 * group it names is removed, the criterion sits in an included model or names a mounted field, and a group holding
 * one of its fields is extracted as an include. These are the kernel bases for the refactor gates of W3b-2b.
 *
 * <p>Each row reports A12's verdict and, where the model builds, the criteria of the model A12 expands, read
 * through its typed {@code IDocumentUniquenessCriterion}. Expansion keeps only the entry model's criteria, at both
 * include levels: an included model's own criterion is gone, and an entry criterion may name a mounted field. So the
 * criteria a group extraction copies into its sub-model only make that sub-model invalid on its own. Own domain
 * (insurance policies and their branch offices).
 */
class UniquenessCriterionStructureKernelTest {

    private static String criterion(String name, String field, String text) {
        return "{\"name\":\"" + name + "\",\"fields\":[{\"fullName\":\"" + field + "\"}],"
                + "\"errorMessage\":[{\"locale\":\"en_US\",\"text\":\"" + text + "\"}]}";
    }

    /** [json] with these criteria inserted as the content member A12 reads. */
    private static String withCriteria(String json, String... criteria) {
        int at = json.indexOf("\"modelRoot\"");
        return json.substring(0, at) + "\"documentUniquenessCriteria\":[" + String.join(",", criteria) + "],"
                + json.substring(at);
    }

    /** A policy with a required number and an inline office whose code is required. */
    private static String inlinePolicy() {
        return DocumentModelDraft.named("policy").locale("en_US")
                .rootGroup("Policy", p -> p
                        .stringField("PolicyNo", f -> f.required())
                        .group("Office", o -> o.stringField("Code", f -> f.required())))
                .build().toJson();
    }

    /** A policy mounting the branch model at {@code /Policy/Office}, at the single-root level. */
    private static String mountingPolicy() {
        return mountingPolicy(MountOptions.single());
    }

    private static String mountingPolicy(MountOptions options) {
        return DocumentModelDraft.named("policy").locale("en_US")
                .rootGroup("Policy", p -> p
                        .stringField("PolicyNo", f -> f.required())
                        .mount("Office", "branch", options))
                .build().toJson();
    }

    private static String branch() {
        return DocumentModelDraft.named("branch").locale("en_US")
                .rootGroup("Branch", b -> b.stringField("Code", f -> f.required()).stringField("City", f -> { }))
                .build().toJson();
    }

    private static ModelResolver resolving(String reference, String json) {
        return asked -> reference.equals(asked) ? json : null;
    }

    /** A12's verdict, and the criteria of the model it expands when it admits it. */
    private static String measured(String json, ModelResolver resolver) {
        try {
            ModelLoader.loadAndCheck(json, resolver);
        } catch (ModelConsistencyException rejected) {
            return rejected.notifications().stream().map(n -> n.message().trim()).collect(Collectors.joining(" | "));
        } catch (RuntimeException raised) {
            return raised.getClass().getSimpleName() + ": " + raised.getMessage();
        }
        return "admitted; criteria " + KernelModelOracle.of(json, resolver).model().getContent()
                .getDocumentUniquenessCriteria().stream()
                .map(UniquenessCriterionStructureKernelTest::render)
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private static String render(IDocumentUniquenessCriterion criterion) {
        return criterion.getName() + " " + criterion.getFieldFullNames() + " " + criterion.getErrorMessage().values();
    }

    @Test
    void aCriterionFollowsTheStructureItNames() {
        String policyKey = criterion("PolicyKey", "/Policy/PolicyNo", "Policy $/Policy/PolicyNo$ exists");
        String officeKey = criterion("OfficeKey", "/Policy/Office/Code", "Office $/Policy/Office/Code$ exists");
        String branchKey = criterion("BranchKey", "/Branch/Code", "Branch $/Branch/Code$ exists");
        ModelResolver none = asked -> null;

        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("control: the inline policy with both criteria", measured(withCriteria(inlinePolicy(), policyKey, officeKey), none));
        actual.put("the criterion's field removed", measured(ModelDocument.read(withCriteria(inlinePolicy(), policyKey))
                .removeField("/Policy/PolicyNo").toJson(), none));
        actual.put("the group holding the criterion's field removed", measured(ModelDocument.read(
                withCriteria(inlinePolicy(), officeKey)).removeGroup("/Policy/Office").toJson(), none));

        actual.put("an included model's criterion", measured(mountingPolicy(), resolving("branch", withCriteria(branch(), branchKey))));
        actual.put("an included model's criterion, mounted at the model root", measured(
                mountingPolicy(MountOptions.single().asModelRoot()), resolving("branch", withCriteria(branch(), branchKey))));
        actual.put("an entry criterion naming a mounted field", measured(withCriteria(mountingPolicy(), officeKey),
                resolving("branch", branch())));
        actual.put("an entry and an included criterion of one name", measured(withCriteria(mountingPolicy(),
                criterion("BranchKey", "/Policy/Office/Code", "Taken")), resolving("branch", withCriteria(branch(), branchKey))));

        ModelDocument extracting = ModelDocument.read(withCriteria(inlinePolicy(), policyKey, officeKey));
        String office = extracting.extractGroupAsInclude("/Policy/Office", "office");
        String remainder = extracting.toJson();
        actual.put("the extracted sub-model alone", measured(office, none));
        actual.put("the model the group was extracted from", measured(remainder, resolving("office", office)));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("control: the inline policy with both criteria", "admitted; criteria [PolicyKey [/Policy/PolicyNo] [Policy $/Policy/PolicyNo$ exists], OfficeKey [/Policy/Office/Code] [Office $/Policy/Office/Code$ exists]]");
        expected.put("the criterion's field removed", "Document Uniqueness Criterion 'PolicyKey': The field '/Policy/PolicyNo' does not exist in the Document Model.");
        expected.put("the group holding the criterion's field removed", "Document Uniqueness Criterion 'OfficeKey': The field '/Policy/Office/Code' does not exist in the Document Model.");
        expected.put("an included model's criterion", "admitted; criteria []");
        expected.put("an included model's criterion, mounted at the model root", "admitted; criteria []");
        expected.put("an entry criterion naming a mounted field", "admitted; criteria [OfficeKey [/Policy/Office/Code] [Office $/Policy/Office/Code$ exists]]");
        expected.put("an entry and an included criterion of one name", "admitted; criteria [BranchKey [/Policy/Office/Code] [Taken]]");
        expected.put("the extracted sub-model alone", "Document Uniqueness Criterion 'PolicyKey': The field '/Policy/PolicyNo' does not exist in the Document Model. | Document Uniqueness Criterion 'OfficeKey': The field '/Policy/Office/Code' does not exist in the Document Model.");
        expected.put("the model the group was extracted from", "admitted; criteria [PolicyKey [/Policy/PolicyNo] [Policy $/Policy/PolicyNo$ exists], OfficeKey [/Policy/Office/Code] [Office $/Policy/Office/Code$ exists]]");
        assertEquals(expected, actual);
    }
}
