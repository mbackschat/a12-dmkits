package io.github.mbackschat.a12.dm.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.adapter.laws.notificationClass
import io.github.mbackschat.a12.dm.consistency.CK_MODEL_NOT_BUILDABLE
import io.github.mbackschat.a12.dm.consistency.ConsistencyWorkspace
import io.github.mbackschat.a12.dm.consistency.DiagnosticCode
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.interpreter.ModelWorkspace
import io.github.mbackschat.a12.dm.interpreter.WorkspaceModelSource
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What A12 reads from the models an entry model reaches, an included model and a type-definition library, and that
 * the interpreter's workspace and the portable checker refuse the same entries (KERNEL-FINDINGS KF594). Own domain
 * (an insurance cover, its component and its code types).
 */
class WorkspaceReachedModelDiffTest {

    /**
     * Every model the entry reaches is read whole, the entry itself, an included model and a type-definition library
     * alike, so a model-level member A12 cannot bind in any of them leaves the entry model unbuilt: the base year and
     * the three primitive `boolean`s. Each carrier's untouched and bound rows are the controls.
     */
    @Test
    fun aReachedModelsUnbindableModelMemberLeavesTheEntryUnbuilt() {
        val members = linkedMapOf(
            "modelInfo.baseYear \"NULL\"" to listOf(Triple("modelInfo", "baseYear", "NULL")),
            "modelInfo.immutable 1.5" to listOf(Triple("modelInfo", "immutable", 1.5)),
            "modelInfo.metaData 1.5" to listOf(Triple("modelInfo", "metaData", 1.5)),
            "modelConfig.fieldRefByShortNameAllowed 1.5" to listOf(Triple("modelConfig", "fieldRefByShortNameAllowed", 1.5)),
        )
        val bound = listOf(Triple("modelInfo", "baseYear", 2024), Triple("modelInfo", "immutable", false),
            Triple("modelInfo", "metaData", false), Triple("modelConfig", "fieldRefByShortNameAllowed", false))
        val observed = linkedMapOf<String, String>()
        for (carrier in Carrier.entries) {
            observed["$carrier untouched"] = observe(carrier) {}
            observed["$carrier bound"] = observe(carrier) { content -> bound.forEach { content.setMember(it) } }
            members.forEach { (row, edits) -> observed["$carrier $row"] = observe(carrier) { content -> edits.forEach { content.setMember(it) } } }
        }
        assertEquals(observed.mapValues { (row, _) ->
            if (row.endsWith("untouched") || row.endsWith("bound")) BUILT else NOT_BUILT
        }, observed)
    }

