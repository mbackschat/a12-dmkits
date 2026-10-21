package io.github.mbackschat.a12.dm.interpreter

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The int binding against the rows A12 measured (`adapter.laws.RepeatabilityTextReadingDiffTest`,
 * `ModelTextReadingDiffTest`; KERNEL-FINDINGS KF590, KF593), held on every target so Node binds as the JVM does.
 */
class JavaIntBindingTest {

    private fun bound(raw: String): String = when (val binding = Json.parseToJsonElement(raw).bindJavaInt()) {
        is JavaIntBinding.Bound -> "${binding.value}"
        JavaIntBinding.NoValue -> "no value"
        JavaIntBinding.Unbindable -> "unbindable"
    }

    @Test
    fun eachMemberBindsAsA12Binds() {
        val rows = linkedMapOf(
            "3" to "3", "3.7" to "3", "-3.7" to "-3", "1e1" to "10", "1.0E9" to "1000000000", "-0" to "0", "0.5" to "0",
            "2147483647.0" to "2147483647", "-2147483648" to "-2147483648", "2147483647.5" to "unbindable",
            "-2147483648.5" to "unbindable", "2147483648.0" to "unbindable", "3e10" to "unbindable",
            "1e9999999999" to "unbindable", "1e-2147483649" to "unbindable",
            "\"3\"" to "3", "\" 3 \"" to "3", "\"\\t3\\n\"" to "3", "\"\\u0663\"" to "3", "\"\\uFF13\"" to "3", "\"+3\"" to "3",
            "\"\"" to "no value", "\"null\"" to "no value", "\" null \"" to "no value", "null" to "no value",
            "\"NULL\"" to "unbindable", "\"3.7\"" to "unbindable", "\"1e1\"" to "unbindable", "\"\\u00A03\"" to "unbindable",
            "\"99999999999\"" to "unbindable", "true" to "unbindable",
        )
        assertEquals(rows, rows.mapValues { (raw, _) -> bound(raw) })
    }
}
