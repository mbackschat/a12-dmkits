package io.github.mbackschat.a12.dm.adapter.review;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.PossibleCauseFilter;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

/**
 * Two members A12 stores and serializes since DM 29.6/29.7 reach a review: an element's {@code changeNote} on every
 * element kind, and a rule's possible-cause filter. A note is metadata; the filter decides which entities a fired
 * message names, so a change to it is a reference change. Own domain (insurance policies).
 */
class ModelDiffChangeNoteTest {

    private static final String RULE = "/Policy/NeedsHolder";
    private static final String COMPUTATION = "/Policy/NetPremiumComp";

    private static String policy(PossibleCauseFilter filter) {
        String json = DocumentModelDraft.named("policy").locale("en_US")
                .typeDef("PolicyNumber", type -> type.string(text -> text.maxLength(12)))
                .rootGroup("Policy", policy -> policy
                        .numberField("Premium", number -> { })
                        .numberField("NetPremium", number -> { })
                        .stringField("Holder", text -> { })
                        .group("Rider", rider -> rider.stringField("Code", text -> { })))
                .build().toJson();
        json = ModelWriter.addRule(json, DraftRule.builder().groupPath("/Policy").name("NeedsHolder")
                .errorEntityRelPath("../Holder").errorCondition("FieldFilled(Premium) And FieldNotFilled(Holder) And FieldFilled(Rider/Code)")
                .errorCode("NEEDS_HOLDER").severity("ERROR")
                .messages(List.of(new LocalizedText("en_US", "A priced policy needs a holder.")))
                .possibleCauses(filter).build());
        return ModelWriter.addComputation(json, DraftComputation.builder().groupPath("/Policy").name("NetPremiumComp")
                .computedFieldRelPath("../NetPremium")
                .alternatives(List.of(DraftComputation.Alternative.builder("[Premium]").build())).build(), List.of(new LocalizedText("en_US", "The net premium follows the premium.")));
    }

    @Test
    void aChangedNoteIsMetadataOnEveryElementKind() {
        String base = policy(new PossibleCauseFilter(List.of("Premium"), List.of()));
        ModelDocument head = ModelDocument.read(base);
        List<String> refs = List.of("/Policy", "/Policy/Holder", RULE, COMPUTATION, "PolicyNumber");
        refs.forEach(ref -> head.setChangeNote(ref, "reviewed"));

        Map<String, List<String>> actual = new LinkedHashMap<>();
        for (ModelDiff.ElementChange change : ModelDiff.between(base, head.toJson()).changes()) {
            actual.put(change.id(), List.of(change.change().name(), aspects(change), String.join(",", change.reasons()),
                    change.risk().name()));
        }
        Map<String, List<String>> expected = new LinkedHashMap<>();
        refs.forEach(ref -> expected.put(ref, List.of("MODIFIED", "changeNote", "METADATA_CHANGED", "LOW")));
        assertEquals(expected, sorted(actual));
    }

    @Test
    void aChangedPossibleCauseFilterIsAReferenceChange() {
        ModelDiff diff = ModelDiff.between(policy(new PossibleCauseFilter(List.of("Premium"), List.of())),
                policy(new PossibleCauseFilter(List.of("Premium", "Rider/Code"), List.of("Holder"))));
        assertEquals(1, diff.changes().size(), diff.changes().toString());
        ModelDiff.ElementChange change = diff.changes().getFirst();
        assertEquals(RULE, change.id());
        assertEquals(List.of(
                        new ModelDiff.Delta("possibleCauses.ifFilled", "Premium", "Premium,Rider/Code"),
                        new ModelDiff.Delta("possibleCauses.ifNotFilled", null, "Holder")),
                change.deltas());
        assertEquals(List.of("REFERENCE_CHANGED"), change.reasons());
    }

    private static String aspects(ModelDiff.ElementChange change) {
        return String.join(",", change.deltas().stream().map(ModelDiff.Delta::aspect).toList());
    }

    private static Map<String, List<String>> sorted(Map<String, List<String>> actual) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        List.of("/Policy", "/Policy/Holder", RULE, COMPUTATION, "PolicyNumber").forEach(ref -> {
            if (actual.containsKey(ref)) {
                out.put(ref, actual.get(ref));
            }
        });
        actual.forEach(out::putIfAbsent);
        return out;
    }
}
