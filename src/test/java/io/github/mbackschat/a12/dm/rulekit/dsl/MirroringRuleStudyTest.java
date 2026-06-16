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

/**
 * The §3.6 STUDY — a real, highly-repetitive field-mirroring rule rebuilt from the efficiency primitives.
 * The rule: an "effective" address block must mirror one of several source blocks (null-safe: both-empty
 * counts as equal), the source chosen by a 2×2 of flags. Hand-written it is ~40 lines of copy-pasted
 * {@code (AllFieldsFilled(a,b) And [a]==[b]) Or NoFieldFilled(a,b)}; here it is ~15 lines that <em>render to
 * exactly that</em> — the assertion below pins the rendered output, so the rebuild is provably faithful.
 */
class MirroringRuleStudyTest {

    @Test
    void aRepetitiveMirroringRuleIsRebuiltFromTheEfficiencyPrimitives() {
        BoundModel m = BoundModel.ofJson(model());

        // tag::study-mirroring[]
        // the fixed target block — the "effective" address (mirrored in every branch)
        List<StringRef> target = List.of(m.str("/Invoice/Effective/Name"),
                m.str("/Invoice/Effective/Zip"), m.str("/Invoice/Effective/City"));

        // the two binary axes that SELECT the source block
        StringRef preferred = m.str("/Invoice/PreferredName");
        List<FieldRef> poBox = List.of(m.field("/Invoice/PoBox/Zip"), m.field("/Invoice/PoBox/City"));

        // assemble the source block from the two axis choices (primary name? PO-box in use?)
        BiFunction<Boolean, Boolean, List<StringRef>> sources = (primary, useExtras) -> List.of(
                primary ? preferred : m.str("/Invoice/LegalName"),
                useExtras ? m.str("/Invoice/PoBox/Zip") : m.str("/Invoice/Street/Zip"),
                useExtras ? m.str("/Invoice/PoBox/City") : m.str("/Invoice/Street/City"));

        // every target mirrors its source, null-safe — the inner 3× repetition collapses to a zip + fold
        BiFunction<List<StringRef>, List<StringRef>, Condition> mirrors = (src, tgt) -> allOf(
                IntStream.range(0, tgt.size()).mapToObj(i -> src.get(i).agreesWith(tgt.get(i))).toList());

        // the 4 branches ARE the 2×2 cross-product — a loop, not four copy-pasted blocks
        List<Condition> branches = new ArrayList<>();
        for (boolean primary : new boolean[] {true, false}) {
            for (boolean useExtras : new boolean[] {true, false}) {
                Condition selector = (primary ? preferred.filled() : preferred.notFilled())
                        .and(useExtras ? atLeastOneFieldFilled(poBox) : noFieldFilled(poBox));
                branches.add(selector.and(mirrors.apply(sources.apply(primary, useExtras), target)));
            }
        }

        Condition rule = m.group("/Invoice/DigitalChannel").filled()
                .and(atLeastOneFieldFilled(target.stream().map(StringRef::field).toList()))
                .and(anyOf(branches));
        // end::study-mirroring[]

        assertEquals(EXPECTED, new DslRenderer().render(rule));
    }

