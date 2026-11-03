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
        claimsKeyed: Boolean = false,
        recoveries: RecoveriesIndex? = null,
        parts: Boolean = false,
    ): List<String> {
        val rule = rule(errorField, condition, message)
        val source = if (inInsured) {
            model(ruleInInsured = rule, claimsKeyed = claimsKeyed, recoveries = recoveries, parts = parts)
        } else {
            model(ruleInPolicy = rule, claimsKeyed = claimsKeyed, recoveries = recoveries, parts = parts)
        }
        return checkRead(ModelSource(source)).findings.filter { it.justifiesRejection }.map { it.code }.distinct()
    }

    /** One rule named `Probe`, comma-led so it splices into [model]'s element lists. */
    fun rule(errorField: String, condition: String, message: String = "x") =
        """,{"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"$condition",
                  "errorEntityRelPath":"$errorField",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"$message"}]}}"""

    /**
     * How a repeatable `Recoveries` beside `Claims` declares its index field — the fixture's
     * `PolicyPathFixture.RecoveriesIndex`, which puts an error field there on a level parallel to `Claims`.
     */
    enum class RecoveriesIndex(val indexField: String?, val claimRefType: String) {
        NONE(null, "StringType"),
        CLAIM_REF("ClaimRef", "StringType"),
        OTHER_NAME("RecoveryRef", "StringType"),
        CLAIM_REF_AS_NUMBER("ClaimRef", "NumberType"),
    }

    /**
     * The model, with [ruleInPolicy] and [ruleInInsured] spliced in as already comma-led element lists,
     * `ClaimRef` declared `Claims`' index field when [claimsKeyed], a `Recoveries` group after `Claims` when
     * [recoveries] is given, and a repeatable `Parts` inside `Claims` when [parts].
     */
    fun model(
        ruleInPolicy: String = "",
        ruleInInsured: String = "",
        claimsKeyed: Boolean = false,
        recoveries: RecoveriesIndex? = null,
        parts: Boolean = false,
    ) = """
            {"header":{"modelVersion":"29.4.0","id":"policy","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},"modelConfig":{"fieldRefByShortNameAllowed":true},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Holder","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Group","id":"g1","name":"Insured","Group":{"elements":[
                  {"type":"Field","id":"f1","name":"Remark","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Field","id":"f2","name":"Age","Field":{"fieldType":{"type":"NumberType"}}}$ruleInInsured
                ]}},
                {"type":"Group","id":"g2","name":"Broker","Group":{"elements":[
                  {"type":"Field","id":"f3","name":"BrokerName","Field":{"fieldType":{"type":"StringType"}}}
                ]}},
                {"type":"Group","id":"g3","name":"Claims","Group":{"repeatability":20,${if (claimsKeyed) "\"indexFieldName\":\"ClaimRef\"," else ""}"elements":[
                  {"type":"Field","id":"f4","name":"ClaimRef","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Field","id":"f5","name":"Remark","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Field","id":"f6","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}}${if (parts) PARTS else ""}
                ]}}${recoveries?.let(::recoveriesGroup) ?: ""}$ruleInPolicy
              ]}}
             ]}}}
            """.trimIndent()

    private const val PARTS = """,{"type":"Group","id":"g5","name":"Parts","Group":{"repeatability":20,""" +
        """"elements":[{"type":"Field","id":"f10","name":"PartCost","Field":{"fieldType":{"type":"NumberType"}}}]}}"""

    private fun recoveriesGroup(index: RecoveriesIndex) =
        """,{"type":"Group","id":"g4","name":"Recoveries","Group":{"repeatability":20,""" +
            (index.indexField?.let { "\"indexFieldName\":\"$it\"," } ?: "") +
            """"elements":[""" +
            """{"type":"Field","id":"f7","name":"ClaimRef","Field":{"fieldType":{"type":"${index.claimRefType}"}}},""" +
            """{"type":"Field","id":"f8","name":"RecoveryRef","Field":{"fieldType":{"type":"StringType"}}},""" +
            """{"type":"Field","id":"f9","name":"Note","Field":{"fieldType":{"type":"StringType"}}}]}}"""
}
