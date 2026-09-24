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
        return ModelDocument.read(DocumentModelDraft.named("policy").locale("en_US")
                        .rootGroup("Policy", p -> p
                                .field("Holder", FieldKind.STRING)
                                .group("Insured", i -> i.field("Remark", FieldKind.STRING).field("Age", FieldKind.NUMBER))
                                .group("Broker", b -> b.field("BrokerName", FieldKind.STRING))
                                .group("Claims", c -> c.repeatable(20)
                                        .field("ClaimRef", FieldKind.STRING)
                                        .field("Remark", FieldKind.STRING)
                                        .field("Amount", FieldKind.NUMBER)))
                        .build().toJson())
                .setFieldRefByShortNameAllowed(shortNameRefs)
                .toJson();
    }
}
