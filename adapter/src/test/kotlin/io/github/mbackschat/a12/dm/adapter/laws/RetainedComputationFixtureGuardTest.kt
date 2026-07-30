package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RetainedComputationFixtureGuardTest {

    @Test
    fun committedModelMatchesItsTypedAuthoringSurface() {
        val fixture = Path.of(System.getProperty(FIXTURE_PROPERTY))
        val expected = RetainedComputationFixture.modelJson()
        if (java.lang.Boolean.getBoolean(FIXTURE_FIX_PROPERTY)) {
            Files.createDirectories(fixture.parent)
            Files.writeString(fixture, expected)
            return
        }

        assertEquals(
            JSON.readTree(expected),
            JSON.readTree(Files.readString(fixture)),
            "$fixture drifted from its typed modelkit/rulekit author",
        )
    }

    private companion object {
        val JSON = ObjectMapper()
        const val FIXTURE_PROPERTY = "retained.computation.fixture"
        const val FIXTURE_FIX_PROPERTY = "retained.computation.fixture.fix"
    }
}
