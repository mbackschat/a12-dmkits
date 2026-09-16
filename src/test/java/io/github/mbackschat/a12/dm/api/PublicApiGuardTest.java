package io.github.mbackschat.a12.dm.api;

import io.github.mbackschat.a12.dm.Checkout;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.Map;
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

    private static final Checkout CHECKOUT = Checkout.locate();

    /** A published artifact: its Maven artifact id (= golden file stem) and its Gradle module directory. */
    private record Kit(String artifact, String moduleDir) { }

    private static final List<Kit> KITS = List.of(
            new Kit("dmkits-core", "core"),
            new Kit("dmkits-rulekit", "rulekit"),
            new Kit("dmkits-modelkit", "modelkit"),
            new Kit("dmkits-edit", "edit"),
            new Kit("dmkits-schemakit", "schemakit"),
            // The read-back provider is published but is NOT on this suite's runtime classpath, and that is
            // deliberate: read-back REFUSES a classpath carrying two providers, so registering one beside
            // :adapter's would fail every kernel-oracle test here on resolution rather than on its subject.
            // It is loaded for the dump alone, by classLoader below.
            new Kit("dmkits-interpreter-bridge", "interpreter-bridge"),
            new Kit("dmkits-consistency-gate", "consistency-gate"),
            new Kit("dmkits-interpreter-preflight", "interpreter-preflight"));

    @Test
    @DisabledIfSystemProperty(named = "api.fix", matches = "true")
    void everyKitPublicApiMatchesItsGolden() throws IOException {
        for (Kit k : KITS) {
            Path golden = goldenFile(k);
            assertTrue(Files.exists(golden), () ->
                    "missing API golden " + CHECKOUT.relative(golden) + " — generate it once with "
                    + "`./gradlew dumpKitApi`, then commit it.");
            String live = PublicApiDumper.dump(mainClasses(k), classLoader(k));
            String committed = Files.readString(golden);
            assertEquals(committed, live, () ->
                    "The public API of " + k.artifact() + " has DRIFTED from its golden "
                    + CHECKOUT.relative(golden) + ".\n"
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
            String first = PublicApiDumper.dump(mainClasses(k), classLoader(k));
            String second = PublicApiDumper.dump(mainClasses(k), classLoader(k));
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
    private static final List<String> ALLOWED_TYPE_PREFIXES = List.of("java.", "javax.",
            "io.github.mbackschat.a12.dm.core.",
            "io.github.mbackschat.a12.dm.rulekit.",
            "io.github.mbackschat.a12.dm.modelkit.",
            "io.github.mbackschat.a12.dm.edit.",
            "io.github.mbackschat.a12.dm.schemakit.",
            "io.github.mbackschat.a12.dm.bridge.",
            "io.github.mbackschat.a12.dm.gate.",
            "io.github.mbackschat.a12.dm.preflight.");

    /**
     * <b>Signatures that reference one of OUR OTHER publications rather than a kit, kept as a named ratchet.</b>
     * The blanket {@code io.github.mbackschat.} prefix this list replaced passed the letter of the rule while
     * missing its purpose: {@code dmkits-interpreter-bridge} declares {@code dmkits-interpreter}
     * {@code runtime}-scoped, so a consumer compiling against a bridge signature that mentions
     * {@code dm.interpreter.ast.Expr} does NOT have that type on their compile classpath — exactly the leak the
     * rule exists to stop, one prefix short of being caught (DEV-API-GAPS {@code DG45}).
     *
     * <p>It is an exemption rather than a fix because the classes are Kotlin {@code internal} and public only
     * by JVM accident, so nothing is advertised to a consumer that a Kotlin one can even see. It is a RATCHET:
     * {@link #everyForeignSignatureExemptionIsStillNeeded()} fails when an entry stops matching, so a leak
     * that gets fixed cannot leave its licence behind, and a NEW leak in another package is not covered by it.
     */
    private static final Map<String, String> FOREIGN_SIGNATURE_EXEMPTIONS = Map.of(
            "dmkits-interpreter-bridge", "io.github.mbackschat.a12.dm.interpreter.");

    /**
     * <b>Per-artifact prefixes that artifact's OWN POM advertises at {@code api} scope</b> — so a consumer
     * compiling against such a signature resolves the type with no extra dependency, which is the rule's
     * actual test rather than a licence against it.
     *
     * <p>Kept apart from {@link #FOREIGN_SIGNATURE_EXEMPTIONS} because the two are opposite findings that
     * would read identically in one map. An exemption excuses a LEAK: the bridge declares
     * {@code dmkits-interpreter} at {@code runtime} scope, so a consumer does not get those types and the
     * signature is the defect the rule exists to catch. This map records the case where there is no leak —
     * {@code dmkits-consistency-gate} declares {@code a12-consistency} at {@code api} scope on purpose,
     * because the checker's own report IS what the façade returns, and restating that report in a second
     * shape is the one outcome worse than naming its type.
     *
     * <p>Ratcheted by {@link #everyForeignSignatureExemptionIsStillNeeded()} exactly as the exemptions are:
     * an allowance that stops matching a live signature is deleted, never left standing.
     */
    private static final Map<String, List<String>> API_SCOPED_PUBLICATIONS = Map.of(
            "dmkits-consistency-gate", List.of("io.github.mbackschat.a12.dm.consistency."),
            // Both of this façade's dependencies are `api`-scoped, and both have to be: it takes the
            // interpreter's own source type so a consumer needs no second representation of their model, and
            // it returns the checker's report because the coverage evidence is the half that says how much
            // weight its answer bears.
            "dmkits-interpreter-preflight", List.of("io.github.mbackschat.a12.dm.consistency.",
                    "io.github.mbackschat.a12.dm.interpreter."));

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
            for (Class<?> type : PublicApiDumper.apiTypes(mainClasses(k), classLoader(k))) {
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
            if (!isAllowed(kit, c)) {
                violations.add(kit.artifact() + ": " + type.getName() + " — " + where + " references " + c.getName());
            }
        }
    }

    /** A type a consumer of {@code kit} resolves with no extra compile dependency, or its named exemption. */
    private static boolean isAllowed(Kit kit, Class<?> c) {
        if (ALLOWED_TYPE_PREFIXES.stream().anyMatch(c.getName()::startsWith)) {
            return true;
        }
        List<String> advertised = API_SCOPED_PUBLICATIONS.get(kit.artifact());
        if (advertised != null && advertised.stream().anyMatch(c.getName()::startsWith)) {
            return true;
        }
        String exempt = FOREIGN_SIGNATURE_EXEMPTIONS.get(kit.artifact());
        return exempt != null && c.getName().startsWith(exempt);
    }

    /**
     * The ratchet's own control: every exemption above must still be MATCHED by a live signature. Without it
     * an exemption outlives the leak it excuses and silently re-opens the hole for the next type in that
     * package — the failure mode the blanket prefix already demonstrated once.
     */
    @Test
    @DisabledIfSystemProperty(named = "api.fix", matches = "true")
    void everyForeignSignatureExemptionIsStillNeeded() {
        Map<String, String> allowances = new java.util.LinkedHashMap<>(FOREIGN_SIGNATURE_EXEMPTIONS);
        API_SCOPED_PUBLICATIONS.forEach((artifact, prefixes) ->
                prefixes.forEach(prefix -> allowances.put(artifact + " " + prefix, prefix)));
        for (Map.Entry<String, String> exemption : allowances.entrySet()) {
            // The key is the artifact for an exemption and "<artifact> <prefix>" for an api-scoped allowance,
            // because one artifact may advertise several packages and each needs its own live-signature check.
            String artifact = exemption.getKey().split(" ")[0];
            Kit kit = KITS.stream().filter(k -> k.artifact().equals(artifact)).findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "allowance names an artifact that is not a published kit: " + artifact));
            List<String> matched = new ArrayList<>();
            for (Class<?> type : PublicApiDumper.apiTypes(mainClasses(kit), classLoader(kit))) {
                List<String> referenced = new ArrayList<>();
                collectForeignReferencesIgnoringExemptions(kit, type, referenced);
                referenced.stream().filter(v -> v.contains(exemption.getValue())).forEach(matched::add);
            }
            assertFalse(matched.isEmpty(), () ->
                    "the '" + exemption.getKey() + "' allowance for " + exemption.getValue() + " matches no live"
                    + " signature — what it covered is gone, so DELETE the entry rather than leaving a licence"
                    + " for the next type in that package");
        }
    }

    /** As {@link #collectForeignReferences}, with the exemptions off — the ratchet's measuring instrument. */
    private static void collectForeignReferencesIgnoringExemptions(
            Kit kit, Class<?> type, List<String> violations) {
        List<String> all = new ArrayList<>();
        collectForeignReferences(new Kit(kit.artifact() + " (unexempted)", kit.moduleDir()), type, all);
        violations.addAll(all);
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
        Path dir = CHECKOUT.resolve("api");
        Files.createDirectories(dir);
        for (Kit k : KITS) {
            Files.writeString(goldenFile(k), PublicApiDumper.dump(mainClasses(k), classLoader(k)));
        }
    }

    private static Path goldenFile(Kit k) {
        return CHECKOUT.resolve("api").resolve(k.artifact() + ".api");
    }

    /**
     * Every compiler output of the module. Both are offered rather than one chosen by language: a Kotlin module
     * that gains a single {@code .java} file would otherwise ship it in the jar while the golden never saw it,
     * which is the one way "a surface change turns this red" can quietly stop being true.
     */
    private static List<Path> mainClasses(Kit k) {
        Path module = CHECKOUT.resolve(k.moduleDir());
        List<Path> dirs = new ArrayList<>();
        for (Path candidate : List.of(
                module.resolve("build/classes/java/main"),
                module.resolve("build/classes/kotlin/main"))) {
            if (Files.isDirectory(candidate)) {
                dirs.add(candidate);
            }
        }
        if (dirs.isEmpty()) {
            throw new IllegalStateException("no compiled classes for " + k.artifact() + " under " + module
                    + " — the module must be compiled before the API dump, or this guard passes vacuously");
        }
        return dirs;
    }

    /**
     * A loader that can see {@code k}'s classes. Parent-first over this suite's own loader, so a kit already on
     * the test classpath resolves to exactly the same classes as before; a module that is deliberately absent
     * from it (see the bridge, above) is read straight from its compiler output, with its dependencies still
     * resolving through the parent.
     */
    private static ClassLoader classLoader(Kit k) {
        List<Path> classes = mainClasses(k);
        try {
            java.net.URL[] urls = new java.net.URL[classes.size()];
            for (int i = 0; i < classes.size(); i++) {
                urls[i] = classes.get(i).toUri().toURL();
            }
            return new java.net.URLClassLoader(urls, PublicApiGuardTest.class.getClassLoader());
        } catch (java.net.MalformedURLException e) {
            throw new IllegalStateException("not a loadable classes directory: " + classes, e);
        }
    }

}
