package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.mbackschat.a12.dm.core.ModelLoader;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * When a bare short name inside an INCLUDED model is bound, and whose
 * {@code fieldRefByShortNameAllowed} decides it — the basis for the portable checker's mounted-reference
 * resolution (PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN, B3).
 *
 * <p><b>Two flags and one collision, because neither flag alone separates the mechanisms.</b> A provider's
 * own flag binds its references BEFORE integration, so the reference emerges naming an absolute host path; the
 * including model's flag resolves whatever is still bare AFTER expansion, over the whole tree. Both routes
 * accept a name that is unique either way, so a resolver using only one of them passes six of these eight
 * cells. The separating cell is a name the host also declares with the provider's flag ON — early binding
 * survives the collision, late binding cannot — and its twin one flag away is where the collision bites.
 *
 * <p>The oracle is the Kernel end to end: {@code ModelLoader.loadAndCheck} deserializes, expands through the
 * real {@code IDocumentModelService.expand}, and runs {@code checkConsistency}. The whole table is asserted
 * at once rather than row by row, so a shift in one cell does not hide the rest of the grid.
 */
class IncludedShortNameBindingLawsTest {

    /** The one member each row varies, rewritten on the builder's own output rather than hand-authored. */
    private static String withFlag(String json, boolean on) {
        return json.replaceAll("\"fieldRefByShortNameAllowed\" : (true|false)",
                "\"fieldRefByShortNameAllowed\" : " + on);
    }

    /**
     * The provider owns the rule, so its reference is what travels. {@code Zip} sits one group BELOW the
     * rule's own group, which is what makes the bare spelling need the fallback rather than the ordinary
     * declaring-group lookup.
     */
    private static String provider(boolean flag) {
        String base = DocumentModelDraft.named("address").locale("en_US")
                .rootGroup("Address", address -> address
                        .field("Street", FieldKind.STRING)
                        .group("Inner", inner -> inner.field("Zip", FieldKind.STRING)))
                .build().toJson();
        return withFlag(EditSession.read(base)
                .addRule("/Address", "Own", "../Inner/Zip", "OWN", "FieldFilled(Zip)", "ERROR",
                        List.of(new LocalizedText("en_US", "zip needed")))
                .toJson(), flag);
    }

    private static String host(boolean flag, boolean collide) {
        return withFlag(DocumentModelDraft.named("order").locale("en_US")
                .include("addr", "address")
                .rootGroup("Order", order -> {
                    order.field("Note", FieldKind.STRING);
                    if (collide) {
                        order.field("Zip", FieldKind.STRING);
                    }
                    order.mount("ShipTo", "addr");
                })
                .build().toJson(), flag);
    }

    private static String verdict(boolean collide, boolean hostFlag, boolean providerFlag) {
        ModelResolver resolver = reference -> "address".equals(reference) ? provider(providerFlag) : null;
        try {
            ModelLoader.loadAndCheck(host(hostFlag, collide), resolver);
            return "ACCEPTED";
        } catch (RuntimeException rejected) {
            String message = String.valueOf(rejected.getMessage());
            int code = message.indexOf("[MVK_");
            return code < 0 ? message : message.substring(code + 1, message.indexOf(']', code));
        }
    }

    @Test
    void aProvidersOwnFlagBindsItsBareNamesBeforeTheIncludingModelsFlagCanSeeThem() {
        Map<String, String> measured = new LinkedHashMap<>();
        for (boolean collide : new boolean[] {false, true}) {
            for (boolean hostFlag : new boolean[] {true, false}) {
                for (boolean providerFlag : new boolean[] {true, false}) {
                    measured.put("collision=" + collide + " host=" + hostFlag + " provider=" + providerFlag,
                            verdict(collide, hostFlag, providerFlag));
                }
            }
        }

        Map<String, String> expected = new LinkedHashMap<>();
        // No collision: either flag reaches the provider's unique `Zip`, and with neither there is no tier
        // beyond the rule's own group — which is the control that the fallback is what these rows exercise.
        expected.put("collision=false host=true provider=true", "ACCEPTED");
        expected.put("collision=false host=true provider=false", "ACCEPTED");
        expected.put("collision=false host=false provider=true", "ACCEPTED");
        expected.put("collision=false host=false provider=false", "MVK_INVALID_ENTITY");
        // With the host declaring `Zip` too, the two routes finally disagree: bound early it still names the
        // provider's field, bound late it names two and cannot choose.
        expected.put("collision=true host=true provider=true", "ACCEPTED");
        expected.put("collision=true host=true provider=false", "MVK_FIELDNAME_NOT_UNIQUE");
        expected.put("collision=true host=false provider=true", "ACCEPTED");
        expected.put("collision=true host=false provider=false", "MVK_INVALID_ENTITY");

        assertEquals(expected, measured);
    }
}
