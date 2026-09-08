package io.github.mbackschat.a12.dm.buildlogic

/**
 * The decision half of `guardInputs`' exclusive-ownership counterpart. A guard owning runtime inputs the
 * broad `test` task does not declare must run in a DEDICATED task AND be excluded from `test`. Two
 * hand-kept lists of one fact drift, so `test`'s excludes are DERIVED from the owning tasks' include
 * filters and the two cannot disagree.
 *
 * Every rule below refuses rather than picking a winner, because each silent alternative has a failure mode
 * that survives a text-matching guard: an empty derivation excludes nothing while every text guard still
 * passes, and last-wins ownership can name a REGENERATOR as the lane to run (it writes goldens instead of
 * asserting).
 */
object ExclusiveHosting {

    /** `taskName -> the test-class patterns its filter includes`, in declaration order. */
    fun emptyOwnerRefusal(broadPath: String, owners: Map<String, Set<String>>): String? =
        owners.entries.firstOrNull { it.value.isEmpty() }?.let { (name, _) ->
            "'$name' is declared an exclusive owner but its filter includes NO test class, so " +
                "$broadPath would silently re-run what that lane is supposed to own."
        }

    /**
     * Two owners claiming one class is refused rather than resolved: with last-wins the corrective message
     * would name whichever lane was configured last, which is a build-order accident.
     */
    fun ambiguousOwnershipRefusal(owners: Map<String, Set<String>>): String? {
        val seen = LinkedHashMap<String, String>()
        for ((name, patterns) in owners) {
            for (pattern in patterns) {
                val incumbent = seen[pattern]
                if (incumbent != null && incumbent != name) {
                    return "'$pattern' is claimed as exclusively owned by both '$incumbent' and '$name'. " +
                        "Exactly one lane owns a class; classify the other as a non-owner with its reason."
                }
                seen[pattern] = name
            }
        }
        return null
    }

    /** Valid only once [emptyOwnerRefusal] and [ambiguousOwnershipRefusal] are both null. */
    fun hostByClass(owners: Map<String, Set<String>>): Map<String, String> =
        LinkedHashMap<String, String>().apply {
            owners.forEach { (name, patterns) -> patterns.forEach { put(it, name) } }
        }

    /**
     * Which classes stay OUT of a native-image capture: those owned by a lane classified as build tooling.
     * Its traffic is not the product's reachability — tracing javac once leaked `jdk.compiler` resource
     * bundles into the shipped metadata.
     */
    fun buildToolingClasses(
        hostByClass: Map<String, String>,
        untracedDuringCapture: Set<String>,
    ): Set<String> =
        hostByClass.filterValues { it in untracedDuringCapture }.keys

    fun untracedNonOwnerRefusal(
        broadPath: String,
        untracedDuringCapture: Set<String>,
        owningTaskNames: Collection<String>,
    ): String? =
        untracedDuringCapture.firstOrNull { it !in owningTaskNames }?.let {
            "'$it' is classified untraced-during-capture but owns nothing in $broadPath; " +
                "the two lists must agree."
        }

    /**
     * A Test task filtering classes out of the same compiled output as the broad lane must be classified —
     * an owner, or a reasoned non-owner. Unfiltered lanes own no individual class, and a lane compiled from
     * a separate source set cannot run in the broad lane at all.
     *
     * The last two facts arrive as suppliers, not values: reading a task's filter and resolving its
     * `testClassesDirs` are configuration-time work, and a task exempted by NAME must not pay for either.
     */
    fun requiresClassification(
        taskName: String,
        broadTaskName: String,
        owningTaskNames: Collection<String>,
        classifiedNonOwners: Collection<String>,
        hasIncludePatterns: () -> Boolean,
        sharesCompiledOutput: () -> Boolean,
    ): Boolean =
        taskName != broadTaskName &&
            taskName !in owningTaskNames &&
            taskName !in classifiedNonOwners &&
            hasIncludePatterns() &&
            sharesCompiledOutput()

    fun unclassifiedLaneRefusal(broadPath: String, taskName: String): String =
        "Test task '$taskName' filters classes out of the same compiled output as $broadPath but is " +
            "neither an exclusive owner nor a classified non-owner. Add it to the owners list, or classify " +
            "it with a reason (a regenerator over already-owned classes, or an additive lane the broad " +
            "suite also runs)."

    /**
     * A capture re-hosts an owned lane's classes in the traced lane, so that lane must SUPPLY the owner's
     * configuration-time system properties. Omitting them made 13 packaged-launcher cases exec the module
     * directory instead of the executable.
     *
     * A key may be omitted only when classified: it controls JUnit scheduling rather than a test or product
     * input, so copying it would alter scheduling without adding a reachable product shape.
     */
    fun invalidClassificationRefusal(
        ownerName: String,
        declaredProperties: Set<String>,
        classified: Map<String, String>,
    ): String? =
        if (classified.any { (key, reason) -> key !in declaredProperties || reason.isBlank() }) {
            invalidClassificationMessage(ownerName)
        } else {
            null
        }

