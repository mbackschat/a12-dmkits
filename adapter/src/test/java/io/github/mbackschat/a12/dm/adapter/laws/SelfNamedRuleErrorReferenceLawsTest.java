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
     * <b>The disagreeing shape is never admitted.</b> A rule named after a field of its own group collides with
     * that field, and A12 refuses the model whichever reference the rule carries. On kernel 31.1.1 the collision
     * is reported as converter sentences — "Cannot have two child elements with the same name" and the duplicate
     * id — beside {@code MVK_ERROR_FIELD_MISSING} / {@code MVK_MISSING_ERROR_FIELD}. On 30.8.1 it threw while
     * converting, so no check ran. Both spellings draw the same refusal; the empty one only adds its own
     * "error entity is not set properly" sentence. The reference is not what is refused.
     *
     * <p><b>What that settles.</b> {@code Rule.errorEntityRelPath()} and {@code EntityPath.relativize}
     * disagree on exactly one input — a rule whose element base and error target are the same path — and no
     * admitted kernel model carries it. So the hand-rolled form has no observable behaviour the typed algebra lacks,
     * and delegating is safe (DEV-API-GAPS {@code DG50}).
     *
     * <p><b>Two controls, because "everything here is refused" would be a dead gate.</b> The renamed rule
     * with a plain {@code ../CustomerName} is ADMITTED, so the fixture reaches the kernel at all. The renamed
     * rule with the EMPTY reference is the sharper one: it is refused {@code MVK_ERROR_FIELD_MISSING} /
     * {@code MVK_MISSING_ERROR_FIELD}, "The error entity is not set properly". So an empty reference is not
     * merely unmeasured — the kernel rejects it outright, and had the disagreeing shape been admitted, the
     * hand-rolled {@code ../Field} form would have been the CORRECT one and {@code relativize} the wrong one.
     * Delegating is safe because the input is never admitted, not because the two agree.
     */
    @Test
    void aRuleNamedAfterAFieldOfItsGroupIsRefusedWhicheverReferenceItCarries() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        verdicts.put("self-named, ../Field reference", verdict(FIELD, "../" + FIELD));
        verdicts.put("self-named, EMPTY reference", verdict(FIELD, ""));
        verdicts.put("renamed control, ../Field reference", verdict("NameRequired", "../" + FIELD));
        verdicts.put("renamed control, EMPTY reference", verdict("NameRequired", ""));

        String collision = "ERROR: Field with path '/Order/CustomerName': Cannot have two child elements with the "
                + "same name [CustomerName] in group '/Order'.";
        String duplicateId = "ERROR: The id [/Order/CustomerName] of element on path '/Order/CustomerName' is not "
                + "unique: Used by [/Order/CustomerName, /Order/CustomerName].";
        String errorField = "MVK_ERROR_FIELD_MISSING, MVK_MISSING_ERROR_FIELD";
        assertEquals(Map.of(
                "self-named, ../Field reference", String.join(", ", collision, duplicateId, errorField),
                "self-named, EMPTY reference", String.join(", ", collision,
                        "ERROR: Rule with id '/Order/CustomerName': The error entity is not set properly.",
                        duplicateId, errorField),
                "renamed control, ../Field reference", ADMITTED,
                "renamed control, EMPTY reference", String.join(", ",
                        "ERROR: Rule with id '/Order/NameRequired': The error entity is not set properly.",
                        errorField)), verdicts);
    }

    // ---- instruments ------------------------------------------------------------------------------

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
            return "CONVERSION: " + e.getMessage();
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