    /**
     * A model's frame, the `header` and `content` objects and the members under them, is bound by A12's reader in
     * every model the entry reaches, so a member it cannot bind leaves the entry model unbuilt wherever it sits. Three
     * members are needed only by the entry's consistency check, so they cost a reached model nothing: the version, the
     * model info and a `null` supported character (KERNEL-FINDINGS KF650). On the entry, A12's runtime cannot
     * evaluate the last two, so the interpreter's preparation refuses them there (INTERPRETER-FINDINGS IF451). Each
     * carrier's untouched row is the control.
     */
    @Test
    fun aReachedModelsUnbindableFrameLeavesTheEntryUnbuilt() {
        val edits = linkedMapOf<String, (ObjectNode) -> Unit>(
            "header absent" to { it.remove("header") },
            "content absent" to { it.remove("content") },
            "header.id []" to { (it.path("header") as ObjectNode).putArray("id") },
            "content.modelRoot \"x\"" to { (it.path("content") as ObjectNode).put("modelRoot", "x") },
            "content.modelConfig.supportedCharacters [{}]" to { (it.at("/content/modelConfig") as ObjectNode).putArray("supportedCharacters").addObject() },
            "header.labels {}" to { (it.path("header") as ObjectNode).putObject("labels") },
            "header.modelVersion absent" to { (it.path("header") as ObjectNode).remove("modelVersion") },
            "content.modelInfo absent" to { (it.path("content") as ObjectNode).remove("modelInfo") },
            "content.modelInfo.name null" to { (it.at("/content/modelInfo") as ObjectNode).putNull("name") },
            "content.modelConfig null" to { (it.path("content") as ObjectNode).putNull("modelConfig") },
            "content.modelConfig.conditionLanguage {}" to { (it.at("/content/modelConfig") as ObjectNode).putObject("conditionLanguage") },
            "content.modelConfig.supportedCharacters [null]" to { (it.at("/content/modelConfig") as ObjectNode).putArray("supportedCharacters").addNull() },
            "content.modelRoot.rootGroups null" to { (it.at("/content/modelRoot") as ObjectNode).putNull("rootGroups") },
        )
        val observed = linkedMapOf<String, String>()
        for (carrier in Carrier.entries) {
            observed["$carrier untouched"] = models(carrier) {}.let { (entry, provider) -> observeWithConsistency(entry, provider) }
            edits.forEach { (row, edit) ->
                observed["$carrier $row"] = models(carrier, edit).let { (entry, provider) -> observeWithConsistency(entry, provider) }
            }
        }
        val checkOnly = setOf("header.modelVersion absent", "content.modelInfo absent", "content.modelConfig.supportedCharacters [null]")
        // A12's runtime cannot evaluate these two on the entry, so runtime preparation refuses them there (KF650).
        val unevaluable = setOf("content.modelInfo absent", "content.modelConfig.supportedCharacters [null]")
        // No text `header.id` or no `content` object: the checker's admission refuses the source and names why. A
        // library it cannot admit leaves the entry's library type unfound, which kernel 31.1.1 states as a sentence
        // rather than leaving no model (KF652), so a library row reads as unadmitted like an include row.
        val unadmitted = setOf("header absent", "content absent", "header.id []")
        assertEquals(observed.mapValues { (row, _) ->
            val edit = row.substringAfter(' ')
            when {
                edit == "untouched" -> BUILT_AND_CHECKED
                edit in unadmitted -> NOT_BUILT_OR_ADMITTED
                edit !in checkOnly -> NOT_BUILT_OR_CHECKED
                row.startsWith("ENTRY") && edit in unevaluable -> BUILT_NOT_CHECKED_OR_PREPARED
                row.startsWith("ENTRY") -> BUILT_NOT_CHECKED
                else -> BUILT_AND_CHECKED
            }
        }, observed)
    }

    /**
     * A type-definition library is expanded whatever root groups it holds on kernel 31.1.1, empty or not. Kernel
     * 30.8.1 refused any ("Cannot expand import because the root group of the imported model … contains elements"),
     * in a compatibility check 31.1.0 removed with `DocumentModelExpandSanityChecks`.
     */
    @Test
    fun aTypeDefinitionLibraryWithARootGroupIsExpanded() {
        fun library(edit: (ObjectNode) -> Unit) = (MAPPER.readTree(TYPES) as ObjectNode).also { edit(it.path("content") as ObjectNode) }.toString()
        fun group(name: String, elements: String) =
            MAPPER.readTree("""{"type":"Group","id":"/$name","name":"$name","Group":{"elements":$elements}}""")
        val field = """[{"type":"Field","id":"/Types/Note","name":"Note","Field":{"fieldType":{"type":"StringType"}}}]"""
        val rows = linkedMapOf(
            "no root group" to library {},
            "no modelRoot" to library { it.remove("modelRoot") },
            "an empty root group" to library { (it.at("/modelRoot/rootGroups") as ArrayNode).add(group("Types", "[]")) },
            "a root group holding a field" to library { (it.at("/modelRoot/rootGroups") as ArrayNode).add(group("Types", field)) },
            "two empty root groups" to library {
                (it.at("/modelRoot/rootGroups") as ArrayNode).add(group("Types", "[]")).add(group("More", "[]"))
            },
        )
        val observed = rows.mapValues { (_, library) -> observe(coded().toString(), library) }
        assertEquals(rows.mapValues { BUILT }, observed)
    }

