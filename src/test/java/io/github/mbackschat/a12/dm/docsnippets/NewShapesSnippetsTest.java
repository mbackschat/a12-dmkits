package io.github.mbackschat.a12.dm.docsnippets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.core.KernelCodeRefusal;
import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.modelkit.DatePrecision;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoolExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateRangeIndexedValue;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateTimeExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupListRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.Quantifier;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.TimeExpr;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

/**
 * The canonical, runnable snippets for the typed authoring shapes closed under DEV-API-GAPS §2 — each
 * tagged region is mirrored verbatim into {@code docs/API-GUIDE.md} and locked by
 * {@link io.github.mbackschat.a12.dm.DocSnippetFreshnessTest}. Authored here so the guide shows code that
 * genuinely compiles and renders against the real kernel (typed own-domain fixtures).
 */
class NewShapesSnippetsTest {

    private static final BoundModel m = Fixtures.order();
    private static final String APPROVAL_JSON = DocumentModelDraft.named("approval").locale("en_US")
            .rootGroup("Approval", root -> root
                    .field("Error", FieldKind.STRING)
                    .group("Rows", rows -> rows.repeatable(10)
                            .field("Approved", FieldKind.BOOLEAN)))
            .build().toJson();
    private static final BoundModel approval = BoundModel.ofJson(APPROVAL_JSON);
    private static final String PROMO_JSON = DocumentModelDraft.named("promo").locale("en_US")
            .rootGroup("Shop", root -> root
                    .dateField("CheckDate", d -> d.format("yyyy-MM-dd"))
                    .field("Season", FieldKind.STRING)
                    .field("Banner", FieldKind.STRING)
                    .group("Runs", runs -> runs.repeatable(20).indexField("RunKey")
                            .field("RunKey", FieldKind.STRING)
                            .dateRangeField("RunWindow", r -> r.format("yyyy-MM-dd").separator("/"))
                            .dateField("LaunchedAbout",
                                    d -> d.format("yyyy-MM-dd").datePrecision(DatePrecision.MONTH_OPTIONAL))
                            .stringField("StartDay", t -> t.maxLength(2).pattern("[0-9]+", "Digits only."))
                            .enumField("Channel", e -> e.value("WEB", l -> l.label("en_US", "Online"))
                                    .value("STORE", l -> l.label("en_US", "In store")))
                            .booleanField("Featured", b -> { })))
            .build().toJson();
    private static final BoundModel promo = BoundModel.ofJson(PROMO_JSON);
    private static final String FULFILMENT_JSON = DocumentModelDraft.named("fulfilment").locale("en_US")
            .rootGroup("Fulfilment", root -> root
                    // A TimeType field declaring a DATE format: model-legal, and the shape an imported or
                    // hand-edited model really does arrive in. It stores and compares as a date.
                    .timeField("PromisedDay", f -> f.format("yyyy-MM-dd"))
                    .dateField("ShippedOn", f -> f.format("yyyy-MM-dd"))
                    .dateRangeField("DeliveryWindow", r -> r.format("yyyy-MM-dd").separator("/")))
            .build().toJson();
    private static final BoundModel fulfilment = BoundModel.ofJson(FULFILMENT_JSON);
    private static final String DEPOT_JSON = DocumentModelDraft.named("depot").locale("en_US")
            .rootGroup("Depot", root -> root
                    .group("Stock", stock -> stock.numberField("StockLevel", n -> n.fractionDigits(0)))
                    .group("Policy", policy -> policy.numberField("MinimumOnHand", n -> n.fractionDigits(0))))
            .build().toJson();
    private static final BoundModel depot = BoundModel.ofJson(DEPOT_JSON);
    private static final String WAREHOUSE_JSON = DocumentModelDraft.named("warehouse").locale("en_US")
            .rootGroup("Item", item -> item
                    .field("Title", FieldKind.STRING)
                    .group("Grading", g -> g.field("Grade", FieldKind.NUMBER))
                    .group("Batches", b -> b.repeatable(4)
                            .field("Lot", FieldKind.STRING)
                            .group("Seal", s -> s.field("Code", FieldKind.STRING))))
            .build().toJson();
    private static final BoundModel warehouse = BoundModel.ofJson(WAREHOUSE_JSON);

