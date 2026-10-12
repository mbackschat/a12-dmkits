package io.github.mbackschat.a12.dm.predicates

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The reserved-namespace law, held to the shapes its three former copies disagreed about.
 *
 * The rows are not decoration: the case-insensitivity is what a `startsWith` would get wrong, and the
 * `vk_vkX` row is what a copy comparing against `vk_vk_` — with the trailing underscore the Kernel's
 * generated names happen to carry — would get wrong. Both readings are available from the generated names
 * alone, which is why the law is measured (`validate.laws.RuleSlotLawsTest`, `ComputationSlotLawsTest`)
 * rather than derived from them.
 */
class ElementNamePredicatesTest {

    @Test
    fun reservedClassificationRequiresAFormallyAdmittedName() {
        for ((name, expected) in listOf(
            "vk_vkProbe" to true,
            "vk_vk.Bad" to false,
            ("vk_vk" + "x".repeat(95)) to true,
            ("vk_vk" + "x".repeat(96)) to false,
        )) assertEquals(expected, isReservedElementName(name), name)
    }

    @Test
    fun whichNamesSitInA12sReservedNamespace() {
        val actual = mutableMapOf<String, Boolean>()
        actual["vk_vkPrice_req — a generated name"] = isReservedElementName("vk_vkPrice_req")
        actual["vk_vk exactly"] = isReservedElementName("vk_vk")
        actual["vk_vkPrice — the prefix with no separator"] = isReservedElementName("vk_vkPrice")
        actual["VK_VK — upper case"] = isReservedElementName("VK_VKPrice")
        actual["Vk_Vk — mixed case"] = isReservedElementName("Vk_VkPrice")
        actual["PriceRule — an ordinary name"] = isReservedElementName("PriceRule")
        actual["vk_v — one character short"] = isReservedElementName("vk_v")
        actual["myvk_vkRule — the prefix not at the start"] = isReservedElementName("myvk_vkRule")
        actual["empty"] = isReservedElementName("")

        val expected = mutableMapOf<String, Boolean>()
        expected["vk_vkPrice_req — a generated name"] = true
        expected["vk_vk exactly"] = true
        // The trailing separator is NOT part of the law: A12's generated names carry one, and reading the law
        // off those names would admit `vk_vkPrice`, which the Kernel refuses.
        expected["vk_vkPrice — the prefix with no separator"] = true
        expected["VK_VK — upper case"] = true
        expected["Vk_Vk — mixed case"] = true
        expected["PriceRule — an ordinary name"] = false
        expected["vk_v — one character short"] = false
        // The law is about the PREFIX, so a name merely containing it is ordinary.
        expected["myvk_vkRule — the prefix not at the start"] = false
        expected["empty"] = false
        assertEquals(expected, actual, "which element names A12 reserves for itself")
    }
}
