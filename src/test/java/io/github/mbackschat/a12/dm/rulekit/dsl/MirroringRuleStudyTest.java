package io.github.mbackschat.a12.dm.rulekit.dsl;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.allOf;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.anyOf;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.atLeastOneFieldFilled;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.noFieldFilled;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
// GroupRef/StringRef/FieldRef/Condition/DslRenderer are in this package (io.…rulekit.dsl)

/**
 * The §3.6 STUDY — a real, highly-repetitive consistency rule rebuilt from the efficiency primitives.
 *
 * <p>Domain (insurance): an application submitted through a broker portal. The policyholder block restated
 * on the <em>policy schedule</em> ({@code Schedule/*}) is captured apart from where the underlying name and
 * address were entered, so the form engine can't auto-sync it — a rule enforces that it <b>mirrors</b>
 * whichever source the application's flags select (entity type × correspondence-vs-residence address),
 * null-safe so a not-yet-filled block (both empty) is fine. Hand-written it is the same
 * {@code (AllFieldsFilled(a,b) And [a]==[b]) Or NoFieldFilled(a,b)} idiom copy-pasted 12× across 4 near-
 * identical branches; here it is ~15 lines that <em>render to exactly that</em> (the assertion pins it).
 */
class MirroringRuleStudyTest {

    @Test
    void aRepetitiveMirroringRuleIsRebuiltFromTheEfficiencyPrimitives() {
        BoundModel m = BoundModel.ofJson(model());

        // tag::study-mirroring[]
        // the fixed target block — the policyholder block restated on the policy schedule
        List<StringRef> target = List.of(m.str("/Application/Schedule/Name"),
                m.str("/Application/Schedule/Zip"), m.str("/Application/Schedule/City"));

        // the two binary axes that SELECT the source block
        StringRef isOrg = m.str("/Application/PolicyholderIsOrganization");
        List<FieldRef> correspondence = List.of(
                m.field("/Application/Correspondence/Zip"), m.field("/Application/Correspondence/City"));

        // assemble the source block from the two axis choices (organization or person? correspondence address?)
        BiFunction<Boolean, Boolean, List<StringRef>> sources = (org, useCorrespondence) -> List.of(
                org ? m.str("/Application/OrgName") : m.str("/Application/PersonName"),
                useCorrespondence ? m.str("/Application/Correspondence/Zip") : m.str("/Application/Residence/Zip"),
                useCorrespondence ? m.str("/Application/Correspondence/City") : m.str("/Application/Residence/City"));

        // every target field mirrors its source, null-safe — the inner 3× repetition collapses to a zip + fold
        BiFunction<List<StringRef>, List<StringRef>, Condition> mirrors = (src, tgt) -> allOf(
                IntStream.range(0, tgt.size()).mapToObj(i -> src.get(i).agreesWith(tgt.get(i))).toList());

        // the 4 branches ARE the 2×2 cross-product — a loop, not four copy-pasted blocks
        List<Condition> branches = new ArrayList<>();
        for (boolean org : new boolean[] {true, false}) {
            for (boolean useCorrespondence : new boolean[] {true, false}) {
                Condition selector = (org ? isOrg.filled() : isOrg.notFilled())
                        .and(useCorrespondence ? atLeastOneFieldFilled(correspondence) : noFieldFilled(correspondence));
                branches.add(selector.and(mirrors.apply(sources.apply(org, useCorrespondence), target)));
            }
        }

        Condition rule = m.group("/Application/BrokerSubmission").filled()
                .and(atLeastOneFieldFilled(target.stream().map(StringRef::field).toList()))
                .and(anyOf(branches));
        // end::study-mirroring[]

        assertEquals(EXPECTED, new DslRenderer().render(rule));
    }

