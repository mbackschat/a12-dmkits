package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Text rendering remains generic as typedef and include element families join semantic diff. */
class DiffTextDeclarationTest {

    @Test
    void rendersTypeDefinitionAndIncludeChanges() throws Exception {
        var data = new ObjectMapper().readTree("""
                {"changes":[
                  {"element":"TYPEDEF","id":"Money","change":"MODIFIED","risk":"MEDIUM",
                   "riskReasons":["TYPE_CHANGED"],"deltas":[]},
                  {"element":"INCLUDE","id":"pricing","change":"MODIFIED","risk":"MEDIUM",
                   "riskReasons":["INCLUDE_CHANGED"],"deltas":[]}],
                 "refactors":[]}
                """);

        String rendered = new DiffText().render(data);

        assertTrue(rendered.contains("typedef Money") && rendered.contains("TYPE_CHANGED"), rendered);
        assertTrue(rendered.contains("include pricing") && rendered.contains("INCLUDE_CHANGED"), rendered);
    }
}
