package io.github.mbackschat.a12.dm.consistency

/**
 * The basket `ModelDrafts.basket()` builds on the kernel side, as the checker reads it: a `Total`, a repeatable
 * `Lines` with a `Qty` and a fixed `Packaging`, a repeatable `Vouchers`, and a fixed `Fees`.
 *
 * Shared by the mirrors whose laws twin measures on that same draft, so the two sides cannot drift apart on
 * the model while each keeps its own rows.
 *
 * @param inBasket declarations appended to `/Basket`, each led by its comma.
 * @param inLines declarations appended to `/Basket/Lines`, each led by its comma.
 */
internal fun basketModel(inBasket: String = "", inLines: String = ""): ModelSource = ModelSource(
    """
    {"header":{"id":"basket","locales":[{"code":"en_US"}]},
     "content":{"modelRoot":{"rootGroups":[
      {"type":"Group","id":"g0","name":"Basket","Group":{"elements":[
        {"type":"Field","id":"f0","name":"Total","Field":{"fieldType":{"type":"NumberType"}}},
        {"type":"Group","id":"g1","name":"Lines","Group":{"repeatability":3,"elements":[
          {"type":"Field","id":"f1","name":"Qty","Field":{"fieldType":{"type":"NumberType"}}},
          {"type":"Group","id":"g2","name":"Packaging","Group":{"elements":[
            {"type":"Field","id":"f2","name":"Weight","Field":{"fieldType":{"type":"NumberType"}}}]}}
          $inLines]}},
        {"type":"Group","id":"g3","name":"Vouchers","Group":{"repeatability":3,"elements":[
          {"type":"Field","id":"f3","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}}]}},
        {"type":"Group","id":"g4","name":"Fees","Group":{"elements":[
          {"type":"Field","id":"f4","name":"Handling","Field":{"fieldType":{"type":"NumberType"}}}]}}
        $inBasket
      ]}}]}}}
    """.trimIndent(),
)