    @Test
    void groupCountScopeSnippet() {
        // tag::group-count-scope[]
        // Seal is fixed inside the repeatable Batches. Its scope keeps the star on Batches, which a
        // GroupRef cannot spell: the GroupRef factories star a group only when the group itself repeats.
        GroupListRef seals = warehouse.flattenGroup("/Item/Batches*/Seal");
        NumberExpr sealedBatches = Dsl.numberOfFilledGroups(seals);
        NumberExpr sealsAndGrading = Dsl.numberOfFilledGroups(seals, warehouse.flattenGroup("/Item/Grading"));
        // end::group-count-scope[]
        assertEquals("NumberOfFilledGroups(/Item/Batches*/Seal)", new DslRenderer().render(sealedBatches.expression()));
        assertEquals("NumberOfFilledGroups(/Item/Batches*/Seal, /Item/Grading)",
                new DslRenderer().render(sealsAndGrading.expression()));
        Condition titled = warehouse.field("/Item/Title").filled();
        assertKernelAccepts(WAREHOUSE_JSON, "/Item", titled.and(sealedBatches.gt(0)), "../Title");
        assertKernelAccepts(WAREHOUSE_JSON, "/Item", titled.and(sealsAndGrading.gt(0)), "../Title");
    }

    @Test
    void dateMinMaxSnippet() {
        // tag::date-minmax[]
        // Min/Max preserve a homogeneous temporal family: DATE → DateExpr, TIME → TimeExpr,
        // DATETIME → DateTimeExpr. Mixing families does not compile.
        DateExpr earliestDate = Dsl.min(m.date("/Order/OrderDate"), m.date("/Order/DeliveryDate"));
        TimeExpr earliestTime = Dsl.min(m.time("/Order/PickupTime"), Dsl.time("09", "00", "00"));
        DateTimeExpr latestInstant = Dsl.max(m.dateTime("/Order/ScheduledAt"),
                m.dateTime("/Order/CompletedAt"));
        // end::date-minmax[]
        assertEquals("Min([/Order/OrderDate], [/Order/DeliveryDate])",
                new DslRenderer().render(earliestDate.expression()));
        assertEquals("Min([/Order/PickupTime], Time(\"09\", \"00\", \"00\"))",
                new DslRenderer().render(earliestTime.expression()));
        assertEquals("Max([/Order/ScheduledAt], [/Order/CompletedAt])",
                new DslRenderer().render(latestInstant.expression()));
    }

    @Test
    void temporalCrossOrderingSnippet() {
        // tag::temporal-cross-ordering[]
        Condition startsAfterOrderDay = m.dateTime("/Order/ScheduledAt")
                .after(m.date("/Order/OrderDate"));
        Condition orderDayNotAfterCompletion = m.date("/Order/OrderDate")
                .onOrBefore(m.dateTime("/Order/CompletedAt"));
        // end::temporal-cross-ordering[]
        assertEquals("[/Order/ScheduledAt] > [/Order/OrderDate]",
                new DslRenderer().render(startsAfterOrderDay));
        assertEquals("[/Order/OrderDate] <= [/Order/CompletedAt]",
                new DslRenderer().render(orderDayNotAfterCompletion));
        assertKernelAccepts(startsAfterOrderDay, "../ScheduledAt");
        assertKernelAccepts(orderDayNotAfterCompletion, "../OrderDate");
    }

