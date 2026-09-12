package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B/B3: a model that MOUNTS another model is checked against the source set that carries it.
 *
 * Mounting is not a convenience here, it decides verdicts. Every reference into an included subtree reads as
 * undeclared while the provider is absent, so the checker had to suspend the whole analysis; with the
 * provider supplied it can answer, and the answer depends on getting the placement exactly right. The three
 * facts it rests on are the Kernel's, source-verified in [KERNEL-FINDINGS KF3]: the provider's root group is
 * REPLACED by the mount group rather than added above it, the provider is fully expanded before it is
 * mounted, and a reference that named the vanished root is rewritten rather than left to fail.
 */
class WorkspaceMountTest {

    private fun source(json: String) = ModelSource(json.trimIndent())

    /** A provider whose root group carries [elements]; its own root name must never survive into a path. */
    private fun provider(id: String, root: String, elements: String, flag: Boolean = false) = source(
        """
        {"header":{"id":"$id"},"content":{
          "modelConfig":{"fieldRefByShortNameAllowed":$flag},
          "modelRoot":{"rootGroups":[
            {"type":"Group","id":"g","name":"$root","Group":{"elements":[$elements]}}
          ]}}}
        """,
    )

    private fun field(name: String) = """{"type":"Field","id":"f-$name","name":"$name","Field":{}}"""

    private fun rule(name: String, errorPath: String, condition: String) =
        """{"type":"Rule","id":"r-$name","name":"$name","Rule":{"errorCode":"C",
           "errorEntityRelPath":"$errorPath","errorCondition":"$condition"}}"""