    /**
     * The classification is read out of a task's `extraProperties`, which is untyped — anything at all can be
     * put there. An absent one is the normal case and means "nothing is classified"; a present one that is
     * not a map of rows is a mistake and must read as one rather than as an empty classification, which
     * would silently exempt nothing while looking like it exempted everything the author intended.
     */
    fun classificationRows(raw: Any?): Map<String, String>? = when (raw) {
        null -> emptyMap()
        is Map<*, *> -> raw.entries.associate { (key, value) -> key.toString() to (value as? String ?: "") }
        else -> null
    }

    fun invalidClassificationMessage(ownerName: String): String =
        "'$ownerName' has an invalid nativeCaptureNonRuntimeSystemProperties classification; " +
            "every row must name one declared system property and give a non-blank reason."


    fun unsuppliedPropertyRefusal(
        broadPath: String,
        ownerName: String,
        ownerProperties: Set<String>,
        broadProperties: Set<String>,
        classified: Set<String>,
    ): String? {
        val unsupplied = (ownerProperties - broadProperties - classified).sorted()
        return if (unsupplied.isEmpty()) {
            null
        } else {
            "-PrkNativeAgent makes $broadPath run '$ownerName''s classes, but $unsupplied is declared on " +
                "'$ownerName' and not here. Supply it on the traced lane; do NOT exclude the classes unless " +
                "they are build tooling — a lane can drive the product in-process as well, and dropping it " +
                "shrinks the captured metadata silently."
        }
    }
}

/**
 * The `--tests` rerouting half: which owned classes a caller's pattern selects, and what to tell them.
 *
 * Ownership creates a trap — `--tests '*AnOwnedClass'` on the broad lane fails as Gradle's bare "No tests
 * found for given includes", which names neither the exclusion nor the task that DOES host the class. Same
 * self-explaining-surface rule the CLI follows: emit the command to run instead.
 */
object TestPatternRouting {

    /**
     * Gradle's `--tests` accepts a trailing `.method`, so a pattern is probed with that segment dropped too;
     * `*` is the only wildcard and everything else is literal.
     */
    fun matches(pattern: String, fqcn: String): Boolean {
        fun hit(p: String) = Regex(p.split('*').joinToString(".*") { Regex.escape(it) }).matches(fqcn)
        return hit(pattern) ||
            (pattern.contains('.') && hit(pattern.substring(0, pattern.lastIndexOf('.'))))
    }

    fun misrouted(hostByClass: Map<String, String>, patterns: List<String>): Map<String, String> =
        if (patterns.isEmpty()) {
            emptyMap()
        } else {
            hostByClass.filterKeys { fqcn -> patterns.any { matches(it, fqcn) } }
        }

    /** A compiled class file under a class root, as a fully-qualified name; nested classes are skipped. */
    fun fullyQualifiedName(root: java.io.File, classFile: java.io.File): String? {
        if (!classFile.name.endsWith(".class") || classFile.name.contains('$')) {
            return null
        }
        val relative = root.toPath().relativize(classFile.toPath()).toString()
            .replace(java.io.File.separatorChar, '.')
        return relative.removeSuffix(".class")
    }

    /**
     * The corrective. Echoes the patterns the caller actually typed, so a `.method` selector survives, and
     * names no `-Pfast`, which is only implemented for the adapter lanes.
     */
    fun misroutedRefusal(
        taskPathPrefix: String,
        patterns: List<String>,
        misrouted: Map<String, String>,
    ): String =
        "--tests $patterns matches only classes ${taskPathPrefix}test deliberately excludes, because a " +
            "dedicated task owns their runtime inputs. Run that task instead:\n" +
            misrouted.entries.joinToString("\n") { (fqcn, owner) ->
                "  $fqcn\n      ./build.sh $taskPathPrefix$owner " +
                    patterns.filter { matches(it, fqcn) }.joinToString(" ") { "--tests '$it'" }
            }

    /**
     * The EFFECT assertion, not a restatement of the wiring: if an owned class actually executes in the
     * broad lane — by any mutation of the derivation, however written — the lane fails naming the leak.
     * That is what the text-matching guards could not see, and what let five classes double-run unnoticed.
     */
    fun leakRefusal(broadPath: String, leaked: Collection<String>): String? =
        if (leaked.isEmpty()) {
            null
        } else {
            "$broadPath executed ${leaked.size} class(es) a dedicated lane owns exclusively: " +
                "${leaked.sorted()}. Their runtime inputs are declared on the owning task, not here, so " +
                "running them in this lane can go up-to-date into a false green."
        }
}
