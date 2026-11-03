package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationError
import io.github.mbackschat.a12.dm.interpreter.ModelWorkspace
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.interpreter.WorkspaceModelSource
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What evaluation does with a model whose frame A12 builds and then cannot check (KERNEL-FINDINGS KF650's four
 * check-only shapes), with no consistency check in front of either Kernel strategy. Beside the unchanged control,
 * each shape is evaluated by dynamic Groovy, generated Java and the interpreter, and A12's consistency check is the
 * fourth column. A Kernel failure is named by the A12 class that throws, so a failure of our harness cannot pass for
 * one.
 *
 * Only a missing `modelVersion` is evaluated by the Kernel. The other three throw before any rule runs: in the
 * generated-code id, in the runtime's A12 settings (on kernel 30.8.1 missing locales failed in model expansion
 * instead) and in the code generator's character pre-check. The interpreter's runtime
 * preparation refuses the same three by the member's path and evaluates the fourth (INTERPRETER-FINDINGS IF451). Own
 * domain: an insurance policy whose reference is required.
 */
class CheckOnlyFrameRuntimeDiffTest {

    private val mapper = ObjectMapper()

    private val base: String = DocumentModelDraft.named("policy-frame-runtime").locale("en_US")
        .rootGroup("Policy") { p -> p.stringField("Ref") { } }
        .build().toJson()

    private val ruleCondition: String = RuntimeLaws.render(BoundModel.ofJson(base).str("/Policy/Ref").notFilled())

    private val sound: String = RuntimeLaws.withRule(base, "/Policy", "RRef", "../Ref", "REF", ruleCondition)

    private fun edited(pointer: String, value: JsonNode?): String = edited(sound, pointer, value)

    private fun edited(model: String, pointer: String, value: JsonNode?): String {
        val root = mapper.readTree(model) as ObjectNode
        val parent = root.at(pointer.substringBeforeLast('/')) as ObjectNode
        val name = pointer.substringAfterLast('/')
        if (value == null) parent.remove(name) else parent.set<JsonNode>(name, value)
        return root.toString()
    }

    /** The control and the four shapes, each edited into [model]. */
    private fun shapes(model: String): Map<String, String> = linkedMapOf(
        "control" to model,
        "modelVersion absent" to edited(model, "/header/modelVersion", null),
        "modelInfo absent" to edited(model, "/content/modelInfo", null),
        "locales absent" to edited(model, "/header/locales", null),
        "locales null" to edited(model, "/header/locales", mapper.nullNode()),
        "a null supported character" to edited(model, "/content/modelConfig/supportedCharacters", mapper.readTree("[\"a-z\", null]")),
    )

    /** `[dynamic Groovy, generated Java, interpreter, A12's consistency check]` over a policy with no `Ref`. */
    private fun observed(model: String): List<String> =
        evaluated(model) { PreparedModel.loadExpandedJson(model) } +
            attempt { RuntimeLaws.consistencyMessages(model) }.ifEmpty { "consistent" }

    /** `[dynamic Groovy, generated Java, interpreter]`: A12 evaluating the self-contained [model], beside [interpreter]. */
    private fun evaluated(model: String, interpreter: () -> PreparedModel): List<String> {
        val kernelDoc = RuntimeLaws.doc().group("/Policy", 1)
        return listOf(
            attempt { RuntimeLaws.messages(model, kernelDoc).map { it.code() } },
            attempt { RuntimeLaws.kernelJavaEngine(model).messagesModern(kernelDoc, null, java.util.Locale.US).map { it.code() } },
            try {
                val prepared = interpreter()
                prepared.createInterpreter().validateFull(prepared.documents.readDocument("""{"Policy":{}}"""))
                    .findings.map { it.code }.sorted().joinToString()
            } catch (refused: ModelPreparationError) {
                "refused " + refused.diagnostics.joinToString { "${it.code} ${it.path}" }
            },
        )
    }

    /** A Kernel failure is named by the innermost A12 class on its stack, so a failure of our harness cannot pass for one. */
    private fun attempt(engine: () -> List<String>) = try {
        engine().sorted().joinToString()
    } catch (refused: RuntimeException) {
        val thrower = refused.stackTrace.firstOrNull { it.className.startsWith("com.mgmtp.") }
        "A12 throws in ${thrower?.className?.substringAfterLast('.') ?: "no A12 class: $refused"}"
    }

