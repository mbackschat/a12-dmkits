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
    /**
     * Every non-Boolean exclusion value is FALSE to the Kernel and the content survives (KF3's coercion
     * matrix, re-measured 2026-09-12 across these four), so none may suspend an include A12 expands in full.
     * The QUOTED `"true"` is the row that matters: it reads as the flag being set and is not, so a check on
     * the text rather than the type refuses a legal model.
     */
    @Test
    fun noMalformedExclusionValueSuspendsTheMount() {
        val address = provider("address", "Address", field("Street"))

        listOf("\"true\"", "\"yes\"", "1", "{}").forEach { value ->
            val report = check(host(mountBody = """"modelAlias":"addr","excludeRules":$value"""), address)
            assertTrue(CheckFamily.CONDITION_REFERENCES in report.checksCompleted, "$value: ${report.gaps}")
        }
        assertEquals(
            emptySet(),
            check(host(mountBody = """"modelAlias":"addr","excludeRules":true"""), address).checksCompleted,
            "the boolean IS the flag, and that one suspends",
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