    @Test
    void dateRangeEqualitySnippet() {
        // tag::date-range-equality[]
        var requestedWindow = Dsl.dateRange(m.date("/Order/OrderDate"), m.date("/Order/DeliveryDate"));
        Condition windowChanged = m.dateRange("/Order/CoverageWindow").ne(requestedWindow);
        Condition requestedWindowChanged = requestedWindow.ne(
                Dsl.dateRange(m.date("/Order/OrderDate"), m.date("/Order/DeliveryDate")));
        var firstObservedWindow = Dsl.firstFilledDateRange(m.flatten("/Order/Items*/ItemCoverage"));
        Computation coverageWindow = m.computation(m.dateRange("/Order/CoverageWindow"))
                .name("CoverageWindowComp").calc(requestedWindow.expression())
                .message(Dsl.en("Coverage window follows the order and delivery dates.")).build();
        Rule windowMismatch = m.rule(m.group("/Order")).name("CoverageWindowMatchesOrder")
                .errorField(m.dateRange("/Order/CoverageWindow")).when(windowChanged)
                .code("COVERAGE_WINDOW_MISMATCH").severity(Severity.ERROR)
                .message(Dsl.en("The coverage window must span the order and delivery dates.")).build();
        // end::date-range-equality[]
        assertEquals("[/Order/CoverageWindow] != DateRange(/Order/OrderDate, /Order/DeliveryDate)",
                new DslRenderer().render(windowChanged));
        assertEquals("FirstFilledValue(/Order/Items*/ItemCoverage)",
                new DslRenderer().render(firstObservedWindow.expression()));
        assertEquals("../CoverageWindow", coverageWindow.computedFieldRelPath());
        assertEquals("../CoverageWindow", windowMismatch.errorEntityRelPath());
        // The kernel is the oracle for the new overload too: a DATE_RANGE field IS a legal error entity, which
        // is what makes the errorField/computation parity meaningful rather than merely symmetric.
        var ruleResult = new RuleValidator().check(Fixtures.orderJson(), windowMismatch.draft());
        assertTrue(ruleResult.ok(), () -> "kernel rejected a DATE_RANGE error field: " + ruleResult.diagnostics());
        assertKernelAccepts(windowChanged, "../CoverageWindow");
        assertKernelAccepts(requestedWindowChanged, "../OrderDate");
        assertKernelAccepts(m.dateRange("/Order/CoverageWindow").eq(firstObservedWindow), "../CoverageWindow");
        var computationResult = new RuleValidator().check(Fixtures.orderJson(), coverageWindow.draft());
        assertTrue(computationResult.ok(), () -> "kernel rejected the date-range computation snippet: "
                + computationResult.diagnostics());
    }

    @Test
    void firstFilledOwnerEntitiesSnippet() {
        // tag::first-filled-owner-entities[]
        StringExpr firstLabel = Dsl.firstFilledString(
                m.enumOf("/Order/Priority").category("SpeedClass").firstFilledOperand(),
                m.str("/Order/CustomerName").field().firstFilledOperand());
        NumberExpr firstIndexedQuantity = Dsl.firstFilledNumber(
                m.num("/Order/Items/Count").semanticIndex("SKU-1").index().firstFilledOperand(),
                m.num("/Order/Items/UnitWeight").semanticIndex("SKU-1").index().firstFilledOperand());
        BoolExpr firstApproval = Dsl.firstFilledBoolean(
                approval.flatten("/Approval/Rows*/Approved"));
        StringExpr firstBillingValue = Dsl.firstFilledString(
                m.firstFilledGroup("/Order/BillingAddress"));
        // end::first-filled-owner-entities[]
        assertEquals("FirstFilledValue(/Order/Priority -> SpeedClass, /Order/CustomerName)",
                new DslRenderer().render(firstLabel.expression()));
        assertEquals("FirstFilledValue(/Order/Items/Count For \"SKU-1\", /Order/Items/UnitWeight For \"SKU-1\")",
                new DslRenderer().render(firstIndexedQuantity.expression()));
        assertEquals("FirstFilledValue(/Approval/Rows*/Approved)",
                new DslRenderer().render(firstApproval.expression()));
        assertEquals("FirstFilledValue(/Order/BillingAddress)",
                new DslRenderer().render(firstBillingValue.expression()));
        assertKernelAccepts(firstLabel.eq("priority"), "../CustomerName");
        assertKernelAccepts(m.str("/Order/CustomerName").filled().and(firstIndexedQuantity.gt(0)),
                "../CustomerName");
        assertKernelAccepts(APPROVAL_JSON, "/Approval",
                approval.str("/Approval/Error").filled().and(firstApproval.isTrue()), "../Error");
        assertKernelAccepts(firstBillingValue.eq("billing"), "../BillingAddress/PostalCode");
    }

    @Test
    void fieldRoundingSnippet() {
        // tag::field-rounding[]
        // Expression and *Value forms both accept optional 0..14 places; omission means 0.
        NumberExpr wholeUnitPrice = Dsl.roundAccounting(m.num("/Order/UnitPrice").asNumber());
        NumberExpr roundedUnitPrice = Dsl.roundAccountingValue(m.num("/Order/UnitPrice"), 2);
        Condition unitPriceNeedsRounding = m.num("/Order/UnitPrice").ne(roundedUnitPrice);
        // end::field-rounding[]
        assertEquals("RoundAccounting([/Order/UnitPrice])", new DslRenderer().render(wholeUnitPrice.expression()));
        assertEquals("[/Order/UnitPrice] != RoundAccountingValue(/Order/UnitPrice, 2)",
                new DslRenderer().render(unitPriceNeedsRounding));
    }

