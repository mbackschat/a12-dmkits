package io.github.mbackschat.a12.dm.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Public-API surface lock for the five published Java kits — a golden-diff <em>visibility</em> guard
 * (Set S; {@code docs/RELEASE-SPEC.md} §11, {@code docs/TESTING-SPEC.md} "structural-law guardians").
 *
 * <p>The kits publish to Maven ({@code io.github.mbackschat.a12.dm:dmkits-*}); once a consumer builds against
 * them, a change to a public signature is a change to their expectations. At 0.x a break is <em>allowed</em>,
 * so this is <b>not</b> a japicmp-style "fail on any incompatibility" gate — it is the project's established
 * golden-guard pattern ({@code DocSnippetFreshnessTest}, the schema {@code examples}): the committed golden is
 * the reviewed truth, the guard fails on any drift, and the author regenerates deliberately so the change
 * surfaces as a reviewable diff. {@link PublicApiDumper} produces the dump; this class asserts and, under
 * {@code -Dapi.fix=true} ({@code ./gradlew dumpKitApi}), regenerates. Verifier and fixer share one dump path,
 * so they cannot disagree; CI never runs the fixer, so a forgotten regenerate still fails the build.
 *
 * <p>Alongside the golden diff, {@link #noThirdPartyTypeOnAnyPublishedSignature()} enforces the one HARD
 * surface rule (Set W): no third-party type on any public/protected signature — the golden makes a change
 * visible, the rule makes the whole leak class unshippable.
 *
 * <p><b>Placement.</b> This guard reads the committed goldens under {@code api/} at runtime, so it runs in the
 * dedicated {@code apiGuardTest} task with {@code api/} declared as <em>its</em> inputs (never on the broad
 * suite) — a golden edit re-runs only this guard and can't restore from the build cache into a false green
 * (TESTING-SPEC "Tests that read repo files at runtime"). A kit's public API changing invalidates the task via
 * its compiled classes already on the test classpath.
 */
class PublicApiGuardTest {

    /** A published kit: its Maven artifact id (= golden file stem) and its Gradle module directory. */
    private record Kit(String artifact, String moduleDir) {}

    private static final List<Kit> KITS = List.of(
            new Kit("dmkits-core", "core"),
            new Kit("dmkits-rulekit", "rulekit"),
            new Kit("dmkits-modelkit", "modelkit"),
            new Kit("dmkits-edit", "edit"),
            new Kit("dmkits-schemakit", "schemakit"));

    @Test
    @DisabledIfSystemProperty(named = "api.fix", matches = "true")
    void everyKitPublicApiMatchesItsGolden() throws IOException {
        for (Kit k : KITS) {
            Path golden = goldenFile(k);
            assertTrue(Files.exists(golden), () ->
                    "missing API golden " + repoRoot().relativize(golden) + " — generate it once with "
                    + "`./gradlew dumpKitApi`, then commit it.");
            String live = PublicApiDumper.dump(mainClasses(k), classLoader());
            String committed = Files.readString(golden);
            assertEquals(committed, live, () ->
                    "The public API of " + k.artifact() + " has DRIFTED from its golden "
                    + repoRoot().relativize(golden) + ".\n"
                    + "At 0.x a public-surface change is allowed, but it must be DELIBERATE and REVIEWED: "
                    + "regenerate the goldens with `./gradlew dumpKitApi` and review the resulting diff in the "
                    + "same change (this is a visibility guard, not a no-break promise — docs/RELEASE-SPEC.md §11).");
        }
    }

    /**
     * Determinism is the whole game: a flaky dump makes the guard noise. Dumping twice in one JVM catches the
     * common non-determinism (unsorted reflection iteration); the regenerate-twice-diff check covers the rest.
     */
    @Test
    @DisabledIfSystemProperty(named = "api.fix", matches = "true")
    void theDumperIsDeterministic() {
        for (Kit k : KITS) {
            String first = PublicApiDumper.dump(mainClasses(k), classLoader());
            String second = PublicApiDumper.dump(mainClasses(k), classLoader());
            assertEquals(first, second, () ->
                    "PublicApiDumper is non-deterministic for " + k.artifact()
                    + " — two dumps of the same classes differ, so the golden guard would be flaky.");
        }
    }

    /**
     * Signature-type prefixes a Maven-Central consumer resolves with NO extra dependency: the JDK and our own
     * kits. Everything else on a public signature forces a third-party compile dependency the POM does not
     * advertise (the kits keep Jackson {@code implementation}-scoped).
     */
    private static final List<String> ALLOWED_TYPE_PREFIXES = List.of("java.", "javax.", "io.github.mbackschat.");

    /**
     * The <b>class</b> guard behind the Set W cleanup (root-cause generalization of "Jackson leaks on public
     * kit signatures"): no {@code public}/{@code protected} signature of any published kit may reference a
     * type outside {@code java.*}/{@code javax.*}/{@code io.github.mbackschat.*} — supertypes, interfaces,
     * type-parameter bounds, field/parameter/return/exception types, through generics/arrays/wildcards. Unlike
     * the golden diff (which shows any drift but relies on review to spot a leak), this fails the build on the
     * whole class of defect, not just the Jackson instance.
     */
    @Test
    @DisabledIfSystemProperty(named = "api.fix", matches = "true")
    void noThirdPartyTypeOnAnyPublishedSignature() {
        List<String> violations = new ArrayList<>();
        for (Kit k : KITS) {
            for (Class<?> type : PublicApiDumper.apiTypes(mainClasses(k), classLoader())) {
                collectForeignReferences(k, type, violations);
            }
        }
        assertTrue(violations.isEmpty(), () ->
                "Third-party types on published kit signatures (a consumer would need an undeclared compile "
                + "dependency — Set W keeps them implementation-scoped and invisible; re-type or hide the seam, "
                + "never expose the vendor type):\n  " + String.join("\n  ", violations));
    }

    /** Every foreign type referenced by {@code type}'s API-visible declarations, appended as violations. */
    private static void collectForeignReferences(Kit kit, Class<?> type, List<String> violations) {
        Set<Class<?>> supers = flatten(type.getGenericInterfaces());
        supers.addAll(flatten(type.getGenericSuperclass()));
        check(kit, type, "extends/implements", violations, supers);
        check(kit, type, "type parameters", violations, boundsOf(type.getTypeParameters()));
        for (Field f : type.getDeclaredFields()) {
            if (isApi(f.getModifiers()) && !f.isSynthetic()) {
                check(kit, type, "field " + f.getName(), violations, flatten(f.getGenericType()));
            }
        }
        for (Constructor<?> c : type.getDeclaredConstructors()) {
            if (isApi(c.getModifiers()) && !c.isSynthetic()) {
                check(kit, type, "constructor", violations, executableTypes(c));
            }
        }
        for (Method m : type.getDeclaredMethods()) {
            if (isApi(m.getModifiers()) && !m.isSynthetic() && !m.isBridge()) {
                Set<Class<?>> referenced = executableTypes(m);
                referenced.addAll(flatten(m.getGenericReturnType()));
                check(kit, type, "method " + m.getName(), violations, referenced);
            }
        }
    }

    private static boolean isApi(int mods) {
        return Modifier.isPublic(mods) || Modifier.isProtected(mods);
    }

    /** Parameter + exception types and type-parameter bounds of one constructor/method. */
    private static Set<Class<?>> executableTypes(Executable e) {
        Set<Class<?>> out = flatten(e.getGenericParameterTypes());
        out.addAll(flatten(e.getGenericExceptionTypes()));
        out.addAll(boundsOf(e.getTypeParameters()));
        return out;
    }

    private static Set<Class<?>> boundsOf(TypeVariable<?>[] vars) {
        Set<Class<?>> out = new LinkedHashSet<>();
        for (TypeVariable<?> v : vars) {
            out.addAll(flatten(v.getBounds()));
        }
        return out;
    }

    private static void check(Kit kit, Class<?> type, String where, List<String> violations, Set<Class<?>> referenced) {
        for (Class<?> c : referenced) {
            if (ALLOWED_TYPE_PREFIXES.stream().noneMatch(c.getName()::startsWith)) {
                violations.add(kit.artifact() + ": " + type.getName() + " — " + where + " references " + c.getName());
            }
        }
    }

    private static Set<Class<?>> flatten(Type... types) {
        Set<Class<?>> out = new LinkedHashSet<>();
        for (Type t : types) {
            flattenInto(t, out);
        }
        return out;
    }

    /**
     * Every raw class a generic {@link Type} mentions (through parameterized args, arrays, wildcard bounds).
     * A {@link TypeVariable} is terminal here — its bounds are checked once at the declaration site
     * ({@link #boundsOf}), which also keeps self-referential bounds ({@code T extends Comparable<T>}) finite.
     */
    private static void flattenInto(Type t, Set<Class<?>> out) {
        switch (t) {
            case null -> { }
            case Class<?> c -> {
                Class<?> base = c;
                while (base.isArray()) {
                    base = base.getComponentType();
                }
                if (!base.isPrimitive()) {
                    out.add(base);
                }
            }
            case ParameterizedType p -> {
                flattenInto(p.getRawType(), out);
                flatten(p.getActualTypeArguments()).forEach(out::add);
            }
            case GenericArrayType g -> flattenInto(g.getGenericComponentType(), out);
            case WildcardType w -> {
                flatten(w.getUpperBounds()).forEach(out::add);
                flatten(w.getLowerBounds()).forEach(out::add);
            }
            case TypeVariable<?> v -> { }
            default -> throw new IllegalStateException("unhandled Type kind on a published signature: " + t);
        }
    }

    /**
     * The autonomous fixer — only under {@code -Dapi.fix=true} (i.e. {@code ./gradlew dumpKitApi}). Rewrites
     * every kit golden in place from the live dump; never runs in CI (so a forgotten regenerate still fails).
     */
    @Test
    @EnabledIfSystemProperty(named = "api.fix", matches = "true")
    void regenerateGoldens() throws IOException {
        Path dir = repoRoot().resolve("api");
        Files.createDirectories(dir);
        for (Kit k : KITS) {
            Files.writeString(goldenFile(k), PublicApiDumper.dump(mainClasses(k), classLoader()));
        }
    }

    private static Path goldenFile(Kit k) {
        return repoRoot().resolve("api").resolve(k.artifact() + ".api");
    }

    private static Path mainClasses(Kit k) {
        return repoRoot().resolve(k.moduleDir()).resolve("build/classes/java/main");
    }

    private static ClassLoader classLoader() {
        return PublicApiGuardTest.class.getClassLoader();
    }

    /** The repo root — the nearest ancestor holding {@code settings.gradle} (robust to the module working dir). */
    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("settings.gradle"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("repo root (settings.gradle) not found from " + Path.of("").toAbsolutePath());
        }
        return p;
    }
}
