package io.github.mbackschat.a12.dm.adapter.perf

import kotlin.reflect.KClass

/**
 * Declares which public `DmInterpreter` entry point a performance test times.
 *
 * The declaration lives on the timing guard itself so there is no separate class/method pointer that can drift
 * to an unrelated test. [InterpreterPerfCoverageTest] discovers these annotations through JUnit
 * and resolves [method] plus [parameterTypes] against the real `DmInterpreter` surface.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class TimesInterpreterEntryPoint(
    val method: String,
    vararg val parameterTypes: KClass<*>,
)
