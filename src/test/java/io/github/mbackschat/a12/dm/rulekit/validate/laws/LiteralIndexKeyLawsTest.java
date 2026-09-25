package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;

/**
 * KERNEL-FINDINGS KF479 — <b>what a LITERAL semantic-index key is: a string constant the index field's own
 * validation judges, or a digit sequence the grammar reads as an entity name</b>.
 *
 * <p>A12 runs the index field's value validation over a string constant and refuses one it does not admit
 * ({@code CheckSemanticIndexImpl}, {@code MVK_INDEX_VALUE_INVALID}); an enumeration without a table admits the
 * tokens its values declare ({@code IEnumerationType.isValueApplicable} over the values' {@code value}s). So
 * the rival readings the enumeration rows separate are a label admitted beside its token, and a token matched
 * without its case.
 *
 * <p>An unquoted key is an {@code entitySpec}, and a path segment admits a bare digit sequence
 * ({@code nameOhnePunkt: … | ZiffernFolge}), so {@code For 5} names an entity called {@code 5} rather than a
 * number; [KF476] measured that on a single digit, and the digit rows here take it to any length and to a
 * leading zero, and fence off a decimal.
 *
 * <p>A sound key addresses the level it keys ([KF476]), so the probe {@code Sum(<keyed>) > 0} names the arity
 * class of a lone field there. The ordering rows put an unaddressed read beside the keyed operand, where A12
 * names whichever it reads first.
 *
 * <p>The same key forms read the same under the German keyword {@code Fuer}: the literal is the key's, and
 * the language only chooses the word that introduces it.
 *
 * <p>Own domain (insurance endorsements).
 */
class LiteralIndexKeyLawsTest {

    private static final String POLICY = DocumentModelDraft.named("policy-literal-key").locale("en_US")
            .rootGroup("Policy", p -> p
                    .stringField("Reference", f -> { })
                    .group("Claims", g -> g.repeatable(5).indexField("ClaimRef")
                            .stringField("ClaimRef", f -> { })
                            .numberField("Amount", f -> f.fractionDigits(2)))
                    .group("Endorsements", g -> g.repeatable(5).indexField("Status")
                            .enumField("Status", e -> e
                                    .value("OPEN", l -> l.label("en_US", "Open endorsement"))
                                    .value("CLOSED", l -> l.label("en_US", "Closed endorsement")))
                            .numberField("Amount", f -> f.fractionDigits(2)))
                    .group("Riders", g -> g.repeatable(5).stringField("RiderRef", f -> { })))
            .build().toJson();

    /** The same model, its conditions read in German. */
    private static final String POLICY_DE = ModelDocument.read(POLICY).setConditionLanguage("de_DE").toJson();

    private static final String UNADDRESSED = "[Riders/RiderRef] == \"a\"";

    private static String verdict(String condition) {
        return KernelLaws.verdictModel(POLICY, "/Policy", "../Reference", "FieldFilled(Reference) And " + condition);
    }

    private static String sum(String keyed) {
        return "Sum(" + keyed + ") > 0";
    }

    private static String enumerationKey(String constant) {
        return sum("Endorsements/Amount For \"" + constant + "\"");
    }

    @Test
    void anEnumerationIndexAdmitsExactlyItsTokens() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a declared token", verdict(enumerationKey("OPEN")));
        actual.put("an undeclared value", verdict(enumerationKey("PENDING")));
        actual.put("a declared token in another case", verdict(enumerationKey("open")));
        actual.put("a declared token's label", verdict(enumerationKey("Open endorsement")));
        actual.put("a declared token ahead of an unaddressed read",
                verdict(enumerationKey("OPEN") + " And " + UNADDRESSED));
        actual.put("an undeclared value ahead of an unaddressed read",
                verdict(enumerationKey("PENDING") + " And " + UNADDRESSED));
        actual.put("an unaddressed read ahead of a declared token", verdict(UNADDRESSED + " And " + enumerationKey("OPEN")));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a declared token", "MVK_PARAMSIZE_INVALIDN");
        expected.put("an undeclared value", "MVK_INDEX_VALUE_INVALID");
        expected.put("a declared token in another case", "MVK_INDEX_VALUE_INVALID");
        expected.put("a declared token's label", "MVK_INDEX_VALUE_INVALID");
        expected.put("a declared token ahead of an unaddressed read", "MVK_PARAMSIZE_INVALIDN");
        expected.put("an undeclared value ahead of an unaddressed read", "MVK_INDEX_VALUE_INVALID");
        expected.put("an unaddressed read ahead of a declared token", "MVK_NO_WILDCARD");
        assertEquals(expected, actual, "which constant keys an enumeration index admits");
    }

    @Test
    void aDigitSequenceKeyIsAnEntityName() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("one digit — the KF476 control", verdict(sum("Claims/Amount For 5")));
        actual.put("several digits", verdict(sum("Claims/Amount For 12")));
        actual.put("a leading zero", verdict(sum("Claims/Amount For 05")));
        actual.put("over an enumeration index", verdict(sum("Endorsements/Amount For 5")));
        actual.put("ahead of an unaddressed read", verdict(sum("Claims/Amount For 5") + " And " + UNADDRESSED));
        actual.put("behind an unaddressed read", verdict(UNADDRESSED + " And " + sum("Claims/Amount For 5")));
        actual.put("a decimal, which is no digit sequence", verdict(sum("Claims/Amount For 5.5")));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("one digit — the KF476 control", "MVK_INVALID_ENTITY");
        expected.put("several digits", "MVK_INVALID_ENTITY");
        expected.put("a leading zero", "MVK_INVALID_ENTITY");
        expected.put("over an enumeration index", "MVK_INVALID_ENTITY");
        expected.put("ahead of an unaddressed read", "MVK_INVALID_ENTITY");
        expected.put("behind an unaddressed read", "MVK_NO_WILDCARD");
        // Not an entity name at all: the grammar's key takes an entity spec or a string, and a decimal is neither.
        expected.put("a decimal, which is no digit sequence", "MVK_UNEXPECTED_TOKEN");
        assertEquals(expected, actual, "what a digit-sequence key names");
    }

    @Test
    void theGermanKeywordIntroducesTheSameKey() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a declared token", german("Summe(Endorsements/Amount Fuer \"OPEN\") > 0"));
        actual.put("a declared token in another case", german("Summe(Endorsements/Amount Fuer \"open\") > 0"));
        actual.put("a digit sequence", german("Summe(Claims/Amount Fuer 5) > 0"));
        actual.put("a decimal", german("Summe(Claims/Amount Fuer 5.5) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a declared token", "MVK_PARAMSIZE_INVALIDN");
        expected.put("a declared token in another case", "MVK_INDEX_VALUE_INVALID");
        expected.put("a digit sequence", "MVK_INVALID_ENTITY");
        expected.put("a decimal", "MVK_UNEXPECTED_TOKEN");
        assertEquals(expected, actual, "a literal key under the German keyword");
    }

    private static String german(String condition) {
        return KernelLaws.verdictModel(POLICY_DE, "/Policy", "../Reference", "FeldAngegeben(Reference) Und " + condition);
    }
}
