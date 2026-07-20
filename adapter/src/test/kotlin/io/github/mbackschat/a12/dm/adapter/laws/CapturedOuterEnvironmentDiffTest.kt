package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A Having filter captures the complete outer repetition environment, not one scalar row index.
 * Each `$` CurrentRepetition lookup selects the coordinate belonging to its own group path.
 */
class CapturedOuterEnvironmentDiffTest {

    @Test
    fun parentAndDescendantOuterCoordinatesRemainDistinctInsideHaving() {
        val base = DocumentModelDraft.named("captured-environment").locale("en_US")
            .rootGroup("Survey") { survey ->
                survey.group("Parents") { parent ->
                    parent.repeatable(4)
                        .stringField("ParentKey") { }
                        .stringField("Marker") { }
                        .indexField("ParentKey")
                    parent.group("Children") { child ->
                        child.repeatable(4)
                            .stringField("ChildKey") { }
                            .stringField("Probe") { }
                            .indexField("ChildKey")
                    }
                }
            }.build().toJson()
        val model = BoundModel.ofJson(base)
        val parents = model.group(PARENTS)
        val children = model.group(CHILDREN)
        val candidateParent = Dsl.currentRepetition(parents)
        val filter = candidateParent.eq(Dsl.currentRepetition(parents, true))
            .and(candidateParent.eq(Dsl.currentRepetition(children, true)))
        val condition = model.field(PROBE).filled().and(
            Dsl.atLeastOneFieldFilled(model.flatten("$PARENTS*/Marker").having(filter)))
        val rule = model.rule(model.group(ROOT)).name("CapturedEnvironment")
            .errorField(model.field(PROBE))
            .`when`(condition)
            .code(CODE)
            .severity(Severity.ERROR)
            .message(Dsl.en("captured"))
            .build()
        val modelJson = model.add(rule).toJson()

        val dynamicDoc = RuntimeLaws.doc()
            .group(ROOT, 1)
            .group(PARENTS, 1, 1)
            .field(PARENT_KEY, "P1", 1, 1, 1)
            .field(MARKER, "marked", 1, 1, 1)
            .group(CHILDREN, 1, 1, 1)
            .field(CHILD_KEY, "C1", 1, 1, 1, 1)
            .field(PROBE, "one", 1, 1, 1, 1)
            .group(CHILDREN, 1, 1, 2)
            .field(CHILD_KEY, "C2", 1, 1, 2, 1)
            .field(PROBE, "two", 1, 1, 2, 1)
        val definition = ModelLoader.load(modelJson)
        val interpreterDoc = definition.newDocument()
            .group(ROOT, 1)
            .group(PARENTS, 1, 1)
            .field(PARENT_KEY, "P1", 1, 1, 1)
            .field(MARKER, "marked", 1, 1, 1)
            .group(CHILDREN, 1, 1, 1)
            .field(CHILD_KEY, "C1", 1, 1, 1, 1)
            .field(PROBE, "one", 1, 1, 1, 1)
            .group(CHILDREN, 1, 1, 2)
            .field(CHILD_KEY, "C2", 1, 1, 2, 1)
            .field(PROBE, "two", 1, 1, 2, 1)

        val dynamic = RuntimeLaws.messages(modelJson, dynamicDoc)
            .filter { it.code() == CODE }.map { it.field() }.sorted()
        val staticEngine = RuntimeLaws.kernelJavaEngine(modelJson)
        val static = staticEngine.messages(staticEngine.instance(dynamicDoc))
            .filter { it.code() == CODE }.map { it.field() }.sorted()
        val interpreted = DmInterpreter(definition).validateFull(interpreterDoc)
            .filter { it.code == CODE }.map { it.errorPath }.sorted()

        assertEquals(listOf("/Survey[1]/Parents[1]/Children[1]/Probe"), dynamic,
            "only the diagonal child row fires")
        assertEquals(dynamic, static, "static-Java")
        assertEquals(dynamic, interpreted, "interpreter")
    }
}

private const val ROOT = "/Survey"
private const val PARENTS = "/Survey/Parents"
private const val CHILDREN = "/Survey/Parents/Children"
private const val PARENT_KEY = "/Survey/Parents/ParentKey"
private const val MARKER = "/Survey/Parents/Marker"
private const val CHILD_KEY = "/Survey/Parents/Children/ChildKey"
private const val PROBE = "/Survey/Parents/Children/Probe"
private const val CODE = "CAPTURED_ENV"
