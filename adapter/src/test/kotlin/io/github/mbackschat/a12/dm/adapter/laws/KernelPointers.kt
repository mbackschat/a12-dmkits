package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.md.document.apiV2.DocumentMultiPointer
import com.mgmtp.a12.kernel.md.document.apiV2.DocumentPointer
import com.mgmtp.a12.kernel.md.document.apiV2.PartiallyKnownDocumentMultiPointer
import com.mgmtp.a12.kernel.md.document.apiV2.immutable.GroupInstanceV2

/*
 * The (path, repetitions) view of the three pointer kinds kernel 31's md custom-condition SPI passes
 * (`check(DocumentV2, Set<out DocumentMultiPointer>?, Set<DocumentPointer>, PartiallyKnownDocumentMultiPointer)`),
 * under the names the V1 `IEntityInstance` it replaced used (A12K-3684). The differentials record exactly that
 * pair, so keeping the names keeps their assertions unchanged across the upgrade.
 */

internal val DocumentPointer.path: String get() = fullName()
internal val DocumentPointer.repetitions: List<Int> get() = repetitionIndexes()

internal val PartiallyKnownDocumentMultiPointer.path: String get() = fullName()
internal val PartiallyKnownDocumentMultiPointer.repetitions: List<Int> get() = repetitionIndexes()

internal val DocumentMultiPointer.path: String get() = fullName()
internal val DocumentMultiPointer.repetitions: List<Int> get() = pathParts.map { it.repetitionIndex() }

/** Every field of the document that carries a value, with its pointer — the V2 walk of the V1 `entityInstances`. */
internal fun GroupInstanceV2.filledFields(prefix: DocumentPointer = DocumentPointer.of(emptyList())): List<Pair<DocumentPointer, Any>> =
    directFields().mapNotNull { (name, field) -> field?.value()?.let { prefix.withAppended(name, 1) to it } } +
        directSubgroups().flatMap { (name, rows) ->
            rows.toList().flatMapIndexed { index, row -> row.filledFields(prefix.withAppended(name, index + 1)) }
        }