    @Test
    void theSameShapeBecomesAReusableBuilder() {
        BoundModel m = BoundModel.ofJson(model());

        // (a) the flagship rule, now via the reusable mirrorRule — renders IDENTICALLY to the inline build,
        //     so the extraction is provably faithful; the 2×2 just builds the `choices` list.
        List<StringRef> schedule = List.of(m.str("/Application/Schedule/Name"),
                m.str("/Application/Schedule/Zip"), m.str("/Application/Schedule/City"));
        StringRef isOrg = m.str("/Application/PolicyholderIsOrganization");
        List<FieldRef> correspondence = List.of(
                m.field("/Application/Correspondence/Zip"), m.field("/Application/Correspondence/City"));
        List<SourceChoice> scheduleChoices = new ArrayList<>();
        for (boolean org : new boolean[] {true, false}) {
            for (boolean useCorrespondence : new boolean[] {true, false}) {
                scheduleChoices.add(new SourceChoice(
                        (org ? isOrg.filled() : isOrg.notFilled())
                                .and(useCorrespondence ? atLeastOneFieldFilled(correspondence)
                                        : noFieldFilled(correspondence)),
                        List.of(org ? m.str("/Application/OrgName") : m.str("/Application/PersonName"),
                                useCorrespondence ? m.str("/Application/Correspondence/Zip")
                                        : m.str("/Application/Residence/Zip"),
                                useCorrespondence ? m.str("/Application/Correspondence/City")
                                        : m.str("/Application/Residence/City"))));
            }
        }
        assertEquals(EXPECTED, new DslRenderer().render(
                mirrorRule(m.group("/Application/BrokerSubmission"), schedule, scheduleChoices)));

        // (b) a DIFFERENT rule of the same kind — provide new target + sources, call the builder. That's it.
        // tag::study-mirror-reuse[]
        // the premium-payer block must mirror either the policyholder or a designated third-party payer
        StringRef byThirdParty = m.str("/Application/PaidByThirdParty");
        List<StringRef> payer = List.of(m.str("/Application/Payer/Name"), m.str("/Application/Payer/Iban"));
        Condition payerRule = mirrorRule(m.group("/Application/BrokerSubmission"), payer, List.of(
                new SourceChoice(byThirdParty.filled(),
                        List.of(m.str("/Application/ThirdParty/Name"), m.str("/Application/ThirdParty/Iban"))),
                new SourceChoice(byThirdParty.notFilled(),
                        List.of(m.str("/Application/Policyholder/Name"), m.str("/Application/Policyholder/Iban")))));
        // end::study-mirror-reuse[]
        assertEquals(EXPECTED_2, new DslRenderer().render(payerRule));
    }

    // --- reusable parametric combinators: write once, every rule of this kind is then just data ----

    // tag::study-mirror-combinators[]
    /** Null-safe block mirror: each target field agrees with its source (both filled & equal, or both empty). */
    static Condition mirrors(List<StringRef> sources, List<StringRef> target) {
        return allOf(IntStream.range(0, target.size())
                .mapToObj(i -> sources.get(i).agreesWith(target.get(i))).toList());
    }

    /** One guarded source alternative: when {@code when} holds, the target must mirror {@code sources}. */
    record SourceChoice(Condition when, List<StringRef> sources) {}

    /** The whole rule SHAPE, parameterized: a context guard + "target has data" + an OR of guarded mirrors. */
    static Condition mirrorRule(GroupRef context, List<StringRef> target, List<SourceChoice> choices) {
        return context.filled()
                .and(atLeastOneFieldFilled(target.stream().map(StringRef::field).toList()))
                .and(anyOf(choices.stream().map(c -> c.when().and(mirrors(c.sources(), target))).toList()));
    }
    // end::study-mirror-combinators[]

    /** The rendered output the ~15-line build produces — the repetitive form you'd otherwise hand-write. */
    private static final String EXPECTED =
            "GroupFilled(/Application/BrokerSubmission) And AtLeastOneFieldFilled(/Application/Schedule/Name, "
            + "/Application/Schedule/Zip, /Application/Schedule/City) And "
            + "((FieldFilled(/Application/PolicyholderIsOrganization) And "
            + "AtLeastOneFieldFilled(/Application/Correspondence/Zip, /Application/Correspondence/City) And "
            + "((AllFieldsFilled(/Application/OrgName, /Application/Schedule/Name) And [/Application/OrgName] == "
            + "[/Application/Schedule/Name]) Or NoFieldFilled(/Application/OrgName, /Application/Schedule/Name)) And "
            + "((AllFieldsFilled(/Application/Correspondence/Zip, /Application/Schedule/Zip) And "
            + "[/Application/Correspondence/Zip] == [/Application/Schedule/Zip]) Or "
            + "NoFieldFilled(/Application/Correspondence/Zip, /Application/Schedule/Zip)) And "
            + "((AllFieldsFilled(/Application/Correspondence/City, /Application/Schedule/City) And "
            + "[/Application/Correspondence/City] == [/Application/Schedule/City]) Or "
            + "NoFieldFilled(/Application/Correspondence/City, /Application/Schedule/City))) Or "
            + "(FieldFilled(/Application/PolicyholderIsOrganization) And "
            + "NoFieldFilled(/Application/Correspondence/Zip, /Application/Correspondence/City) And "
            + "((AllFieldsFilled(/Application/OrgName, /Application/Schedule/Name) And [/Application/OrgName] == "
            + "[/Application/Schedule/Name]) Or NoFieldFilled(/Application/OrgName, /Application/Schedule/Name)) And "
            + "((AllFieldsFilled(/Application/Residence/Zip, /Application/Schedule/Zip) And "
            + "[/Application/Residence/Zip] == [/Application/Schedule/Zip]) Or "
            + "NoFieldFilled(/Application/Residence/Zip, /Application/Schedule/Zip)) And "
            + "((AllFieldsFilled(/Application/Residence/City, /Application/Schedule/City) And "
            + "[/Application/Residence/City] == [/Application/Schedule/City]) Or "
            + "NoFieldFilled(/Application/Residence/City, /Application/Schedule/City))) Or "
            + "(FieldNotFilled(/Application/PolicyholderIsOrganization) And "
            + "AtLeastOneFieldFilled(/Application/Correspondence/Zip, /Application/Correspondence/City) And "
            + "((AllFieldsFilled(/Application/PersonName, /Application/Schedule/Name) And [/Application/PersonName] == "
            + "[/Application/Schedule/Name]) Or NoFieldFilled(/Application/PersonName, /Application/Schedule/Name)) And "
            + "((AllFieldsFilled(/Application/Correspondence/Zip, /Application/Schedule/Zip) And "
            + "[/Application/Correspondence/Zip] == [/Application/Schedule/Zip]) Or "
            + "NoFieldFilled(/Application/Correspondence/Zip, /Application/Schedule/Zip)) And "
            + "((AllFieldsFilled(/Application/Correspondence/City, /Application/Schedule/City) And "
            + "[/Application/Correspondence/City] == [/Application/Schedule/City]) Or "
            + "NoFieldFilled(/Application/Correspondence/City, /Application/Schedule/City))) Or "
            + "(FieldNotFilled(/Application/PolicyholderIsOrganization) And "
            + "NoFieldFilled(/Application/Correspondence/Zip, /Application/Correspondence/City) And "
            + "((AllFieldsFilled(/Application/PersonName, /Application/Schedule/Name) And [/Application/PersonName] == "
            + "[/Application/Schedule/Name]) Or NoFieldFilled(/Application/PersonName, /Application/Schedule/Name)) And "
            + "((AllFieldsFilled(/Application/Residence/Zip, /Application/Schedule/Zip) And "
            + "[/Application/Residence/Zip] == [/Application/Schedule/Zip]) Or "
            + "NoFieldFilled(/Application/Residence/Zip, /Application/Schedule/Zip)) And "
            + "((AllFieldsFilled(/Application/Residence/City, /Application/Schedule/City) And "
            + "[/Application/Residence/City] == [/Application/Schedule/City]) Or "
            + "NoFieldFilled(/Application/Residence/City, /Application/Schedule/City))))";

