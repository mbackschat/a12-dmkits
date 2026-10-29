package io.github.mbackschat.a12.dm.predicates

import kotlin.test.Test
import kotlin.test.assertEquals

/** The countries a bare uppercase locale pair may name, against the running JDK's own list (KERNEL-FINDINGS KF649). */
class CountryListJvmTest {

    @Test
    fun theListIsTheJdksOwn() {
        assertEquals(java.util.Locale.getISOCountries().toSortedSet(), ISO_COUNTRIES.toSortedSet())
    }
}