    /**
     * A host whose root group `Order` holds a field `Code`, [extra], and a mount group `ShipTo` carrying
     * [mountBody] — the one member each row varies.
     */
    private fun host(mountBody: String = """"modelAlias":"addr"""", extra: String = "", flag: Boolean = false) =
        source(
            """
            {"header":{"id":"order","modelReferences":[
              {"alias":"addr","purpose":"include","reference":"address"}
            ]},"content":{
              "modelConfig":{"fieldRefByShortNameAllowed":$flag},
              "modelRoot":{"rootGroups":[
                {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                  ${field("Code")},
                  {"type":"Group","id":"g1","name":"ShipTo","Group":{$mountBody}}${if (extra.isEmpty()) "" else ",$extra"}
                ]}}
              ]}}}
            """,
        )

    private fun check(vararg sources: ModelSource, entry: String = "order") =
        ConsistencyWorkspace.fromSources(sources.toList()).check(entry)

    /**
     * The placement fact everything else rests on: the provider's root group DISAPPEARS. Its children become
     * the mount's children, so `Street` is reachable at `/Order/ShipTo/Street` and the provider's own root
     * name `Address` appears in no path at all — which is the control, because a resolver that simply nested
     * the provider under the mount would resolve the second spelling and look correct on the first.
     */
    @Test
    fun aMountedProvidersChildrenBecomeTheMountsChildrenAndItsRootNameVanishes() {
        val address = provider("address", "Address", field("Street"))
        val reaches = host(extra = rule("R", "../ShipTo/Street", "FieldFilled(ShipTo/Street)"))
        val nests = host(extra = rule("R", "../Address/Street", "FieldFilled(Address/Street)"))

        assertEquals(emptyList(), check(reaches, address).findings)
        assertEquals(
            listOf("MVK_ERROR_FIELD_MISSING", "MVK_INVALID_ENTITY"),
            check(nests, address).findings.map { it.code },
        )
    }

    /**
     * A rule that travelled with the fragment still binds. Its relative path is move-invariant precisely
     * because the root was replaced rather than added: `Street`'s parent was the provider root and is now the
     * mount, at the same depth. An ABSOLUTE reference is the case that does move, and the Kernel rewrites it
     * before integration (KF3) — so `/Address/Street` must resolve even though the host tree has no
     * `/Address` at all, which the second row's control model makes explicit.
     */
    @Test
    fun anIncludedRulesOwnReferencesBindAfterTheMove() {
        val relative = provider(
            "address",
            "Address",
            field("Street") + "," + rule("Filled", "../Street", "FieldFilled(Street)"),
        )
        val absolute = provider(
            "address",
            "Address",
            field("Street") + "," + rule("Filled", "../Street", "FieldFilled(/Address/Street)"),
        )
        val stale = provider(
            "address",
            "Address",
            field("Street") + "," + rule("Filled", "../Street", "FieldFilled(/Order/Address/Street)"),
        )

        assertEquals(emptyList(), check(host(), relative).findings)
        assertEquals(emptyList(), check(host(), absolute).findings)
        assertEquals(
            listOf("MVK_INVALID_ENTITY"),
            check(host(), stale).findings.map { it.code },
            "only the provider's OWN root prefix is rewritten; a host-shaped path is left as written",
        )
    }

    /** A provider is fully expanded before it is mounted, so a nested mount's children arrive already placed. */
    @Test
    fun aProviderIsExpandedBeforeItIsMounted() {
        val geo = provider("geo", "Geo", field("Zip"))
        val address = source(
            """
            {"header":{"id":"address","modelReferences":[
              {"alias":"g","purpose":"include","reference":"geo"}
            ]},"content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g","name":"Address","Group":{"elements":[
                ${field("Street")},
                {"type":"Group","id":"gg","name":"Where","Group":{"modelAlias":"g"}}
              ]}}
            ]}}}
            """,
        )
        val entry = host(extra = rule("R", "../ShipTo/Where/Zip", "FieldFilled(ShipTo/Where/Zip)"))

        assertEquals(emptyList(), check(entry, address, geo).findings)
    }

    /** One provider mounted at two points expands at both — a mount is a placement, not a consumption. */
    @Test
    fun theSameProviderMountsAtEveryPointThatNamesIt() {
        val address = provider("address", "Address", field("Street"))
        val entry = source(
            """
            {"header":{"id":"order","modelReferences":[
              {"alias":"addr","purpose":"include","reference":"address"}
            ]},"content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Group","id":"g1","name":"ShipTo","Group":{"modelAlias":"addr"}},
                {"type":"Group","id":"g2","name":"BillTo","Group":{"modelAlias":"addr"}},
                ${rule("R", "../ShipTo/Street", "FieldFilled(ShipTo/Street) And FieldFilled(BillTo/Street)")}
              ]}}
            ]}}}
            """,
        )

        assertEquals(emptyList(), check(entry, address).findings)
    }

    /**
     * A cycle is a coverage gap rather than a hang or a raise. It is stated at the mount that closed the
     * loop, because that is the declaration an author edits.
     */
    @Test
    fun anIncludeCycleIsReportedRatherThanFollowed() {
        val address = source(
            """
            {"header":{"id":"address","modelReferences":[
              {"alias":"back","purpose":"include","reference":"order"}
            ]},"content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g","name":"Address","Group":{"elements":[
                {"type":"Group","id":"gg","name":"Loop","Group":{"modelAlias":"back"}}
              ]}}
            ]}}}
            """,
        )
        val report = check(host(), address)

        assertEquals(ConsistencyStatus.Inconclusive, report.status)
        assertTrue(
            report.gaps.any { it.at == "/Order/ShipTo/Loop" && "cycle" in it.reason },
            report.gaps.toString(),
        )
    }

    /**
     * The three mount options this slice does not reproduce each suspend the analysis by name. Reading them
     * as absent would place declarations A12 drops, or place them one level off — a tree that is confidently
     * wrong, which is worse than an incomplete answer. The plain mount is the control.
     */
    @Test
    fun anUnimplementedMountOptionSuspendsInsteadOfGuessing() {
        val address = provider("address", "Address", field("Street"))
        val suspended = mapOf(
            """"modelAlias":"addr","includeLevel":"MODEL_ROOT"""" to "MODEL_ROOT",
            """"modelAlias":"addr","excludeRules":true""" to "excludeRules",
            """"modelAlias":"addr","excludeComputations":true""" to "excludeComputations",
        )

        suspended.forEach { (body, named) ->
            val report = check(host(mountBody = body), address)
            assertEquals(emptySet(), report.checksCompleted, named)
            assertTrue(report.gaps.any { it.at == "/Order/ShipTo" && named in it.reason }, "$named: ${report.gaps}")
        }
        assertTrue(CheckFamily.CONDITION_REFERENCES in check(host(), address).checksCompleted)
    }

    /**
     * A non-Boolean exclusion flag is FALSE to the Kernel and the content survives (KF3's coercion matrix),
     * so it must not suspend an include A12 expands in full — the read-path half of the over-strictness rule.
     */
    @Test
    fun aMalformedExclusionFlagDoesNotSuspendTheMount() {
        val address = provider("address", "Address", field("Street"))
        val report = check(host(mountBody = """"modelAlias":"addr","excludeRules":"yes""""), address)

        assertTrue(CheckFamily.CONDITION_REFERENCES in report.checksCompleted, report.gaps.toString())
    }

    /** SINGLE_RG needs exactly one root group in the provider; the Kernel refuses zero or several. */
    @Test
    fun aProviderWithoutExactlyOneRootGroupSuspendsTheMount() {
        val two = source(
            """
            {"header":{"id":"address"},"content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"a","name":"Address","Group":{"elements":[${field("Street")}]}},
              {"type":"Group","id":"b","name":"Other","Group":{"elements":[${field("City")}]}}
            ]}}}
            """,
        )
        val report = check(host(), two)

        assertTrue(report.gaps.any { it.at == "/Order/ShipTo" && "exactly one root group" in it.reason })
    }

    /**
     * A mount group that also carries local elements is ALREADY EXPANDED to the Kernel: the local elements
     * survive and the reference is silently ignored (KF3). So it needs no provider at all, which is the row
     * a checker that keyed only on the alias would get wrong.
     */
    @Test
    fun aMountGroupWithLocalElementsIsAlreadyExpanded() {
        val entry = host(
            mountBody = """"modelAlias":"addr","elements":[${field("Street")}]""",
            extra = rule("R", "../ShipTo/Street", "FieldFilled(ShipTo/Street)"),
        )
        val report = check(entry)

        assertEquals(emptyList(), report.findings)
        assertEquals(emptyList(), report.gaps.filter { it.at == "/Order/ShipTo" })
    }

    /**
     * The short-name fallback has two owners, and one collision separates them.
     *
     * A provider whose OWN `fieldRefByShortNameAllowed` is on has its bare names bound before integration —
     * `FieldFilled(Code)` expands to `FieldFilled(/Order/ShipTo/Inner/Code)` regardless of what the host
     * declares or which flag the host carries — so the host's `Code` cannot retarget it. A reference in the
     * ENTRY model is bound over the whole expanded tree instead, so the mounted `Code` makes it ambiguous
     * (`MVK_FIELDNAME_NOT_UNIQUE`, measured on the expanded model, which names both fields). And a provider
     * that leaves its own flag off has nothing bound: the bare name travels and is then resolved like any
     * other, which the host flag here is off for, so it names nothing.
     *
     * The rows point three ways on purpose. Reading the entry model's flag for everything fails the first,
     * scoping every fallback to its author fails the second, and binding regardless of the provider's flag
     * fails the third.
     */
    @Test
    fun theShortNameFallbackHasTwoOwnersAndOneCollisionSeparatesThem() {
        val inner = """{"type":"Group","id":"in","name":"Inner","Group":{"elements":[${field("Code")}]}},"""
        val bound = provider("address", "Address", inner + rule("Bare", "../Inner/Code", "FieldFilled(Code)"), true)
        val unbound = provider("address", "Address", inner + rule("Bare", "../Inner/Code", "FieldFilled(Code)"))
        val hostRuleGroup = """{"type":"Group","id":"g2","name":"Lines","Group":{"elements":[
            ${rule("HostBare", "../../Code", "FieldFilled(Code)")}
        ]}}"""

        assertEquals(
            emptyList(),
            check(host(), bound).findings,
            "the provider's own flag bound its bare name before it moved, whatever the host says",
        )
        assertEquals(
            listOf("MVK_FIELDNAME_NOT_UNIQUE"),
            check(host(extra = hostRuleGroup, flag = true), bound).findings.map { it.code },
            "an entry-model bare name is bound over the expanded tree and sees the mounted field too",
        )
        assertEquals(
            listOf("MVK_INVALID_ENTITY"),
            check(host(), unbound).findings.map { it.code },
            "with neither flag on there is no fallback to reach the provider's own field",
        )
    }

    /** An id supplied twice is a diagnostic and the first source wins, so a report never depends on order. */
    @Test
    fun aRepeatedSourceIdentityIsReported() {
        val first = provider("address", "Address", field("Street"))
        val second = provider("address", "Address", field("City"))
        val entry = host(extra = rule("R", "../ShipTo/Street", "FieldFilled(ShipTo/Street)"))
        val report = check(entry, first, second)

        assertEquals(emptyList(), report.findings, "the FIRST source is the one that mounted")
        assertEquals(
            listOf(DiagnosticCode.SOURCE_IDENTITY_NOT_UNIQUE),
            report.diagnostics.map { it.code },
        )
    }

    /** An entry id no source declares is a diagnostic, never an exception and never an invented verdict. */
    @Test
    fun anUnknownEntryModelIsADiagnostic() {
        val report = check(provider("address", "Address", field("Street")), entry = "order")

        assertEquals(ConsistencyStatus.Inconclusive, report.status)
        assertEquals(emptyList(), report.findings)
        assertEquals(listOf(DiagnosticCode.SOURCE_NOT_IN_WORKSPACE), report.diagnostics.map { it.code })
        assertEquals(emptySet(), report.checksCompleted)
    }

    /**
     * A provider that could not be ADMITTED is the cause of the mount the report then calls unexpanded, so
     * both travel together — a consumer shown only "the mount did not expand" cannot act on it.
     */
    @Test
    fun anUnadmittableProviderIsReportedBesideTheMountItBlocked() {
        val report = check(host(), source("{ not json"))

        assertEquals(listOf(DiagnosticCode.SOURCE_NOT_WELL_FORMED), report.diagnostics.map { it.code })
        assertTrue(report.gaps.any { it.at == "/Order/ShipTo" }, report.gaps.toString())
    }
}
