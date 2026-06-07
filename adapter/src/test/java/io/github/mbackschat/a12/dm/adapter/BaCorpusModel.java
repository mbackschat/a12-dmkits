package io.github.mbackschat.a12.dm.adapter;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * The unified own-domain model for the BA-derived DSL corpus ({@link BaCorpusTest}). A logistics
 * <em>Shipment</em> domain, rich enough to host every construct the kernel-team BA reference exercises:
 * fields of every value kind (string/number/enum+category/boolean/confirm/date/time/date-time/
 * date-fragment/date-range, plus bounded integers and a DD.MM.yyyy date), repeatable + nested groups
 * (for {@code *} flatten, aggregates, iteration, {@code $} correlation), and confirm flags for
 * {@code NumberOfValueInFields}. Own field names + business content — the BA examples' <em>mechanisms</em>
 * are transferred here, never their text (license-safe rule).
 */
final class BaCorpusModel {

    private BaCorpusModel() {
    }

    static String json() {
        return DocumentModelDraft.named("shipment").locale("en_US")
                .rootGroup("Shipment", s -> s
                        .stringField("ShipmentRef", b -> { })
                        .stringField("Recipient", b -> { })
                        .enumField("Channel", e -> e.value("ROAD").value("RAIL").value("AIR").value("SEA")
                                .category("Speed", "SLOW", "SLOW", "FAST", "SLOW"))
                        .enumField("Priority", e -> e.value("LOW").value("NORMAL").value("HIGH"))
                        .numberField("Weight", n -> n.fractionDigits(2))
                        .numberField("DeclaredValue", n -> n.fractionDigits(2))
                        .numberField("ParcelCount", n -> { })
                        .numberField("BackorderCount", n -> { })
                        .numberField("RoundedWeight", n -> { })
                        .field("IsExpress", io.github.mbackschat.a12.dm.modelkit.FieldKind.BOOLEAN)
                        .field("TermsAccepted", io.github.mbackschat.a12.dm.modelkit.FieldKind.CONFIRM)
                        .field("OptInA", io.github.mbackschat.a12.dm.modelkit.FieldKind.CONFIRM)
                        .field("OptInB", io.github.mbackschat.a12.dm.modelkit.FieldKind.CONFIRM)
                        .field("OptInC", io.github.mbackschat.a12.dm.modelkit.FieldKind.CONFIRM)
                        .dateField("DispatchDate", d -> d.format("yyyy-MM-dd"))
                        .dateField("ArrivalDate", d -> d.format("yyyy-MM-dd"))
                        .dateField("DispatchDateLocal", d -> d.format("dd.MM.yyyy"))
                        .timeField("PickupTime", t -> t.format("HH:mm:ss"))
                        .timeField("WindowStart", t -> t.format("HH:mm:ss"))
                        .timeField("WindowEnd", t -> t.format("HH:mm:ss"))
                        .dateTimeField("ScheduledAt", dt -> dt.format("yyyy-MM-dd'T'HH:mm:ss"))
                        .dateRangeField("CoverPeriod", r -> r.format("dd.MM.yyyy").separator("-"))
                        .dateFragmentField("BillingMonth", f -> f.fragmentFormat("yyyy-MM"))
                        .numberField("Day", n -> n.min("1").max("31"))
                        .numberField("Month", n -> n.min("1").max("12"))
                        .numberField("Year", n -> n.min("1900").max("2100"))
                        .group("BillingAddress", g -> g
                                .stringField("Street", b -> { })
                                .stringField("City", b -> { }))
                        .group("Parcels", g -> g.repeatable(50).indexField("Tag")
                                .stringField("Tag", b -> { })
                                .numberField("ParcelWeight", n -> n.fractionDigits(2))
                                .numberField("ParcelValue", n -> n.fractionDigits(2))
                                .dateField("ParcelDate", d -> d.format("yyyy-MM-dd"))
                                .dateRangeField("ParcelCoverage", r -> r.format("dd.MM.yyyy").separator("-")))
                        .group("Stops", g -> g.repeatable(50)
                                .stringField("StopName", b -> { })
                                .dateField("StopDate", d -> d.format("yyyy-MM-dd"))))
                .build().toJson();
    }
}
