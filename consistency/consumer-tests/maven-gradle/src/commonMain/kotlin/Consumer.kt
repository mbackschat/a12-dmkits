import io.github.mbackschat.a12.dm.consistency.CHECKER_VERSION
import io.github.mbackschat.a12.dm.consistency.CheckOutcome
import io.github.mbackschat.a12.dm.consistency.ConsistencyStatus
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check as checkModel
import io.github.mbackschat.a12.dm.consistency.outcome

fun verifyChecker(version: String, sound: String, broken: String) {
    check(CHECKER_VERSION == version)
    val accepted = checkModel(ModelSource(sound))
    check(accepted.modelId == "storefront")
    check(accepted.outcome == CheckOutcome.ALL_CHECKS_PASSED)
    check(accepted.findings.isEmpty() && accepted.diagnostics.isEmpty())
    check(!accepted.status.complete)
    val rejected = checkModel(ModelSource(broken))
    check(rejected.status == ConsistencyStatus.Invalid)
    check(rejected.outcome == CheckOutcome.REJECTED)
    check(rejected.findings.isNotEmpty())
    println("published checker $version consumer passed")
}