    @Test
    void refArithmeticSnippet() {
        // tag::ref-arithmetic[]
        // Arithmetic reads off a kind-typed ref, like comparison does — no asNumber()/expression() round trip.
        NumberExpr lineTotal = m.num("/Order/Quantity").times(m.num("/Order/UnitPrice"));
        Condition mostlyBackordered = m.num("/Order/BackorderQuantity").times(2).gt(m.num("/Order/Quantity"));
        // end::ref-arithmetic[]
        assertEquals("[/Order/Quantity] * [/Order/UnitPrice]", new DslRenderer().render(lineTotal.expression()));
        assertEquals("[/Order/BackorderQuantity] * 2 > [/Order/Quantity]",
                new DslRenderer().render(mostlyBackordered));
    }

    @Test
    void indexedPresenceSnippet() {
        // tag::indexed-presence[]
        // semanticIndex(key) is the presence handle for the field value a semantic index resolves to;
        // .filled()/.notFilled() test its presence. (The VALUE for a comparison is forIndex(key).)
        Condition present = m.num("/Order/Items/Count").semanticIndex("SKU-1").filled();
        // end::indexed-presence[]
        assertEquals("FieldFilled(/Order/Items/Count For \"SKU-1\")", new DslRenderer().render(present));
    }

    @Test
    void indexedGroupSnippet() {
        // tag::indexed-group[]
        // forIndex(key) selects one row of a group scope, as semanticIndex(key) selects one cell of a field.
        // The row is a GroupScope, so it stands beside a plain scope in the group-scoped field-fill quantifiers.
        GroupListRef items = m.flattenGroup("/Order/Items");
        Condition itemComplete = Dsl.allFieldsFilled(items.forIndex("SKU-1"), m.flattenGroup("/Order/ShippingAddress"));
        // The selected cell is a group-list operand beside a field.
        Condition anyGiven = Dsl.atLeastOneGroupFilled(
                m.num("/Order/Items/Count").semanticIndex("SKU-1").index(), m.field("/Order/CustomerName"));
        // The selected row has a presence of its own, as a bare group does.
        Condition itemPresent = items.forIndex("SKU-1").filled();
        // end::indexed-group[]
        assertEquals("AllFieldsFilled(/Order/Items For \"SKU-1\", /Order/ShippingAddress)",
                new DslRenderer().render(itemComplete));
        assertEquals("AtLeastOneGroupFilled(/Order/Items/Count For \"SKU-1\", /Order/CustomerName)",
                new DslRenderer().render(anyGiven));
        assertEquals("GroupFilled(/Order/Items For \"SKU-1\")", new DslRenderer().render(itemPresent));
    }

    @Test
    void indexedCoercionSnippet() {
        // tag::indexed-coercion[]
        // fieldValueAsString also takes a semanticIndex(key) operand — the indexed field value coerced to
        // string, e.g. to splice a keyed number value into a concatenation (the corpus shape).
        Condition c = m.str("/Order/CustomerName").eq(
                Dsl.text("C-").plus(Dsl.fieldValueAsString(m.num("/Order/Items/Count").semanticIndex("SKU-1"))));
        // end::indexed-coercion[]
        assertEquals("[/Order/CustomerName] == \"C-\" + FieldValueAsString(/Order/Items/Count For \"SKU-1\")",
                new DslRenderer().render(c));
    }

    @Test
    void indexedRangeSnippet() {
        // tag::indexed-range[]
        // dateRange(f).forIndex(key) is the range cell a semantic index resolves to. It keeps its own type,
        // so the bound extractors take it — which FirstFilledValue's computed range, deliberately, cannot.
        DateRangeIndexedValue springRun = promo.dateRange("/Shop/Runs/RunWindow").forIndex("spring");
        Condition startsOnCheckDate =
                Dsl.startOfDateRange(springRun).eq(promo.date("/Shop/CheckDate"));
        // end::indexed-range[]
        assertEquals("StartOfDateRange(/Shop/Runs/RunWindow For \"spring\") == [/Shop/CheckDate]",
                new DslRenderer().render(startsOnCheckDate));
        // The END twin renders its own bound — without this row a START/END swap in either factory is invisible.
        assertEquals("EndOfDateRange(/Shop/Runs/RunWindow For \"spring\") == [/Shop/CheckDate]",
                new DslRenderer().render(Dsl.endOfDateRange(springRun).eq(promo.date("/Shop/CheckDate"))));
    }

