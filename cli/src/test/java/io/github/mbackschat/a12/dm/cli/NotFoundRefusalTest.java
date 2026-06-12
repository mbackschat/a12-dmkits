package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * AP1 closed (CLI-CONFORMANCE-SPEC): a not-found target on a mutating verb is a <b>structured refusal</b>
 * (outcome:refused, exit 2, written:false, a coded RK_NO_SUCH_* diagnostic) — never the bare stderr string
 * 11 of 13 verbs returned before. The refactor-gate "prove the gate fires" requirement, over the whole
 * mutating surface in one parameterized sweep. (The diagnostic-conformance fuzzer (#17) generalizes this to
 * any future verb derived from the live tree; this pins the specific codes per verb.)
 */
class NotFoundRefusalTest {

    private static final ObjectMapper M = new ObjectMapper();

    @TempDir
    static Path dir;
    static Path model;

    @BeforeAll
    static void writeModel() throws Exception {
        String json = DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o
                        .field("Note", FieldKind.STRING)
                        .group("Shipping", s -> s.field("Zip", FieldKind.STRING)))
                .build()
                .toJson();
        model = dir.resolve("order.dm.json");
        Files.writeString(model, json);
    }

    static Stream<Arguments> notFoundCases() {
        return Stream.of(
                arguments(List.of("field", "add", "--group", "/Order/Nope", "--name", "X", "--kind", "NUMBER"), "RK_NO_SUCH_GROUP"),
                arguments(List.of("group", "add", "--parent", "/Order/Nope", "--name", "X"), "RK_NO_SUCH_GROUP"),
                arguments(List.of("field", "remove", "/Order/Nope"), "RK_NO_SUCH_FIELD"),
                arguments(List.of("group", "remove", "/Order/Nope"), "RK_NO_SUCH_GROUP"),
                arguments(List.of("group", "extract", "/Order/Nope", "--reference", "sub"), "RK_NO_SUCH_GROUP"),
                arguments(List.of("typedef", "remove", "NoSuchTd"), "RK_NO_SUCH_TYPEDEF"),
                arguments(List.of("typedef", "rename", "NoSuchTd", "--to", "X"), "RK_NO_SUCH_TYPEDEF"),
                arguments(List.of("typedef", "inline", "NoSuchTd"), "RK_NO_SUCH_TYPEDEF"),
                arguments(List.of("include", "inline", "nosuchalias"), "RK_NO_SUCH_INCLUDE"),
                arguments(List.of("include", "remove", "nosuchalias"), "RK_NO_SUCH_INCLUDE"),
                arguments(List.of("rule", "remove", "/Order/Nope"), "RK_NO_SUCH_RULE"),
                arguments(List.of("rule", "modify", "/Order/Nope", "--condition", "FieldFilled(Note)"), "RK_NO_SUCH_RULE"),
                arguments(List.of("computation", "remove", "/Order/Nope"), "RK_NO_SUCH_COMPUTATION"));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("notFoundCases")
    void aBogusTargetIsAStructuredRefusal(List<String> verbArgs, String expectedCode) throws Exception {
        List<String> args = new ArrayList<>(List.of("-m", model.toString()));
        args.addAll(verbArgs);
        String before = Files.readString(model);

        var res = CliTestSupport.run(args.toArray(new String[0]));

        assertEquals(2, res.code(), () -> verbArgs + " -> " + res.err());
        JsonNode j = M.readTree(res.out());
        assertEquals("refused", j.path("outcome").asText(), verbArgs::toString);
        JsonNode diag = j.path("diagnostics").path(0);
        assertEquals(expectedCode, diag.path("code").asText(), () -> verbArgs + " => " + res.out());
        assertTrue(diag.has("where") && diag.has("fix"), () -> verbArgs + " diagnostic needs where + fix");
        assertEquals(before, Files.readString(model), () -> verbArgs + " must not mutate the model");
    }
}
