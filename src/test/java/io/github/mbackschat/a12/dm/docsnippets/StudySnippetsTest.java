package io.github.mbackschat.a12.dm.docsnippets;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.allOf;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.anyOf;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Severity.ERROR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.edit.GroupEditor;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorEntry;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldInfo;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * The canonical, runnable §9 study snippets — each tagged region is mirrored verbatim into
 * {@code docs/API-GUIDE.md} and locked by {@link io.github.mbackschat.a12.dm.DocSnippetFreshnessTest}.
 * Authored here so the doc shows code that genuinely compiles and runs against the real kernel.
 * (Teaching-heavy / elided / deliberately-non-compiling study blocks stay {@code <!-- teaser -->}.)
 */
class StudySnippetsTest {

    @Test
    void naryComposition() {
        BoundModel m = Fixtures.subscription();
        NumberRef fee = m.num("/Subscription/Addons/MonthlyFee");
        Condition r1 = fee.filled();
        Condition r2 = fee.gt(0);
        Condition r3 = fee.lt(1000);
        // tag::nary[]
        Condition c = allOf(r1, r2, r3);            // And-fold: "r1 And r2 And r3" — no redundant parentheses
        Condition d = allOf(r1, anyOf(r2, r3));     // "r1 And (r2 Or r3)" — parens per the kernel's mixing rule
        Condition e = allOf(List.of(r1, r2, r3));   // the LIST form — fold clauses a loop/stream built (bulk authoring)
        // end::nary[]
        assertNotNull(new DslRenderer().render(c));
        assertTrue(new DslRenderer().render(d).contains(" Or "));
        assertEquals(new DslRenderer().render(c), new DslRenderer().render(e));
    }

    @Test
    void createModelFromScratch() {
        // tag::create-model[]
        String json = DocumentModelDraft.named("orders").locale("en_US")
                .rootGroup("Order", o -> o
                        .field("CustomerName", FieldKind.STRING)
                        .field("Quantity", FieldKind.NUMBER)
                        .group("Shipping", s -> s.field("PostalCode", FieldKind.STRING)))
                .build()
                .toJson();                         // kernel-valid DM-JSON — ready to read/edit
        // end::create-model[]
        assertTrue(json.contains("Order"));
    }

    @Test
    void rulesAlongWithStructureOneSession() {
        String json = Fixtures.subscriptionJson();
        // tag::edit-session[]
        EditSession s = EditSession.read(json);
        GroupEditor addons = s.group("/Subscription/Addons");
        addons.addField("Discount", FieldKind.NUMBER);                                 // structure (modelkit) — chains
        NumberRef discount = addons.num("Discount");                                   // ref to the just-added field
        addons.addRule(r -> r.name("DiscountNotNegative")                              // rule (rulekit), pre-scoped here
                .errorField(discount)
                .when(discount.filled().and(discount.lt(0)))
                .code("DISCOUNT_NEGATIVE").severity(Severity.ERROR)
                .message(Dsl.en("Discount must not be negative."), Dsl.de("Rabatt darf nicht negativ sein."))
                .build());
        s.removeGroup("/Subscription/Billing");                                        // delete (modelkit, session-level)
        String edited = s.validate().toJson();                                         // kernel gate, once
        // end::edit-session[]
        assertTrue(edited.contains("Discount"));
    }

    @Test
    void semanticsAsData() {
        // tag::catalog[]
        OperatorCatalog catalog = OperatorCatalog.load();                                  // ✅
        OperatorEntry e = catalog.byId("GroupFilled").orElseThrow();
        e.meaning();      // "True iff the referenced group is filled. For a repeatable group this means the row EXISTS — ..."
        e.gotchas();      // ["'filled' means 'row exists', not 'row has data' — ...", "an enclosing GroupFilled(Group) counts as referencing the rule's error field ..."]
        e.keywords().de(); // "KontextAngegeben"
        e.invalidExample(); // dsl + the exact MVK_* + the fix — every example kernel-validated in CI (N7)
        // end::catalog[]
        assertTrue(catalog.byId("GroupFilled").isPresent());
    }

    @Test
    void navigatingTheModel() {
        BoundModel m = Fixtures.subscription();
        // tag::navigation[]
        // iterate + match — Stream.filter IS the matcher (no custom matcher API, no XPath-for-DM):
        List<FieldInfo> money = m.fields()
                .filter(f -> f.kind() == ValueKind.NUMBER && f.scale() == 2)
                .toList();

        m.fields(m.group("/Subscription/Addons"))        // scoped to a group
                .forEach(f -> System.out.println(f.path() + " : " + f.kind()));

        Optional<FieldInfo> peek = m.fieldAt("/Subscription/Tier");   // non-throwing — exploring is lenient,
        NumberRef fee = money.getFirst().asNum();                      // narrowing is strict (authoring guarantees)

        // which makes rule templates (§3.4) one stream pipeline:
        List<Rule> rules = m.fields()
                .filter(f -> f.kind() == ValueKind.NUMBER && f.scale() == 2)
                .map(f -> m.rule(f.group()).name(f.name() + "NonNegative").errorField(f.asNum())
                        .when(f.asNum().filled().and(f.asNum().lt(0)))
                        .code("NON_NEGATIVE_" + f.name().toUpperCase()).severity(ERROR)
                        .message(en(f.name() + " must not be negative."),               // a message per declared
                                 de(f.name() + " darf nicht negativ sein."))            // locale (the locale law)
                        .build())
                .toList();
        // end::navigation[]
        assertFalse(money.isEmpty());
        assertNotNull(rules);
        assertNotNull(peek);
        assertNotNull(fee);
    }

    @Test
    void findAnnotatedFieldsAndAddSiblingAuditGroups() {
        String json = EditSession.read(Fixtures.subscriptionJson())
                .setAnnotation("/Subscription/Billing/BaseFee", "audited", "true")
                .toJson();
        // tag::navigation-annotations[]
        BoundModel m = BoundModel.ofJson(json);       // navigate this immutable snapshot
        EditSession s = EditSession.read(json);       // mutate this session

        m.fields()
                .filter(f -> f.annotation("audited").isPresent())   // find by annotation — plain Stream.filter
                .toList()                                           // materialize before mutating (don't match new fields)
                .forEach(f -> {
                    String audit = f.name() + "_AUDIT";
                    s.addGroup(f.groupPath(), audit);               // a sibling group of the matched field
                    s.group(f.groupPath() + "/" + audit)            // → GroupEditor: three audit fields
                            .stringField("auditUser", b -> {})
                            .dateTimeField("auditAt", b -> {})
                            .stringField("auditAction", b -> {});
                });
        String edited = s.validate().toJson();        // kernel gate, once
        // end::navigation-annotations[]
        assertTrue(edited.contains("BaseFee_AUDIT"));
        assertTrue(edited.contains("auditUser"));
    }
}
