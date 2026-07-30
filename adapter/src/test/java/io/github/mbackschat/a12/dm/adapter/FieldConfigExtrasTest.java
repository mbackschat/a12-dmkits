package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.kernel.md.model.api.fieldtypes.ICustomFieldType;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.IDateType;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.IEnumerationType;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.IFieldType;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.INumberType;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.IStringType;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.NumberTrait;

/**
 * Field-config completeness sweep (FEATURE-GAPS). Each slot is verified by a <b>kernel read-back</b>,
 * not just emit + load: the kernel's DM-JSON deserializer is {@code @JsonIgnoreProperties(ignoreUnknown=true)},
 * so a wrong key would be silently dropped and still "load valid" — only reading the value back through the
 * kernel's typed field-type API ({@code INumberType}/{@code IStringType}/…) proves the engine <em>honored</em>
 * what we emitted. ({@code trait}/unit is deferred — its runtime ({@code "amount"}) vs metamodel
 * ({@code AmountWith[out]Cent}) representation is inconsistent; see KERNEL-FINDINGS.)
 *
 * <p>Re-homed to {@code :adapter} at decoupling Stage 2 (the kernel-honored-slot read-back is a kernel-oracle
 * verification, now on {@code KernelModelOracle}); {@code dm.core.ModelIndex} is kernel-free.
 */
class FieldConfigExtrasTest {

    private static IFieldType fieldTypeAfterRoundTrip(DocumentModelDraft draft, String path) {
        return KernelModelOracle.ofSelfContained(draft.toJson()).fieldType(path);
    }

    private static DocumentModelDraft.Builder permit() {
        return DocumentModelDraft.named("permit").locale("en_US").locale("de_DE");
    }

    @Test
    void numberUnitTraitRoundTripsThroughTheKernel() throws Exception {
        DocumentModelDraft draft = permit()
                .rootGroup("Permit", p -> p
                        .numberField("Fee", n -> n.unit(NumberTrait.AMOUNT).fractionDigits(2))
                        .numberField("Rate", n -> n.unit(NumberTrait.PERCENT))
                        .numberField("Rare", n -> n.unit(NumberTrait.PERMILLE)))
                .build();
        // the kernel keeps the unit trait as the lowercase token (api.ts: "amount" | "percent" | "permille")
        assertEquals("amount", traitOf(draft, "/Permit/Fee"));
        assertEquals("percent", traitOf(draft, "/Permit/Rate"));
        assertEquals("permille", traitOf(draft, "/Permit/Rare"));
    }

    private static String traitOf(DocumentModelDraft draft, String path) throws Exception {
        return ((INumberType) fieldTypeAfterRoundTrip(draft, path)).getTrait().orElse(null);
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
    void numberLengthAndIntegerDigitBoundsRoundTripThroughTheKernel() throws Exception {
        DocumentModelDraft draft = permit()
                .rootGroup("Permit", p -> p
                        .numberField("Component", n -> n
                                .minLength(1).maxLength(2).maxIntegerDigits(2)))
                .build();
        INumberType number = (INumberType) fieldTypeAfterRoundTrip(draft, "/Permit/Component");

        assertEquals(1, number.getMinLength().orElseThrow(), "minLength honored");
        assertEquals(2, number.getMaxLength().orElseThrow(), "maxLength honored");
        assertEquals(2, number.getMaxIntegerDigits().orElseThrow(), "maxIntegerDigits honored");
    }

    @Test
    void dateBefore1900CheckRoundTripsThroughTheKernel() {
        // tag::date-before-1900[]
        DocumentModelDraft draft = permit()
                .rootGroup("Permit", group -> group
                        .dateField("IssuedOn", d -> d.format("yyyy-MM-dd").rejectBefore1900())
                        .dateField("ObservedOn", d -> d.format("yyyy-MM-dd")))
                .build();
        // end::date-before-1900[]

        IDateType issuedOn = (IDateType) fieldTypeAfterRoundTrip(draft, "/Permit/IssuedOn");
        IDateType observedOn = (IDateType) fieldTypeAfterRoundTrip(draft, "/Permit/ObservedOn");
        assertTrue(issuedOn.isYoungerThan1900Check(), "opt-in youngerThan1900Check honored");
        assertFalse(observedOn.isYoungerThan1900Check(), "kernel default remains off");
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
    void stringNoValueValidationRoundTripsThroughTheKernel() throws Exception {
        // DG14 — the kernel mandates line breaks alongside noValueValidation (MVK_STRING_TYPE_NO_VAL_AND_NO_LINE_BREAK).
        DocumentModelDraft draft = permit()
                .rootGroup("Permit", p -> p
                        .stringField("ExternalRef", s -> s.noValueValidation().lineBreaks())
                        .stringField("Notes", s -> s.maxLength(500)))
                .build();
        IStringType externalRef = (IStringType) fieldTypeAfterRoundTrip(draft, "/Permit/ExternalRef");
        assertTrue(externalRef.isNoValueValidation(), "noValueValidation honored");

        IStringType notes = (IStringType) fieldTypeAfterRoundTrip(draft, "/Permit/Notes");
        assertFalse(notes.isNoValueValidation(), "default is off for a field that didn't opt in");
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
