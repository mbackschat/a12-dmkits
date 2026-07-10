package io.github.mbackschat.a12.dm.cli.field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

/**
 * T2 — {@code field add} located the parent with {@code --group} while every sibling add-verb
 * ({@code group}/{@code include}/{@code multiselect}/{@code attachment}) used {@code --parent}. A
 * discoverable {@code --parent} alias now matches the family (and {@code --group} keeps working — it also
 * carries a distinct meaning as a rule scope elsewhere, so it stays as the primary name).
 */
class FieldAddParentAliasTest {

    @Test
    void parentAddsTheFieldExactlyAsGroupDoes(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        var res = CliTestSupport.run("-m", model.toString(), "field", "add",
                "--parent", "/Order", "--name", "Foo", "--kind", "NUMBER");
        assertEquals(0, res.code(), res::err);
        assertTrue(Files.readString(model).contains("\"Foo\""), "the field was added via --parent");
    }

    @Test
    void parentIsDiscoverableInHelp(@TempDir Path dir) throws Exception {
        var help = CliTestSupport.run("field", "add", "--help");
        assertTrue(help.out().contains("--parent") || help.err().contains("--parent"),
                () -> "--parent must appear in the help so it is discoverable: " + help.out() + help.err());
    }
}
