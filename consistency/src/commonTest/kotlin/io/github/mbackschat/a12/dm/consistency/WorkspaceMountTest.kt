package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B/B3: a model that MOUNTS another model is checked against the source set that carries it.
 *
 * Mounting is not a convenience here, it decides verdicts. Every reference into an included subtree reads as
 * undeclared while the provider is absent, so the checker had to suspend the whole analysis; with the
 * provider supplied it can answer, and the answer depends on getting the placement exactly right. The facts
 * it rests on are the Kernel's, source-verified in [KERNEL-FINDINGS KF3]: the placement depends on the
 * include LEVEL — `SINGLE_RG` replaces the mount group with the provider's sole root's children while
 * `MODEL_ROOT` mounts every root as a subgroup keeping its name — a provider is fully expanded before it is
 * mounted, and a reference that named a root the mount moved is rewritten rather than left to fail.
 */
class WorkspaceMountTest {

    private fun source(json: String) = ModelSource(json.trimIndent())

    /** A provider whose root group carries [elements]; its own root name must never survive into a path. */
    private fun provider(id: String, root: String, elements: String, flag: Boolean = false) = source(
        """
        {"header":{"id":"$id","locales":[{"code":"en_US"}]},"content":{
          "modelConfig":{"fieldRefByShortNameAllowed":$flag},
          "modelRoot":{"rootGroups":[
            {"type":"Group","id":"g","name":"$root","Group":{"elements":[$elements]}}
          ]}}}
        """,
    )

    private fun field(name: String) = """{"type":"Field","id":"f-$name","name":"$name","Field":{"fieldType":{"type":"StringType"}}}"""

    /**
     * A rule carrying an `en_US` text only — which is what makes it findings-free under [host] and NOT under
     * [bilingualHost], whose entry model also declares `de_DE`.
     *
     * The coupling is named rather than left to be discovered: the declaration-slot family judges every
     * message against the ENTRY model's locale set, so a row that swaps one host for the other silently
     * gains an `MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING` it was not written to expect.
     */
    private fun rule(name: String, errorPath: String, condition: String) =
        """{"type":"Rule","id":"r-$name","name":"$name","Rule":{"errorCode":"C",
           "errorMessage":[{"locale":"en_US","text":"x"}],
           "errorEntityRelPath":"$errorPath","errorCondition":"$condition"}}"""

