package io.github.mbackschat.a12.dm.rulekit.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The catalog must expose every typed bare-group overload and its computation-sensitive traversal law. */
class BareGroupFillCatalogTest {

    private static final List<String> OPERATORS = List.of(
            "AllFieldsFilled",
            "NoFieldFilled",
            "AtLeastOneFieldFilled",
            "NotAllFieldsFilled",
            "FieldsNotCollectivelyFilled",
            "NotExactlyOneFieldFilled",
            "MoreThanOneFieldFilled");

    @Test
    void allSevenFieldFillQuantifiersExposeGroupScopeAndLazyDeclarationOrder() {
        OperatorCatalog catalog = OperatorCatalog.load();
        for (String id : OPERATORS) {
            OperatorEntry entry = catalog.byId(id).orElseThrow();
            assertEquals(List.of("field-list-or-groups"),
                    entry.operands().stream().map(OperatorEntry.Operand::type).toList(), id);
            assertTrue(entry.constraints().stream().anyMatch(text -> text.contains("group")),
                    () -> id + " must explain the group operand");
            assertTrue(entry.gotchas().stream().anyMatch(gotcha ->
                            gotcha.kind() == GotchaKind.SURPRISE
                                    && gotcha.text().contains("declaration order")
                                    && gotcha.text().contains("stops")),
                    () -> id + " must expose declaration-order lazy stopping");
            assertTrue(entry.validExample().dsl().contains("(ShippingAddress)"),
                    () -> id + " must carry a kernel-executed bare-group example");
        }
    }
}