    @Test
    void ruleGroupSnippet() {
        // tag::rule-group[]
        // Dsl.ruleGroup() is the rule's OWN group (the kernel's RegelKontext) — it renders the reserved
        // keyword, never a path. On a rule iterating a repeatable group it guards "is this row present".
        Condition attachment = Dsl.ruleGroup().filled()
                .and(Dsl.notExactlyOneFieldFilled(m.num("/Order/Items/Count").field(),
                        m.num("/Order/Items/UnitWeight").field()));
        // end::rule-group[]
        assertEquals("GroupFilled(RuleGroup) And NotExactlyOneFieldFilled(/Order/Items/Count, /Order/Items/UnitWeight)",
                new DslRenderer().render(attachment));
    }

    @Test
    void temporalDeclaredFormatSnippet() {
        // tag::temporal-declared-format[]
        // A12 reads a temporal field's DECLARED FORMAT and never its kind, at every gate below the
        // declaration — so a TimeType field declared `yyyy-MM-dd` IS a date: it carries the Gregorian floor,
        // compares against a DATE, and answers the date extractors. The typed handles follow the declaration,
        // so `date(...)` accepts it and no raw condition string is needed for a model that arrives this way.
        Condition promisedAfterShipping = fulfilment.date("/Fulfilment/PromisedDay")
                .after(fulfilment.date("/Fulfilment/ShippedOn"));
        NumberExpr promisedYear = Dsl.yearFromDate(fulfilment.date("/Fulfilment/PromisedDay"));
        // The rule reaches the CONSTRUCTORS too: such a cell is an admitted DateRange endpoint, and a field
        // declared `yyyy` is admitted at Date(...)'s complete-Year position, on any date-bearing kind.
        Condition windowFollowsPromise = fulfilment.dateRange("/Fulfilment/DeliveryWindow")
                .ne(Dsl.dateRange(fulfilment.date("/Fulfilment/PromisedDay"),
                        fulfilment.date("/Fulfilment/ShippedOn")));
        // …and the entity-list EXTREMA: MaxValue/MinValue compare their operands' declared component sets,
        // so this cell is a legal member of a plain DATE list. No factory refuses the mixture; the kernel
        // arbitrates whether the sets agree.
        Condition latestIsPromise = Dsl.latestOf(fulfilment.field("/Fulfilment/PromisedDay"),
                fulfilment.field("/Fulfilment/ShippedOn")).eq(fulfilment.date("/Fulfilment/PromisedDay"));
        // Read the declaration that decided it; `HoursFromTime` on the same cell is refused by the kernel.
        String declared = fulfilment.fieldAt("/Fulfilment/PromisedDay").orElseThrow().format().orElseThrow();
        // …and `temporalClass()` is that declaration's verdict: which classifier the field actually has,
        // whatever its kind says. Use it to DISPATCH on a model you did not author, never to refuse.
        ValueKind classifier = fulfilment.fieldAt("/Fulfilment/PromisedDay").orElseThrow().temporalClass();
        // end::temporal-declared-format[]
        assertEquals("[/Fulfilment/PromisedDay] > [/Fulfilment/ShippedOn]",
                new DslRenderer().render(promisedAfterShipping));
        assertEquals("YearFromDate(/Fulfilment/PromisedDay) == 2024",
                new DslRenderer().render(promisedYear.eq(2024)));
        assertEquals("[/Fulfilment/DeliveryWindow] != DateRange(/Fulfilment/PromisedDay,"
                + " /Fulfilment/ShippedOn)", new DslRenderer().render(windowFollowsPromise));
        assertEquals("MaxValue(/Fulfilment/PromisedDay, /Fulfilment/ShippedOn) == [/Fulfilment/PromisedDay]",
                new DslRenderer().render(latestIsPromise));
        assertEquals("yyyy-MM-dd", declared);
        assertEquals(ValueKind.DATE, classifier, "the declaration decides, not the TIME kind");
        assertKernelAccepts(FULFILMENT_JSON, "/Fulfilment", promisedAfterShipping, "../PromisedDay");
        assertKernelAccepts(FULFILMENT_JSON, "/Fulfilment", promisedYear.eq(2024), "../PromisedDay");
        assertKernelAccepts(FULFILMENT_JSON, "/Fulfilment", windowFollowsPromise, "../DeliveryWindow");
        assertKernelAccepts(FULFILMENT_JSON, "/Fulfilment", latestIsPromise, "../PromisedDay");
    }

