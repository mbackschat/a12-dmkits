package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.kernel.md.model.api.fieldtypes.IStringType;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.core.ModelLoader;

/**
 * The <b>Attachment</b> group template (FEATURE-GAPS). A {@code usageType: "attachment"} group carries
 * a kernel-mandated composition — 8 named fields, 4 rules (matched by exact {@code errorCondition} string in
 * the model's condition language), and a {@code content} field with {@code noValueValidation} +
 * {@code lineBreaksPermitted}. The generator emits all of it; the kernel's {@code AttachmentGroupRule}
 * validates it at {@code checkConsistency} (KERNEL-FINDINGS), so a correct template <em>loads</em> — the
 * standing proof that the emitted rule strings round-trip byte-for-byte (R1).
 */
class AttachmentTemplateTest {

    private static final List<String> ATTACHMENT_FIELDS = List.of(
            "original_filename", "internal_filename", "content", "attachment_id",
            "size", "mime_type", "category", "description");

    private static ModelIndex load(String json) throws Exception {
        return ModelIndex.of(ModelLoader.loadSelfContained(new StringReader(json)));
    }

    private static List<String> fieldNames(ModelIndex index, String groupPath) {
        return index.fieldPaths(groupPath).map(p -> p.substring(p.lastIndexOf('/') + 1)).sorted().toList();
    }

    @Test
    void aDefaultAttachmentIsAcceptedByTheKernel_en() throws Exception {
        String json = DocumentModelDraft.named("doc").locale("en_US")
                .attachmentGroup("Invoice", a -> { })
                .build().toJson();

        ModelIndex index = load(json);   // kernel-valid → AttachmentGroupRule passed (the 8 fields + 4 rule strings)
        assertEquals("attachment", index.group("/Invoice").getUsageType().orElse(null));
        assertEquals(ATTACHMENT_FIELDS.stream().sorted().toList(), fieldNames(index, "/Invoice"),
                "the 8 canonical attachment fields are present");
        IStringType content = (IStringType) index.fieldType("/Invoice/content");
        assertTrue(content.isNoValueValidation(), "content has noValueValidation");
        assertTrue(content.isLineBreaksPermitted(), "content permits line breaks");
    }

    @Test
    void aDefaultAttachmentIsAcceptedByTheKernel_de() throws Exception {
        String json = DocumentModelDraft.named("doc").locale("de_DE")
                .attachmentGroup("Anlage", a -> { })
                .build().toJson();
        assertEquals("attachment", load(json).group("/Anlage").getUsageType().orElse(null));
    }

    @Test
    void attachmentDomainKnobsStillValidate() throws Exception {
        String json = DocumentModelDraft.named("doc").locale("en_US")
                .attachmentGroup("Invoice", a -> a
                        .repeatable(20)
                        .maxSizeBytes(10_000_000)
                        .allowedMimeTypes("application/pdf", "image/png")
                        .categories("CONTRACT", "RECEIPT")
                        .message(AttachmentRule.MIME_TYPE, "en_US", "Pick a content type."))
                .build().toJson();
        ModelIndex index = load(json);
        assertTrue(index.isRepeating("/Invoice"), "a list of attachments is repeatable");
        assertEquals(ATTACHMENT_FIELDS.stream().sorted().toList(), fieldNames(index, "/Invoice"));
    }

    @Test
    void anAttachmentOnANonEnDeModelIsRefused() {
        // the kernel only knows en_US/de_DE attachment rule sets — anything else must refuse, not crash
        assertThrows(IllegalStateException.class, () -> DocumentModelDraft.named("doc").locale("fr_FR")
                .attachmentGroup("Invoice", a -> { })
                .build());
    }

    @Test
    void anAttachmentMissingAMandatedRuleIsRejectedByTheKernel() throws Exception {
        String good = DocumentModelDraft.named("doc").locale("en_US")
                .attachmentGroup("Invoice", a -> { })
                .build().toJson();
        // tamper: drop the mime-type rule's condition → AttachmentGroupRule must report the missing required rule
        String tampered = good.replace("GroupFilled(RuleGroup) and FieldNotFilled(mime_type)",
                "GroupFilled(RuleGroup) and FieldNotFilled(original_filename)");
        assertTrue(tampered.contains("FieldNotFilled(original_filename)"), "the tamper applied");
        ModelConsistencyException ex = assertThrows(ModelConsistencyException.class,
                () -> ModelLoader.loadSelfContained(new StringReader(tampered)));
        assertTrue(ex.getMessage().toUpperCase().contains("ATTACHMENT"),
                () -> "the kernel rejects an attachment missing a mandated rule: " + ex.getMessage());
    }
}