    /**
     * A host whose root group `Order` holds a field `Code`, [extra], and a mount group `ShipTo` carrying
     * [mountBody] — the one member each row varies.
     */
    private fun host(mountBody: String = """"modelAlias":"addr"""", extra: String = "", flag: Boolean = false) =
        source(
            """
            {"header":{"id":"order","locales":[{"code":"en_US"}],"modelReferences":[
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
        // The target's code alone: both members fail here, and A12 stops a rule at its error target rather
        // than reporting each member. That law is measured on an UNMOUNTED carrier
        // (`RuleMemberVerdictWithholdingLawsTest`) and assumed to hold across the mount, which nothing here
        // measures — an assumption worth naming, and a safe one in direction, since it only drops a finding.
        // What this row locks is that the nested path does not bind at all, which the single code says.
        assertEquals(
            listOf("MVK_ERROR_FIELD_MISSING"),
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

    /**
     * A provider with two root groups, both carrying one field — the shape only a `MODEL_ROOT` mount admits,
     * since `SINGLE_RG` refuses a provider that does not declare exactly one root.
     */
    private fun twoRootProvider(rules: String = "") = source(
        """
        {"header":{"id":"address","locales":[{"code":"en_US"}]},"content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g","name":"Address","Group":{"elements":[${field("Street")}$rules]}},
          {"type":"Group","id":"g2","name":"Contact","Group":{"elements":[${field("Email")}]}}
        ]}}}
        """,
    )

    private val modelRoot = """"modelAlias":"addr","includeLevel":"MODEL_ROOT""""

    /**
     * The `MODEL_ROOT` placement is the OPPOSITE of `SINGLE_RG`'s: every top-level group of the provider is
     * mounted as a subgroup of the mount and KEEPS its own name (KF3), so nothing disappears and both roots
     * are addressable. Its Kernel basis is the `model-root-multiple-groups` and `reference-family-model-root`
     * cases of the shared matrix, whose expected placement [WorkspaceMatrixReconciliationTest] holds.
     *
     * The second model is the control, and it is the mistake worth catching: flattening the roots onto the
     * mount — reusing the `SINGLE_RG` placement — resolves `ShipTo/Street` and would satisfy a row that only
     * asked whether the provider's field arrived somewhere.
     */
    @Test
    fun aModelRootIncludeKeepsEveryProviderRootGroupUnderTheMount() {
        val reaches = host(
            mountBody = modelRoot,
            extra = rule(
                "R",
                "../ShipTo/Address/Street",
                "FieldFilled(ShipTo/Address/Street) And FieldFilled(ShipTo/Contact/Email)",
            ),
        )
        val flattened = host(mountBody = modelRoot, extra = rule("R", "../ShipTo/Street", "FieldFilled(ShipTo/Street)"))

        val report = check(reaches, twoRootProvider())

        // A suspended mount silences every family, so silence alone would pass this row before the mount was
        // implemented at all — the completed family is what makes the empty finding list a measurement.
        assertTrue(CheckFamily.CONDITION_REFERENCES in report.checksCompleted, report.gaps.toString())
        assertEquals(emptyList(), report.findings)
        // The error target's code alone, for the reason the SINGLE_RG row above states: A12 stops a rule at
        // its unresolvable error target rather than reporting its condition as well.
        assertEquals(
            listOf("MVK_ERROR_FIELD_MISSING"),
            check(flattened, twoRootProvider()).findings.map { it.code },
        )
    }

    /**
     * An absolute reference inside a `MODEL_ROOT` provider moves with the mount and KEEPS its root segment,
     * and that holds across roots: a rule in one root naming a field in its sibling is rehosted by the prefix
     * of the root it NAMES, not by the one it sits in. A single mount-wide substitution passes every
     * same-root row and gets this one wrong, which is why the reference points sideways.
     *
     * The third model is the boundary in the other direction, and the row that separates per-root
     * substitution from prefixing every absolute path: `/Order/Code` names no root of the provider, so no
     * target of it moved and the Kernel leaves it as written (KF3) — after mounting it binds to the HOST's
     * own field. Prefixing it instead would report `MVK_INVALID_ENTITY` for a reference A12 resolves, which
     * is the one direction this checker may not take. That the Kernel rewrites by moved target is KF3's
     * measured mechanism on the `SINGLE_RG` carrier; if it turned out to prefix more widely here, this
     * checker would stay silent where A12 speaks, which is the safe half.
     */
    @Test
    fun aModelRootProvidersAbsoluteReferencesAreRehostedPerRoot() {
        // Every condition names the rule's own error field too, because A12 refuses a rule whose condition
        // does not (`MVK_ERROR_FIELD_NOT_REFERENCED`) — so the reference under test is the second conjunct.
        fun provider(reference: String) =
            twoRootProvider(rules = "," + rule("Filled", "../Street", "FieldFilled(Street) And FieldFilled($reference)"))
        val ownRoot = provider("/Address/Street")
        val sibling = provider("/Contact/Email")
        val hostShaped = provider("/Order/Code")
        val entry = host(mountBody = modelRoot)

        listOf(ownRoot, sibling, hostShaped).forEach { source ->
            val report = check(entry, source)
            // Non-vacuity again: an unexpanded mount reports nothing about anything.
            assertTrue(CheckFamily.CONDITION_REFERENCES in report.checksCompleted, report.gaps.toString())
            assertEquals(emptyList(), report.findings)
        }
    }

    /** A provider is fully expanded before it is mounted, so a nested mount's children arrive already placed. */
    @Test
    fun aProviderIsExpandedBeforeItIsMounted() {
        val geo = provider("geo", "Geo", field("Zip"))
        val address = source(
            """
            {"header":{"id":"address","locales":[{"code":"en_US"}],"modelReferences":[
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
            {"header":{"id":"order","locales":[{"code":"en_US"}],"modelReferences":[
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
            {"header":{"id":"address","locales":[{"code":"en_US"}],"modelReferences":[
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
     * A computation whose computed field does not resolve — the same one-defect instrument as [broken], and
     * a heavier one: A12 does not BUILD such a model, so retaining it costs the whole report its verdict
     * while excluding it leaves a model A12 checks in full.
     *
     * It carries a message because the slot family judges a computation's `errorMessage` exactly as a rule's,
     * so omitting it would add two unrelated codes to every row.
     */
    private fun brokenComputation() =
        """{"type":"Computation","id":"c0","name":"Total","Computation":{
           "errorMessage":[{"locale":"en_US","text":"x"}],
           "computedFieldRelPath":"../Missing","computationAlternatives":[{"operation":"42.00"}]}}"""

    /** A rule whose error target does not resolve, so its presence in the fact base is one finding. */
    private fun broken() = rule("Broken", "../Missing", "FieldFilled(Street)")

    /**
     * An excluded executable is not a silent element, it is ABSENT: A12 drops the provider's rules before it
     * checks the expanded model (KF3), so nothing about that rule is A12's verdict and reporting its defect
     * would be this checker speaking alone. The instrument is a provider rule that IS defective — an
     * unresolvable error target — so retention and exclusion differ by one finding rather than by nothing.
     *
     * The unflagged mount is the control that the silence is the flag's work and not a mount that failed to
     * expand, and the completed family says the same thing from the other side.
     */
    @Test
    fun anExcludedExecutablesDefectIsNotReportedBecauseA12NeverChecksIt() {
        val ruleProvider = provider("address", "Address", field("Street") + "," + broken())
        val computationProvider = provider("address", "Address", field("Street") + "," + brokenComputation())
        val excluded = mapOf(
            """"modelAlias":"addr","excludeRules":true""" to ruleProvider,
            """"modelAlias":"addr","excludeComputations":true""" to computationProvider,
        )

        excluded.forEach { (mountBody, source) ->
            val report = check(host(mountBody = mountBody), source)
            assertTrue(CheckFamily.CONDITION_REFERENCES in report.checksCompleted, report.gaps.toString())
            assertEquals(emptyList(), report.findings, mountBody)
        }
        assertEquals(
            listOf("MVK_ERROR_FIELD_MISSING"),
            check(host(), ruleProvider).findings.map { it.code },
            "without the flag the same provider's rule is checked",
        )
        assertEquals(
            listOf("CK_MODEL_NOT_BUILDABLE"),
            check(host(), computationProvider).findings.map { it.code },
            "and without the flag its unresolvable computed field costs the model its verdict",
        )
    }

    /**
     * An excluded rule is dropped from the fact base, but a declaration A12's own READER cannot bind is still
     * diagnosed: the reader binds every declaration before expansion drops any of them, so an unreadable
     * envelope makes the source unreadable whether this mount keeps the rule or not. It is the same argument
     * the missing-`id` diagnostic rests on, and its twin was silently lost until 2026-09-15.
     */
    @Test
    fun anExcludedDeclarationStillDiagnosesWhatA12sReaderCannotBind() {
        val unbindable = provider(
            "address",
            "Address",
            field("Street") + ",{\"type\":\"Rule\",\"id\":\"r\",\"name\":\"R\",\"Rule\":5}",
        )

        listOf(""""modelAlias":"addr"""", """"modelAlias":"addr","excludeRules":true""").forEach { mountBody ->
            val report = check(host(mountBody = mountBody), unbindable)

            assertEquals(
                listOf(DiagnosticCode.DECLARATION_NOT_WELL_FORMED),
                report.diagnostics.map { it.code },
                mountBody,
            )
        }
    }

    /**
     * An `attachment`-usage group shields its subtree from an exclusion applied from above it, and the shield
     * is read from the FINAL expanded `usageType` (KF3). Under `SINGLE_RG` that is the provider root's own
     * slot, which REPLACES the mount's — so the second row is the one that separates the two readings: an
     * `attachment` marker authored on the MOUNT group is cleared by a provider root that declares none, and
     * supplies no shield. Both are locked against the Kernel for the placement half by the shared matrix's
     * two attachment cases; what is measured here is which declaration the shield is read from.
     */
    @Test
    fun anAttachmentProviderRootShieldsItsSubtreeAndAMountsOwnMarkerDoesNot() {
        val shielded = source(
            """
            {"header":{"id":"address","locales":[{"code":"en_US"}]},"content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g","name":"Address","Group":{"usageType":"attachment","elements":[
                ${field("Street")},${broken()}
              ]}}
            ]}}}
            """,
        )
        val plain = provider("address", "Address", field("Street") + "," + broken())
        val markedMount = """"modelAlias":"addr","excludeRules":true,"usageType":"attachment""""

        assertEquals(
            listOf("MVK_ERROR_FIELD_MISSING"),
            check(host(mountBody = """"modelAlias":"addr","excludeRules":true"""), shielded).findings.map { it.code },
            "the provider root is an attachment, so its rules survive the outer exclusion",
        )
        assertEquals(
            emptyList(),
            check(host(mountBody = markedMount), plain).findings,
            "the mount's own marker is replaced by the provider root's absent slot, so nothing is shielded",
        )
    }

    /**
     * The exclusion members are INCLUDE directives: on an ordinary group A12 accepts them inertly and the
     * content survives (KF3), so a checker that read them wherever they appear would drop declarations A12
     * checks — an under-report that hides real defects.
     */
    @Test
    fun anOrdinaryGroupsExclusionFlagsAreInert() {
        val address = provider("address", "Address", field("Street"))
        val entry = host(
            extra = """{"type":"Group","id":"g2","name":"Notes","Group":{"excludeRules":true,"elements":[
                ${field("Text")},${rule("Broken", "../Missing", "FieldFilled(Text)")}
            ]}}""",
        )

        assertEquals(listOf("MVK_ERROR_FIELD_MISSING"), check(entry, address).findings.map { it.code })
    }

    /**
     * Every non-Boolean exclusion value is FALSE to the Kernel and the content survives (KF3's coercion
     * matrix, re-measured 2026-09-12 across these four), so none may drop a declaration A12 checks — the
     * read-path half of the over-strictness rule. The
     * QUOTED `"true"` is the row that matters: it reads as the flag being set and is not, so a check on the
     * text rather than the type silences findings about a rule A12 judges.
     */
    @Test
    fun noMalformedExclusionValueDropsAnIncludedRule() {
        val address = provider("address", "Address", field("Street") + "," + broken())

        listOf("\"true\"", "\"yes\"", "1", "{}").forEach { value ->
            val report = check(host(mountBody = """"modelAlias":"addr","excludeRules":$value"""), address)
            assertEquals(listOf("MVK_ERROR_FIELD_MISSING"), report.findings.map { it.code }, value)
        }
        assertEquals(
            emptyList(),
            check(host(mountBody = """"modelAlias":"addr","excludeRules":true"""), address).findings,
            "the boolean IS the flag, and that one drops the rule",
        )
    }

    /**
     * `includeLevel` is a strict two-valued enum, so any other spelling makes the model unreadable to A12
     * ("No enum constant …IncludeLevel.model_root", measured). Expanding it as the default would produce a
     * verdict for a source the Kernel refuses to load — the explicit `SINGLE_RG` row is the control that this
     * is about the spelling rather than about the member being present.
     */
    @Test
    fun anIncludeLevelSpellingA12CannotReadSuspendsTheMount() {
        val address = provider("address", "Address", field("Street"))
        val report = check(host(mountBody = """"modelAlias":"addr","includeLevel":"model_root""""), address)

        assertEquals(emptySet(), report.checksCompleted)
        assertTrue(report.gaps.any { it.at == "/Order/ShipTo" && "refuses" in it.reason }, report.gaps.toString())
        assertTrue(
            CheckFamily.CONDITION_REFERENCES in
                check(host(mountBody = """"modelAlias":"addr","includeLevel":"SINGLE_RG""""), address)
                    .checksCompleted,
        )
    }

    /**
     * A provider that does not declare every locale of the ENTRY model cannot be mounted at all.
     *
     * A12 refuses the expansion outright — `DocumentModelExpansionException`, before any declaration is read
     * — so a checker that mounted it would analyse a tree the Kernel never builds, and every finding about it
     * would be the checker's own reading. Measured 2026-09-12 by `IncludedDeclarationLocaleScopeLawsTest`,
     * which also carries the row that bounds it: a provider declaring MORE than the entry mounts normally,
     * which is why this suspends on coverage rather than on equality.
     */
    @Test
    fun aProviderMissingAnEntryLocaleSuspendsTheMount() {
        val narrow = source(
            """
            {"header":{"id":"address","locales":[{"code":"en_US"}]},"content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g","name":"Address","Group":{"elements":[${field("Street")}]}}
            ]}}}
            """,
        )
        val report = check(bilingualHost(), narrow)

        assertEquals(emptySet(), report.checksCompleted)
        assertTrue(report.gaps.any { it.at == "/Order/ShipTo" && "de_DE" in it.reason }, report.gaps.toString())
    }

    /** The control for the row above: the same provider declaring both locales mounts and is checked. */
    @Test
    fun aProviderCoveringEveryEntryLocaleMountsNormally() {
        val wide = source(
            """
            {"header":{"id":"address","locales":[{"code":"en_US"},{"code":"de_DE"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g","name":"Address","Group":{"elements":[${field("Street")}]}}
            ]}}}
            """,
        )

        assertTrue(CheckFamily.CONDITION_REFERENCES in check(bilingualHost(), wide).checksCompleted)
    }

    /**
     * A MOUNTED declaration's message slots answer to the ENTRY model's locale set, not to the provider's.
     *
     * This is the carrier the family's own tests cannot reach, and it is the one where getting it wrong is
     * invisible: a checker that judged each declaration against the model that WROTE it would report nothing
     * here, because the provider's own rule covers the provider's own single locale. A12 expands first and
     * checks afterwards, so the entry's `de_DE` is what the mounted rule must carry — and the provider is
     * admitted through the mount precisely because it declares a SUPERSET of a narrower entry.
     */
    @Test
    fun aMountedDeclarationsMessagesAreJudgedAgainstTheEntrysLocales() {
        val provider = source(
            """
            {"header":{"id":"address","locales":[{"code":"en_US"},{"code":"de_DE"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g","name":"Address","Group":{"elements":[
                ${field("Street")},
                {"type":"Rule","id":"r","name":"StreetFilled","Rule":{"errorCode":"C",
                 "errorMessage":[{"locale":"en_US","text":"x"}],
                 "errorEntityRelPath":"../Street","errorCondition":"FieldFilled(Street)"}}
              ]}}
            ]}}}
            """,
        )
        val bilingual = check(bilingualHost(), provider).findings
        val monolingual = check(host(), provider).findings

        assertEquals(
            listOf("MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING"),
            bilingual.map { it.code },
            "the entry declares de_DE, so the mounted rule owes a German text: $bilingual",
        )
        assertTrue(bilingual.all { it.at == "/Order/ShipTo/StreetFilled" }, bilingual.toString())
        assertEquals(
            emptyList(),
            monolingual.map { it.code },
            "the same provider under an en_US-only entry owes nothing more: $monolingual",
        )
    }

    /** The host of the rows above, declaring two locales where [host] declares one. */
    private fun bilingualHost() = source(
        """
        {"header":{"id":"order","locales":[{"code":"en_US"},{"code":"de_DE"}],"modelReferences":[
          {"alias":"addr","purpose":"include","reference":"address"}
        ]},"content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
            {"type":"Group","id":"g1","name":"ShipTo","Group":{"modelAlias":"addr"}}
          ]}}
        ]}}}
        """,
    )

    /** SINGLE_RG needs exactly one root group in the provider; the Kernel refuses zero or several. */
    @Test
    fun aProviderWithoutExactlyOneRootGroupSuspendsTheMount() {
        val two = source(
            """
            {"header":{"id":"address","locales":[{"code":"en_US"}]},"content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"a","name":"Address","Group":{"elements":[${field("Street")}]}},
              {"type":"Group","id":"b","name":"Other","Group":{"elements":[${field("City")}]}}
            ]}}}
            """,
        )
        val report = check(host(), two)

        assertTrue(report.gaps.any { it.at == "/Order/ShipTo" && "exactly one root group" in it.reason })
    }

    /** A provider whose sole root group is [root] verbatim, so a row can vary the root itself. */
    private fun providerWithRoot(root: String) = source(
        """{"header":{"id":"address","locales":[{"code":"en_US"}]},
           "content":{"modelRoot":{"rootGroups":[$root]}}}""",
    )

    private fun addressRoot(extra: String = "") =
        """{"type":"Group","id":"g","name":"Address","Group":{$extra"elements":[${field("Street")}]}}"""

    /**
     * A provider root A12 refuses to mount is a coverage gap, not a tree to judge.
     *
     * `SINGLE_RG` needs exactly one root group with its object body, and a NON-REPEATABLE one; A12's own
     * expansion refuses the shapes below ([KERNEL-FINDINGS KF3],
     * `adapter.WorkspaceIncludeStructureKernelTest`) — so mounting one anyway would make every finding under
     * it a verdict about a model the Kernel never builds, which is the failure this module is arranged to
     * avoid. Each row asserts its REASON, because two refusals live here and a gap naming the wrong one sends
     * its reader to the wrong question.
     *
     * The `repeatability` rows are two measured matrices rather than one derived rule, and the STRING arm is
     * the half that cost a defect: a NUMBER truncates toward zero (`1.9` in, `2.1` out) while a STRING binds
     * as an integer, so `"1.5"` is a reader refusal where the number `1.5` is admitted (measured 2026-09-15,
     * after this checker had generalized the number rule and mounted providers A12 refuses).
     */
    @Test
    fun aProviderRootA12RefusesToMountSuspendsTheAnalysis() {
        val unreadable = "reader refuses"
        val repeatable = "is repeatable"
        val refused = mapOf(
            addressRoot(""""repeatability":2,""") to repeatable,
            addressRoot(""""repeatability":2.1,""") to repeatable,
            addressRoot(""""repeatability":"2",""") to repeatable,
            addressRoot(""""repeatability":true,""") to unreadable,
            addressRoot(""""repeatability":"one",""") to unreadable,
            addressRoot(""""repeatability":"1.5",""") to unreadable,
            addressRoot(""""repeatability":"NaN",""") to unreadable,
            addressRoot(""""repeatability":{},""") to unreadable,
            """{"type":"Field","id":"g","name":"Address","Field":{}}""" to "rather than a Group",
            """{"type":"Group","id":"g","name":"Address"}""" to "no `Group` body",
        )
        val mounted = listOf(
            addressRoot(),
            addressRoot(""""repeatability":1,"""),
            addressRoot(""""repeatability":1.9,"""),
            addressRoot(""""repeatability":-1,"""),
            addressRoot(""""repeatability":null,"""),
            addressRoot(""""repeatability":"1","""),
            addressRoot(""""repeatability":" 1 ","""),
            addressRoot(""""repeatability":"01","""),
            addressRoot(""""repeatability":"","""),
        )

        refused.forEach { (root, reason) ->
            val report = check(host(), providerWithRoot(root))
            assertEquals(emptySet(), report.checksCompleted, root)
            assertTrue(
                report.gaps.any { it.at == "/Order/ShipTo" && reason in it.reason },
                "$root: ${report.gaps}",
            )
        }
        mounted.forEach { root ->
            val report = check(host(extra = rule("R", "../ShipTo/Street", "FieldFilled(ShipTo/Street)")),
                providerWithRoot(root))
            assertTrue(CheckFamily.CONDITION_REFERENCES in report.checksCompleted, "$root: ${report.gaps}")
            assertEquals(emptyList(), report.findings, root)
        }
    }

    /**
     * A root element that is not a JSON object cannot be filtered out and mounted around: A12's expansion
     * refuses such a provider (KF3's malformed-input matrix), and dropping the entry instead would let a
     * MODEL_ROOT mount place the remaining roots — or a SINGLE_RG mount satisfy its single-root count — from
     * a source the Kernel never reads.
     */
    @Test
    fun aNonObjectRootElementSuspendsTheMountAtEitherLevel() {
        val provider = source(
            """
            {"header":{"id":"address","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[${addressRoot()},5]}}}
            """,
        )

        listOf(""""modelAlias":"addr"""", modelRoot).forEach { mountBody ->
            val report = check(host(mountBody = mountBody), provider)

            assertEquals(emptySet(), report.checksCompleted, mountBody)
            assertTrue(
                report.gaps.any { it.at == "/Order/ShipTo" && "not a JSON object" in it.reason },
                "$mountBody: ${report.gaps}",
            )
        }
    }

    /**
     * A MODEL_ROOT include of a provider with no root group is refused by A12 (measured 2026-09-15,
     * `adapter.WorkspaceIncludeStructureKernelTest`), and mounting nothing silently is the reading this
     * module refuses in any case: no facts and no gap saying so.
     */
    @Test
    fun aModelRootIncludeOfARootlessProviderSuspendsTheMount() {
        val provider = source(
            """{"header":{"id":"address","locales":[{"code":"en_US"}]},
                "content":{"modelRoot":{"rootGroups":[]}}}""",
        )
        val report = check(host(mountBody = modelRoot), provider)

        assertEquals(emptySet(), report.checksCompleted)
        assertTrue(report.gaps.any { it.at == "/Order/ShipTo" && "no root group" in it.reason }, report.gaps.toString())
    }

    /**
     * A MODEL_ROOT root the checker cannot place suspends the WHOLE mount rather than mounting its siblings,
     * which is the same law the single-root arm applies and the same reason: a tree with one root missing is
     * confidently wrong where an incomplete answer is merely incomplete. Whether A12 admits a nameless root
     * is not measured; suspending under-reports if it does.
     */
    @Test
    fun aNamelessModelRootRootSuspendsTheWholeMount() {
        val provider = source(
            """
            {"header":{"id":"address","locales":[{"code":"en_US"}]},"content":{"modelRoot":{"rootGroups":[
              ${addressRoot()},
              {"type":"Group","id":"g2","Group":{"elements":[${field("Email")}]}}
            ]}}}
            """,
        )
        val report = check(host(mountBody = modelRoot), provider)

        assertEquals(emptySet(), report.checksCompleted)
        assertTrue(report.gaps.any { it.at == "/Order/ShipTo" && "no `name`" in it.reason }, report.gaps.toString())
    }

    /**
     * The same refusal at the other include level, where it is a deliberate UNDER-report: KF3 measures the
     * non-Group root refusal on a `SINGLE_RG` mount, and a `MODEL_ROOT` one reaches the same deserializer, so
     * suspending here costs coverage if A12 turns out to admit it and invents nothing if it does not.
     */
    @Test
    fun aModelRootProvidersNonGroupRootSuspendsTheMountToo() {
        val report = check(
            host(mountBody = modelRoot),
            providerWithRoot("""{"type":"Field","id":"g","name":"Address","Field":{}}"""),
        )

        assertEquals(emptySet(), report.checksCompleted)
        assertTrue(report.gaps.any { it.at == "/Order/ShipTo" }, report.gaps.toString())
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

    /**
     * The provider-bound fallback's denominator is this MOUNT INSTANCE's subtree, and two shapes prove it
     * from opposite sides — a denominator keyed on the authoring model gets both wrong, in the direction that
     * rejects a model A12 accepts. Measured on both through `ModelLoader.loadAndCheck`.
     *
     * A provider is fully expanded before it is bound, so a field its own nested include contributed is
     * inside its index; and one provider mounted twice is two indexes, so the second copy must not make the
     * first ambiguous. The late-binding row is the control: with the provider's flag off the two copies DO
     * collide, which is what the Kernel reports too.
     */
    @Test
    fun theProviderBoundFallbackSeesItsMountInstanceAndNoOther() {
        val geo = provider("geo", "Geo", field("Zip"))
        val nesting = source(
            """
            {"header":{"id":"address","locales":[{"code":"en_US"}],"modelReferences":[
              {"alias":"g","purpose":"include","reference":"geo"}
            ]},"content":{
              "modelConfig":{"fieldRefByShortNameAllowed":true},
              "modelRoot":{"rootGroups":[
                {"type":"Group","id":"g","name":"Address","Group":{"elements":[
                  {"type":"Group","id":"gg","name":"Where","Group":{"modelAlias":"g"}},
                  ${rule("Own", "../Where/Zip", "FieldFilled(Zip)")}
                ]}}
              ]}}}
            """,
        )

        assertEquals(
            emptyList(),
            check(host(), nesting, geo).findings,
            "the provider was expanded before it was bound, so its nested include's field is in its index",
        )

        val twice = source(
            """
            {"header":{"id":"order","locales":[{"code":"en_US"}],"modelReferences":[
              {"alias":"addr","purpose":"include","reference":"address"}
            ]},"content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Group","id":"g1","name":"ShipTo","Group":{"modelAlias":"addr"}},
                {"type":"Group","id":"g2","name":"BillTo","Group":{"modelAlias":"addr"}}
              ]}}
            ]}}}
            """,
        )
        val inner = """{"type":"Group","id":"in","name":"Inner","Group":{"elements":[${field("Code")}]}},"""
        val bound = provider("address", "Address", inner + rule("Bare", "../Inner/Code", "FieldFilled(Code)"), true)
        val unbound = provider("address", "Address", inner + rule("Bare", "../Inner/Code", "FieldFilled(Code)"))

        assertEquals(
            emptyList(),
            check(twice, bound).findings,
            "each mount is its own index, so the second copy must not make the first ambiguous",
        )
        assertEquals(
            listOf("MVK_INVALID_ENTITY", "MVK_INVALID_ENTITY"),
            check(twice, unbound).findings.map { it.code },
            "with nothing bound early and no entry-model flag, neither copy resolves — the control",
        )

        // And the instance is delimited by a SEGMENT, not by a string prefix: `/Order/ShipToBilling` is a
        // different mount that merely starts with `/Order/ShipTo`, and letting it into the first instance's
        // index makes a resolvable provider-bound reference ambiguous — a model A12 accepts, refused.
        val prefixSiblings = source(
            """
            {"header":{"id":"order","locales":[{"code":"en_US"}],"modelReferences":[
              {"alias":"addr","purpose":"include","reference":"address"}
            ]},"content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Group","id":"g1","name":"ShipTo","Group":{"modelAlias":"addr"}},
                {"type":"Group","id":"g2","name":"ShipToBilling","Group":{"modelAlias":"addr"}}
              ]}}
            ]}}}
            """,
        )

        assertEquals(emptyList(), check(prefixSiblings, bound).findings)
    }

    /**
     * Rehosting replaces the provider's own root SEGMENT, never a prefix of a longer name. `/AddressBook` is
     * a different root that merely starts with the same letters, and rewriting it would silently retarget a
     * reference at a path nobody wrote.
     */
    @Test
    fun rehostingStopsAtTheRootGroupsOwnSegmentBoundary() {
        val address = provider(
            "address",
            "Address",
            field("Street") + "," + rule("Ref", "../Street", "FieldFilled(/AddressBook/Thing)"),
        )
        val prepared = prepare(
            admit(host()) as Admission.Admitted,
            mapOf("address" to (admit(address) as Admission.Admitted)),
        )

        assertEquals(
            listOf(Reference.Undeclared("/AddressBook/Thing", rootUnresolved = true)),
            prepared.conditionReferences().map { it.target },
            "a name that merely shares the provider root's opening letters is not the provider root",
        )
    }

    /** A cycle among PROVIDERS alone closes without ever returning to the entry, so it needs its own row. */
    @Test
    fun aCycleThatNeverReachesTheEntryModelIsStillReported() {
        fun mutual(id: String, other: String) = source(
            """
            {"header":{"id":"$id","locales":[{"code":"en_US"}],"modelReferences":[
              {"alias":"x","purpose":"include","reference":"$other"}
            ]},"content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g","name":"${id.uppercase()}","Group":{"elements":[
                {"type":"Group","id":"gg","name":"Next","Group":{"modelAlias":"x"}}
              ]}}
            ]}}}
            """,
        )
        val entry = source(
            """
            {"header":{"id":"order","locales":[{"code":"en_US"}],"modelReferences":[
              {"alias":"addr","purpose":"include","reference":"ma"}
            ]},"content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Group","id":"g1","name":"ShipTo","Group":{"modelAlias":"addr"}}
              ]}}
            ]}}}
            """,
        )
        val report = check(entry, mutual("ma", "mb"), mutual("mb", "ma"))

        assertTrue(
            report.gaps.any { it.at == "/Order/ShipTo/Next/Next" && "cycle" in it.reason },
            report.gaps.toString(),
        )
    }

    /**
     * An EMPTY `elements` array beside an alias is still an unexpanded mount — the Kernel's own idempotency
     * test is "missing or empty", so reading only the missing half would leave the include unresolved and the
     * subtree silently absent.
     */
    @Test
    fun anEmptyElementsArrayBesideAnAliasIsStillAMount() {
        val address = provider("address", "Address", field("Street"))
        val entry = host(
            mountBody = """"modelAlias":"addr","elements":[]""",
            extra = rule("R", "../ShipTo/Street", "FieldFilled(ShipTo/Street)"),
        )

        assertEquals(emptyList(), check(entry, address).findings)
    }

    /**
     * A declaration the checker cannot read is attributed to the SOURCE that wrote it. A workspace reads
     * several files, so naming the entry model would send a reader to one that does not contain the element.
     */
    @Test
    fun aBrokenDeclarationInAProviderNamesTheProvider() {
        val address = ModelSource(
            """
            {"header":{"id":"address","locales":[{"code":"en_US"}]},"content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g","name":"Address","Group":{"elements":[
                {"type":"Field","name":"Street","Field":{"fieldType":{"type":"StringType"}}}
              ]}}
            ]}}}
            """.trimIndent(),
            diagnosticLabel = "shared/address.dm.json",
        )
        val diagnostic = check(host(), address).diagnostics.single()

        assertEquals(DiagnosticCode.DECLARATION_NOT_WELL_FORMED, diagnostic.code)
        assertEquals("address", diagnostic.modelId)
        assertEquals("shared/address.dm.json", diagnostic.diagnosticLabel)
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
