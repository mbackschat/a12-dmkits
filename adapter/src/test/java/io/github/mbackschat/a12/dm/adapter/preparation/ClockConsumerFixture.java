package io.github.mbackschat.a12.dm.adapter.preparation;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/** Authors the packed TypeScript consumer's clock fixture through the same model surface as clients. */
public final class ClockConsumerFixture {
    private ClockConsumerFixture() {}

    public static String renewal() {
        var structure = DocumentModelDraft.named("renewal").locale("en_US")
                .rootGroup("Renewal", group -> group.dateField("DueOn", date -> date.format("yyyy-MM-dd")))
                .build().toJson();
        var session = EditSession.read(structure);
        session.config().timeZone("Europe/Berlin");
        var renewal = session.group("/Renewal");
        var dueOn = renewal.date("DueOn");
        var rule = renewal.rule(builder -> builder.name("DueToday")
                .errorField(dueOn)
                .when(dueOn.filled().and(dueOn.eq(Dsl.today())))
                .code("DUE_TODAY")
                .severity(Severity.ERROR)
                .message(en("The policy renews today."))
                .build());
        session.addRule(ignored -> rule);
        return session.validate().toJson();
    }
}
