package io.github.mbackschat.a12.dm.cli.group;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

/**
 * <b>Declaring a group and NAMING one are separate questions</b>, over the CLI: {@code group add} creates a
 * group carrying no fields — fixed or repeatable — {@code model check} reports the model valid, and only an
 * operand that names one is refused ({@code MVK_GROUP_IS_EMPTY}).
 *
 * <p>The kernel's own account of the refusal, including the subtree gate that separates "empty" from
 * "childless", is {@code validate.laws.EmptyGroupOperandLawsTest}. What this adds is the authoring half: a
 * mid-edit model is routinely in this state — an author adds the group, then its fields — so a tool that
 * refused the declaration would make the obvious edit order impossible.
 */
class EmptyGroupDeclarationCliTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void anEmptyGroupIsDeclarableAndTheModelStaysValidWhileNamingItIsRefused(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("Store_DM.json");
        CliTestSupport.run("model", "new", "--id", "Store_DM", "--locale", "en_US", "--root", "Store",
                "-o", model.toString());
        String m = model.toString();
        CliTestSupport.run("-m", m, "field", "add", "--group", "/Store", "--name", "Reference",
                "--kind", "STRING");
        for (String[] add : new String[][] {
            {"--parent", "/Store", "--name", "Empty"},
            {"--parent", "/Store", "--name", "EmptyRows", "--repeatable", "20"},
        }) {
            String[] argv = new String[4 + add.length];
            argv[0] = "-m";
            argv[1] = m;
            argv[2] = "group";
            argv[3] = "add";
            System.arraycopy(add, 0, argv, 4, add.length);
            CliTestSupport.Result added = CliTestSupport.run(argv);
            assertEquals(0, added.code(), () -> "a group with no fields must be declarable: " + added.err());
        }

        CliTestSupport.Result check = CliTestSupport.run("-m", m, "model", "check");
        JsonNode checked = MAPPER.readTree(check.out());
        assertTrue(checked.path("valid").asBoolean(), check::out);
        assertEquals("KERNEL_CONFIRMED", checked.path("verification").asText(), check::out);

        CliTestSupport.Result named = CliTestSupport.run("-m", m, "rule", "check",
                "--group", "/Store", "--field", "/Store/Reference", "--code", "P",
                "--condition", "FieldFilled(/Store/Reference) And GroupFilled(/Store/Empty)");
        JsonNode refusal = MAPPER.readTree(named.out());
        assertEquals("MVK_GROUP_IS_EMPTY", refusal.path("diagnostics").get(0).path("code").asText(),
                () -> "the declaration is legal; the OPERAND is what is refused: " + named.out());
        // `rule check` is READ_ONLY, so this cannot fail for the reason a naive "nothing was written" check
        // implies. What it does pin is the DECLARE half: the group is still there to be named, and the
        // path form distinguishes it from the sibling EmptyRows, which a bare "Empty" substring would not.
        assertTrue(Files.readString(model).contains("\"/Store/Empty\""),
                "the declaration outlives the operand refusal");
    }
}
