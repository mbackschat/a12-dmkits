package io.github.mbackschat.a12.dm.cli.field;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The target-first {@code field} group (CLI-SPEC §5) — modelkit structure edits on the model named by
 * {@code -m}. {@code add} (Local) and {@code remove} (Cascading — the safe-delete gate refuses a referenced
 * field unless {@code --cascade}). Fixture: {@code order-ruled} (its `DeliveryNotBeforeOrder` rule
 * references `/Order/DeliveryDate`; `/Order/Quantity` is referenced by nothing).
 */
class FieldVerbTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void addAddsAField(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "field", "add",
                "--group", "/Order", "--name", "PromoCode", "--kind", "STRING");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("applied", j.get("outcome").asText());
        assertEquals("/Order/PromoCode", j.path("changed").path("added").asText());
        assertTrue(Files.readString(model).contains("PromoCode"), "the field is persisted");
    }

    @Test
    void addRejectsIncompleteLocaleCoverageCleanly(@TempDir Path dir) throws Exception {
        // 02-crash (probe): a field label that misses a declared locale is a correctable SPEC error, not an
        // internal tool failure — yet `field add` let the modelkit IllegalStateException escape as exit 70 /
        // RK_INTERNAL_ERROR while its siblings (apply / field modify / rule add) rejected the same input
        // cleanly. It must now surface as the standard "invalid field spec" rejection (exit 2), never exit 70.
        Path m = dir.resolve("loc.dm.json");
        assertEquals(0, CliTestSupport.run("model", "new", "--id", "loc", "--locale", "en_US",
                "--root", "Doc", "-o", m.toString()).code());
        assertEquals(0, CliTestSupport.run("-m", m.toString(), "config", "modify", "--add-locale", "de_DE").code(),
                "declare a second locale so a single-locale label is now incomplete");
        Path spec = dir.resolve("partial-label.json");
        Files.writeString(spec, """
                {"group":"/Doc","name":"Title","kind":"STRING","label":{"en_US":"Title"}}""");
        var res = CliTestSupport.run("-m", m.toString(), "field", "add", spec.toString());
        assertNotEquals(70, res.code(), () -> "incomplete locale coverage must NOT be an internal error: " + res.out());
        assertEquals(2, res.code(), () -> "it is a clean spec rejection: " + res.err());
        assertFalse(res.out().contains("RK_INTERNAL_ERROR"), () -> "no internal-error envelope: " + res.out());
        assertTrue(res.err().contains("invalid field spec") && res.err().contains("missing declared locale"),
                () -> "the corrective names the missing locale: " + res.err());
        assertFalse(Files.readString(m).contains("Title"), "nothing written on a rejected spec");
    }

    @Test
    void readWithNoPathListsTheFieldPaths(@TempDir Path dir) throws Exception {
        // P2: no path → a lean name-index, uniform with the read family.
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "field", "read");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("read", j.get("outcome").asText());
        JsonNode fields = j.path("data").path("fields");
        assertTrue(fields.isArray() && fields.size() > 0, () -> "lists field paths: " + res.out());
        assertTrue(fields.toString().contains("/Order/Quantity"), () -> res.out());
    }

    @Test
    void readReturnsTheFieldTypeInData(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "field", "read", "/Order/Quantity");
        assertEquals(0, res.code(), res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("read", j.get("outcome").asText());
        assertEquals("/Order/Quantity", j.path("data").path("field").asText());
        assertTrue(j.path("data").has("type"));
    }

    @Test
    void readEchoesTheFieldLabel(@TempDir Path dir) throws Exception {
        // 05-fr: a targeted `field read` must surface metadata (the label) so an agent confirms a label edit
        // without exporting the whole model — and never reading the raw model JSON.
        Path model = CliTestSupport.orderRuled(dir);   // en_US-only
        Path spec = dir.resolve("label.json");
        Files.writeString(spec, """
                {"group":"/Order","name":"Quantity","label":{"en_US":"Quantity ordered"}}""");
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "field", "modify", spec.toString()).code(),
                "set a label to read back");
        var res = CliTestSupport.run("-m", model.toString(), "field", "read", "/Order/Quantity");
        assertEquals(0, res.code(), res.err());
        assertEquals("Quantity ordered", M.readTree(res.out()).path("data").path("label").path("en_US").asText(),
                () -> "field read echoes the label (no export needed): " + res.out());
    }

    @Test
    void readEchoesPerKindConfigInTheSchemaShape(@TempDir Path dir) throws Exception {
        // 05/07 (number) + 02 (string/enum) probe: a constraint set via `field add` must be READABLE back via
        // `field read`, in the SAME per-kind block shape the write accepts — so an agent confirms it persisted
        // through this read alone, never the raw model JSON (the missing read-back drove the no-raw-read breaches).
        Path m = dir.resolve("cfg.dm.json");
        assertEquals(0, CliTestSupport.run("model", "new", "--id", "cfg", "--locale", "en_US",
                "--root", "Doc", "-o", m.toString()).code());
        Path num = dir.resolve("num.json");
        Files.writeString(num, """
                {"group":"/Doc","name":"Age","kind":"NUMBER","number":{"min":"18","max":"120","maxFractionalDigits":0,"unit":"AMOUNT"}}""");
        assertEquals(0, CliTestSupport.run("-m", m.toString(), "field", "add", num.toString()).code());
        Path str = dir.resolve("str.json");
        Files.writeString(str, """
                {"group":"/Doc","name":"Code","kind":"STRING","string":{"pattern":"M[0-9]{6}","minLength":2,"maxLength":7,"patternMessage":"bad"}}""");
        assertEquals(0, CliTestSupport.run("-m", m.toString(), "field", "add", str.toString()).code());
        Path en = dir.resolve("enum.json");
        Files.writeString(en, """
                {"group":"/Doc","name":"Tier","kind":"ENUM","enum":{"values":[{"value":"BASIC"},{"value":"PLUS"}]}}""");
        assertEquals(0, CliTestSupport.run("-m", m.toString(), "field", "add", en.toString()).code());

        JsonNode age = M.readTree(CliTestSupport.run("-m", m.toString(), "field", "read", "/Doc/Age").out())
                .path("data").path("number");
        assertEquals("18", age.path("min").asText(), () -> "field read echoes number.min (set via field add)");
        assertEquals("120", age.path("max").asText());
        assertEquals("AMOUNT", age.path("unit").asText(), "the stored trait echoes back as the schema's unit");
        assertEquals(0, age.path("maxFractionalDigits").asInt(), "the scale is surfaced");

        JsonNode code = M.readTree(CliTestSupport.run("-m", m.toString(), "field", "read", "/Doc/Code").out())
                .path("data").path("string");
        assertEquals("M[0-9]{6}", code.path("pattern").asText(), () -> "field read echoes string.pattern");
        assertEquals(2, code.path("minLength").asInt());
        assertEquals(7, code.path("maxLength").asInt());
        assertEquals("bad", code.path("patternMessages").path("en_US").asText(), "the per-locale pattern message round-trips");

        JsonNode tier = M.readTree(CliTestSupport.run("-m", m.toString(), "field", "read", "/Doc/Tier").out())
                .path("data").path("enum").path("values");
        assertTrue(tier.toString().contains("BASIC") && tier.toString().contains("PLUS"),
                () -> "field read echoes the enum values: " + tier);
    }

    @Test
    void dateAndTimeFieldFormatsAreFixedAndLocaleIndependent(@TempDir Path dir) throws Exception {
        // probe 08: date/time field FORMATS are lowercase Java patterns from a FIXED allowed set, independent
        // of the model's locale AND conditionLanguage. The editor's uppercase presets are rejected, TIME needs
        // full seconds, and the accepted DATERANGE (format, separator) pairs are the same under en/de. KERNEL-FINDINGS §6.
        Path m = dir.resolve("d.dm.json");
        assertEquals(0, CliTestSupport.run("model", "new", "--id", "d", "--locale", "en_US",
                "--root", "D", "-o", m.toString()).code());
        // TIME: full seconds required
        assertNotEquals(0, addFieldRc(m, dir, """
                {"group":"/D","name":"T1","kind":"TIME","time":{"format":"HH:mm"}}"""), "TIME HH:mm (no seconds) is rejected");
        assertEquals(0, addFieldRc(m, dir, """
                {"group":"/D","name":"T2","kind":"TIME","time":{"format":"HH:mm:ss"}}"""), "TIME HH:mm:ss is accepted");
        // DATE: the editor's uppercase label is not a valid format value; the lowercase pattern is
        assertNotEquals(0, addFieldRc(m, dir, """
                {"group":"/D","name":"D1","kind":"DATE","date":{"format":"YYYY-MM-DD"}}"""), "the uppercase editor label is rejected");
        assertEquals(0, addFieldRc(m, dir, """
                {"group":"/D","name":"D2","kind":"DATE","date":{"format":"yyyy-MM-dd"}}"""), "the lowercase pattern is accepted");
        // DATERANGE: both verified pairs accepted; a mismatched separator rejected
        assertEquals(0, addFieldRc(m, dir, """
                {"group":"/D","name":"R1","kind":"DATERANGE","dateRange":{"format":"yyyy-MM-dd","separator":"/"}}"""),
                "the ISO pair yyyy-MM-dd + / is accepted");
        assertEquals(0, addFieldRc(m, dir, """
                {"group":"/D","name":"R2","kind":"DATERANGE","dateRange":{"format":"dd.MM.yyyy","separator":"-"}}"""),
                "the German pair dd.MM.yyyy + - is accepted");
        assertNotEquals(0, addFieldRc(m, dir, """
                {"group":"/D","name":"R3","kind":"DATERANGE","dateRange":{"format":"yyyy-MM-dd","separator":"-"}}"""),
                "a mismatched separator (yyyy-MM-dd + -) is rejected");
        // LOCALE-INDEPENDENCE: a de_DE conditionLanguage accepts the IDENTICAL set (not a different one)
        Path de = dir.resolve("de.dm.json");
        assertEquals(0, CliTestSupport.run("model", "new", "--id", "de", "--locale", "en_US",
                "--root", "D", "-o", de.toString()).code());
        assertEquals(0, CliTestSupport.run("-m", de.toString(), "config", "modify", "--condition-language", "de_DE").code());
        assertEquals(0, addFieldRc(de, dir, """
                {"group":"/D","name":"R4","kind":"DATERANGE","dateRange":{"format":"yyyy-MM-dd","separator":"/"}}"""),
                "a de_DE conditionLanguage STILL accepts the ISO pair (the allowed set is locale-independent)");
        assertEquals(0, addFieldRc(de, dir, """
                {"group":"/D","name":"R5","kind":"DATERANGE","dateRange":{"format":"dd.MM.yyyy","separator":"-"}}"""),
                "a de_DE conditionLanguage accepts the German pair too");
    }

    @Test
    void anInvalidDateFormatRejectCarriesTheValidForms(@TempDir Path dir) throws Exception {
        // 08-diag: the kernel's "date range format is invalid" reject had a message but no code/fix — nothing
        // to act on. The diagnostic must now carry a message-matched hint naming the valid lowercase forms.
        Path m = dir.resolve("d.dm.json");
        assertEquals(0, CliTestSupport.run("model", "new", "--id", "d", "--locale", "en_US",
                "--root", "D", "-o", m.toString()).code());
        Path spec = dir.resolve("bad.json");
        Files.writeString(spec, """
                {"group":"/D","name":"Win","kind":"DATERANGE","dateRange":{"format":"YYYY-MM-DD","separator":" - "}}""");
        var res = CliTestSupport.run("-m", m.toString(), "field", "add", spec.toString());
        assertNotEquals(0, res.code(), "the uppercase format + spaced separator is rejected");
        boolean hasFormFix = false;
        for (JsonNode d : M.readTree(res.out()).path("diagnostics")) {
            String fix = d.path("fix").asText("");
            hasFormFix |= fix.contains("yyyy-MM-dd") && fix.toLowerCase().contains("lowercase");
        }
        assertTrue(hasFormFix, () -> "the date-format reject names the valid lowercase forms: " + res.out());
    }

    /** Write {@code spec} to a temp file and run {@code field add}; return the exit code. */
    private static int addFieldRc(Path model, Path dir, String spec) throws Exception {
        Path f = Files.createTempFile(dir, "fa", ".json");
        Files.writeString(f, spec);
        return CliTestSupport.run("-m", model.toString(), "field", "add", f.toString()).code();
    }

    @Test
    void removesAnUnreferencedField(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "field", "remove", "/Order/Quantity");
        assertEquals(0, res.code(), res.err());
        assertEquals("applied", M.readTree(res.out()).get("outcome").asText());
        assertFalse(Files.readString(model).contains("\"/Order/Quantity\""));
    }

    @Test
    void refusesAReferencedFieldWithoutCascade(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "field", "remove", "/Order/DeliveryDate");
        assertEquals(2, res.code());
        JsonNode j = M.readTree(res.out());
        assertEquals("refused", j.get("outcome").asText());
        assertTrue(j.path("data").path("referencedBy").path("rules").toString().contains("DeliveryNotBeforeOrder"));
        assertTrue(j.path("diagnostics").toString().contains("RK_REFERENCED"));
        assertTrue(Files.readString(model).contains("/Order/DeliveryDate"), "untouched");
    }

    @Test
    void cascadeRemovesFieldAndReferrers(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "field", "remove", "/Order/DeliveryDate", "--cascade");
        assertEquals(0, res.code(), res.err());
        assertEquals("applied", M.readTree(res.out()).get("outcome").asText());
        assertFalse(Files.readString(model).contains("DeliveryNotBeforeOrder"), "the referencing rule cascades away");
    }

    // --- G19: field read/remove accept --group/--name too (the locator style learned from `field add`) ---

    @Test
    void readAcceptsGroupAndNameLocator(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "field", "read", "--group", "/Order", "--name", "Quantity");
        assertEquals(0, res.code(), res::err);
        assertEquals("/Order/Quantity", M.readTree(res.out()).path("data").path("field").asText(),
                () -> "--group/--name resolves to the same field as the positional path: " + res.out());
    }

    @Test
    void removeAcceptsGroupAndNameLocator(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "field", "remove", "--group", "/Order", "--name", "Quantity");
        assertEquals(0, res.code(), res::err);
        assertFalse(Files.readString(model).contains("\"/Order/Quantity\""), "removed via the --group/--name locator");
    }

    @Test
    void fieldLocatorRejectsBothForms(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "field", "read",
                "/Order/Quantity", "--group", "/Order", "--name", "Quantity");
        assertEquals(2, res.code(), "a positional AND --group/--name together is a usage error");
    }

    // --- G20: field read echoes the friendly required state (closes the verify-a-refactor loop) ---

    @Test
    void readEchoesTheFriendlyRequiredState(@TempDir Path dir) throws Exception {
        Path m = dir.resolve("loc.dm.json");
        assertEquals(0, CliTestSupport.run("model", "new", "--id", "loc", "--locale", "en_US",
                "--root", "Doc", "-o", m.toString()).code());
        // a required field (via a spec), a conditionally-required one, and a plain optional one
        Path reqSpec = dir.resolve("req.json");
        Files.writeString(reqSpec, "{\"group\":\"/Doc\",\"name\":\"Must\",\"kind\":\"STRING\",\"required\":true}");
        assertEquals(0, CliTestSupport.run("-m", m.toString(), "field", "add", reqSpec.toString()).code());
        Path condSpec = dir.resolve("cond.json");
        Files.writeString(condSpec, "{\"group\":\"/Doc\",\"name\":\"Maybe\",\"kind\":\"STRING\",\"required\":\"ifParentPresent\"}");
        assertEquals(0, CliTestSupport.run("-m", m.toString(), "field", "add", condSpec.toString()).code());
        assertEquals(0, CliTestSupport.run("-m", m.toString(), "field", "add",
                "--group", "/Doc", "--name", "Free", "--kind", "STRING").code());

        assertTrue(M.readTree(CliTestSupport.run("-m", m.toString(), "field", "read", "/Doc/Must").out())
                .path("data").path("required").asBoolean(), "a required field reads required:true");
        assertEquals("ifParentPresent", M.readTree(CliTestSupport.run("-m", m.toString(), "field", "read", "/Doc/Maybe").out())
                .path("data").path("required").asText(), "a conditionally-required field reads \"ifParentPresent\"");
        assertFalse(M.readTree(CliTestSupport.run("-m", m.toString(), "field", "read", "/Doc/Free").out())
                .path("data").path("required").asBoolean(), "a plain field reads required:false");
    }
}
