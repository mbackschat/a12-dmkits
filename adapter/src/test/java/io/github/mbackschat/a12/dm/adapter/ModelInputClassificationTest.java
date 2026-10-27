package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Reader;
import java.io.Writer;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import com.mgmtp.a12.kernel.md.model.api.IDocumentModel;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelSerializer;
import com.mgmtp.a12.model.notification.RankedNotification;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.SortOrder;
import io.github.mbackschat.a12.dm.core.ModelInputException;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

class ModelInputClassificationTest {

    @Test
    void aCompleteOwnerObjectGraphDoesNotReclassifyAnUnexpectedNullPointer() {
        String json = DocumentModelDraft.named("complete").locale("en_US")
                .rootGroup("Root", root -> root.group("Rows", rows -> rows.repeatable(2)
                        .field("Amount", FieldKind.NUMBER).field("Total", FieldKind.NUMBER)
                        .sortField("Amount", SortOrder.ASC))).build().toJson();
        var model = BoundModel.ofJson(json);
        var rows = model.group("/Root/Rows");
        var amount = model.num("/Root/Rows/Amount");
        var total = model.num("/Root/Rows/Total");
        String complete = model.add(model.rule(rows).name("PositiveAmount").errorField(amount)
                        .when(amount.gt(0)).code("POSITIVE_AMOUNT").severity(Severity.ERROR)
                        .message(Dsl.en("Amount is positive.")).build())
                .add(model.computation(total).name("CopyAmount").calc(amount.value())
                        .message(Dsl.en("Copy amount.")).build()).toJson();
        NullPointerException defect = new NullPointerException("unexpected serializer defect");
        IDocumentModelSerializer serializer = new IDocumentModelSerializer() {
            @Override
            public IDocumentModel deserialize(Reader reader) { throw defect; }

            @Override
            public void serialize(IDocumentModel value, Writer writer, Consumer<RankedNotification> notifications) {
                throw new AssertionError("deserialization must not write");
            }
        };
        assertSame(defect, assertThrows(NullPointerException.class,
                () -> KernelModelInput.deserialize(serializer, complete)));
    }

    /**
     * A relative path that climbs past the root, or a slash-led one, fails inside the kernel's reference
     * resolution ({@code ElementPathUtils.absPath} / {@code splitRelPath}), after the reader parsed the JSON: the
     * model cannot be read, so the failure is input, not a tool defect (KERNEL-FINDINGS KF643).
     */
    @Test
    void aReferencePathTheKernelCannotResolveIsUnreadableInput() {
        for (String target : List.of("../../../Total", "/Root/Total")) {
            String json = COMPUTED_FIELD.matcher(withCopyComputation())
                    .replaceFirst("\"computedFieldRelPath\":\"" + target + "\"");
            assertTrue(json.contains(target), "the target edit missed");
            var failure = assertThrows(ModelInputException.class, () -> BoundModel.ofJson(json), target);
            assertTrue(failure.getMessage().contains("cannot be resolved"), failure::getMessage);
        }
    }

    @Test
    void anIndexFailureOutsideThePathArithmeticKeepsItsIdentity() {
        IndexOutOfBoundsException defect = new IndexOutOfBoundsException("unexpected serializer defect");
        IDocumentModelSerializer serializer = new IDocumentModelSerializer() {
            @Override
            public IDocumentModel deserialize(Reader reader) { throw defect; }

            @Override
            public void serialize(IDocumentModel value, Writer writer, Consumer<RankedNotification> notifications) {
                throw new AssertionError("deserialization must not write");
            }
        };
        assertSame(defect, assertThrows(IndexOutOfBoundsException.class,
                () -> KernelModelInput.deserialize(serializer, withCopyComputation())));
    }

    private static final Pattern COMPUTED_FIELD = Pattern.compile("\"computedFieldRelPath\"\\s*:\\s*\"[^\"]*\"");

    private static String withCopyComputation() {
        var model = BoundModel.ofJson(DocumentModelDraft.named("copy").locale("en_US")
                .rootGroup("Root", root -> root.field("Amount", FieldKind.NUMBER).field("Total", FieldKind.NUMBER))
                .build().toJson());
        return model.add(model.computation(model.num("/Root/Total")).name("CopyAmount")
                .calc(model.num("/Root/Amount").value()).message(Dsl.en("Copy amount.")).build()).toJson();
    }

    @Test
    void malformedResolvedModelsRetainTheReferenceIdentity() {
        var resolver = KernelResolvers.of(reference -> "{\"header\":");
        var failure = assertThrows(ModelInputException.class, () -> resolver.getDocumentModel("external"));
        assertTrue(failure.getMessage().contains("external"), failure::getMessage);
    }

    @Test
    void malformedRewriteInputUsesTheSameClassification() {
        assertThrows(ModelInputException.class,
                () -> ReferenceRewriter.rewriteReferences("{\"header\":", Map.of()));
    }

    @Test
    void aResolverDefectKeepsItsIdentity() {
        String json = DocumentModelDraft.named("host").locale("en_US")
                .include("child", "external")
                .rootGroup("Root", root -> root.mount("Child", "child")).build().toJson();
        NullPointerException defect = new NullPointerException("unexpected resolver defect");
        assertSame(defect, assertThrows(NullPointerException.class,
                () -> KernelModelPreparation.expand(json, reference -> { throw defect; })));
    }
}