    private static void assertKernelAccepts(Condition condition, String errorField) {
        assertKernelAccepts(Fixtures.orderJson(), "/Order", condition, errorField);
    }

    private static void assertKernelAccepts(
            String modelJson, String group, Condition condition, String errorField) {
        var result = new RuleValidator().check(modelJson, new DraftRule(
                group, "GuideSnippet", errorField, new DslRenderer().render(condition),
                "GUIDE", "ERROR"));
        assertTrue(result.ok(), () -> "kernel rejected guide snippet '"
                + new DslRenderer().render(condition) + "' at " + errorField + ": " + result.diagnostics());
    }

    @Test
    void starredValueListSnippet() {
        // tag::starred-value-list[]
        // A value-list quantifier over a flattened repetition list — the starred FIELDS side.
        Condition anyForbidden = Dsl.atLeastOneFieldValueIncludedInValueList(
                List.of("A", "B"), m.flatten("/Order/Items*/Sku"));   // …(/Order/Items*/Sku In "A", "B")
        // end::starred-value-list[]
        assertEquals("AtLeastOneFieldValueIncludedInValueList(/Order/Items*/Sku In \"A\", \"B\")",
                new DslRenderer().render(anyForbidden));
    }

    @Test
    void groupScopeValueListSnippet() {
        // tag::group-scope-value-list[]
        // A value-list quantifier over a GROUP scope — the kernel expands the group's fields (recursively).
        // No kind-typed overload: a group has no kind of its own, so the expansion's kind is kernel-arbitrated.
        Condition addressPlaceholder = Dsl.noFieldValueIncludedInValueList(
                List.of("unknown", "n/a"), m.flattenGroup("/Order/ShippingAddress"));
        // end::group-scope-value-list[]
        assertEquals("NoFieldValueIncludedInValueList(/Order/ShippingAddress In \"unknown\", \"n/a\")",
                new DslRenderer().render(addressPlaceholder));
        assertKernelAccepts(m.num("/Order/Quantity").filled().and(addressPlaceholder), "../Quantity");
    }

    @Test
    void mixedEntityListSnippet() {
        // tag::mixed-entity-list[]
        // ONE entity list, mixed members — the kernel's own shape: each member may be a field or a group,
        // in either order. The homogeneous varargs families stay the ergonomic choice; reach for this when
        // a list genuinely mixes, and on the read path, where a mixed list has to be representable at all.
        Condition partiallyFilled = Dsl.entityListPredicate(Quantifier.FIELDS_NOT_COLLECTIVELY_FILLED,
                List.of(m.flattenGroup("/Order/ShippingAddress"), m.field("/Order/Quantity")));
        // end::mixed-entity-list[]
        assertEquals("FieldsNotCollectivelyFilled(/Order/ShippingAddress, /Order/Quantity)",
                new DslRenderer().render(partiallyFilled));
        assertKernelAccepts(partiallyFilled, "../Quantity");
    }

    @Test
    void modelBoundEntityListSnippet() {
        // tag::model-bound-entity-list[]
        // Through the MODEL, an entity-list aggregate reads each group operand's expansion first, as A12
        // does, so a refusal can name A12's own class on a list that holds a group. Dsl's twin cannot see
        // the expansion, so it refuses the same input with the class left unnamed.
        NumberExpr filledParts = m.numberOfFilledFields(m.flattenGroup("/Order/ShippingAddress"),
                m.field("/Order/Quantity"));
        // One non-repeatable group is too few to count: MVK_PARAMSIZE_INVALIDGN, named because the model
        // shows the group holds a field. An empty group would have answered MVK_GROUP_IS_EMPTY first.
        KernelCodeRefusal tooFew = assertThrows(KernelCodeRefusal.class,
                () -> m.numberOfFilledGroups(m.group("/Order/ShippingAddress")));
        // end::model-bound-entity-list[]
        assertEquals("NumberOfFilledFields(/Order/ShippingAddress, /Order/Quantity) > 1",
                new DslRenderer().render(filledParts.gt(1)));
        assertKernelAccepts(filledParts.gt(1), "../Quantity");
        assertEquals(KernelCode.MVK_PARAMSIZE_INVALIDGN, tooFew.code());
        assertFalse(assertThrows(IllegalArgumentException.class,
                () -> Dsl.numberOfFilledGroups(m.group("/Order/ShippingAddress"))) instanceof KernelCodeRefusal,
                "the Dsl twin must leave the class unnamed, or the snippet's contrast is false");
    }

