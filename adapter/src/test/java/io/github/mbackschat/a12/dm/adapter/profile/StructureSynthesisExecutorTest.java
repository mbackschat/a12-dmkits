package io.github.mbackschat.a12.dm.adapter.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;

class StructureSynthesisExecutorTest {

    @Test
    void materializesThePlannedTreeAndEveryRepresentableFieldKindThroughTheTypedSurface() {
        StructureSynthesisPlan plan = new StructureSynthesisPlan(
                List.of(
                        group("Portfolio", -1, 1, 1),
                        group("Contracts", 0, 2, 12),
                        group("Terms", 1, 3, 1),
                        group("Audit", -1, 1, 2)),
                List.of(
                        field("Amount", 0, ValueKind.NUMBER, false),
                        field("Narrative", 0, ValueKind.STRING, false),
                        field("Category", 1, ValueKind.ENUMERATION, false),
                        field("Active", 1, ValueKind.BOOLEAN, false),
                        field("Approved", 1, ValueKind.CONFIRM, false),
                        field("EffectiveDate", 2, ValueKind.DATE, false),
                        field("RecordedAt", 2, ValueKind.DATE_TIME, false),
                        field("Cutoff", 2, ValueKind.TIME, false),
                        field("AccountingMonth", 2, ValueKind.DATE_FRAGMENT, false),
                        field("CoverageWindow", 2, ValueKind.DATE_RANGE, false),
                        field("ProjectCode", 2, ValueKind.CUSTOM, true),
                        field("AuditText", 3, ValueKind.STRING, true)));

        String json = StructureSynthesisExecutor.execute(plan).toJson();

        BoundModel.ofJson(json);
        ModelDocument document = ModelDocument.read(json);
        assertEquals(
                List.of(
                        "/Portfolio",
                        "/Portfolio/Contracts",
                        "/Portfolio/Contracts/Terms",
                        "/Audit"),
                document.groupPaths());
        assertEquals(12, document.maxRepetitions("/Portfolio/Contracts"));
        assertEquals(2, document.maxRepetitions("/Audit"));
        assertEquals(
                Map.ofEntries(
                        Map.entry("/Portfolio/Amount", "NUMBER"),
                        Map.entry("/Portfolio/Narrative", "STRING"),
                        Map.entry("/Portfolio/Contracts/Category", "ENUMERATION"),
                        Map.entry("/Portfolio/Contracts/Active", "BOOLEAN"),
                        Map.entry("/Portfolio/Contracts/Approved", "CONFIRM"),
                        Map.entry("/Portfolio/Contracts/Terms/EffectiveDate", "DATE"),
                        Map.entry("/Portfolio/Contracts/Terms/RecordedAt", "DATE_TIME"),
                        Map.entry("/Portfolio/Contracts/Terms/Cutoff", "TIME"),
                        Map.entry("/Portfolio/Contracts/Terms/AccountingMonth", "DATE_FRAGMENT"),
                        Map.entry("/Portfolio/Contracts/Terms/CoverageWindow", "DATE_RANGE"),
                        Map.entry("/Portfolio/Contracts/Terms/ProjectCode", "CUSTOM"),
                        Map.entry("/Audit/AuditText", "STRING")),
                document.fieldPaths().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                path -> path,
                                path -> document.fieldKind(path).orElseThrow())));
        assertEquals(json, StructureSynthesisExecutor.execute(plan).toJson());
    }

    @Test
    void refusesAnUnrepresentableFieldKindBeforeBuilding() {
        StructureSynthesisPlan plan = new StructureSynthesisPlan(
                List.of(group("Portfolio", -1, 1, 1)),
                List.of(field("Unknown", 0, ValueKind.UNKNOWN, false)));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> StructureSynthesisExecutor.execute(plan));

        assertEquals(
                "planned field /Portfolio/Unknown has no synthesizable typed field kind",
                failure.getMessage());
    }

    private static StructureSynthesisPlan.GroupSlot group(
            String name, int parentIndex, int depth, int maxRepetitions) {
        return new StructureSynthesisPlan.GroupSlot(
                name, parentIndex, depth, maxRepetitions);
    }

    private static StructureSynthesisPlan.FieldSlot field(
            String name, int groupIndex, ValueKind kind, boolean computed) {
        return new StructureSynthesisPlan.FieldSlot(name, groupIndex, kind, computed);
    }
}
