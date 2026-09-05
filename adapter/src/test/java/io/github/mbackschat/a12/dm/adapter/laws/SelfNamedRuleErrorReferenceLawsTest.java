package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §11 — what a rule's {@code errorEntityRelPath} may be when the rule's NAME equals its error
 * field's last segment in the same group, so that the element base and the target are the same path.
 *
 * <p><b>Why this shape needed its own measurement.</b> {@code Rule.errorEntityRelPath()} hand-rolls
 * {@code "../" + errorField.substring(group.length() + 1)} while its sibling
 * {@code Computation.computedFieldRelPath()} delegates to {@code EntityPath.relativize} — and on exactly this
 * shape the two DISAGREE: {@code relativize} returns the EMPTY reference (it is asked to relativize a path
 * against itself) where the hand-rolled form returns {@code ../CustomerName}. Unifying them is therefore a
 * behaviour change on a published derived value, not a refactor, which is what {@code DEV-API-GAPS}
 * {@code DG50} was opened to settle.
 *
 * <p><b>The verb cannot answer it, which is why this is a laws test and not a CLI one.</b>
 * {@code rule check --group /Order --field /Order/CustomerName --name CustomerName} returns
 * {@code PRECHECK RK_NAME_EXISTS} — our own client-side check, which short-circuits before the kernel is
 * asked. {@link RuntimeLaws#withRule} splices the rule into the model as raw DM-JSON, landing downstream of
 * every gate of ours, so the verdict below is the kernel's.
 *
 * <p>Own domain (retail ordering).
 */
class SelfNamedRuleErrorReferenceLawsTest {

    private static final String GROUP = "/Order";
    private static final String FIELD = "CustomerName";
    private static final String ADMITTED = "ADMITTED";
    private static final Pattern CODE = Pattern.compile("\\[(MVK_[A-Z_0-9]+)]");

    /**
     * <b>The disagreeing shape is UNREACHABLE, and it fails a whole layer earlier than the MVK gate.</b> A
     * rule named after a field of its own group is refused while the model is being CONVERTED — an
     * {@code IllegalArgumentException} out of the kernel's own {@code Group.addChildEntity}, "Cannot have two
     * child elements with the same name" — so `checkConsistency` never runs and no {@code MVK_*} code exists
     * for it. Both spellings of the reference behave identically, which is the point: the reference is not
     * what is refused.
     *
     * <p><b>What that settles.</b> {@code Rule.errorEntityRelPath()} and {@code EntityPath.relativize}
     * disagree on exactly one input — a rule whose element base and error target are the same path — and no
     * kernel model can carry it. So the hand-rolled form has no observable behaviour the typed algebra lacks,
     * and delegating is safe (DEV-API-GAPS {@code DG50}).
     *
     * <p><b>Two controls, because "everything here is refused" would be a dead gate.</b> The renamed rule
     * with a plain {@code ../CustomerName} is ADMITTED, so the fixture reaches the kernel at all. The renamed
     * rule with the EMPTY reference is the sharper one: it is refused {@code MVK_ERROR_FIELD_MISSING} /
     * {@code MVK_MISSING_ERROR_FIELD}, "The error entity is not set properly". So an empty reference is not
     * merely unmeasured — the kernel rejects it outright, and had the disagreeing shape been reachable, the
     * hand-rolled {@code ../Field} form would have been the CORRECT one and {@code relativize} the wrong one.
     * Delegating is safe because the input is unreachable, not because the two agree.
     */
    @Test
    void aRuleNamedAfterAFieldOfItsGroupIsRefUsedAtCONVERSION_WhicheverReferenceItCarries() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        verdicts.put("self-named, ../Field reference", verdict(FIELD, "../" + FIELD));
        verdicts.put("self-named, EMPTY reference", verdict(FIELD, ""));
        verdicts.put("renamed control, ../Field reference", verdict("NameRequired", "../" + FIELD));
        verdicts.put("renamed control, EMPTY reference", verdict("NameRequired", ""));

        assertEquals(Map.of(
                "self-named, ../Field reference", NAME_COLLISION,
                "self-named, EMPTY reference", NAME_COLLISION,
                "renamed control, ../Field reference", ADMITTED,
                "renamed control, EMPTY reference",
                "ERROR: Rule with id '/Order/NameRequired': The error entity is not set properly., "
                        + "MVK_ERROR_FIELD_MISSING, MVK_MISSING_ERROR_FIELD"), verdicts);
    }

    // ---- instruments ------------------------------------------------------------------------------

    private static final String NAME_COLLISION = "CONVERSION: two child elements with the same name";

    /**
     * The kernel's verdict on a model whose rule is spliced in as raw DM-JSON, past every check of ours — and
     * the CONVERSION layer's verdict when the model does not survive deserialization, which is a different
     * gate and is reported as such rather than folded into the MVK codes.
     */
    private static String verdict(String ruleName, String errorEntityRelPath) {
        String model = RuntimeLaws.withRule(order(), GROUP, ruleName, errorEntityRelPath, "ORD",
                "FieldFilled(" + FIELD + ")");
        List<String> notifications;
        try {
            notifications = RuntimeLaws.consistencyMessages(model);
        } catch (RuntimeException e) {
            return e.getMessage() != null && e.getMessage().contains("same name")
                    ? NAME_COLLISION : "CONVERSION: " + e.getMessage();
        }
        if (notifications.isEmpty()) {
            return ADMITTED;
        }
        return notifications.stream().map(SelfNamedRuleErrorReferenceLawsTest::codeOf).distinct().sorted()
                .reduce((a, b) -> a + ", " + b).orElse(ADMITTED);
    }

    private static String codeOf(String notification) {
        Matcher m = CODE.matcher(notification);
        return m.find() ? m.group(1) : notification;
    }

    private static String order() {
        return DocumentModelDraft.named("retail-order").locale("en_US")
                .rootGroup("Order", order -> order
                        .stringField(FIELD, f -> { })
                        .numberField("Quantity", f -> f.fractionDigits(0, 0)))
                .build().toJson();
    }
}
