package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.KernelConditionReadBack;
import io.github.mbackschat.a12.dm.bridge.InterpreterConditionReadBack;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.spi.ConditionReadBack;

/**
 * The bridge's READ of a {@code $}-correlated comparison, per declared kind — the control the typed-authoring
 * half ({@code validate.laws.CorrelatedBooleanComparisonLawsTest}) structurally cannot be.
 *
 * <p><b>Why it is a separate file rather than a row there.</b> That class proves the kernel admits the shape
 * and that rulekit can now author it. Neither says the bridge THREADS the marker on the way back in: typing a
 * comparison operand rebuilds the model handle from the path alone, and a rebuild that drops the marker
 * produces a legal, well-typed, silently WRONG condition — it reads a different row and renders identically to
 * the uncorrelated one. Measured: with the read side reverted, every assertion in the authoring class still
 * passed. A guard whose mutant survives is a claim, so this is the control that makes it a mechanism.
 *
 * <p>The carrier is the {@code CurrentRepetition} catalog example with one conjunct swapped — the same
 * kernel-admitted shape the authoring class uses, and for the same reason: two earlier hand-built carriers
 * were refused by gates upstream of the leaf under test.
 *
 * <p>Own domain (warehouse stock counting).
 */
class CorrelatedScalarComparisonReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);
    private static final String BIN = "/Warehouse/Aisles/Bins";

    private static final String WAREHOUSE = DocumentModelDraft.named("warehouse-read-correlated")
            .locale("en_US")
            .rootGroup("Warehouse", warehouse -> warehouse
                    .group("Aisles", aisles -> aisles.repeatable(20)
                            .stringField("Code", field -> { })
                            .group("Bins", bins -> bins.repeatable(20)
                                    .numberField("Quantity", field -> { })
                                    .field("Recounted", FieldKind.BOOLEAN)
                                    .field("Signed", FieldKind.CONFIRM))))
            .build().toJson();

    /**
     * Every declared kind whose typed handle can carry the marker, read back through the bridge and compared
     * against the KERNEL provider's read of the same rule — so neither side can be wrong alone. The NUMBER row
     * is the one that already worked; the two below it are the ones that silently did not.
     */
    @Test
    void theBridgeKeepsTheCorrelationMarkerOnEveryScalarKindTheKernelAdmits() {
        Map<String, String> kernelReads = new LinkedHashMap<>();
        Map<String, String> bridgeReads = new LinkedHashMap<>();
        Map<String, String> leaves = new LinkedHashMap<>();
        leaves.put("NUMBER", "[" + BIN + "/Quantity] == [$" + BIN + "/Quantity]");
        leaves.put("BOOLEAN", "[$" + BIN + "/Recounted] == True");
        leaves.put("CONFIRM", "[$" + BIN + "/Signed] == True");

        leaves.forEach((kind, leaf) -> {
            String json = ruledModel(kind, leaf);
            // The splice lands DOWNSTREAM of every gate of ours, so the kernel's own admission is asserted
            // here rather than assumed: a refused model reads back as whatever the converters make of it,
            // and comparing two reads of an illegal rule would agree while measuring nothing.
            assertEquals(java.util.List.of(), RuntimeLaws.consistencyMessages(json),
                    () -> kind + ": the carrier must be kernel-admitted before its read-back means anything");
            kernelReads.put(kind, render(new KernelConditionReadBack(), json, kind));
            bridgeReads.put(kind, render(new InterpreterConditionReadBack(), json, kind));
        });

        assertEquals(kernelReads, bridgeReads, "the bridge must read what the kernel reads, marker included");
        bridgeReads.forEach((kind, rendered) -> assertEquals(1, countMarkersIn(rendered, kind),
                () -> kind + ": the correlated leaf lost its $ on the way back — " + rendered));
    }

    /**
     * The marker count the leaf itself contributes, past the two `CurrentRepetition` arms that carry one
     * unconditionally. Counting rather than string-matching is what keeps this from passing on the carrier's
     * own markers when the leaf's is gone.
     */
    private static int countMarkersIn(String rendered, String kind) {
        String leafField = switch (kind) {
            case "NUMBER" -> "/Quantity]";
            case "BOOLEAN" -> "/Recounted]";
            default -> "/Signed]";
        };
        return rendered.split("\\$" + BIN.replace("/", "/") + leafField, -1).length - 1;
    }

    /** The catalog's admitted condition with its final conjunct replaced, spliced in as raw DM-JSON. */
    private static String ruledModel(String ruleName, String correlatedLeaf) {
        String condition = "FieldFilled(" + BIN + "/Quantity) And "
                + "AtLeastOneFieldFilled(/Warehouse/Aisles*/Bins*/Quantity Having "
                + "CurrentRepetition(" + BIN + ") != CurrentRepetition($" + BIN + ") And "
                + correlatedLeaf + ")";
        return RuntimeLaws.withRule(WAREHOUSE, "/Warehouse", ruleName,
                "../Aisles/Bins/Quantity", "WH", condition);
    }

    private static String render(ConditionReadBack provider, String json, String ruleName) {
        ReadResult result = provider.readAll(json, ReferenceResolvers.selfContained())
                .get("/Warehouse/" + ruleName);
        return RENDER.render(assertInstanceOf(ReadResult.Converted.class, result,
                () -> ruleName + " did not convert: " + result).condition());
    }
}