    /** The rendered output the ~15-line build above produces — the repetitive form you'd otherwise hand-write. */
    private static final String EXPECTED =
            "GroupFilled(/Invoice/DigitalChannel) And AtLeastOneFieldFilled(/Invoice/Effective/Name, "
            + "/Invoice/Effective/Zip, /Invoice/Effective/City) And ("
            + "(FieldFilled(/Invoice/PreferredName) And AtLeastOneFieldFilled(/Invoice/PoBox/Zip, /Invoice/PoBox/City) "
            + "And ((AllFieldsFilled(/Invoice/PreferredName, /Invoice/Effective/Name) And [/Invoice/PreferredName] == "
            + "[/Invoice/Effective/Name]) Or NoFieldFilled(/Invoice/PreferredName, /Invoice/Effective/Name)) And "
            + "((AllFieldsFilled(/Invoice/PoBox/Zip, /Invoice/Effective/Zip) And [/Invoice/PoBox/Zip] == "
            + "[/Invoice/Effective/Zip]) Or NoFieldFilled(/Invoice/PoBox/Zip, /Invoice/Effective/Zip)) And "
            + "((AllFieldsFilled(/Invoice/PoBox/City, /Invoice/Effective/City) And [/Invoice/PoBox/City] == "
            + "[/Invoice/Effective/City]) Or NoFieldFilled(/Invoice/PoBox/City, /Invoice/Effective/City)))"
            + " Or (FieldFilled(/Invoice/PreferredName) And NoFieldFilled(/Invoice/PoBox/Zip, /Invoice/PoBox/City) "
            + "And ((AllFieldsFilled(/Invoice/PreferredName, /Invoice/Effective/Name) And [/Invoice/PreferredName] == "
            + "[/Invoice/Effective/Name]) Or NoFieldFilled(/Invoice/PreferredName, /Invoice/Effective/Name)) And "
            + "((AllFieldsFilled(/Invoice/Street/Zip, /Invoice/Effective/Zip) And [/Invoice/Street/Zip] == "
            + "[/Invoice/Effective/Zip]) Or NoFieldFilled(/Invoice/Street/Zip, /Invoice/Effective/Zip)) And "
            + "((AllFieldsFilled(/Invoice/Street/City, /Invoice/Effective/City) And [/Invoice/Street/City] == "
            + "[/Invoice/Effective/City]) Or NoFieldFilled(/Invoice/Street/City, /Invoice/Effective/City)))"
            + " Or (FieldNotFilled(/Invoice/PreferredName) And AtLeastOneFieldFilled(/Invoice/PoBox/Zip, "
            + "/Invoice/PoBox/City) And ((AllFieldsFilled(/Invoice/LegalName, /Invoice/Effective/Name) And "
            + "[/Invoice/LegalName] == [/Invoice/Effective/Name]) Or NoFieldFilled(/Invoice/LegalName, "
            + "/Invoice/Effective/Name)) And ((AllFieldsFilled(/Invoice/PoBox/Zip, /Invoice/Effective/Zip) And "
            + "[/Invoice/PoBox/Zip] == [/Invoice/Effective/Zip]) Or NoFieldFilled(/Invoice/PoBox/Zip, "
            + "/Invoice/Effective/Zip)) And ((AllFieldsFilled(/Invoice/PoBox/City, /Invoice/Effective/City) And "
            + "[/Invoice/PoBox/City] == [/Invoice/Effective/City]) Or NoFieldFilled(/Invoice/PoBox/City, "
            + "/Invoice/Effective/City)))"
            + " Or (FieldNotFilled(/Invoice/PreferredName) And NoFieldFilled(/Invoice/PoBox/Zip, /Invoice/PoBox/City) "
            + "And ((AllFieldsFilled(/Invoice/LegalName, /Invoice/Effective/Name) And [/Invoice/LegalName] == "
            + "[/Invoice/Effective/Name]) Or NoFieldFilled(/Invoice/LegalName, /Invoice/Effective/Name)) And "
            + "((AllFieldsFilled(/Invoice/Street/Zip, /Invoice/Effective/Zip) And [/Invoice/Street/Zip] == "
            + "[/Invoice/Effective/Zip]) Or NoFieldFilled(/Invoice/Street/Zip, /Invoice/Effective/Zip)) And "
            + "((AllFieldsFilled(/Invoice/Street/City, /Invoice/Effective/City) And [/Invoice/Street/City] == "
            + "[/Invoice/Effective/City]) Or NoFieldFilled(/Invoice/Street/City, /Invoice/Effective/City)))"
            + ")";

    private static String model() {
        return DocumentModelDraft.named("invoice").locale("en_US")
                .rootGroup("Invoice", inv -> inv
                        .field("PreferredName", FieldKind.STRING)
                        .field("LegalName", FieldKind.STRING)
                        .group("Effective", e -> e.field("Name", FieldKind.STRING)
                                .field("Zip", FieldKind.STRING).field("City", FieldKind.STRING))
                        .group("Street", s -> s.field("Zip", FieldKind.STRING).field("City", FieldKind.STRING))
                        .group("PoBox", p -> p.field("Zip", FieldKind.STRING).field("City", FieldKind.STRING))
                        .group("DigitalChannel", d -> d.field("Active", FieldKind.STRING)))
                .build().toJson();
    }
}
