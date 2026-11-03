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
     * The basket model — one repeatable group whose fields sit at TWO owning depths ({@code Lines/Qty} and
     * {@code Lines/Packaging/Weight}), a parallel repeatable group, and a non-repeatable group outside both.
     * That shape is what separates the two rejection codes of {@code SumOfProducts}: sharing a starred group
     * is not the same as sharing an owning group, and a plain field inside the starred group rejects
     * differently from one outside it ({@code SumOfProductsLawsTest}).
     */
    public static DocumentModelDraft basket() {
        return DocumentModelDraft.named("basket").locale("en_US")
                .rootGroup("Basket", b -> b
                        .field("Total", FieldKind.NUMBER)
                        .group("Lines", g -> g
                                .repeatable(3)
                                .field("Qty", FieldKind.NUMBER)
                                .group("Packaging", p -> p.field("Weight", FieldKind.NUMBER)))
                        .group("Vouchers", g -> g.repeatable(3).field("Amount", FieldKind.NUMBER))
                        .group("Fees", g -> g.field("Handling", FieldKind.NUMBER)))
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
                        .dateField("FullDate", dd -> dd.format("yyyy-MM-dd"))
                        // A DATE declared `yyyy` beside the complete one: the pair the `Date(...)` complete-Year
                        // position separates, since that gate reads the declared format and not the kind.
                        .dateField("YearAsDate", dd -> dd.format("yyyy")))
                .build();
    }

    /**
     * Date-range declaration profiles with <b>no base year</b> — for the range comparability partition. Carries
     * a year-only range and a full-date range, plus the fragment and date endpoints each is built from, so a
     * construction and a stored range can be paired both within and across a declaration profile.
     */
    public static DocumentModelDraft dateRangeProfiles() {
        return DocumentModelDraft.named("range-profiles").locale("en_US")
                .rootGroup("Cover", c -> c
                        .field("Ref", FieldKind.STRING)
                        .dateFragmentField("FromYear", f -> f.fragmentFormat("yyyy"))
                        .dateFragmentField("ToYear", f -> f.fragmentFormat("yyyy"))
                        .dateField("ToYearAsDate", d -> d.format("yyyy"))
                        .dateRangeField("YearWindow", r -> r.format("yyyy").separator("/"))
                        .dateField("FromDate", d -> d.format("yyyy-MM-dd"))
                        .dateField("ToDate", d -> d.format("yyyy-MM-dd"))
                        .dateRangeField("DateWindow", r -> r.format("yyyy-MM-dd").separator("/"))
                        .dateRangeField("MonthWindow", r -> r.format("MM").separator("/"))
                        .dateRangeField("DayWindow", r -> r.format("MM-dd").separator("/"))
                        .group("Periods", periods -> periods.repeatable(20).indexField("Key")
                                .field("Key", FieldKind.STRING)
                                .dateRangeField("RowMonthWindow", r -> r.format("MM").separator("/"))))
                .build();
    }

    /** Enum comparability fixture — textless, identity-labelled, and genuinely remapped declarations. */
    public static DocumentModelDraft enumComparability() {
        return DocumentModelDraft.named("enums").locale("en_US")
                .rootGroup("Doc", d -> d
                        .field("Str", FieldKind.STRING)
                        .enumField("NoText", e -> e.value("A").value("B"))
                        .enumField("IdentityText", e -> e
                                .value("A", l -> l.label("en_US", "A"))
                                .value("B", l -> l.label("en_US", "B")))
                        .enumField("WithText", e -> e
                                .value("A", l -> l.label("en_US", "Alpha"))
                                .value("B", l -> l.label("en_US", "Beta")))
                        .enumField("WithText2", e -> e
                                .value("A", l -> l.label("en_US", "Alpha"))
                                .value("B", l -> l.label("en_US", "Beta")))
                        .enumField("ForwardConflict", e -> e
                                .value("A", l -> l.label("en_US", "Alternate"))
                                .value("C", l -> l.label("en_US", "Gamma")))
                        .enumField("ReverseConflict", e -> e
                                .value("C", l -> l.label("en_US", "Alpha"))
                                .value("D", l -> l.label("en_US", "Delta")))
                        .enumField("IdentityMixed", e -> e
                                .value("A", l -> l.label("en_US", "A"))
                                .value("B", l -> l.label("en_US", "Beta")))
                        .enumField("PartialCompatible", e -> e
                                .value("A", l -> l.label("en_US", "Alpha"))
                                .value("C", l -> l.label("en_US", "Gamma")))
                        .enumField("DisjointCompatible", e -> e
                                .value("C", l -> l.label("en_US", "Gamma"))
                                .value("D", l -> l.label("en_US", "Delta"))))
                .build();
    }

    /**
     * The routing model — a shipment's service tier, chosen from a requested/fallback pair whose three enum
     * fields all carry the same {@code ServiceTier} classification over one value domain (so a projection of
     * any of them is assignable to any other), plus an index-keyed repeatable {@code Legs} group and a
     * non-repeatable distance outside it. That shape reaches <b>both</b> halves of the kernel's projected-read
     * class on a computed field: a <em>category</em> projection of the computed field and a
     * <em>semantic-index</em> read of it, each with an accepted different-target control.
     */
    public static DocumentModelDraft routing() {
        return DocumentModelDraft.named("routing").locale("en_US")
                .rootGroup("Routing", root -> root
                        .enumField("SelectedTier", e -> e.value("ECONOMY").value("EXPRESS")
                                .category("ServiceTier", "ECONOMY", "EXPRESS"))
                        .enumField("RequestedTier", e -> e.value("ECONOMY").value("EXPRESS")
                                .category("ServiceTier", "ECONOMY", "EXPRESS"))
                        .enumField("FallbackTier", e -> e.value("ECONOMY").value("EXPRESS")
                                .category("ServiceTier", "ECONOMY", "EXPRESS"))
                        .numberField("PlannedDistance", n -> n.fractionDigits(0))
                        // two STRING peers of the Legs index field, so a semantic-index KEY can be the
                        // computed field itself (the kernel matches the key against the error field too)
                        // and a second, non-target key is available as the accepted control
                        .stringField("PlannedLeg", f -> f.maxLength(10))
                        .stringField("PreferredLeg", f -> f.maxLength(10))
                        .group("Legs", g -> g.repeatable(10).indexField("LegRef")
                                .stringField("LegRef", f -> f.maxLength(10))
                                .numberField("Distance", n -> n.fractionDigits(0)))
                        // a second group that contains nothing of Legs, so a two-entity group predicate can
                        // be written without the pair being a nesting duplicate
                        .group("Depots", d -> d.stringField("DepotRef", f -> f.maxLength(10))))
                .build();
    }

    /**
     * A policy with a repeatable claim list holding a nested repeatable and a non-repeatable group, beside a second
     * repeatable group and a non-repeatable one: the shape every {@code SumOfTerms} admission row is measured on
     * ({@code validate.laws.SumOfTermsTermLawsTest}, the catalog's claim probes).
     */
    public static DocumentModelDraft policyClaims() {
        return policyClaims(DocumentModelDraft.named("policy-sum-of-terms-term").locale("en_US"));
    }

    /** {@link #policyClaims()} with a declared base year, for a term that reads {@code BaseYear}. */
    public static DocumentModelDraft policyClaims(int baseYear) {
        return policyClaims(DocumentModelDraft.named("policy-sum-of-terms-term").locale("en_US").baseYear(baseYear));
    }

    private static DocumentModelDraft policyClaims(DocumentModelDraft.Builder named) {
        return named
                .rootGroup("Policy", p -> p
                        .stringField("Holder", f -> { })
                        .numberField("Total", n -> n.fractionDigits(2))
                        .group("Address", a -> a.numberField("Zip", n -> n.fractionDigits(0)))
                        .group("Vehicles", v -> v.repeatable(2).numberField("Value", n -> n.fractionDigits(0)))
                        .group("Claims", c -> c.repeatable(3).indexField("ClaimRef")
                                .stringField("ClaimRef", f -> { })
                                .stringField("Status", f -> { })
                                .numberField("Amount", n -> n.fractionDigits(2))
                                .group("Assessment", g -> g.numberField("Score", n -> n.fractionDigits(0)))
                                .group("Parts", pp -> pp.repeatable(4)
                                        .numberField("Qty", n -> n.fractionDigits(0))
                                        .numberField("Price", n -> n.fractionDigits(2)))))
                .build();
    }

    /**
     * Shipping — an index group with a repeatable group BELOW it ({@code Packages}/{@code Parcels}), one with a
     * repeatable group ABOVE it ({@code Regions}/{@code Stores}), and a repeatable group with no index group on
     * its chain ({@code Lines}): the three star placements a semantic index is judged by (KERNEL-FINDINGS KF351).
     */
    public static DocumentModelDraft shipping() {
        return DocumentModelDraft.named("shipping").locale("en_US")
                .rootGroup("Order", o -> o
                        .field("Title", FieldKind.STRING)
                        .group("Packages", g -> g.repeatable(5).indexField("PackageCode")
                                .field("PackageCode", FieldKind.STRING)
                                .field("Carrier", FieldKind.STRING)
                                .group("Parcels", p -> p.repeatable(5).field("Label", FieldKind.STRING)
                                        .field("Tracking", FieldKind.STRING))
                                .group("Dimensions", d -> d.field("Length", FieldKind.NUMBER)
                                        .field("Width", FieldKind.NUMBER))
                                .group("Schedule", s -> s.dateField("ShippedOn", f -> f.format("yyyy-MM-dd"))
                                        .dateField("DeliveredOn", f -> f.format("yyyy-MM-dd"))))
                        .group("Regions", g -> g.repeatable(5).field("RegionName", FieldKind.STRING)
                                .group("Stores", s -> s.repeatable(5).indexField("StoreCode")
                                        .field("StoreCode", FieldKind.STRING).field("Manager", FieldKind.STRING)))
                        .group("Lines", g -> g.repeatable(5).field("Sku", FieldKind.STRING)))
                .build();
    }

    /** A repeatable root group (multi-form) + a plain root group — for the NumberOfFilledGroups root-group rule. */
    public static DocumentModelDraft repeatableRoot() {
        return DocumentModelDraft.named("multiform").locale("en_US")
                .rootGroup("Persons", p -> p.repeatable(50)
                        .field("Name", FieldKind.STRING))
                .rootGroup("Header", h -> h
                        .field("Title", FieldKind.STRING))
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
