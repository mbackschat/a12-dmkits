package io.github.mbackschat.a12.dm.adapter

/**
 * The one reader for a fixture on the adapter's test classpath.
 *
 * Each test class used to carry a private copy, and the copies differed only in how a missing fixture failed —
 * most with a `NullPointerException` on the absent stream, which names nothing. This one names the path.
 */
object TestResources {

    /** The UTF-8 text of the test-classpath resource at [path], such as `models/order.dm.json`. */
    @JvmStatic
    fun text(path: String): String =
        checkNotNull(TestResources::class.java.classLoader.getResourceAsStream(path)) {
            "fixture not on the test classpath: $path"
        }.use { String(it.readAllBytes(), Charsets.UTF_8) }
}
