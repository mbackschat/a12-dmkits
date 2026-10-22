package io.github.mbackschat.a12.dm.cli.field;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * An edit keeps the KIND of a number token it does not touch. The writers parse numbers as {@code BigDecimal}, which
 * prints {@code 1e0} as {@code 1}, so an unrelated edit used to turn a boolean member A12 refuses into the integer it
 * reads as set (KERNEL-FINDINGS KF569): the edit succeeded, the model then loaded with the member on, and
 * {@code rule check} called it kernel-valid (measured 2026-10-22).
 *
 * <p>Each verb goes through a different writer, {@code field add} through modelkit and {@code rule add} through
 * rulekit, and {@code rule check} injects its candidate through rulekit too. Own domain (an insurance claim).
 */
class BooleanMemberTokenFidelityTest {

    private static final String LINE_BREAKS = "lineBreaksPermitted";
    private static final String MAX_VALUE = "maxValue";

    @TempDir
    Path dir;

    private int files;

    private static Pattern member(String name) {
        return Pattern.compile("\"" + name + "\"\\s*:\\s*([^,}\\s]+)");
    }

    private Path model(String member, String token) throws Exception {
        String draft = DocumentModelDraft.named("claim").locale("en_US")
                .rootGroup("Claim", g -> g.stringField("Note", s -> s.lineBreaks())
                        .numberField("Amount", n -> n.fractionDigits(2).max("9000")))
                .build().toJson();
        Matcher written = member(member).matcher(draft);
        if (!written.find() || written.find()) {
            throw new IllegalStateException("the draft must write " + member + " once");
        }
        Path file = dir.resolve("model-" + files++ + ".dm.json");
        Files.writeString(file, member(member).matcher(draft).replaceFirst("\"" + member + "\": " + token));
        return file;
    }

    private static String token(String member, Path model) throws Exception {
        Matcher m = member(member).matcher(Files.readString(model));
        if (!m.find()) {
            throw new AssertionError(member + " is gone from " + model);
        }
        return m.group(1);
    }

    /** Whether {@code field add} landed, and the member's token afterwards. */
    private String afterFieldAdd(String member, String token) throws Exception {
        Path model = model(member, token);
        Path spec = dir.resolve("field-" + files++ + ".json");
        Files.writeString(spec, """
                {"group":"/Claim","name":"Adjuster","kind":"STRING"}""");
        int code = CliTestSupport.run("-m", model.toString(), "field", "add", spec.toString()).code();
        return (code == 0) + " " + token(member, model);
    }

    /** Whether {@code rule add} landed, and the member's token afterwards. */
    private String afterRuleAdd(String member, String token) throws Exception {
        Path model = model(member, token);
        Path spec = dir.resolve("rule-" + files++ + ".json");
        Files.writeString(spec, """
                {"field":"/Claim/Amount","condition":"[Amount] < 0","code":"NEGATIVE_AMOUNT","name":"AmountNotNegative",
                 "messages":[{"locale":"en_US","text":"The amount must not be negative."}]}""");
        int code = CliTestSupport.run("-m", model.toString(), "rule", "add", spec.toString()).code();
        return (code == 0) + " " + token(member, model);
    }

    /** Each verb's outcome per token, keyed by token and verb, for one member. */
    private Map<String, String> afterEdits(String member, String... tokens) throws Exception {
        Map<String, String> observed = new LinkedHashMap<>();
        for (String token : tokens) {
            observed.put(token + " after field add", afterFieldAdd(member, token));
            observed.put(token + " after rule add", afterRuleAdd(member, token));
        }
        return observed;
    }

    /**
     * On a bound A12 reads in any spelling, the edit lands and the untouched token keeps its kind: an exponent token
     * whose decimal has scale 0 gains {@code E+0}. The integer and the fraction, which print as written, are the
     * controls and come back verbatim, so the fix cannot be a blanket respelling.
     */
    @Test
    void anUnrelatedEditKeepsTheKindOfANumberToken() throws Exception {
        assertEquals(Map.of(
                        "25.00e2 after field add", "true 2500E+0", "25.00e2 after rule add", "true 2500E+0",
                        "2500 after field add", "true 2500", "2500 after rule add", "true 2500",
                        "2500.50 after field add", "true 2500.50", "2500.50 after rule add", "true 2500.50"),
                afterEdits(MAX_VALUE, "25.00e2", "2500", "2500.50"));
    }

    /**
     * A boolean member written {@code 1e0} keeps a token A12 refuses, so the edited model fails the kernel and the
     * edit is refused with the file untouched, where it used to land with the member turned on. {@code 1} is the
     * control: that edit lands.
     */
    @Test
    void anEditOfABooleanTokenA12RefusesIsRefused() throws Exception {
        assertEquals(Map.of(
                        "1e0 after field add", "false 1e0", "1e0 after rule add", "false 1e0",
                        "1.0e1 after field add", "false 1.0e1", "1.0e1 after rule add", "false 1.0e1",
                        "1 after field add", "true 1", "1 after rule add", "true 1"),
                afterEdits(LINE_BREAKS, "1e0", "1.0e1", "1"));
    }

    /**
     * {@code rule check} judges the model as written: before the fix it handed the kernel {@code 1} and called a model
     * A12 does not load kernel-valid. The kernel's own refusal of a floating-point boolean token is the oracle;
     * {@code 1} and {@code 0} are the control.
     */
    @Test
    void ruleCheckJudgesTheTokenAsWritten() throws Exception {
        Map<String, Boolean> observed = new LinkedHashMap<>();
        for (String token : new String[] {"1e0", "1.0e1", "1", "0"}) {
            var res = CliTestSupport.run("-m", model(LINE_BREAKS, token).toString(), "rule", "check",
                    "--field", "/Claim/Amount", "--condition", "[Amount] < 0", "--code", "NEGATIVE_AMOUNT");
            observed.put(token, res.code() == 0);
        }
        assertEquals(Map.of("1e0", false, "1.0e1", false, "1", true, "0", true), observed);
    }
}