    @Test
    void numberOfValueSingleListSnippet() {
        // tag::number-of-value-single-list[]
        // NumberOfValueInFields over ONE flattened repetition list — the single-list overload. The kernel's
        // arity gate is "≥2 fields OR ≥1 group", so one starred list (a "group" operand) is legal on its own;
        // the constant may be a number, string, or boolean, and the list may be Having-filtered.
        Condition anyZeroCount = Dsl.numberOfValueInFields(0L, m.flatten("/Order/Items*/Count")).gt(0);
        Condition anyZeroCounted = Dsl.numberOfValueInFields(
                0L, m.flatten("/Order/Items*/Count").having(m.num("/Order/Items/Count").ge(0))).gt(0);
        // end::number-of-value-single-list[]
        assertEquals("NumberOfValueInFields(0 In /Order/Items*/Count) > 0",
                new DslRenderer().render(anyZeroCount));
        assertEquals("NumberOfValueInFields(0 In /Order/Items*/Count Having [/Order/Items/Count] >= 0) > 0",
                new DslRenderer().render(anyZeroCounted));
    }

    @Test
    void enumValueListUnionSnippet() {
        // tag::enum-value-list-union[]
        // An enum value-list quantifier member may be a direct reference OR a category projection
        // [field -> Cat]; each literal rides the UNION of the selected members' domains — "STANDARD" from
        // Priority's stored values, "FAST" from the SpeedClass category (KERNEL-FINDINGS §8).
        Condition standardOrFast = Dsl.atLeastOneFieldValueIncludedInValueList(
                List.of("STANDARD", "FAST"),
                m.enumOf("/Order/Priority"),
                m.enumOf("/Order/Priority").category("SpeedClass"));
        // end::enum-value-list-union[]
        assertEquals("AtLeastOneFieldValueIncludedInValueList(/Order/Priority,"
                + " /Order/Priority -> SpeedClass In \"STANDARD\", \"FAST\")",
                new DslRenderer().render(standardOrFast));
    }

    @Test
    void enumerationConcatenationSnippet() {
        // tag::enum-concatenation[]
        // An enumeration joins text through plus. It contributes its STORED value, never a label, and renders
        // braced, which A12 admits whatever the labels (KERNEL-FINDINGS KF396, KF530).
        StringExpr label = m.enumOf("/Order/Priority").plus(" / ").plus(m.str("/Order/ProductCode").asString());
        Condition staleName = m.str("/Order/CustomerName").ne(label);
        // end::enum-concatenation[]
        assertEquals("[/Order/CustomerName] != { [/Order/Priority] } + \" / \" + [/Order/ProductCode]",
                new DslRenderer().render(staleName));
        assertKernelAccepts(staleName, "../CustomerName");
    }

    @Test
    void selectedConcatenationSnippet() {
        // tag::selected-concatenation[]
        // A key-selected enumeration cell joins text by its stored value, braced like a direct read; a category
        // projection joins by its category value (KERNEL-FINDINGS KF396, KF530).
        StringExpr channel = promo.enumOf("/Shop/Runs/Channel").forIndex(promo.field("/Shop/Season")).plus(" / ")
                .plus(promo.str("/Shop/Banner").asString());
        Condition staleBanner = promo.str("/Shop/Banner").ne(channel);
        StringExpr speed = m.enumOf("/Order/Priority").category("SpeedClass").plus("-")
                .plus(m.str("/Order/ProductCode").asString());
        Condition staleName = m.str("/Order/CustomerName").ne(speed);
        // end::selected-concatenation[]
        assertEquals("[/Shop/Banner] != { [/Shop/Runs/Channel For /Shop/Season] } + \" / \" + [/Shop/Banner]",
                new DslRenderer().render(staleBanner));
        assertEquals("[/Order/CustomerName] != [/Order/Priority -> SpeedClass] + \"-\" + [/Order/ProductCode]",
                new DslRenderer().render(staleName));
        assertKernelAccepts(PROMO_JSON, "/Shop", staleBanner, "../Banner");
        assertKernelAccepts(staleName, "../CustomerName");
    }

