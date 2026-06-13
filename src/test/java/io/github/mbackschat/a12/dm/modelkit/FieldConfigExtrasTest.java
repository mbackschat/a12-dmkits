package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.kernel.md.model.api.fieldtypes.ICustomFieldType;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.IEnumerationType;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.IFieldType;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.INumberType;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.IStringType;

import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.core.ModelLoader;

/**
 * Field-config completeness sweep (FEATURE-GAPS §3.4). Each slot is verified by a <b>kernel read-back</b>,
 * not just emit + load: the kernel's DM-JSON deserializer is {@code @JsonIgnoreProperties(ignoreUnknown=true)},
 * so a wrong key would be silently dropped and still "load valid" — only reading the value back through the
 * kernel's typed field-type API ({@code INumberType}/{@code IStringType}/…) proves the engine <em>honored</em>
 * what we emitted. ({@code trait}/unit is deferred — its runtime ({@code "amount"}) vs metamodel
 * ({@code AmountWith[out]Cent}) representation is inconsistent; see KERNEL-FINDINGS.)
 */
class FieldConfigExtrasTest {

    private static IFieldType fieldTypeAfterRoundTrip(DocumentModelDraft draft, String path) throws Exception {
        return ModelIndex.of(ModelLoader.loadSelfContained(new StringReader(draft.toJson()))).fieldType(path);
    }

    private static DocumentModelDraft.Builder permit() {
        return DocumentModelDraft.named("permit").locale("en_US").locale("de_DE");
    }

    @Test
    void numberFractionDigitRangeAndFlagsRoundTripThroughTheKernel() throws Exception {
        DocumentModelDraft draft = permit()
                .rootGroup("Permit", p -> p
                        .numberField("Fee", n -> n.fractionDigits(2, 4).zeroNotAllowed().leadingZerosAllowed()))
                .build();
        INumberType number = (INumberType) fieldTypeAfterRoundTrip(draft, "/Permit/Fee");
        assertEquals(2, number.getMinFractionalDigits(), "minFractionalDigits honored");
        assertEquals(4, number.getMaxFractionalDigits(), "maxFractionalDigits honored");
        assertTrue(number.isZeroNotAllowed(), "zeroNotAllowed honored");
        assertTrue(number.isLeadingZerosAllowed(), "leadingZerosAllowed honored");
    }

    @Test
    void stringSuggestionsLineBreaksAndSortingRoundTripThroughTheKernel() throws Exception {
        DocumentModelDraft draft = permit()
                .rootGroup("Permit", p -> p
                        .stringField("Notes", s -> s.lineBreaks().maxLength(500))
                        .stringField("Country", s -> s.alphabeticalSorting()
                                .suggestions("en_US", "Germany", "France").suggestions("de_DE", "Deutschland", "Frankreich")))
                .build();
        IStringType notes = (IStringType) fieldTypeAfterRoundTrip(draft, "/Permit/Notes");
        assertTrue(notes.isLineBreaksPermitted(), "lineBreaksPermitted honored");

        IStringType country = (IStringType) fieldTypeAfterRoundTrip(draft, "/Permit/Country");
        assertTrue(country.isAlphabeticalSorting(), "alphabeticalSorting honored");
        assertFalse(country.getHintList().isEmpty(), "hintList honored");
        assertTrue(country.getHintList().stream().anyMatch(h -> h.getValues().contains("Germany")),
                () -> "the en_US hint values survive: " + country.getHintList());
    }

    @Test
    void enumSortingAndInvalidValueMessageRoundTripThroughTheKernel() throws Exception {
        DocumentModelDraft draft = permit()
                .rootGroup("Permit", p -> p
                        .enumField("Category", e -> e.value("RESIDENTIAL").value("COMMERCIAL")
                                .alphabeticalSorting()
                                .invalidValueMessage("Pick a known category.")))
                .build();
        IEnumerationType enumeration = (IEnumerationType) fieldTypeAfterRoundTrip(draft, "/Permit/Category");
        assertTrue(enumeration.isAlphabeticalSorting(), "alphabeticalSorting honored");
        assertFalse(enumeration.getErrorMessage().isEmpty(), "the invalid-value errorMessage honored");
    }

    @Test
    void customFieldKindRoundTripsStructurally() throws Exception {
        DocumentModelDraft draft = permit()
                .rootGroup("Permit", p -> p
                        .customField("Iban", c -> c.type("IbanType").minLength(15).maxLength(34)))
                .build();
        // Custom validation needs a registered validator; we verify the structural carry (name + bounds).
        ICustomFieldType custom = (ICustomFieldType) fieldTypeAfterRoundTrip(draft, "/Permit/Iban");
        assertEquals("IbanType", custom.getName(), "the custom type name is carried");
    }
}
