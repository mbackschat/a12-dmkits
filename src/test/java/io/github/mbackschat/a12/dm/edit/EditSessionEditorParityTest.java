package io.github.mbackschat.a12.dm.edit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * F6 — ONE reflective guardian over the {@link EditSession}↔editor surface (DEV-API-GAPS §2). The ~26 session
 * verbs and the editor mutators had <b>zero direct tests</b>, and that is exactly where the derived guards did
 * not reach — so an editor could silently drift from (or lag) the session. Two invariants, both DERIVED by
 * reflection (no per-method {@code @Test}), so a NEW editor method or a dropped session verb trips the net:
 *
 * <ol>
 *   <li><b>Forwarding parity</b> — every editor <em>mutator</em> (a public method that returns its own editor
 *       type, i.e. a chaining edit) forwards to an {@link EditSession} verb of the SAME name (two documented
 *       aliases where the session's name is more qualified), OR is one of the per-type field <em>builder</em>
 *       conveniences (which forward through {@code addField}). The editor invents no behavior the session
 *       lacks — the functional-core safety rule (API-GUIDE §5c). A method that is none of these must be
 *       classified here, so it cannot slip in untested.</li>
 *   <li><b>Editor completeness</b> — the element-ATTRIBUTE surface the session exposes is reachable from the
 *       applicable editor, so {@code GroupEditor}/{@code FieldEditor} never lag the session's element edits
 *       again (the F7 gap). Model-level verbs (model id/label/locale/config) and structure/refactor verbs
 *       (add/remove/rename/move) are deliberately session-only — an editor is an element-attribute handle, not
 *       a tree surgeon — so they are not required on an editor.</li>
 * </ol>
 */
class EditSessionEditorParityTest {

    /** Editor mutator → the session verb it forwards to, when the names differ (documented aliases). */
    private static final Map<String, String> FORWARD_ALIAS = Map.of(
            "setType", "setFieldType",   // FieldEditor.setType → EditSession.setFieldType
            "modify", "modifyField");    // FieldEditor.modify  → EditSession.modifyField

    /** Editor-only conveniences: the per-type field builders forward through {@code addField}, so they have no
     *  same-named session verb (by design — the session takes a built {@code Field}, the editor configures one). */
    private static final Set<String> FIELD_BUILDERS = Set.of(
            "stringField", "numberField", "enumField", "dateField", "timeField", "dateTimeField",
            "dateRangeField", "dateFragmentField", "customField");

    @Test
    void everyEditorMutatorForwardsToASessionVerbOrIsAKnownBuilder() {
        Set<String> sessionVerbs = fluentSessionVerbs();
        for (Class<?> editor : List.of(GroupEditor.class, FieldEditor.class)) {
            for (Method m : editorMutators(editor)) {
                String name = m.getName();
                boolean forwards = sessionVerbs.contains(FORWARD_ALIAS.getOrDefault(name, name))
                        || FIELD_BUILDERS.contains(name);
                assertTrue(forwards, () -> editor.getSimpleName() + "." + name + " neither forwards to a "
                        + "same-named EditSession verb nor is a per-type field builder — classify it here "
                        + "(the editor must not invent behavior the session lacks). Session verbs: " + sessionVerbs);
            }
        }
    }

    @Test
    void theEditorsExposeEveryElementAttributeSessionVerb() {
        // a FIELD is an element with type + metadata; its attribute edits must all be reachable from FieldEditor
        assertEditorHas(FieldEditor.class, "setType", "setLabel", "setAnnotation", "removeAnnotation",
                "setInternalDescription", "removeInternalDescription",
                "setExternalDescription", "removeExternalDescription", "setRequiredness", "setHelperText",
                "setGlobal", "setTransient", "setRequiredErrorMessages", "modify");
        // a GROUP carries labels/metadata + list config (repeatability, index field, sort fields)
        assertEditorHas(GroupEditor.class, "setLabel", "setAnnotation", "removeAnnotation",
                "setInternalDescription", "removeInternalDescription",
                "setExternalDescription", "removeExternalDescription", "setRepeatable", "setNonRepeatable",
                "setIndexField", "setSortFields");
        // rules and computations are both Kernel elements; neither held editor may force metadata edits
        // back through a full path on EditSession
        assertEditorHas(RuleEditor.class, "setAnnotation", "removeAnnotation",
                "setInternalDescription", "removeInternalDescription",
                "setExternalDescription", "removeExternalDescription");
        assertEditorHas(ComputationEditor.class, "setAnnotation", "removeAnnotation",
                "setInternalDescription", "removeInternalDescription",
                "setExternalDescription", "removeExternalDescription");
    }

    // ── reflection helpers ────────────────────────────────────────────────────────────────────────────

    /** The session's fluent verbs — public instance methods returning {@link EditSession} (the chaining edits)
     *  or {@code String} (the one edit that yields a sub-model, {@code extractGroupAsInclude}). */
    private static Set<String> fluentSessionVerbs() {
        Set<String> out = new TreeSet<>();
        for (Method m : EditSession.class.getDeclaredMethods()) {
            if (isPublicInstance(m) && (m.getReturnType() == EditSession.class || m.getReturnType() == String.class)) {
                out.add(m.getName());
            }
        }
        return out;
    }

    /** An editor's mutators — public instance methods returning the editor's own type (a chaining edit); this
     *  excludes navigation (returns a ref), {@code ref()}, and the rule/computation compose peers by return type. */
    private static List<Method> editorMutators(Class<?> editor) {
        List<Method> out = new ArrayList<>();
        for (Method m : editor.getDeclaredMethods()) {
            if (isPublicInstance(m) && m.getReturnType() == editor) {
                out.add(m);
            }
        }
        return out;
    }

    private static void assertEditorHas(Class<?> editor, String... names) {
        Set<String> have = new TreeSet<>();
        for (Method m : editor.getMethods()) {
            if (isPublicInstance(m)) {
                have.add(m.getName());
            }
        }
        for (String name : names) {
            assertTrue(have.contains(name),
                    () -> editor.getSimpleName() + " lacks the element-attribute verb '" + name
                            + "' — the editor must not lag the session surface. Has: " + have);
        }
    }

    private static boolean isPublicInstance(Method m) {
        return Modifier.isPublic(m.getModifiers()) && !Modifier.isStatic(m.getModifiers())
                && !m.isSynthetic() && !m.isBridge();
    }
}
