package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * Which computed-field kinds a tolerance band admits, and why {@code MVK_ERROR_INVALID_FIELDTYPE} ("When using
 * tolerance comparisons with rounding mode 'Exact', the calculated field … may only be of type 'Number'", a key
 * kernel 31.3.1 introduced) is unreachable from a stored model.
 *
 * <p>The calculation module asks the field's type only after the generated comparison {@code [target] <band>
 * <operation>} has parsed, and the parser already refuses a band whose operands are not number-like
 * ({@code CheckVergleichsBedingungImpl}, step 3). A field operand is number-like exactly when its type is a
 * {@code INumberType} ({@code OperationResultTypeMap.getOpTypeForField}); the only other number-like result,
 * {@code BASE_YEAR}, belongs to the {@code BaseYear} constant, never to a field. So every non-NUMBER target draws
 * the parser's class first, under {@code RoundUp} as under {@code Exact}, and the rows below show it.
 *
 * <p>The unbanded row is the control that ties the refusal to the band. The operation reads a field of the
 * target's own kind, so no row measures a type mismatch between target and operation instead.
 *
 * <p>Own domain (a ferry booking).
 */
class ComputedFieldToleranceKindLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String BOOKING = DocumentModelDraft.named("ferry-booking").locale("en_US")
            .rootGroup("Booking", b -> b
                    .field("Fare", FieldKind.NUMBER)
                    .field("QuotedFare", FieldKind.NUMBER)
                    .field("VesselName", FieldKind.STRING)
                    .field("QuotedVessel", FieldKind.STRING)
                    .field("SailingDate", FieldKind.DATE)
                    .field("QuotedSailing", FieldKind.DATE))
            .build().toJson();

    @Test
    void aBandOnANonNumberTargetIsRefusedByTheParserBeforeTheCalculationModuleAsks() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("NUMBER target, banded — the control", computing("../Fare", "[QuotedFare]", true, null));
        actual.put("STRING target, banded", computing("../VesselName", "[QuotedVessel]", true, null));
        actual.put("STRING target, no band", computing("../VesselName", "[QuotedVessel]", false, null));
        actual.put("STRING target, banded, RoundUp", computing("../VesselName", "[QuotedVessel]", true, "RoundUp"));
        actual.put("DATE target, banded", computing("../SailingDate", "[QuotedSailing]", true, null));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("NUMBER target, banded — the control", KernelLaws.ADMITTED);
        expected.put("STRING target, banded", "MVK_INVALID_TYPE_FOR_TOLERANCE_OP");
        expected.put("STRING target, no band", KernelLaws.ADMITTED);
        expected.put("STRING target, banded, RoundUp", "MVK_INVALID_TYPE_FOR_TOLERANCE_OP");
        expected.put("DATE target, banded", "MVK_INVALID_TYPE_FOR_TOLERANCE_OP");
        assertEquals(expected, actual, "which computed-field kinds a tolerance band admits");
    }

    /** The model with one computation into [target], computing [operation], banded or not, under [rounding]. */
    private static String computing(String target, String operation, boolean banded, String rounding) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(BOOKING);
            ObjectNode computation = (ObjectNode) ((ArrayNode) root.path("content").path("modelRoot")
                    .path("rootGroups").get(0).path("Group").path("elements")).addObject();
            computation.put("type", "Computation").put("id", "cquote").put("name", "QuoteCarriedOver");
            ObjectNode body = computation.putObject("Computation");
            body.put("computedFieldRelPath", target);
            ObjectNode alternative = body.putArray("computationAlternatives").addObject().put("operation", operation);
            if (banded) {
                alternative.put("toleranceRangeOp", "DiffersWithToleranceRange1");
            }
            if (rounding != null) {
                body.put("roundingMode", rounding);
            }
            body.putArray("errorMessage").addObject().put("locale", "en_US").put("text", "the quote carried over");
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }
}
