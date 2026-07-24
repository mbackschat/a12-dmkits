package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;

/**
 * KERNEL-FINDINGS — the single-list {@code NumberOfValueInFields} form (DG21 → API-FINDINGS finding).
 *
 * <p>The kernel's arity gate for {@code NumberOfValueInFields} is "at least two fields OR at least one group"
 * ({@code MVK_PARAMSIZE_INVALIDN}), so a SINGLE flattened repetition list ({@code Group*}{@code /field}, a
 * "group" operand) is legal — {@code NumberOfValueInFields(1 In Items*}{@code /Qty)}. The typed single-list
 * factory overloads ({@code value × FieldListRef}, one per constant family) author exactly that; the rendered
 * output is proven kernel-legal here (F4), including the optional {@code Having} filter.
 */
class NumberOfValueInFieldsSingleListLawsTest {

    private static final String MODEL = DocumentModelDraft.named("orders").locale("en_US")
        .rootGroup("Order", (Consumer<GroupBuilder>) o -> {
            o.stringField("CustomerName", b -> { });
            o.group("Items", (Consumer<GroupBuilder>) items -> {
                items.repeatable(10);
                items.numberField("Qty", b -> b.fractionDigits(0, 0));
                items.stringField("Sku", b -> { });
                items.field("Gift", FieldKind.BOOLEAN);
            });
        })
        .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(MODEL);

    private static String cond(Condition c) {
        return new DslRenderer().render(c);
    }

    /** Guard the aggregate with {@code FieldFilled(CustomerName) And …} so the probe rule references its own
     *  error field ({@code MVK_ERROR_FIELD_NOT_REFERENCED} would otherwise mask what is being tested). */
    private static String guarded(NumberExpr aggregate) {
        return cond(Dsl.fieldFilled(M.field("/Order/CustomerName")).and(aggregate.gt(0)));
    }

    // The default DslRenderer (no group base) renders ABSOLUTE paths; a rule rendered relative to its group
    // (the KernelLaws acceptance path below) uses the group-relative `Items*`/`[Items/…]` form. Both are the
    // same NumberOfValueInFields node — the render locks the value × single-list mapping the overload builds.
    @Test
    void singleListFormsRenderTheKernelString() {
        assertEquals("NumberOfValueInFields(0 In /Order/Items*/Qty) > 0",
            cond(Dsl.numberOfValueInFields(0L, M.flatten("/Order/Items*/Qty")).gt(0)));
        assertEquals("NumberOfValueInFields(\"SKU-1\" In /Order/Items*/Sku) > 0",
            cond(Dsl.numberOfValueInFields("SKU-1", M.flatten("/Order/Items*/Sku")).gt(0)));
        assertEquals("NumberOfValueInFields(True In /Order/Items*/Gift) > 0",
            cond(Dsl.numberOfValueInFields(true, M.flatten("/Order/Items*/Gift")).gt(0)));
    }

    @Test
    void filteredSingleListRendersTheHaving() {
        FieldListRef filtered = M.flatten("/Order/Items*/Qty").having(M.num("/Order/Items/Qty").gt(0));
        assertEquals("NumberOfValueInFields(1 In /Order/Items*/Qty Having [/Order/Items/Qty] > 0) > 0",
            cond(Dsl.numberOfValueInFields(1L, filtered).gt(0)));
    }

    @Test
    void theKernelAcceptsEverySingleListForm() {
        KernelLaws.acceptsModel(MODEL, "/Order", "../CustomerName",
            guarded(Dsl.numberOfValueInFields(0L, M.flatten("/Order/Items*/Qty"))));
        KernelLaws.acceptsModel(MODEL, "/Order", "../CustomerName",
            guarded(Dsl.numberOfValueInFields("SKU-1", M.flatten("/Order/Items*/Sku"))));
        KernelLaws.acceptsModel(MODEL, "/Order", "../CustomerName",
            guarded(Dsl.numberOfValueInFields(true, M.flatten("/Order/Items*/Gift"))));
    }

    @Test
    void theKernelAcceptsTheFilteredSingleListForm() {
        FieldListRef filtered = M.flatten("/Order/Items*/Qty").having(M.num("/Order/Items/Qty").gt(0));
        KernelLaws.acceptsModel(MODEL, "/Order", "../CustomerName",
            guarded(Dsl.numberOfValueInFields(1L, filtered)));
    }
}