    @Test
    void selectedFlagSnippet() {
        // tag::selected-flag[]
        // A key-selected Boolean cell compares with True or False like a Boolean field; a confirm cell's
        // ConfirmExpr, like ConfirmRef, offers only the True comparisons.
        Condition featured = promo.bool("/Shop/Runs/Featured").forIndex(promo.field("/Shop/Season")).isTrue();
        // end::selected-flag[]
        assertEquals("[/Shop/Runs/Featured For /Shop/Season] == True", new DslRenderer().render(featured));
        assertKernelAccepts(PROMO_JSON, "/Shop", featured, "../Season");
    }

    @Test
    void keyedTemporalOperandSnippet() {
        // tag::keyed-temporal-operand[]
        // A key-selected cell stands where a temporal operation reads ONE field: the source of ValueAsDate,
        // and a component of a Date construction (KERNEL-FINDINGS KF531).
        DateRef checkDate = promo.date("/Shop/CheckDate");
        Condition launchedLate = promo.valueAsDateLastDay(
                promo.date("/Shop/Runs/LaunchedAbout").semanticIndex("spring")).after(checkDate);
        Condition startsLate = Dsl.date(promo.str("/Shop/Runs/StartDay").semanticIndex(promo.field("/Shop/Season")),
                Dsl.temporalComponent("01"), Dsl.temporalComponent("2024")).after(checkDate);
        // end::keyed-temporal-operand[]
        assertEquals("ValueAsDate(/Shop/Runs/LaunchedAbout For \"spring\", LastDay) > [/Shop/CheckDate]",
                new DslRenderer().render(launchedLate));
        assertEquals("Date(/Shop/Runs/StartDay For /Shop/Season, \"01\", \"2024\") > [/Shop/CheckDate]",
                new DslRenderer().render(startsLate));
        assertKernelAccepts(PROMO_JSON, "/Shop", launchedLate, "../CheckDate");
        assertKernelAccepts(PROMO_JSON, "/Shop", startsLate, "../CheckDate");
    }

    @Test
    void computationSuppressionSnippet() {
        // tag::computation-suppress[]
        // errorCodesToSuppress — the kernel's model-level escape hatch (domain-checked): it lets a
        // scale-mismatched operation load (the static assignment-scale gate, MVK_INVALID_COMPARE_DEC_PLACES).
        NumberExpr product = m.num("/Order/UnitPrice").asNumber().times(m.num("/Order/UnitPrice").asNumber());
        Computation comp = m.computation(m.num("/Order/BackorderQuantity"))
                .name("BackorderProjection")
                .calc(product.expression())              // scale-4 product into a scale-0 target
                .errorCodesToSuppress("MVK_INVALID_COMPARE_DEC_PLACES")
                .message(Dsl.en("Backorder derived from the unit price."))
                .build();
        // end::computation-suppress[]
        assertTrue(new RuleValidator().check(Fixtures.orderJson(), comp.draft()).ok());
    }

    @Test
    void computationDeclaringGroupSnippet() {
        // tag::computation-group[]
        // A computation LIVES in one group and WRITES to another. `group(...)` declares the element in the
        // policy container while it computes the stock block's field; the stored computedFieldRelPath is
        // derived from the pair, never authored. The one refusal is CONTAINMENT UNDER DERIVED ITERATION:
        // when the computation iterates, the computed field must lie at or below this group
        // (MVK_ERROR_FIELD_NOT_IN_RULEGROUP). It iterates from a per-row operand OR from a repeatable target.
        Computation floorPolicy = depot.computation(depot.num("/Depot/Stock/StockLevel"))
                .name("StockFloorPolicy")                        // → /Depot/Policy/StockFloorPolicy
                .group(depot.group("/Depot/Policy"))
                .calc(depot.num("/Depot/Policy/MinimumOnHand").value())
                .message(Dsl.en("Stock level follows the depot's minimum-on-hand policy."))
                .build();
        // end::computation-group[]
        assertEquals("/Depot/Policy", floorPolicy.groupPath());
        assertEquals("../../Stock/StockLevel", floorPolicy.computedFieldRelPath());
        assertTrue(new RuleValidator().check(DEPOT_JSON, floorPolicy.draft()).ok());
    }
}
