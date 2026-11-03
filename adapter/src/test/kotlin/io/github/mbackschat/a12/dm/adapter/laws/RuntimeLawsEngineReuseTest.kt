package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.md.document.apiV2.DocumentMultiPointer
import com.mgmtp.a12.kernel.md.document.apiV2.DocumentPointer
import com.mgmtp.a12.kernel.md.document.apiV2.PartiallyKnownDocumentMultiPointer
import com.mgmtp.a12.kernel.md.document.apiV2.immutable.DocumentV2
import com.mgmtp.a12.kernel.md.rt.api.ICustomCondition
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Oracle-harness lifetime guard: convenience laws reuse Groovy code generation per exact model JSON without
 * confusing derived models that retain the same A12 header id. Test assertions and per-call bindings stay fresh. */
class RuntimeLawsEngineReuseTest {

    private val base = RuntimeLaws.model("models/subscription.dm.json")

    @BeforeEach
    fun clearSharedOracleStateBeforeEachTest() {
        RuntimeLaws.clearSharedKernelEngines()
    }

    @AfterEach
    fun clearSharedOracleStateAfterEachTest() {
        RuntimeLaws.clearSharedKernelEngines()
    }

    @Test
    fun identicalModelJsonBuildsTheDynamicEngineOnceAcrossCallsAndLocales() {
        val model = RuntimeLaws.withRuleMessages(
            base,
            "/Subscription",
            "Filled",
            "../Tier",
            "FILLED",
            "FieldFilled(/Subscription/Tier)",
            "english",
            "deutsch",
        )
        val document = filledDocument()

        assertEquals("english", RuntimeLaws.errorTextOf(model, "FILLED", document, Locale.US).orElseThrow())
        assertEquals("deutsch", RuntimeLaws.errorTextOf(model, "FILLED", document, Locale.GERMANY).orElseThrow())
        assertEquals(1, RuntimeLaws.sharedKernelEngineBuildCount())
        assertEquals(1, RuntimeLaws.sharedKernelEngineCacheSize())
        assertEquals(64, RuntimeLaws.sharedKernelEngineCacheLimit())
    }

    @Test
    fun derivedModelsWithTheSameHeaderIdNeverShareGeneratedCode() {
        val filledModel = RuntimeLaws.withRule(
            base, "/Subscription", "Filled", "../Tier", "FILLED", "FieldFilled(/Subscription/Tier)",
        )
        val emptyModel = RuntimeLaws.withRule(
            base, "/Subscription", "Empty", "../Tier", "EMPTY", "FieldNotFilled(/Subscription/Tier)",
        )

        assertEquals(listOf("FILLED"), RuntimeLaws.messages(filledModel, filledDocument())
            .map { it.code() }.filter { it == "FILLED" || it == "EMPTY" })
        assertEquals(listOf("EMPTY"), RuntimeLaws.messages(emptyModel, emptyDocument())
            .map { it.code() }.filter { it == "FILLED" || it == "EMPTY" })
        assertEquals(2, RuntimeLaws.sharedKernelEngineBuildCount())
        assertEquals(2, RuntimeLaws.sharedKernelEngineCacheSize())
    }

    @Test
    fun cachedEngineKeepsAlternatingDocumentsIsolated() {
        val model = EditSession.read(base).also { session ->
            session.addRule { bound ->
                val tier = bound.field(TIER)
                bound.rule(bound.group(SUBSCRIPTION)).name("Filled").code("FILLED")
                    .errorField(tier)
                    .`when`(tier.filled())
                    .severity(Severity.ERROR)
                    .message(Dsl.en("Tier is filled."), Dsl.de("Tarifstufe ist ausgefüllt."))
                    .build()
            }
            session.addRule { bound ->
                val tier = bound.field(TIER)
                bound.rule(bound.group(SUBSCRIPTION)).name("Empty").code("EMPTY")
                    .errorField(tier)
                    .`when`(tier.notFilled())
                    .severity(Severity.ERROR)
                    .message(Dsl.en("Tier is empty."), Dsl.de("Tarifstufe ist leer."))
                    .build()
            }
            session.validate()
        }.toJson()

        fun authoredCodes(document: RuntimeLaws.Doc): List<String> = RuntimeLaws.messages(model, document)
            .map { it.code() }
            .filter { it == "FILLED" || it == "EMPTY" }

        assertEquals(listOf("FILLED"), authoredCodes(filledDocument()))
        assertEquals(listOf("EMPTY"), authoredCodes(emptyDocument()))
        assertEquals(listOf("FILLED"), authoredCodes(filledDocument()))
        assertEquals(1, RuntimeLaws.sharedKernelEngineBuildCount())
        assertEquals(1, RuntimeLaws.sharedKernelEngineCacheSize())
    }

    @Test
    fun cachedEngineKeepsTheClockBindingPerCall() {
        val order = RuntimeLaws.model("models/order.dm.json")
        val model = RuntimeLaws.withRule(
            order,
            "/Order",
            "BeforeNow",
            "../ScheduledAt",
            "BEFORE_NOW",
            "[/Order/ScheduledAt] < Now",
        )
        val document = RuntimeLaws.doc().group("/Order", 1)
            .field("/Order/ScheduledAt", "2025-06-23T12:00:00", 1, 1)

        assertEquals(emptyList<String>(), RuntimeLaws.messagesModern(
            model, document, Date.from(Instant.parse("2025-06-23T08:00:00Z")),
        ).map { it.code() }.filter { it == "BEFORE_NOW" })
        assertEquals(listOf("BEFORE_NOW"), RuntimeLaws.messagesModern(
            model, document, Date.from(Instant.parse("2025-06-23T14:00:00Z")),
        ).map { it.code() }.filter { it == "BEFORE_NOW" })
        assertEquals(1, RuntimeLaws.sharedKernelEngineBuildCount())
    }

    @Test
    fun cachedEngineInvokesTheSuppliedCustomConditionOnEveryCall() {
        val conditionName = "Counted"
        val model = RuntimeLaws.withRule(
            base,
            "/Subscription",
            "Custom",
            "../Tier",
            "CUSTOM",
            RuntimeLaws.render(
                BoundModel.ofJson(base).field("/Subscription/Tier").filled()
                    .and(Dsl.customCondition(conditionName)),
            ),
        )
        val calls = AtomicInteger()
        val condition = object : ICustomCondition {
            override fun check(
                document: DocumentV2,
                relevant: Set<out DocumentMultiPointer>?,
                formallyIncorrect: Set<DocumentPointer>,
                errorEntity: PartiallyKnownDocumentMultiPointer,
            ): Boolean {
                calls.incrementAndGet()
                return true
            }
        }

        repeat(2) {
            assertEquals(listOf("CUSTOM"), RuntimeLaws.messages(
                model, filledDocument(), mapOf(conditionName to condition),
            ).map { it.code() }.filter { it == "CUSTOM" })
        }
        assertEquals(2, calls.get())
        assertEquals(1, RuntimeLaws.sharedKernelEngineBuildCount())
    }

    private fun filledDocument() = RuntimeLaws.doc()
        .group(SUBSCRIPTION, 1)
        .field(TIER, "BASIC", 1, 1)

    private fun emptyDocument() = RuntimeLaws.doc().group(SUBSCRIPTION, 1)

    private companion object {
        const val SUBSCRIPTION = "/Subscription"
        const val TIER = "/Subscription/Tier"
    }
}
