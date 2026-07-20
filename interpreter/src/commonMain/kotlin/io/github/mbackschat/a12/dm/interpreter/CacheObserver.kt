package io.github.mbackschat.a12.dm.interpreter

/**
 * Internal structural observation seam for the cache-lifetime NFR guards. It exposes logical entry counts only:
 * no cached value or implementation detail crosses the seam, and production pays only a null branch.
 */
internal fun interface CacheObserver {
    fun cacheEntries(owner: Any, kind: CacheOwnerKind, entries: Int)
}

/** The bounded owner classes covered by the cache-lifetime guard. */
internal enum class CacheOwnerKind {
    DOCUMENT,
    PARSED_MODEL,
    OVERLAY_VIEWS,
}