    /**
     * An included model must declare every locale the entry declares: the demand is coverage, so a superset is
     * admitted. A type-definition library is held to none of it on kernel 31.1.1, where 30.8.1 held it to the same
     * demand (`DocumentModelExpandSanityChecks.checkLocaleCompatibility`, removed in 31.1.0).
     */
    @Test
    fun anIncludedModelMissingAnEntryLocaleLeavesTheEntryUnbuilt() {
        val rows = linkedMapOf<String, String>()
        for (carrier in listOf(Carrier.INCLUDE, Carrier.TYPE_DEFINITIONS)) {
            for ((row, locales) in listOf("same locales" to listOf("en_US", "de_DE"), "a superset" to listOf("en_US", "de_DE", "fr_FR"),
                "missing de_DE" to listOf("en_US"))) {
                val (entry, provider) = when (carrier) {
                    Carrier.INCLUDE -> permit() to MAPPER.readTree(COMPONENT) as ObjectNode
                    else -> coded() to MAPPER.readTree(TYPES) as ObjectNode
                }
                entry.withLocales(listOf("en_US", "de_DE"))
                provider.withLocales(locales)
                rows["$carrier $row"] = observe(entry.toString(), provider.toString())
            }
        }
        assertEquals(rows.mapValues { (row, _) -> if (row == "${Carrier.INCLUDE} missing de_DE") NOT_BUILT else BUILT }, rows)
    }

    /**
     * Which declared locale codes leave the entry unbuilt, by the model that declares them. A code A12's reader
     * refuses (`en_us`) does so in every model the entry reaches. A locale declared twice, in one spelling or two
     * (`en_US`, `en-US`), and the empty code are carrier-dependent: an entry declaring them builds but A12's consistency
     * check cannot read it, an included model declaring them is not expanded, and a type-definition library declaring
     * them is (`DocumentModelExpandSanityChecks`, kernel `30.8.1`). The interpreter answers A12's build and the checker
     * its consistency check; `en_US` and `en-US` alone are the controls.
     */
    @Test
    fun aLocaleCodeA12CannotUseLeavesTheEntryUnbuilt() {
        val sets = linkedMapOf(
            "en_US" to listOf("en_US"), "en-US" to listOf("en-US"), "en_US twice" to listOf("en_US", "en_US"),
            "en_US + en-US" to listOf("en_US", "en-US"), "en_US + en_us" to listOf("en_US", "en_us"),
            "en_US + \"\"" to listOf("en_US", ""), "en_us" to listOf("en_us"), "\"\"" to listOf(""),
        )
        val observed = linkedMapOf<String, String>()
        for (carrier in Carrier.entries) {
            for ((label, codes) in sets) {
                // A provider must still cover the entry's en_US, or the coverage demand answers the row instead.
                if (carrier != Carrier.ENTRY && codes.none { it.replace('-', '_') == "en_US" }) continue
                val (entry, provider) = when (carrier) {
                    Carrier.ENTRY -> cover() to null
                    Carrier.INCLUDE -> permit() to MAPPER.readTree(COMPONENT) as ObjectNode
                    Carrier.TYPE_DEFINITIONS -> coded() to MAPPER.readTree(TYPES) as ObjectNode
                }
                (provider ?: entry).withLocales(codes)
                observed["$carrier [$label]"] = observeWithConsistency(entry.toString(), provider?.toString())
            }
        }
        val usable = setOf("[en_US]", "[en-US]")
        val unreadable = setOf("[en_US + en_us]", "[en_us]")
        assertEquals(observed.mapValues { (row, _) ->
            val codes = row.substringAfter(' ')
            when {
                codes in usable -> BUILT_AND_CHECKED
                codes in unreadable -> NOT_BUILT_OR_CHECKED
                row.startsWith("ENTRY") -> BUILT_NOT_CHECKED
                row.startsWith("INCLUDE") -> NOT_BUILT_OR_CHECKED
                else -> BUILT_AND_CHECKED
            }
        }, observed)
    }