    /** A second rule of the same kind, via {@code mirrorRule} — proof the builder is reusable, not bespoke. */
    private static final String EXPECTED_2 =
            "GroupFilled(/Application/BrokerSubmission) And AtLeastOneFieldFilled(/Application/Payer/Name, "
            + "/Application/Payer/Iban) And ((FieldFilled(/Application/PaidByThirdParty) And "
            + "((AllFieldsFilled(/Application/ThirdParty/Name, /Application/Payer/Name) And "
            + "[/Application/ThirdParty/Name] == [/Application/Payer/Name]) Or "
            + "NoFieldFilled(/Application/ThirdParty/Name, /Application/Payer/Name)) And "
            + "((AllFieldsFilled(/Application/ThirdParty/Iban, /Application/Payer/Iban) And "
            + "[/Application/ThirdParty/Iban] == [/Application/Payer/Iban]) Or "
            + "NoFieldFilled(/Application/ThirdParty/Iban, /Application/Payer/Iban))) Or "
            + "(FieldNotFilled(/Application/PaidByThirdParty) And "
            + "((AllFieldsFilled(/Application/Policyholder/Name, /Application/Payer/Name) And "
            + "[/Application/Policyholder/Name] == [/Application/Payer/Name]) Or "
            + "NoFieldFilled(/Application/Policyholder/Name, /Application/Payer/Name)) And "
            + "((AllFieldsFilled(/Application/Policyholder/Iban, /Application/Payer/Iban) And "
            + "[/Application/Policyholder/Iban] == [/Application/Payer/Iban]) Or "
            + "NoFieldFilled(/Application/Policyholder/Iban, /Application/Payer/Iban))))";

    private static String model() {
        return DocumentModelDraft.named("application").locale("en_US")
                .rootGroup("Application", a -> a
                        .field("PolicyholderIsOrganization", FieldKind.STRING)
                        .field("OrgName", FieldKind.STRING)
                        .field("PersonName", FieldKind.STRING)
                        .field("PaidByThirdParty", FieldKind.STRING)
                        .group("BrokerSubmission", g -> g.field("Submitted", FieldKind.STRING))
                        .group("Schedule", g -> g.field("Name", FieldKind.STRING)
                                .field("Zip", FieldKind.STRING).field("City", FieldKind.STRING))
                        .group("Correspondence", g -> g.field("Zip", FieldKind.STRING).field("City", FieldKind.STRING))
                        .group("Residence", g -> g.field("Zip", FieldKind.STRING).field("City", FieldKind.STRING))
                        .group("Payer", g -> g.field("Name", FieldKind.STRING).field("Iban", FieldKind.STRING))
                        .group("ThirdParty", g -> g.field("Name", FieldKind.STRING).field("Iban", FieldKind.STRING))
                        .group("Policyholder", g -> g.field("Name", FieldKind.STRING).field("Iban", FieldKind.STRING)))
                .build().toJson();
    }
}
