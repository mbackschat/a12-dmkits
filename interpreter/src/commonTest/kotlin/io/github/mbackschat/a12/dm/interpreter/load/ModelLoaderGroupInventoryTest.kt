package io.github.mbackschat.a12.dm.interpreter.load

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lock for the complete declared group inventory (R2.8 substrate): the loader records EVERY Group element id,
 * not only repeatable/index-bearing ones, so the Document transport can validate a group placement against a
 * declared field-less, non-repeatable group instead of guessing from field-path prefixes.
 */
class ModelLoaderGroupInventoryTest {

    @Test
    fun everyDeclaredGroupIsInTheInventoryIncludingFieldlessOnes() {
        val model = """
            {
              "header": {"id": "policy"},
              "content": {
                "modelRoot": {"rootGroups": [{
                  "type": "Group", "id": "/Policy", "name": "Policy",
                  "Group": {"elements": [
                    {
                      "type": "Group", "id": "/Policy/Cover", "name": "Cover",
                      "Group": {"repeatability": 3, "elements": [
                        {
                          "type": "Field", "id": "/Policy/Cover/Premium", "name": "Premium",
                          "Field": {"fieldType": {"type": "NumberType"}}
                        }
                      ]}
                    },
                    {
                      "type": "Group", "id": "/Policy/Notes", "name": "Notes",
                      "Group": {}
                    }
                  ]}
                }]}
              }
            }
        """.trimIndent()

        val def = ModelLoader.load(model)

        assertEquals(
            setOf("/Policy", "/Policy/Cover", "/Policy/Notes"),
            def.groupPaths,
            "the loader records every declared group id, field-less and non-repeatable included",
        )
        assertTrue(
            "/Policy/Notes" in def.entityPaths,
            "a field-less declared group is a known entity path",
        )
    }
}
