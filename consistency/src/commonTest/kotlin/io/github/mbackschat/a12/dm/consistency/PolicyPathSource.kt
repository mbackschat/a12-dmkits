package io.github.mbackschat.a12.dm.consistency

/**
 * The insurance-policy model the path-walk tests share, the checker's copy of
 * `rulekit.validate.laws.PolicyPathFixture` so each row can mirror one measured there.
 *
 * A fixed `Insured` (`Remark`, `Age`) and `Broker` (`BrokerName`) beside a repeatable `Claims` (`ClaimRef`,
 * `Remark`, `Amount`) and a root field `Holder`, with short-name references on and `Remark` declared twice so
 * that a bare `Remark` is ambiguous model-wide.
 */
internal object PolicyPathSource {

    /** The distinct codes of every rejecting finding for one rule, placed at the root group or in `Insured`. */
    fun rejections(
        errorField: String,
        condition: String,
        inInsured: Boolean = false,
        message: String = "x",
    ): List<String> {
        val rule = rule(errorField, condition, message)
        val source = if (inInsured) model(ruleInInsured = rule) else model(ruleInPolicy = rule)
        return check(ModelSource(source)).findings.filter { it.justifiesRejection }.map { it.code }.distinct()
    }

    /** One rule named `Probe`, comma-led so it splices into [model]'s element lists. */
    fun rule(errorField: String, condition: String, message: String = "x") =
        """,{"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"$condition",
                  "errorEntityRelPath":"$errorField",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"$message"}]}}"""

    /** The model, with [ruleInPolicy] and [ruleInInsured] spliced in as already comma-led element lists. */
    fun model(ruleInPolicy: String = "", ruleInInsured: String = "") = """
            {"header":{"id":"policy","locales":[{"code":"en_US"}]},
             "content":{"modelConfig":{"fieldRefByShortNameAllowed":true},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Holder","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Group","id":"g1","name":"Insured","Group":{"elements":[
                  {"type":"Field","id":"f1","name":"Remark","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Field","id":"f2","name":"Age","Field":{"fieldType":{"type":"NumberType"}}}$ruleInInsured
                ]}},
                {"type":"Group","id":"g2","name":"Broker","Group":{"elements":[
                  {"type":"Field","id":"f3","name":"BrokerName","Field":{"fieldType":{"type":"StringType"}}}
                ]}},
                {"type":"Group","id":"g3","name":"Claims","Group":{"repeatability":20,"elements":[
                  {"type":"Field","id":"f4","name":"ClaimRef","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Field","id":"f5","name":"Remark","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Field","id":"f6","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}}
                ]}}$ruleInPolicy
              ]}}
             ]}}}
            """.trimIndent()
}
