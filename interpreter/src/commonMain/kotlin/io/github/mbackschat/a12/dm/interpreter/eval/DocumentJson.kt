package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.A12DocumentJson
import io.github.mbackschat.a12.dm.interpreter.DocumentDecodeLimits
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.jvm.JvmOverloads

/**
 * The **model-level** half of the A12 Document JSON codec — the pair beside [newDocument], for an in-repo
 * consumer that works in [ModelDef] and [Document] rather than in the published `PreparedModel` façade.
 * `dmtool`'s runtime verbs and `model seed` are that consumer.
 *
 * It lives in this engine package on purpose, exactly where [newDocument] lives: the released library contract
 * is the **root-package façade** (`PreparedModel.documents` — owner-bound, snapshot-returning, and the carrier
 * of the serializer's root `id`), and these implementation packages are deliberately *not* frozen by the ABI
 * locks or presented as consumer API. Putting the pair here therefore adds no published contract while removing
 * the reason a consumer would write its own reader or writer for a shape A12 already defines
 * (CLAUDE.md "NEVER invent a shape A12 already defines").
 *
 * Same codec as the façade: same tree shape, same value normalization at ingestion, same resource ceilings, same
 * diagnostics. It carries no document identity — a root `id` is still *validated* on read, so it is never
 * reported as an unknown model path, but nothing at this level can keep it.
 */

/**
 * Read A12's canonical Document JSON into a [Document] of this model.
 *
 * @throws io.github.mbackschat.a12.dm.interpreter.DocumentInputError with every model-level defect aggregated,
 *         or the first resource ceiling exceeded.
 */
@JvmOverloads
fun ModelDef.readDocumentJson(
    json: String,
    limits: DocumentDecodeLimits = DocumentDecodeLimits.defaults(),
): Document = A12DocumentJson.read(this, json, limits).document

/**
 * Write this document as canonical A12 Document JSON against [model]. **Compact** by design: A12's own
 * serializer takes the pretty-printer as caller configuration (`DocumentSerializationConfig.prettyJsonPrinter`),
 * so presentation belongs to the caller, not to the format.
 *
 * @throws io.github.mbackschat.a12.dm.interpreter.DocumentEncodeError if the document holds a state the shape
 *         cannot address.
 */
fun Document.toDocumentJson(model: ModelDef): String = A12DocumentJson.write(model, this, documentId = null)