    /**
     * Two locales the JDK writes as one language tag draw `MVK_SUPP_LANGUAGES_DUPLICATE` from the entry's header
     * alone: an included model or a type-definition library declaring them is expanded and checked without it
     * (KERNEL-FINDINGS KF649, kernel `30.8.1`).
     */
    @Test
    fun onlyTheEntrysLocalesAreComparedByLanguageTag() {
        val observed = Carrier.entries.associateWith { carrier ->
            val (entry, provider) = when (carrier) {
                Carrier.ENTRY -> cover() to null
                Carrier.INCLUDE -> permit() to MAPPER.readTree(COMPONENT) as ObjectNode
                Carrier.TYPE_DEFINITIONS -> coded() to MAPPER.readTree(TYPES) as ObjectNode
            }
            (provider ?: entry).withLocales(listOf("en_US", "en_US_abcdefghi"))
            val sources = listOfNotNull(entry.toString(), provider?.toString())
            val providerId = provider?.at("/header/id")?.asText()
            val a12 = KernelConsistencyOracle().checkConsistency(entry.toString()) { if (it == providerId) provider.toString() else null }
                .map { notificationClass(it.message().trim()) }
            val checker = ConsistencyWorkspace.fromSources(sources.map(::ModelSource))
                .check(entry.at("/header/id").asText()).findings.filter { it.justifiesRejection }.map { it.code }
            a12 to checker
        }
        assertEquals(Carrier.entries.associateWith { if (it == Carrier.ENTRY) DUPLICATE_TAG to DUPLICATE_TAG else NONE to NONE }, observed)
    }

    /** [observe]'s three answers, with A12's consistency verdict beside its build: the checker's oracle. */
    private fun observeWithConsistency(entry: String, provider: String?): String {
        val providerId = provider?.let { MAPPER.readTree(it).at("/header/id").asText() }
        val checked = try {
            KernelConsistencyOracle().checkConsistency(entry) { if (it == providerId) provider else null }
            true
        } catch (unbuildable: RuntimeException) {
            // An included model's repeated locale escapes A12's check as a bare IllegalStateException and its empty
            // code as a NullPointerException, where the entry's surface as ConsistencyCheckFailedException: each is
            // a check that returned no verdict.
            false
        }
        return observe(entry, provider).replace(", interpreter", ", A12 consistency ${if (checked) "checks" else "unbuilt"}, interpreter")
    }

    private fun ObjectNode.withLocales(codes: List<String>) {
        val locales = (path("header") as ObjectNode).putArray("locales")
        codes.forEach { locales.addObject().put("code", it) }
    }

    private enum class Carrier { ENTRY, INCLUDE, TYPE_DEFINITIONS }

    /**
     * A12's build, the interpreter's preparation and the checker's refusal of an entry model, with [edit] applied to
     * the `content` of the model [carrier] names.
     */
    private fun observe(carrier: Carrier, edit: (ObjectNode) -> Unit): String =
        observeRoot(carrier) { root -> edit(root.path("content") as ObjectNode) }

    /** [observe], with [edit] applied to the whole model [carrier] names rather than to its `content`. */
    private fun observeRoot(carrier: Carrier, edit: (ObjectNode) -> Unit): String =
        models(carrier, edit).let { (entry, provider) -> observe(entry, provider) }

    /** The entry model and the provider [carrier] names, if any, with [edit] applied to the model it names. */
    private fun models(carrier: Carrier, edit: (ObjectNode) -> Unit): Pair<String, String?> {
        val (entry, provider) = when (carrier) {
            Carrier.ENTRY -> cover() to null
            Carrier.INCLUDE -> permit() to MAPPER.readTree(COMPONENT) as ObjectNode
            Carrier.TYPE_DEFINITIONS -> coded() to MAPPER.readTree(TYPES) as ObjectNode
        }
        edit(provider ?: entry)
        return entry.toString() to provider?.toString()
    }

    private fun observe(entry: String, provider: String?): String {
        val sources = listOfNotNull(entry, provider)
        val providerId = provider?.let { MAPPER.readTree(it).at("/header/id").asText() }
        val entryId = MAPPER.readTree(entry).at("/header/id").asText()
        val a12 = runCatching { KernelModelOracle.of(entry) { if (it == providerId) provider else null } }.isSuccess
        val interpreter = runCatching {
            ModelWorkspace.fromSources(sources.map { WorkspaceModelSource.builder().json(it).build() }).prepare(entryId)
        }.isSuccess
        val report = ConsistencyWorkspace.fromSources(sources.map(::ModelSource)).check(entryId)
        val checker = when {
            report.findings.any { it.code == CK_MODEL_NOT_BUILDABLE } -> "not built"
            report.diagnostics.any { it.code in ADMISSION_REFUSALS } -> "not admitted"
            else -> "silent"
        }
        return "A12 ${if (a12) "built" else "not built"}, interpreter ${if (interpreter) "prepares" else "refuses"}, checker $checker"
    }

