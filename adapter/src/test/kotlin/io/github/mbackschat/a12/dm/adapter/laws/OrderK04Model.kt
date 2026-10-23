package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft

/**
 * The order a12-kernel-lean's K04 checkpoints measure on, rebuilt to the shape their captures describe: keyed
 * lines holding unkeyed parts and keyed slots, keyed picks joining the lines on `LineNo`, keyed gifts with a
 * fixed card, and unkeyed wraps with fixed ribbon and tag (optionally keyed bows, and gift tags keyed like the gifts).
 * One builder, because the static
 * agreement and the runtime differential must measure the same model.
 */
internal object OrderK04Model {
    fun json(withBows: Boolean = false, withGiftTags: Boolean = false, partCapacity: Int = 5): String = DocumentModelDraft.named("order-k04").locale("en_US")
        .rootGroup("Order") { o -> o
            .stringField("Email") { }
            .stringField("Phone") { }
            .stringField("Tier") { }
            .numberField("Budget") { n -> n.fractionDigits(0) }
            .group("Lines") { l -> l.repeatable(50).indexField("LineNo")
                .stringField("LineNo") { }
                .stringField("Sku") { }
                .stringField("Code") { }
                .numberField("Qty") { n -> n.fractionDigits(0) }
                .numberField("Price") { n -> n.fractionDigits(2) }
                .numberField("Day") { n -> n.fractionDigits(0) }
                .numberField("Month") { n -> n.fractionDigits(0) }
                .group("Parts") { p -> p.repeatable(partCapacity)
                    .stringField("PartNo") { }
                    .numberField("Count") { n -> n.fractionDigits(0) } }
                .group("Slots") { s -> s.repeatable(2).indexField("SlotNo")
                    .stringField("SlotNo") { }
                    .stringField("Mark") { } } }
            .group("Picks") { p -> p.repeatable(10).indexField("LineNo")
                .stringField("LineNo") { }
                .stringField("Ref") { } }
            .group("Gifts") { g -> g.repeatable(2).indexField("GiftNo")
                .stringField("GiftNo") { }
                .stringField("Note") { }
                .group("Card") { c -> c.stringField("Text") { } } }
            .apply {
                if (withGiftTags) group("Tags") { t -> t.repeatable(2).indexField("GiftNo")
                    .stringField("GiftNo") { }
                    .stringField("Label") { } }
            }
            .group("Wraps") { w -> w.repeatable(2)
                .stringField("Style") { }
                .group("Ribbon") { r -> r.stringField("Color") { } }
                .group("Tag") { t -> t.stringField("Label") { } }
                .apply {
                    if (withBows) group("Bows") { b -> b.repeatable(3).indexField("BowNo")
                        .stringField("BowNo") { }
                        .stringField("Shade") { } }
                } } }
        .build().toJson()
}
