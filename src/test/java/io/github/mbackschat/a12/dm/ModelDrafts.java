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

    /**
     * Date fragments of several formats with <b>no base year</b> — for the comparability partition
     * (two date(-fragment) values compare only if both carry a year or neither does; a base year would
     * relax that). BA "Date fragments" L4010-4074.
     */
    public static DocumentModelDraft dateFragments() {
        return DocumentModelDraft.named("fragments").locale("en_US")
                .rootGroup("Doc", d -> d
                        .field("Ref", FieldKind.STRING)
                        .dateFragmentField("MonthOnly", f -> f.fragmentFormat("MM"))
                        .dateFragmentField("YearOnly", f -> f.fragmentFormat("yyyy"))
                        .dateField("FullDate", dd -> dd.format("yyyy-MM-dd")))
                .build();
    }

    /**
     * Enum comparability fixture — a string, a texts-less enum, and two texts-bearing enums with matching
     * value-to-text maps. For the {@code ==}/{@code !=} compatibility matrix (BA L1712-1742): a
     * texts-bearing enum is incomparable with a string and with a texts-less enum.
     */
    public static DocumentModelDraft enumComparability() {
        return DocumentModelDraft.named("enums").locale("en_US")
                .rootGroup("Doc", d -> d
                        .field("Str", FieldKind.STRING)
                        .enumField("NoText", e -> e.value("A").value("B"))
                        .enumField("WithText", e -> e
                                .value("A", l -> l.label("en_US", "Alpha"))
                                .value("B", l -> l.label("en_US", "Beta")))
                        .enumField("WithText2", e -> e
                                .value("A", l -> l.label("en_US", "Alpha"))
                                .value("B", l -> l.label("en_US", "Beta"))))
                .build();
    }

    /** The order model — every field kind, address groups, an index-keyed item list, a categorized enum. */
    public static DocumentModelDraft order() {
        return DocumentModelDraft.named("order").locale("en_US").baseYear(2020)
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
                                .numberField("UnitWeight", n -> n.fractionDigits(2))
                                .dateRangeField("ItemCoverage", r -> r.format("dd.MM.yyyy").separator("-")))
                        .enumField("Priority", e -> e
                                .value("STANDARD").value("EXPRESS").value("OVERNIGHT")
                                .category("SpeedClass", "NORMAL", "FAST", "FAST"))
                        .dateField("OrderDate", d -> d.format("yyyy-MM-dd"))
                        .dateField("DeliveryDate", d -> d.format("yyyy-MM-dd"))
                        .timeField("PickupTime", t -> t.format("HH:mm:ss"))
                        .dateTimeField("ScheduledAt", dt -> dt.format("yyyy-MM-dd'T'HH:mm:ss"))
                        .dateTimeField("CompletedAt", dt -> dt.format("yyyy-MM-dd'T'HH:mm:ss"))
                        .enumField("RushLevel", e -> e.value("1").value("2").value("3"))
                        .dateField("ApproxDate", d -> d.format("dd.MM.yyyy")
                                .datePrecision(io.github.mbackschat.a12.dm.modelkit.DatePrecision.DAY_OPTIONAL))
                        .numberField("BirthDay", n -> n.min("1").max("31"))
                        .numberField("BirthMonth", n -> n.min("1").max("12"))
                        .numberField("BirthYear", n -> n.min("1900").max("2100"))
                        .dateRangeField("CoverageWindow", r -> r.format("dd.MM.yyyy").separator("-")))
                .build();
    }
}
