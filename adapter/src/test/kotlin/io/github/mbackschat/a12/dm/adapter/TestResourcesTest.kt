package io.github.mbackschat.a12.dm.adapter

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TestResourcesTest {

    @Test
    fun readsAFixtureAsUtf8Text() {
        assertTrue(TestResources.text("models/order.dm.json").contains("\"Order\""))
    }

    @Test
    fun aMissingFixtureFailsNamingItsPath() {
        val failure = assertThrows<IllegalStateException> { TestResources.text("models/no-such.dm.json") }
        assertTrue(failure.message!!.contains("models/no-such.dm.json"), failure.message)
    }
}
