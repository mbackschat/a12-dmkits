package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * Whether the CODELESS supplementary-plane finding co-reports with a named message failure, or is displaced
 * by one.
 *
 * <p>Discharges a12-kernel-lean's {@code SPEC-2026-09-08-07}, whose acceptance asks us to retain codeless
 * findings independently of named failures and to withdraw any last-check or short-circuit inference. Every
 * row here pairs an astral character with one named failure, so the grid answers "does the named class
 * replace it" rather than "does it appear at all".
 *
 * <p><b>The codeless finding is identified by its TEXT, which is the whole point of measuring it.</b> A
 * projection that printed only codes would render it {@code (no code)} and make two different refusals
 * indistinguishable — which is exactly how the compact projection that produced the short-circuit inference
 * lost it.
 *
 * <p><b>A second codeless finding turned up that the entry does not name:</b> a linebreak ALONE reports a
 * codeless "no line breaks" finding beside {@code INVALID_LINEBREAK}. So codelessness is not a property of
 * the repertoire check specifically; at least two message checks report in both registers at once.
 */
class MessageRepertoireCoReportingLawsTest {

    private static final String CATALOGUE = DocumentModelDraft.named("catalogue-repertoire").locale("en_US")
            .rootGroup("Catalogue", c -> c
                    .stringField("Reference", x -> { })
                    .stringField("Note", x -> { }))
            .build().toJson();

    private static final String READS = "FieldFilled(Reference) And FieldFilled(Note)";

    private static final String ASTRAL_CHAR = "\uD83D\uDE00";

    /** The codeless findings are identified by their rendered TEXT; a bare "(no code)" would alias them. */
    private static final String ASTRAL = "(no code: Rule with path '/Catalogue/ProbeRule': "
            + "The field contains one or several unsupported signs.)";

    private static final String LINEBREAK = "(no code: Rule with path '/Catalogue/ProbeRule': "
            + "Only values without line breaks are allowed.)";

    private static String reporting(String text) {
        return KernelLaws.messageVerdict(CATALOGUE, "/Catalogue", "../Reference", READS, "en_US", text);
    }

    @Test
    void aCodelessFindingIsReportedBesideEveryNamedMessageFailure() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("plain — the control", reporting("plain"));
        actual.put("astral alone", reporting("ok " + ASTRAL_CHAR));
        actual.put("a linebreak alone", reporting("ok\nmore"));
        actual.put("astral + linebreak", reporting("ok " + ASTRAL_CHAR + "\nmore"));
        actual.put("a control character alone", reporting("ok \u0001 more"));
        actual.put("astral + control character", reporting("ok " + ASTRAL_CHAR + " \u0001 more"));
        actual.put("an odd dollar alone", reporting("ok $ more"));
        actual.put("astral + odd dollar", reporting("ok " + ASTRAL_CHAR + " $ more"));
        actual.put("an unknown entity alone", reporting("ok $NoSuchField$ more"));
        actual.put("astral + unknown entity", reporting("ok " + ASTRAL_CHAR + " $NoSuchField$ more"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("plain — the control", KernelLaws.ADMITTED);
        expected.put("astral alone", ASTRAL);
        // Not named by the entry: the linebreak check ALSO reports in both registers at once.
        expected.put("a linebreak alone", LINEBREAK + ",INVALID_LINEBREAK");
        // Every pair keeps BOTH, and the codeless finding is listed first in every envelope.
        expected.put("astral + linebreak", ASTRAL + ",INVALID_LINEBREAK");
        expected.put("a control character alone", "INVALID_CHARACTERS");
        expected.put("astral + control character", ASTRAL + ",INVALID_CHARACTERS");
        expected.put("an odd dollar alone", "WRONG_NUMBER_OF_DOLLARS");
        expected.put("astral + odd dollar", ASTRAL + ",WRONG_NUMBER_OF_DOLLARS");
        expected.put("an unknown entity alone", "INVALID_ENTITY");
        expected.put("astral + unknown entity", ASTRAL + ",INVALID_ENTITY");
        assertEquals(expected, actual, "a named failure never displaces the codeless finding");
    }
}