    @Test
    fun onlyAMissingVersionIsEvaluatedAndTheInterpreterRefusesTheOtherThreeAsTheKernelDoes() {
        fun unevaluated(thrower: String, member: String) =
            listOf("A12 throws in $thrower", "A12 throws in $thrower", "refused INVALID_MODEL_SOURCE $member", "A12 throws in $thrower")
        assertEquals(
            mapOf(
                "control" to listOf("REF", "REF", "REF", "consistent"),
                "modelVersion absent" to listOf("REF", "REF", "REF", "A12 throws in VersionChecker"),
                "modelInfo absent" to unevaluated("DocumentModelInfoWrapper", "content.modelInfo"),
                "locales absent" to unevaluated("A12SpecificSettings", "header.locales"),
                "locales null" to unevaluated("A12SpecificSettings", "header.locales"),
                "a null supported character" to unevaluated("CharsetUtil", "content.modelConfig.supportedCharacters[1]"),
            ),
            shapes(sound).mapValues { (_, model) -> observed(model) },
            "as [dynamic Groovy, generated Java, interpreter, consistency]",
        )
    }

    /**
     * The rule is not what stops A12: the same shapes on the policy with no rule, which has no localized text for
     * expansion to fail on, still stop both strategies, so a refusal does not depend on the model carrying a rule.
     * On kernel 31.1.1 missing locales fail in the runtime's A12 settings on both strategies, as with the rule; on
     * 30.8.1 dynamic Groovy failed earlier, in its runtime service.
     */
    @Test
    fun withNoRuleA12StillCannotEvaluateTheThree() {
        fun unevaluated(groovy: String, java: String, member: String) =
            listOf("A12 throws in $groovy", "A12 throws in $java", "refused INVALID_MODEL_SOURCE $member", "A12 throws in $java")
        assertEquals(
            mapOf(
                "control" to listOf("", "", "", "consistent"),
                "modelVersion absent" to listOf("", "", "", "A12 throws in VersionChecker"),
                "modelInfo absent" to unevaluated("DocumentModelInfoWrapper", "DocumentModelInfoWrapper", "content.modelInfo"),
                "locales absent" to unevaluated("A12SpecificSettings", "A12SpecificSettings", "header.locales"),
                "locales null" to unevaluated("A12SpecificSettings", "A12SpecificSettings", "header.locales"),
                "a null supported character" to
                    unevaluated("CharsetUtil", "CharsetUtil", "content.modelConfig.supportedCharacters[1]"),
            ),
            shapes(base).mapValues { (_, model) -> observed(model) },
            "as [dynamic Groovy, generated Java, interpreter, consistency]",
        )
    }

    /**
     * The shapes on a REACHED model cost nothing: expansion keeps only the entry's frame, so A12 evaluates the
     * expanded entry whose included component lacks its version or model info or carries a `null` character, and the
     * interpreter's workspace prepares and evaluates it alike. The component's missing locales are not a row, because
     * a reached model not declaring the entry's locales is refused for that (KF594).
     */
    @Test
    fun aReachedModelsShapeIsEvaluatedByEveryEngine() {
        val component = DocumentModelDraft.named("component").locale("en_US")
            .rootGroup("Component") { c -> c.stringField("Description") {} }.build().toJson()
        val entry = DocumentModelDraft.named("policy-frame-runtime").locale("en_US")
            .rootGroup("Policy") { p -> p.stringField("Ref") {}.mount("Details", "component") }.build().toJson()
            .let { RuntimeLaws.withRule(it, "/Policy", "RRef", "../Ref", "REF", ruleCondition) }
        val reached = shapes(component).filterKeys { !it.startsWith("locales") }
        assertEquals(
            reached.mapValues { listOf("REF", "REF", "REF") },
            reached.mapValues { (_, provider) ->
                val expanded = CorpusModelPreparer.prepareKernelJson(entry) { id -> provider.takeIf { id == "component" } }
                evaluated(expanded) {
                    ModelWorkspace.fromSources(listOf(entry, provider).map { WorkspaceModelSource.builder().json(it).build() })
                        .prepare("policy-frame-runtime")
                }
            },
            "as [dynamic Groovy, generated Java, interpreter]",
        )
    }
}
