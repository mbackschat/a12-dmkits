package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;

class BulkWritePathTest {

    @Test
    void addsAWholeTypedProgramPopulationWithOneTerminalValidation() {
        BoundModel model = BoundModel.ofJson(Fixtures.subscriptionJson());
        Rule positiveFee = model.rule(model.group("/Subscription/Addons"))
                .name("BulkPositiveFee")
                .errorField(model.num("/Subscription/Addons/MonthlyFee"))
                .when(model.num("/Subscription/Addons/MonthlyFee").filled()
                        .and(model.num("/Subscription/Addons/MonthlyFee").le(0)))
                .code("BULK_FEE")
                .severity(Severity.ERROR)
                .message(Dsl.en("Fee must be positive."), Dsl.de("Gebühr muss positiv sein."))
                .build();
        Rule billingPresent = model.rule(model.group("/Subscription/Billing"))
                .name("BulkBillingPresent")
                .errorField(model.num("/Subscription/Billing/BaseFee"))
                .when(model.num("/Subscription/Billing/BaseFee").notFilled())
                .code("BULK_BILLING")
                .severity(Severity.ERROR)
                .message(Dsl.en("Billing is required."), Dsl.de("Abrechnung ist erforderlich."))
                .build();
        Computation effectiveFee = model.computation(
                        model.num("/Subscription/Billing/EffectiveFee"))
                .name("BulkEffectiveFee")
                .calc(model.num("/Subscription/Billing/BaseFee").value())
                .message(
                        Dsl.en("Effective fee follows base fee."),
                        Dsl.de("Die Effektivgebühr folgt der Basisgebühr."))
                .build();

        // tag::bulk-program-write[]
        BoundModel written = model.addAll(
                List.of(positiveFee, billingPresent),
                List.of(effectiveFee));
        // end::bulk-program-write[]

        assertTrue(written.toJson().contains("BulkPositiveFee"));
        assertTrue(written.toJson().contains("BulkBillingPresent"));
        assertTrue(written.toJson().contains("BulkEffectiveFee"));
        assertFalse(model.toJson().contains("BulkPositiveFee"), "the source binding remains immutable");
        assertTrue(BoundModel.ofJson(written.toJson()).toJson().contains("BulkEffectiveFee"));
    }
}
