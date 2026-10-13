package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The sentence A12 answers a nested group name past 60 characters with (KERNEL-FINDINGS KF420, corrected
 * 2026-10-13): the path-shape sentence, once at the group and once at each field inside it, and no class.
 * `rulekit.validate.laws.GroupNameAlphabetLawsTest` locks the verdict tier; this locks the words.
 *
 * Own domain (a warehouse bin and its slots).
 */
class GroupNameLengthSentenceLawsTest {

    private fun messages(groupName: String): List<String> = KernelConsistencyOracle()
        .checkConsistency(DocumentModelDraft.named("warehouse-bin").locale("en_US")
            .rootGroup("Bin") { b -> b.stringField("Ref") { }.group(groupName) { g -> g.numberField("Qty") { it.fractionDigits(0) } } }
            .build().toJson(), ReferenceResolvers.selfContained())
        .map { it.message().trim() }

    @Test
    fun sixtyOneCharactersDrawThePathSentenceAtTheGroupAndItsField() {
        assertEquals(emptyList<String>(), messages("A".repeat(60)))
        val name = "A".repeat(61)
        assertEquals(listOf("Group with path '/Bin/$name'", "Field with path '/Bin/$name/Qty'"),
            messages(name).map { it.substringBefore(':') })
        messages(name).forEach {
            assertEquals(true, it.endsWith("A group name may only contain letters, numbers or underscores. Group names must " +
                "begin with a letter or an underscore. Name parts may not be longer than 60 characters and are separated " +
                "by slashes. An absolute group name must begin with a slash."), it)
            assertEquals(false, it.matches(Regex(".*\\[\\w+]$")), it)
        }
    }
}
