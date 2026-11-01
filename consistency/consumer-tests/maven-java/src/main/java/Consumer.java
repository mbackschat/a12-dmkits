import io.github.mbackschat.a12.dm.consistency.CheckKt;
import io.github.mbackschat.a12.dm.consistency.CheckOutcome;
import io.github.mbackschat.a12.dm.consistency.CheckerIdentityKt;
import io.github.mbackschat.a12.dm.consistency.ModelSource;
import io.github.mbackschat.a12.dm.consistency.OutcomeKt;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Consumer {
    public static void main(String[] args) throws Exception {
        var version = System.getenv("CHECKER_VERSION");
        var fixtures = Path.of(System.getenv("CHECKER_FIXTURES"));
        var sound = CheckKt.check(new ModelSource(Files.readString(fixtures.resolve("storefront.dm.json")), null));
        var broken = CheckKt.check(new ModelSource(Files.readString(fixtures.resolve("storefront-broken.dm.json")), null));
        if (!CheckerIdentityKt.CHECKER_VERSION.equals(version)
                || !"storefront".equals(sound.getModelId())
                || OutcomeKt.getOutcome(sound) != CheckOutcome.ALL_CHECKS_PASSED
                || sound.getStatus().getComplete()
                || OutcomeKt.getOutcome(broken) != CheckOutcome.REJECTED
                || broken.getFindings().isEmpty()) {
            throw new AssertionError("Published Maven checker returned an unexpected result");
        }
        System.out.println("published Maven checker " + version + " consumer passed");
    }
}
