package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;

/**
 * The insurance-policy model the resolver-class laws share: a fixed insured party and broker beside repeatable
 * claims, with {@code Remark} declared in two groups so that a bare {@code Remark} is ambiguous model-wide.
 *
 * <p>The short-name flag is a parameter because the fallback it enables is one of the rivals these laws have to
 * separate, and a within-model control (the same model differing in that one call) is the only clean way to do it.
 */
final class PolicyPathFixture {

    private PolicyPathFixture() {
    }

    static String policy(boolean shortNameRefs) {
        return policy(shortNameRefs, false);
    }

    /** …with {@code ClaimRef} optionally declared {@code Claims}' index field, the one call that differs. */
    static String policy(boolean shortNameRefs, boolean claimsKeyed) {
        return withShortNameRefs(shortNameRefs, DocumentModelDraft.named("policy").locale("en_US")
                .rootGroup("Policy", p -> policyMembers(p, claimsKeyed, false)));
    }

    private static void policyMembers(DocumentModelDraft.GroupBuilder p, boolean claimsKeyed, boolean parts) {
        p.field("Holder", FieldKind.STRING)
                .group("Insured", i -> i.field("Remark", FieldKind.STRING).field("Age", FieldKind.NUMBER))
                .group("Broker", b -> b.field("BrokerName", FieldKind.STRING))
                .group("Claims", c -> {
                    c.repeatable(20);
                    if (claimsKeyed) {
                        c.indexField("ClaimRef");
                    }
                    c.field("ClaimRef", FieldKind.STRING)
                            .field("Remark", FieldKind.STRING)
                            .field("Amount", FieldKind.NUMBER);
                    if (parts) {
                        c.group("Parts", pt -> pt.repeatable(20).field("PartCost", FieldKind.NUMBER));
                    }
                });
    }

    private static String withShortNameRefs(boolean shortNameRefs, DocumentModelDraft.Builder draft) {
        return ModelDocument.read(draft.build().toJson()).setFieldRefByShortNameAllowed(shortNameRefs).toJson();
    }

    /** How the repeatable {@code Recoveries} beside the keyed {@code Claims} declares its index field. */
    enum RecoveriesIndex {
        /** No index field, though it carries a {@code ClaimRef} like its keyed siblings. */
        NONE,
        /** {@code ClaimRef}, a text field as in {@code Claims}. */
        CLAIM_REF,
        /** {@code RecoveryRef}, a text field of another name. */
        OTHER_NAME,
        /** {@code ClaimRef}, declared a number where {@code Claims}' is text. */
        CLAIM_REF_AS_NUMBER,
    }

    /**
     * The keyed policy, short-name references on as in {@code policy(true, true)}, with a repeatable
     * {@code Recoveries} sibling of {@code Claims} whose index declaration is the one thing that varies, so an
     * error field there puts the keyed {@code Claims} on a parallel level.
     */
    static String keyedClaimsBesideRecoveries(RecoveriesIndex index) {
        return keyedClaimsBesideRecoveries(index, false);
    }

    /** …with a repeatable {@code Parts} inside {@code Claims} when {@code parts}, so a star can sit below it. */
    static String keyedClaimsBesideRecoveries(RecoveriesIndex index, boolean parts) {
        return withShortNameRefs(true, DocumentModelDraft.named("policy").locale("en_US")
                .rootGroup("Policy", p -> {
                    policyMembers(p, true, parts);
                    p.group("Recoveries", r -> {
                        r.repeatable(20);
                        switch (index) {
                            case NONE -> { }
                            case CLAIM_REF, CLAIM_REF_AS_NUMBER -> r.indexField("ClaimRef");
                            case OTHER_NAME -> r.indexField("RecoveryRef");
                        }
                        r.field("ClaimRef", index == RecoveriesIndex.CLAIM_REF_AS_NUMBER
                                        ? FieldKind.NUMBER : FieldKind.STRING)
                                .field("RecoveryRef", FieldKind.STRING)
                                .field("Note", FieldKind.STRING);
                    });
                }));
    }
}
