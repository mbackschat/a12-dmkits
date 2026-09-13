package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.Fixtures;

/**
 * The declared-name laws the ALPHABET grids deliberately left open.
 *
 * <p>{@code GroupNameAlphabetLawsTest} and {@code FieldNameAlphabetLawsTest} measured which CHARACTERS each
 * carrier admits, and each states what it does not answer: the empty name, the SECOND code a digit-initial
 * name draws, an {@code xml} prefix, and the rule/computation carrier, which had no grid at all. Those are
 * this one's rows, and the portable checker's name families cite them as under-reporting today.
 *
 * <p>Own domain (the shared order fixture): {@code /Order}, {@code /Order/CustomerName}, and the fixture's
 * own first rule.
 */
class DeclaredNameShapeLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The order fixture with the element of [kind] at index 0 of the root group renamed to [name]. */
    private static String withFieldNamed(String name) {
        return rename(name, "CustomerName", null);
    }

    /** …the ROOT GROUP renamed. */
    private static String withRootGroupNamed(String name) {
        return rename(name, null, null);
    }

    /** …a NESTED group renamed, which is a third carrier and not the root one. */
    private static String withNestedGroupNamed(String name) {
        return rename(name, "ShippingAddress", "Group");
    }

    /**
     * …and a group of that name added TWO levels down, which the fixture has none of.
     *
     * <p>The depth row separates "every group below the root" from "an immediate child of the root" — two
     * accounts that the root-vs-nested pair alone cannot tell apart.
     */
    private static String withGroupNestedTwiceNamed(String name) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(Fixtures.orderJson());
            for (var element : root.path("content").path("modelRoot").path("rootGroups").get(0)
                    .path("Group").path("elements")) {
                if ("ShippingAddress".equals(element.path("name").asText()) && element.has("Group")) {
                    ObjectNode added = (ObjectNode) ((com.fasterxml.jackson.databind.node.ArrayNode)
                            element.path("Group").path("elements")).addObject();
                    added.put("type", "Group").put("id", "deep").put("name", name);
                    ObjectNode field = (ObjectNode) added.putObject("Group").putArray("elements")
                            .addObject();
                    field.put("type", "Field").put("id", "deepf").put("name", "Note");
                    field.putObject("Field").putObject("fieldType").put("type", "StringType")
                            .putObject("StringType");
                    return root.toString();
                }
            }
            throw new IllegalStateException("the fixture no longer declares the group `ShippingAddress`");
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }

    /**
     * …and a RULE of that name added to the root group, because the fixture declares none.
     *
     * <p>Adding one is the only way to reach this carrier at all, and it is the carrier the alphabet grids
     * skipped: a rule's name has no measured grid, so the checker asks it nothing today.
     */
    private static String withRuleNamed(String name) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(Fixtures.orderJson());
            ObjectNode rule = (ObjectNode) ((com.fasterxml.jackson.databind.node.ArrayNode)
                    root.path("content").path("modelRoot").path("rootGroups").get(0)
                            .path("Group").path("elements")).addObject();
            rule.put("type", "Rule").put("id", "rname").put("name", name);
            ObjectNode body = rule.putObject("Rule");
            body.put("errorCondition", "FieldFilled(CustomerName)")
                    .put("errorEntityRelPath", "../CustomerName")
                    .put("errorCode", "C1");
            body.putArray("errorMessage").addObject().put("locale", "en_US").put("text", "required");
            return root.toString();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }

    private static String rename(String name, String fieldNamed, String kind) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(Fixtures.orderJson());
            ObjectNode rootGroup = (ObjectNode) root.path("content").path("modelRoot")
                    .path("rootGroups").get(0);
            if (fieldNamed == null && kind == null) {
                rootGroup.put("name", name);
                return root.toString();
            }
            for (var element : rootGroup.path("Group").path("elements")) {
                boolean matches = fieldNamed.equals(element.path("name").asText())
                        && element.has(kind == null ? "Field" : kind);
                if (matches) {
                    ((ObjectNode) element).put("name", name);
                    return root.toString();
                }
            }
            throw new IllegalStateException("the fixture no longer carries the element this row renames");
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }

    @Test
    void whatA12SaysAboutAFieldsOwnNameShape() {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("an ordinary name — the control", withFieldNamed("CustomerReference"));
        rows.put("an EMPTY name", withFieldNamed(""));
        rows.put("a BLANK name", withFieldNamed("   "));
        rows.put("a name starting with a digit", withFieldNamed("1Customer"));
        rows.put("a name starting with `xml`", withFieldNamed("xmlCustomer"));
        rows.put("a name starting with `XML` in capitals", withFieldNamed("XMLCustomer"));
        rows.put("a name merely CONTAINING xml", withFieldNamed("CustomerXmlId"));

        Map<String, String> actual = new LinkedHashMap<>();
        rows.forEach((row, model) -> actual.put(row, KernelLaws.verdictOf(model)));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("an ordinary name — the control", KernelLaws.ADMITTED);
        // `MVK_NAME_MISSING` fires for neither: an unnamed element stops A12 BUILDING the model, so there
        // is no consistency verdict about it and no code for a checker to reproduce.
        expected.put("an EMPTY name", KernelLaws.NOT_BUILDABLE);
        expected.put("a BLANK name", KernelLaws.NOT_BUILDABLE);
        expected.put("a name starting with a digit",
                "MVK_PATH_INVALID,MVK_NAME_STARTS_WITH_DIGIT_A12_DV");
        expected.put("a name starting with `xml`", "MVK_NAME_STARTS_WITH_XML_A12_DV");
        expected.put("a name starting with `XML` in capitals", "MVK_NAME_STARTS_WITH_XML_A12_DV");
        expected.put("a name merely CONTAINING xml", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "what A12 says about a field's own name shape");
    }

    /**
     * The same shapes on the three carriers beside a field — and the codes are NOT the same.
     *
     * <p>The carrier is the axis a single-carrier grid holds constant without noticing, and it moves three
     * separate things at once. <b>The code SPELLING splits field-vs-group:</b> a field's name draws the
     * {@code _A12_DV}-suffixed codes, both group carriers draw the unsuffixed ones, so a checker that
     * learned the law on a field states a code A12 never emits for a group and vice versa. <b>Whether a
     * digit-initial name draws a name code at all splits root-vs-nested:</b> the root group draws
     * {@code MVK_NAME_STARTS_WITH_DIGIT} with no path code, the nested group draws {@code MVK_PATH_INVALID}
     * with no name code, and the field draws both. <b>And the empty name splits three ways</b> — a refusal
     * on a field and a nested group, an untyped raise on the root group, a refusal on a rule.
     *
     * <p>No mechanism is claimed for any of the three: these are the observed verdicts, and this grid holds
     * no case that separates a rival account of them.
     *
     * <p>The rule rows are the sharper half for a checker. <b>A rule's name shape is not judged at all</b> —
     * a digit-initial or dotted rule name leaves A12 unable to BUILD the model, so there is no consistency
     * verdict about it and no code for a checker to reproduce. A name family extended to rules by analogy
     * with fields would refuse on a code that cannot fire there.
     */
    @Test
    void theSameShapesOnEveryOtherCarrier() {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("a NESTED group, ordinary name — the control", withNestedGroupNamed("Delivery"));
        rows.put("a NESTED group with an EMPTY name", withNestedGroupNamed(""));
        rows.put("a NESTED group starting with a digit", withNestedGroupNamed("1Address"));
        rows.put("a NESTED group starting with `xml`", withNestedGroupNamed("xmlAddress"));
        rows.put("a NESTED group starting with `XML` in capitals", withNestedGroupNamed("XMLAddress"));
        rows.put("a NESTED group merely CONTAINING xml", withNestedGroupNamed("AddressXmlId"));
        rows.put("a ROOT group, ordinary name — the control", withRootGroupNamed("Purchase"));
        rows.put("a ROOT group with an EMPTY name", withRootGroupNamed(""));
        rows.put("a ROOT group starting with a digit", withRootGroupNamed("1Order"));
        rows.put("a ROOT group starting with `xml`", withRootGroupNamed("xmlOrder"));
        rows.put("a ROOT group starting with `XML` in capitals", withRootGroupNamed("XMLOrder"));
        rows.put("a RULE, ordinary name — the control", withRuleNamed("CustomerReferencePresent"));
        rows.put("a RULE starting with `xml`", withRuleNamed("xmlRule"));
        rows.put("a RULE with an EMPTY name", withRuleNamed(""));
        rows.put("a RULE starting with a digit", withRuleNamed("1Rule"));
        rows.put("a RULE with a dot in its name", withRuleNamed("Some.Rule"));

        Map<String, String> actual = new LinkedHashMap<>();
        rows.forEach((row, model) -> actual.put(row, KernelLaws.verdictOf(model)));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a NESTED group, ordinary name — the control", KernelLaws.ADMITTED);
        expected.put("a NESTED group with an EMPTY name", KernelLaws.NOT_BUILDABLE);
        // The field row of the grid above draws BOTH codes for this shape; this carrier draws only the path
        // one, so `MVK_NAME_STARTS_WITH_DIGIT` in either spelling is not what a digit-initial group name is
        // reported as here.
        expected.put("a NESTED group starting with a digit", "MVK_PATH_INVALID");
        expected.put("a NESTED group starting with `xml`", "MVK_NAME_STARTS_WITH_XML");
        expected.put("a NESTED group starting with `XML` in capitals", "MVK_NAME_STARTS_WITH_XML");
        expected.put("a NESTED group merely CONTAINING xml", KernelLaws.ADMITTED);
        expected.put("a ROOT group, ordinary name — the control", KernelLaws.ADMITTED);
        // A bare RuntimeException past every typed channel — not a verdict and not a refusal.
        expected.put("a ROOT group with an EMPTY name", KernelLaws.RAISED_UNTYPED);
        // …and no `MVK_PATH_INVALID` beside it, where the two carriers below the root both draw one.
        expected.put("a ROOT group starting with a digit", "MVK_NAME_STARTS_WITH_DIGIT");
        expected.put("a ROOT group starting with `xml`", "MVK_NAME_STARTS_WITH_XML");
        expected.put("a ROOT group starting with `XML` in capitals", "MVK_NAME_STARTS_WITH_XML");
        expected.put("a RULE, ordinary name — the control", KernelLaws.ADMITTED);
        expected.put("a RULE starting with `xml`", KernelLaws.ADMITTED);
        expected.put("a RULE with an EMPTY name", KernelLaws.NOT_BUILDABLE);
        expected.put("a RULE starting with a digit", KernelLaws.NOT_BUILDABLE);
        expected.put("a RULE with a dot in its name", KernelLaws.NOT_BUILDABLE);
        assertEquals(expected, actual, "the same name shapes on every carrier beside a field");
    }

    /**
     * The group law the portable checker applies, re-measured on the ROOT carrier it was never measured on.
     *
     * <p>{@code GroupNameAlphabetLawsTest} measured every one of its rows on {@code Slots}, a group NESTED
     * under {@code /Bin} — one constant across the whole set, invisible from inside it, and the checker's
     * group branch applies the result to every group. The row that makes this worth running is the leading
     * underscore: it is the one group law that REFUSES a name the kernel's own message calls legal, so if the
     * root carrier admits it the checker rejects a legal model, which is the one direction it may not take.
     *
     * <p>It does admit it. <b>The leading-underscore gate is a law of groups BELOW the root and of no other
     * carrier</b>: {@code _Orders} and {@code __other} are both taken as root-group names and both refused
     * one level down, so a checker applying the nested measurement to every group refuses legal models. That
     * defect was live here until this grid was run, and it is why the row exists.
     *
     * <p>The alphabet rows transfer exactly — a hyphen, a space and a colon draw {@code MVK_PATH_INVALID} on
     * both carriers, the separator draws {@code MVK_PATHS_INCONSISTENT} on both, and a non-ASCII letter is
     * admitted on both. So the carrier does not move the law uniformly, and which rows it moves cannot be
     * predicted from the ones it leaves alone.
     */
    @Test
    void theGroupLawsOnTheRootCarrierTheyWereNotMeasuredOn() {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("an ordinary name — the control", withRootGroupNamed("Purchase"));
        rows.put("a leading underscore", withRootGroupNamed("_Orders"));
        rows.put("`__meta` — the reserved exemption", withRootGroupNamed("__meta"));
        rows.put("`__other` — reserved-looking and not reserved", withRootGroupNamed("__other"));
        rows.put("an interior underscore", withRootGroupNamed("Order_List"));
        rows.put("a non-ASCII letter", withRootGroupNamed("Ördner"));
        rows.put("a hyphen", withRootGroupNamed("Order-List"));
        rows.put("a space", withRootGroupNamed("Order List"));
        rows.put("a colon", withRootGroupNamed("Order:List"));
        rows.put("the path separator", withRootGroupNamed("Order/List"));
        rows.put("60 characters — the stated limit", withRootGroupNamed("A".repeat(60)));
        rows.put("61 characters — one over", withRootGroupNamed("A".repeat(61)));
        rows.put("a leading underscore ONE level down — the same-harness control",
                withNestedGroupNamed("_Address"));
        rows.put("a leading underscore TWO levels down — the depth control",
                withGroupNestedTwiceNamed("_Notes"));
        rows.put("an ordinary name two levels down — its own control", withGroupNestedTwiceNamed("Notes"));

        Map<String, String> actual = new LinkedHashMap<>();
        rows.forEach((row, model) -> actual.put(row, KernelLaws.verdictOf(model)));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("an ordinary name — the control", KernelLaws.ADMITTED);
        // The two rows the nested carrier refuses, and the reason this grid exists.
        expected.put("a leading underscore", KernelLaws.ADMITTED);
        expected.put("`__meta` — the reserved exemption", KernelLaws.ADMITTED);
        expected.put("`__other` — reserved-looking and not reserved", KernelLaws.ADMITTED);
        expected.put("an interior underscore", KernelLaws.ADMITTED);
        expected.put("a non-ASCII letter", KernelLaws.ADMITTED);
        expected.put("a hyphen", "MVK_PATH_INVALID");
        expected.put("a space", "MVK_PATH_INVALID");
        expected.put("a colon", "MVK_PATH_INVALID");
        expected.put("the path separator", "MVK_PATHS_INCONSISTENT");
        expected.put("60 characters — the stated limit", KernelLaws.ADMITTED);
        // The 60-character limit is the group carrier's alone and holds here too — refused with no code on
        // either carrier, so there is nothing for a checker to reproduce and it under-reports the row.
        expected.put("61 characters — one over", KernelLaws.NOT_BUILDABLE);
        expected.put("a leading underscore ONE level down — the same-harness control",
                "NAME_STARTS_WITH_UNDERSCORE_AND_WRONG_PATTERN");
        expected.put("a leading underscore TWO levels down — the depth control",
                "NAME_STARTS_WITH_UNDERSCORE_AND_WRONG_PATTERN");
        expected.put("an ordinary name two levels down — its own control", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the nested carrier's group laws, measured on the root carrier");
    }
}