    private fun ObjectNode.setMember(member: Triple<String, String, Any>) {
        ((get(member.first) as? ObjectNode) ?: putObject(member.first)).set<ObjectNode>(member.second, MAPPER.valueToTree(member.third))
    }

    private fun cover() = MAPPER.readTree(DocumentModelDraft.named("cover").locale("en_US")
        .rootGroup("Cover") { g -> g.numberField("Amount") { it.fractionDigits(0) } }.build().toJson()) as ObjectNode

    private fun permit() = MAPPER.readTree(DocumentModelDraft.named("permit").locale("en_US")
        .rootGroup("Permit") { p -> p.mount("Details", "component") }.build().toJson()) as ObjectNode

    /** `cover` with a `Code` field of the library type `CoverCode`, which `types` declares. */
    private fun coded() = cover().also { root ->
        (root.path("header") as ObjectNode).putArray("modelReferences").addObject()
            .put("alias", "types").put("modelType", "document").put("purpose", "typeDefinitions").put("reference", "types")
        (root.at("/content/modelRoot/rootGroups/0/Group/elements") as ArrayNode).addObject()
            .put("type", "Field").put("id", "/Cover/Code").put("name", "Code").putObject("Field")
            .putObject("fieldType").put("type", "TypeDefType").putObject("TypeDefType").put("typeDefinitionId", "CoverCode")
    }

    private companion object {
        val MAPPER = ObjectMapper()
        val COMPONENT: String = DocumentModelDraft.named("component").locale("en_US")
            .rootGroup("Component") { c -> c.stringField("Description") {} }.build().toJson()

        /** A library: type definitions and no root group, which is the shape A12 expands. */
        val TYPES: String = (MAPPER.readTree(DocumentModelDraft.named("types").locale("en_US")
            .rootGroup("Types") { t -> t.stringField("Note") {} }.build().toJson()) as ObjectNode).also { root ->
            (root.at("/content/modelRoot/rootGroups") as ArrayNode).removeAll()
            (root.path("content") as ObjectNode).putArray("typeDefinitions").addObject().put("id", "CoverCode")
                .put("name", "CoverCode").put("revision", "1")
                .putObject("fieldType").put("type", "StringType").putObject("StringType").put("maxLength", 12)
        }.toString()

        const val BUILT = "A12 built, interpreter prepares, checker silent"
        const val NOT_BUILT = "A12 not built, interpreter refuses, checker not built"
        const val BUILT_AND_CHECKED = "A12 built, A12 consistency checks, interpreter prepares, checker silent"
        const val BUILT_NOT_CHECKED = "A12 built, A12 consistency unbuilt, interpreter prepares, checker not built"
        const val BUILT_NOT_CHECKED_OR_PREPARED = "A12 built, A12 consistency unbuilt, interpreter refuses, checker not built"
        const val NOT_BUILT_OR_CHECKED = "A12 not built, A12 consistency unbuilt, interpreter refuses, checker not built"
        const val NOT_BUILT_OR_ADMITTED = "A12 not built, A12 consistency unbuilt, interpreter refuses, checker not admitted"

        /** The checker's admission refusals: a source it cannot read as a DocumentModel at all. */
        val ADMISSION_REFUSALS = setOf(DiagnosticCode.SOURCE_NOT_WELL_FORMED, DiagnosticCode.SOURCE_HAS_NO_IDENTITY,
            DiagnosticCode.SOURCE_HAS_NO_MODEL_CONTENT)
        val DUPLICATE_TAG = listOf("MVK_SUPP_LANGUAGES_DUPLICATE")
        val NONE = emptyList<String>()
    }
}
