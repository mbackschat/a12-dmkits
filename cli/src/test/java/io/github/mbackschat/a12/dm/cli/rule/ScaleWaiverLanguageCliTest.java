package io.github.mbackschat.a12.dm.cli.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;

/**
 * {@code allowDifferingDecimals} writes the scale-gate waiver in the MODEL's condition language, on both routes
 * that author a rule.
 *
 * <p>A German-condition model refuses the English {@code @SuppressWarning} as {@code MVK_UNEXPECTED_TOKEN}
 * ({@code rulekit.validate.laws.ScaleWaiverLanguageLawsTest}), so a flag that always wrote the English keyword
 * produced a rule the kernel rejects in exactly the models that need the other spelling. The kernel runs on
 * each add here, so exit 0 is its verdict and the persisted keyword is the spelling it took. The fixture carries
 * no rules, because a German model would refuse any English condition already in it.
 */
class ScaleWaiverLanguageCliTest {

    @ParameterizedTest
    @CsvSource({"en_US, @SuppressWarning(", "de_DE, @UnterdrueckeWarnung("})
    void ruleAddWritesTheWaiverTheModelsLanguageAdmits(String language, String keyword, @TempDir Path dir)
            throws Exception {
        Path model = model(dir, language);
        Path spec = dir.resolve("spec.json");
        Files.writeString(spec, """
                { "field": "/Order/Quantity", "condition": "[Quantity] == [UnitPrice]", "code": "SCALE_OK",
                  "allowDifferingDecimals": true, "messages": [ {"locale":"en_US","text":"x"} ] }""");

        var res = CliTestSupport.run("-m", model.toString(), "rule", "add", spec.toString());

        assertEquals(0, res.code(), () -> res.out() + res.err());
        assertTrue(Files.readString(model).contains(keyword + "MVK_INVALID_COMPARE_DEC_PLACES)"), keyword);
    }

    @ParameterizedTest
    @CsvSource({"en_US, @SuppressWarning(", "de_DE, @UnterdrueckeWarnung("})
    void applyWritesTheWaiverTheModelsLanguageAdmits(String language, String keyword, @TempDir Path dir)
            throws Exception {
        Path model = model(dir, language);
        Path ops = dir.resolve("ops.json");
        Files.writeString(ops, """
                [ {"target":"rule","op":"add","field":"/Order/Quantity","code":"SCALE_OK",
                   "condition":"[Quantity] == [UnitPrice]","allowDifferingDecimals":true,
                   "messages":[{"locale":"en_US","text":"x"}]} ]""");

        var res = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());

        assertEquals(0, res.code(), () -> res.out() + res.err());
        assertTrue(Files.readString(model).contains(keyword + "MVK_INVALID_COMPARE_DEC_PLACES)"), keyword);
    }

    private static Path model(Path dir, String language) throws Exception {
        Path model = CliTestSupport.order(dir);
        Files.writeString(model, ModelDocument.read(Files.readString(model)).setConditionLanguage(language).toJson());
        return model;
    }
}
