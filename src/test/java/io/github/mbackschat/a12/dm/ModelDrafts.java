package io.github.mbackschat.a12.dm;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * Test models authored with <b>modelkit</b> instead of hand-written DM-JSON — the dogfood of the
 * structure builder (the peer of {@link Fixtures}, which loads committed {@code .dm.json}). Proving
 * these load and validate exactly like the hand-written fixtures is the payoff: structure no longer
 * has to be maintained as raw JSON. {@code logistics} has <b>no</b> committed JSON counterpart at all
 * — it lives only here ({@code DogfoodTest} + {@code IterationLawsTest} are its only definition).
 */
public final class ModelDrafts {

    private ModelDrafts() {
    }

    /** The logistics model — two index-keyed repeatable groups (for parallel iteration). */
    public static DocumentModelDraft logistics() {
        return DocumentModelDraft.named("logistics").locale("en_US")
                .rootGroup("Shipment", s -> s
                        .field("ShipmentRef", FieldKind.STRING)
                        .group("Lines", g -> g
                                .repeatable(50).indexField("LineRef")
                                .field("LineRef", FieldKind.STRING)
                                .field("OrderedQty", FieldKind.NUMBER))
                        .group("Picks", g -> g
                                .repeatable(50).indexField("LineRef")
                                .field("LineRef", FieldKind.STRING)
                                .field("PickedQty", FieldKind.NUMBER)))
                .build();
    }

    /** The order model — every field kind, address groups, an index-keyed item list, a categorized enum. */
    public static DocumentModelDraft order() {
        return DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o
                        .field("CustomerName", FieldKind.STRING)
                        .field("ProductCode", FieldKind.STRING)
                        .field("Quantity", FieldKind.NUMBER)
                        .numberField("UnitPrice", n -> n.fractionDigits(2))
                        .field("BackorderQuantity", FieldKind.NUMBER)
                        .field("ExpressShipping", FieldKind.BOOLEAN)
                        .field("TermsConfirmed", FieldKind.CONFIRM)
                        .group("ShippingAddress", a -> a
                                .field("Street", FieldKind.STRING)
                                .field("City", FieldKind.STRING))
                        .group("BillingAddress", a -> a
                                .field("PostalCode", FieldKind.STRING))
                        .group("Items", i -> i
                                .repeatable(50).indexField("Sku")
                                .field("Sku", FieldKind.STRING)
                                .field("Count", FieldKind.NUMBER)
                                .numberField("UnitWeight", n -> n.fractionDigits(2)))
                        .enumField("Priority", e -> e
                                .value("STANDARD").value("EXPRESS").value("OVERNIGHT")
                                .category("SpeedClass", "NORMAL", "FAST", "FAST"))
                        .dateField("OrderDate", d -> d.format("yyyy-MM-dd"))
                        .dateField("DeliveryDate", d -> d.format("yyyy-MM-dd"))
                        .timeField("PickupTime", t -> t.format("HH:mm:ss"))
                        .dateTimeField("ScheduledAt", dt -> dt.format("yyyy-MM-dd'T'HH:mm:ss"))
                        .numberField("BirthDay", n -> n.min("1").max("31"))
                        .numberField("BirthMonth", n -> n.min("1").max("12"))
                        .numberField("BirthYear", n -> n.min("1900").max("2100")))
                .build();
    }
}
