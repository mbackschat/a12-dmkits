package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.edit.GroupEditor;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.IndexedGroupValue;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringRef;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

class IndexedStarDuplicateLawsTest {
    private static final String MODEL = DocumentModelDraft.named("indexed-shipment-duplicates").locale("en_US")
            .rootGroup("Shipment", s -> s.stringField("Reference", f -> { })
                    .stringField("Wanted", f -> { })
                    .group("Batches", b -> b.repeatable(2).indexField("Code").stringField("Code", f -> { })
                            .group("Parcels", p -> p.repeatable(2).numberField("Quantity", n -> n.fractionDigits(0))
                                    .group("Details", d -> d.numberField("Score", n -> n.fractionDigits(0))))))
            .build().toJson();

    private enum Carrier {
        NONE, SOME, SUM;

        Condition repeated(IndexedGroupValue operand) {
            return switch (this) {
                case NONE -> Dsl.noGroupFilled(operand, operand);
                case SOME -> Dsl.atLeastOneGroupFilled(operand, operand);
                case SUM -> Dsl.sum(operand, operand).eq(0);
            };
        }

        String kernelCondition(IndexedGroupValue operand) {
            String pair = operand.renderedPath() + ", " + operand.renderedPath();
            return switch (this) {
                case NONE -> "NoGroupFilled(" + pair + ")";
                case SOME -> "AtLeastOneGroupFilled(" + pair + ")";
                case SUM -> "Sum(" + pair + ") == 0";
            };
        }
    }

    private final EditSession session = EditSession.read(MODEL);
    private final GroupEditor shipment = session.group("/Shipment");
    private final StringRef reference = shipment.str("Reference");

    private Rule rule(String name, Condition condition) {
        return shipment.rule(r -> r.name(name).code(name).errorField(reference).when(condition)
                .message(Dsl.en("Check selected scope admission.")).build());
    }

    private ValidationResult kernel(String condition) {
        var probe = rule("Probe", reference.filled());
        return KernelLaws.checkModel(MODEL, shipment.ref().path().value(), probe.errorEntityRelPath(),
                probe.render() + " And " + condition);
    }

    @Test
    void lowerStarsRemainExemptAfterEitherIndexSelectionAndRoundTrip() {
        var scope = shipment.flattenGroup("Batches/Parcels*");
        int ordinal = 0;
        for (var operand : List.of(scope.forIndex("A"), scope.forIndex(shipment.str("Wanted").field()))) {
            for (var carrier : Carrier.values()) {
                // Bypass the typed refusal only at this measurement boundary so the kernel settles admission first.
                var kernel = kernel(carrier.kernelCondition(operand));
                assertTrue(kernel.ok(), () -> carrier + " / " + operand + ": " + kernel);
                Condition authored = reference.filled().and(carrier.repeated(operand));
                String name = "Repeated" + ++ordinal;
                var authoredRule = rule(name, authored);
                session.addRule(m -> authoredRule);
                var read = assertInstanceOf(ReadResult.Converted.class,
                        KernelAdapter.ofJson(session.toJson()).read(shipment.ref().path().value() + "/" + name));
                assertEquals(authoredRule.render(), rule(name, read.condition()).render(), name);
            }
        }
    }

    @Test
    void unstarredDuplicatesAndStarredContainmentRemainRefused() {
        var fixed = shipment.flattenGroup("Batches").forIndex("A");
        var star = shipment.flattenGroup("Batches/Parcels*").forIndex("A");
        var child = shipment.flattenGroup("Batches/Parcels*/Details").forIndex("A");
        var duplicate = kernel(Carrier.NONE.kernelCondition(fixed));
        assertTrue(duplicate.diagnostics().stream().anyMatch(d -> d.code().orElse("").equals("MVK_DUPLICATE_PARAM1")));
        assertThrows(IllegalArgumentException.class, () -> Dsl.noGroupFilled(fixed, fixed));
        var overlap = kernel("NoGroupFilled(" + star.renderedPath() + ", " + child.renderedPath() + ")");
        assertTrue(overlap.diagnostics().stream().anyMatch(d -> d.code().orElse("").equals("MVK_DUPLICATE_PARAM2")));
        assertThrows(IllegalArgumentException.class, () -> Dsl.noGroupFilled(star, child));
    }
}
