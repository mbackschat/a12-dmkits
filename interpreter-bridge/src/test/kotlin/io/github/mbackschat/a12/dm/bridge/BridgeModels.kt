package io.github.mbackschat.a12.dm.bridge

import io.github.mbackschat.a12.dm.modelkit.DatePrecision
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.modelkit.ModelDocument
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity

/** Typed modelkit/rulekit fixtures shared by the kernel-free bridge tests. */
internal object BridgeModels {

    val orderJson: String by lazy {
        // Declares a base year because `BaseYear` is only legal in a model that does, and several tests
        // here exercise it. Inert for the temporal component-set supplementation this also feeds: every
        // temporal format below carries YEAR already, so there is nothing for it to supplement.
        DocumentModelDraft.named("order-code-equals").locale("en_US").baseYear(2024)
            .rootGroup("Order") { order ->
                order
                    .field("CustomerName", FieldKind.STRING)
                    .field("ProductCode", FieldKind.STRING)
                    .numberField("Quantity") { it.fractionDigits(0) }
                    .numberField("StockOnHand") { it.fractionDigits(0) }
                    .numberField("UnitPrice") { it.fractionDigits(2) }
                    .numberField("BackorderQuantity") { it.fractionDigits(0) }
                    .field("ExpressShipping", FieldKind.BOOLEAN)
                    .field("TermsConfirmed", FieldKind.CONFIRM)
                    .group("ShippingAddress") { address ->
                        address.field("Street", FieldKind.STRING).field("City", FieldKind.STRING)
                    }
                    .group("BillingAddress") { address ->
                        address.field("PostalCode", FieldKind.STRING)
                    }
                    .group("Items") { items ->
                        items.repeatable(50).indexField("Sku")
                            .field("Sku", FieldKind.STRING)
                            .numberField("Count") { it.fractionDigits(0) }
                            .numberField("StockQty") { it.fractionDigits(0) }
                            .numberField("UnitWeight") { it.fractionDigits(2) }
                            .dateRangeField("ItemCoverage") { it.format("yyyy-MM-dd").separator("/") }
                            .dateField("PromiseDate") { it.format("yyyy-MM-dd") }
                    }
                    .enumField("Priority") {
                        it.value("STANDARD").value("EXPRESS").value("OVERNIGHT")
                            .category("SpeedClass", "NORMAL", "FAST", "FAST")
                    }
                    .dateField("OrderDate") { it.format("yyyy-MM-dd") }
                    .dateField("DeliveryDate") { it.format("yyyy-MM-dd") }
                    .timeField("PickupTime") { it.format("HH:mm:ss") }
                    .dateTimeField("ScheduledAt") { it.format("yyyy-MM-dd'T'HH:mm:ss") }
                    .dateTimeField("CompletedAt") { it.format("yyyy-MM-dd'T'HH:mm:ss") }
                    .enumField("RushLevel") { it.value("1").value("2").value("3") }
                    .dateField("ApproxDate") {
                        it.format("yyyy-MM-dd").datePrecision(DatePrecision.DAY_OPTIONAL)
                    }
                    .numberField("BirthDay") { it.min("1").max("31") }
                    .numberField("BirthMonth") { it.min("1").max("12") }
                    .numberField("BirthYear") { it.min("1900").max("2100") }
                    .dateRangeField("CoverageWindow") { it.format("yyyy-MM-dd").separator("/") }
            }
            .build().toJson()
    }

    val enumRowsJson: String by lazy {
        DocumentModelDraft.named("enum-rows").locale("en_US")
            .rootGroup("Basket") { basket ->
                basket.field("Reference", FieldKind.STRING)
                    .group("Lines") { lines ->
                        lines.repeatable(20)
                            .field("Label", FieldKind.STRING)
                            .enumField("Status") { it.value("OPEN").value("SHIPPED") }
                    }
            }
            .build().toJson()
    }

    fun shipmentJson(): String {
        val model = BoundModel.ofJson(DocumentModelDraft.named("port").locale("en_US")
            .rootGroup("Shipment") { it.field("Carrier", FieldKind.STRING) }
            .build().toJson())
        return model.add(model.rule(model.group("/Shipment"))
            .name("CarrierNamed")
            .errorField(model.str("/Shipment/Carrier"))
            .`when`(model.str("/Shipment/Carrier").notFilled())
            .code("C").severity(Severity.ERROR).message(Dsl.en("x")).build())
            .toJson()
    }

    fun germanBasketJson(): String {
        val structure = ModelDocument.read(DocumentModelDraft.named("warenkorb-de").locale("de_DE")
            .rootGroup("Bestellung") {
                it.field("Kundennummer", FieldKind.STRING)
                    .field("Bestellnummer", FieldKind.STRING)
            }
            .build().toJson())
            .setConditionLanguage("de_DE")
            .setFieldRefByShortNameAllowed(false)
            .toJson()
        val model = BoundModel.ofJson(structure)
        return model.add(model.rule(model.group("/Bestellung"))
            .name("KundennummerAngegeben")
            .errorField(model.str("/Bestellung/Kundennummer"))
            .`when`(model.str("/Bestellung/Kundennummer").filled()
                .and(model.str("/Bestellung/Bestellnummer").notFilled()))
            .code("KD_PFLICHT").severity(Severity.ERROR).message(Dsl.de("x")).build())
            .toJson()
    }

    fun withLineComment(json: String): String = json.replaceFirst(
        "\"type\" : \"Field\"",
        "// the carrier a shipment was booked with\n    \"type\" : \"Field\"",
    )
}
