package io.github.mbackschat.a12.dm.interpreter

import java.lang.reflect.Constructor
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **No pointer value can be constructed on the JVM without passing its validating factory** — asserted against
 * the COMPILED artifact, because the source modifier and the ABI golden both said otherwise and both were wrong.
 *
 * `A12PartiallyKnownMultiPointer`'s primary constructor was `internal constructor`. Kotlin name-mangles internal
 * *functions*, but a constructor is emitted as-is: `javap` showed `flags: (0x0001) ACC_PUBLIC`, with no synthetic
 * marker, and ordinary Java compiled and ran
 * `new A12PartiallyKnownMultiPointer("anything at all", List.of(-7))` — no arity check, no index-domain check,
 * no `fullName` normalization. The binary-compatibility ABI dump hides internal members, so the golden showed
 * nothing either. Only reading the class file, or calling it from Java, could see it.
 *
 * So this test's oracle is `Class.getConstructors()` — the reflective view a Java consumer actually has. A
 * synthetic bridge (Kotlin emits one for a private constructor reached from a companion) is excluded: `javac`
 * will not resolve a synthetic member, which was verified by compiling a Java call to it and observing the
 * compiler refuse.
 */
class A12PointerConstructionClosedTest {

    private fun callableConstructors(type: Class<*>): List<Constructor<*>> =
        type.constructors.filterNot { it.isSynthetic }

    @Test
    fun neitherPointerValueExposesAConstructorToJava() {
        for (type in listOf(A12PartiallyKnownMultiPointer::class.java, A12Pointer::class.java)) {
            val open = callableConstructors(type)
            assertTrue(
                open.isEmpty(),
                "${type.simpleName} exposes ${open.size} non-synthetic public constructor(s) to a Java " +
                    "consumer, so its validating factory can be bypassed: $open",
            )
        }
    }

    /**
     * The positive control. Without it the assertion above passes on any class whose declared constructors this
     * test failed to find at all — so one type that legitimately *does* publish a constructor is checked too.
     * [A12PathPart] is that type: it holds no collection, so its constructor validates completely and there is
     * nothing for a factory to add.
     */
    @Test
    fun thePathPartDoesPublishItsValidatingConstructor() {
        assertEquals(
            1,
            callableConstructors(A12PathPart::class.java).size,
            "A12PathPart is expected to publish exactly its validating primary constructor",
        )
    }

    /** Every declared constructor on the two value types is private or synthetic — the class-file view. */
    @Test
    fun theDeclaredConstructorsArePrivateOrSynthetic() {
        for (type in listOf(A12PartiallyKnownMultiPointer::class.java, A12Pointer::class.java)) {
            type.declaredConstructors.forEach { constructor ->
                assertTrue(
                    constructor.isSynthetic || Modifier.isPrivate(constructor.modifiers),
                    "${type.simpleName} declares a non-private, non-synthetic constructor: $constructor",
                )
            }
        }
    }
}
