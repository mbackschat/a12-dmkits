package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.review.ModelReport;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/** Locks one kernel preparation plus one kernel-free index expansion for adapter-backed reads. */
class KernelAdapterPreparationTest {

    @Test
    void directJsonAdapterDoesNotExpandTheModelForASecondKernelGate() {
        Fixture fixture = fixture();
        CountingResolver resolver = new CountingResolver(fixture.includedJson());

        KernelAdapter.ofJson(fixture.mainJson(), resolver);

        assertEquals(2, resolver.calls(),
                "one core index expansion plus one checked kernel expansion; never a second kernel load");
    }

    @Test
    void modelReportReusesTheSameSingleCheckedAdapterPreparation() {
        Fixture fixture = fixture();
        CountingResolver resolver = new CountingResolver(fixture.includedJson());

        ModelReport.of(fixture.mainJson(), resolver);

        assertEquals(2, resolver.calls(),
                "model report must not gate once and then deserialize/expand the kernel model again");
    }

    @Test
    void directJsonAdapterStillRejectsAKernelInvalidStoredModel() {
        BoundModel model = BoundModel.ofJson(DocumentModelDraft.named("claim").locale("en_US")
                .rootGroup("Claim", group -> group.field("Reference", FieldKind.STRING))
                .build()
                .toJson());
        var reference = model.str("/Claim/Reference");
        String valid = model.add(model.rule(model.group("/Claim"))
                        .name("ReferenceRequired")
                        .errorField(reference)
                        .when(reference.notFilled())
                        .code("REFERENCE_REQUIRED")
                        .severity(Severity.ERROR)
                        .message(Dsl.en("A reference is required."))
                        .build())
                .toJson();
        String invalid = valid.replace("\"errorEntityRelPath\" : \"../Reference\"",
                "\"errorEntityRelPath\" : \"../Missing\"");
        assertNotEquals(valid, invalid, "the fixture corruption must change the stored error field");

        assertThrows(ModelConsistencyException.class, () -> KernelAdapter.ofJson(invalid));
    }

    private static Fixture fixture() {
        String included = DocumentModelDraft.named("tariff").locale("en_US")
                .rootGroup("Tariff", group -> group.field("Rate", FieldKind.NUMBER))
                .build()
                .toJson();
        ModelResolver resolver = id -> "tariff".equals(id) ? included : null;
        EditSession session = EditSession.read(DocumentModelDraft.named("quote").locale("en_US")
                .rootGroup("Quote", group -> group.field("Reference", FieldKind.STRING))
                .build()
                .toJson(), resolver);
        session.mount("/Quote", "CurrentTariff", "tariff");
        return new Fixture(session, included);
    }

    private static final class Fixture {
        private final String mainJson;
        private final String includedJson;

        private Fixture(EditSession session, String includedJson) {
            this.mainJson = session.toJson();
            this.includedJson = includedJson;
        }

        private String mainJson() {
            return mainJson;
        }

        private String includedJson() {
            return includedJson;
        }
    }

    private static final class CountingResolver implements ModelResolver {
        private final String includedJson;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingResolver(String includedJson) {
            this.includedJson = includedJson;
        }

        @Override
        public String resolve(String modelId) {
            calls.incrementAndGet();
            return "tariff".equals(modelId) ? includedJson : null;
        }

        private int calls() {
            return calls.get();
        }
    }
}
