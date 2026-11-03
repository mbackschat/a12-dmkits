package io.github.mbackschat.a12.dm.consistency

/**
 * That a rule's or computation's message for a locale the model does NOT declare names only parameters its
 * declared-locale texts name too, spelled the same way.
 *
 * A12's error-text checker never reads such a text ([examined], [KERNEL-FINDINGS KF197]), and on kernel 30.8.1
 * nothing did, because the expand step dropped undeclared locales before code generation. Kernel 31.1.1 keeps
 * them, and code generation hands every entry of the message map to `FehlertextBuilder`, whose
 * `getParameterMapping` looks each non-empty part between two `$` up among the parameters parsed from the
 * DECLARED locales' texts and throws on a miss — "Error text parameter '…' not valid." — so the model is not
 * built (source-read at `eec9134`, [KERNEL-FINDINGS KF659], `rulekit.validate.laws.ErrorTextLocaleScopeLawsTest`).
 *
 * **The lookup is by SPELLING, not by meaning**, which is the trap: a valid field that no declared text names
 * fails, the same field written as an absolute path where the declared texts write its short name fails, and
 * the identical spelling builds. An empty pair `$$` is skipped, an odd `$` makes the tail a part of its own, and a
 * control character is never asked about at all. The two carriers were measured alike; a field type's own
 * message in an undeclared locale was measured too and still builds, so it is not read here.
 *
 * **It is a [ModelStage.GENERATION] family** because code generation runs only on a model without errors: a
 * defective declared text keeps its own class and the build failure never comes, measured on the rule carrier.
 * One finding per declaration, naming the first part that misses, since the throw ends the build.
 */
internal fun TrackedFactBase.undeclaredLocaleMessageFindings(): List<Finding> = declarations()
    .filter { it.declaredKind == DeclaredKind.RULE || it.declaredKind == DeclaredKind.COMPUTATION }
    .mapNotNull { element ->
        val messages = element.body?.authoredMessages() as? AuthoredMessages.Read ?: return@mapNotNull null
        val undeclared = messages.entries.textsCollectedFor { it !in declaredLocales }
        if (undeclared.isEmpty()) return@mapNotNull null
        val declared = messages.examined(declaredLocales).flatMap { it.text.parameterParts() }.toSet()
        undeclared.firstNotNullOfOrNull { (locale, text) ->
            text.parameterParts().firstOrNull { it !in declared }?.let { locale to it }
        }?.let { (locale, part) ->
            Finding(
                FindingCategory.A12_CONSTRAINT,
                FindingSeverity.ERROR,
                CK_MODEL_NOT_BUILDABLE,
                "in `errorMessage` for `$locale`, a locale the model does not declare: `$$part$` is spelled as no " +
                    "declared locale's text spells a parameter, and A12's code generation looks every message's " +
                    "parameters up among those, so the Kernel cannot build this model",
                element.path,
            )
        }
    }

/** The non-empty parts between two `$`, in the order A12's parameter mapping meets them. */
internal fun String.parameterParts(): List<String> =
    split('$').filterIndexed { index, part -> index % 2 == 1 && part.isNotEmpty() }
