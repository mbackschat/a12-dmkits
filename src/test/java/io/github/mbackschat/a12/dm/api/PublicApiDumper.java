package io.github.mbackschat.a12.dm.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reflection-based public-API dumper for the published Java kits — the mechanism behind the API-surface
 * visibility lock (Set S; see {@code PublicApiGuardTest} + {@code docs/RELEASE-SPEC.md} §11).
 *
 * <p><b>What it emits.</b> Given one kit's compiled {@code build/classes/java/main} tree and a class loader
 * that can resolve it, {@link #dump} walks every <em>API-visible</em> type — a top-level {@code public} type,
 * or a {@code public}/{@code protected} member type nested (transitively) inside one — and renders each type's
 * {@code public}/{@code protected} fields, constructors and methods to a stable, sorted, one-signature-per-line
 * text form (FQ type header; members with generic-rendered types and an API-relevant modifier set). Everything
 * else — package-private/private types and members, synthetic/bridge members, anonymous/local types — is
 * omitted: it has no reach for a Maven-Central consumer, which is exactly the surface this guard freezes.
 *
 * <p><b>Determinism is the whole game.</b> Reflection returns members in an unspecified, JVM-dependent order,
 * so this class never trusts that order: types sort by binary name, members sort by a semantic key (name, then
 * signature), interfaces sort by rendered name. Implementation-only modifiers ({@code synchronized}/{@code native}/
 * {@code strictfp}/{@code volatile}/{@code transient}) and compiler-chosen enum/record {@code final}/{@code abstract}
 * are masked out — they are not part of the contract a consumer compiles against, and including them would add
 * churn without meaning. The result is byte-stable across JDK builds and run orderings (verified by
 * {@code PublicApiGuardTest.theDumperIsDeterministic} + a regenerate-twice diff).
 *
 * <p><b>0.x framing.</b> This is a visibility guard, not a japicmp-style no-break promise: the golden simply
 * makes any public-surface change show up as a reviewable diff. Regenerate deliberately with
 * {@code ./gradlew dumpKitApi}.
 */
final class PublicApiDumper {

    private PublicApiDumper() {}

    /** Modifiers that are part of the API contract; the rest are implementation detail (masked out). */
    private static final int MEMBER_MODS =
            Modifier.PUBLIC | Modifier.PROTECTED | Modifier.STATIC | Modifier.FINAL | Modifier.ABSTRACT;

    /**
     * Type-header modifiers: {@code final}/{@code abstract} matter for a plain {@code class} (they govern
     * subclassing), but are compiler-chosen noise on an {@code enum}/{@code record} (never subclassable) and
     * implied on an {@code interface}. The kind word carries class/interface/enum/record; sealed is added
     * explicitly where it applies.
     */
    private static final int TYPE_MODS_CLASS =
            Modifier.PUBLIC | Modifier.PROTECTED | Modifier.STATIC | Modifier.FINAL | Modifier.ABSTRACT;
    private static final int TYPE_MODS_OTHER =
            Modifier.PUBLIC | Modifier.PROTECTED | Modifier.STATIC;

    /** One rendered member, carried with a semantic sort key so ordering is name-first, not modifier-first. */
    private record Member(String sortKey, String line) {}

    /**
     * The deterministic public-API dump of one kit, read from its compiled main-classes tree.
     *
     * @param mainClassesDir the kit's {@code build/classes/java/main} directory
     * @param loader         a class loader that can resolve the kit (the test class loader)
     */
    static String dump(Path mainClassesDir, ClassLoader loader) {
        List<Class<?>> types = new ArrayList<>();
        for (String fqcn : binaryNames(mainClassesDir)) {
            Class<?> c;
            try {
                c = Class.forName(fqcn, false, loader);
            } catch (Throwable t) { // NoClassDefFoundError et al. — a type we can't even load isn't usable API.
                throw new IllegalStateException("could not load " + fqcn + " for the API dump", t);
            }
            if (isApiType(c)) {
                types.add(c);
            }
        }
        types.sort(Comparator.comparing(Class::getName));
        return types.stream().map(PublicApiDumper::renderType).collect(Collectors.joining("\n\n")) + "\n";
    }

    /** All class binary names under {@code dir}, excluding {@code module-info}/{@code package-info}. */
    private static List<String> binaryNames(Path dir) {
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("no compiled classes at " + dir
                    + " — the kit must be compiled before the API dump (it is a test-classpath dependency)");
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files
                    .filter(p -> p.toString().endsWith(".class"))
                    .map(p -> dir.relativize(p).toString())
                    .map(s -> s.substring(0, s.length() - ".class".length()).replace('/', '.').replace('\\', '.'))
                    .filter(n -> !n.endsWith("module-info") && !n.endsWith("package-info"))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException("walking " + dir, e);
        }
    }

    /**
     * A type is API-visible iff a Maven-Central consumer can name it: a {@code public} top-level type, or a
     * {@code public}/{@code protected} member type whose enclosing type is itself API-visible. Anonymous, local
     * and synthetic types are never API.
     */
    private static boolean isApiType(Class<?> c) {
        if (c.isSynthetic() || c.isAnonymousClass() || c.isLocalClass()) {
            return false;
        }
        int m = c.getModifiers();
        Class<?> declaring = c.getDeclaringClass();
        if (declaring == null) {
            return Modifier.isPublic(m);
        }
        return (Modifier.isPublic(m) || Modifier.isProtected(m)) && isApiType(declaring);
    }

    private static boolean isApiMember(int mods) {
        return Modifier.isPublic(mods) || Modifier.isProtected(mods);
    }

    // ---- rendering ------------------------------------------------------------------------------------------

    private static String renderType(Class<?> c) {
        StringBuilder header = new StringBuilder(typeHeader(c));

        List<Member> members = new ArrayList<>();
        for (Field f : c.getDeclaredFields()) {
            if (isApiMember(f.getModifiers()) && !f.isSynthetic()) {
                members.add(renderField(f));
            }
        }
        for (Constructor<?> k : c.getDeclaredConstructors()) {
            if (isApiMember(k.getModifiers()) && !k.isSynthetic()) {
                members.add(renderConstructor(k));
            }
        }
        for (Method mth : c.getDeclaredMethods()) {
            if (isApiMember(mth.getModifiers()) && !mth.isSynthetic() && !mth.isBridge()) {
                members.add(renderMethod(mth));
            }
        }
        members.sort(Comparator.comparing(Member::sortKey).thenComparing(Member::line));

        if (members.isEmpty()) {
            return header.toString();
        }
        return members.stream().map(Member::line)
                .collect(Collectors.joining("\n", header.append('\n').toString(), ""));
    }

    private static String typeHeader(Class<?> c) {
        String kind;
        int typeMods;
        if (c.isAnnotation()) {
            kind = "@interface";
            typeMods = TYPE_MODS_OTHER;
        } else if (c.isInterface()) {
            kind = "interface";
            typeMods = TYPE_MODS_OTHER;
        } else if (c.isEnum()) {
            kind = "enum";
            typeMods = TYPE_MODS_OTHER;
        } else if (c.isRecord()) {
            kind = "record";
            typeMods = TYPE_MODS_OTHER;
        } else {
            kind = "class";
            typeMods = TYPE_MODS_CLASS;
        }

        StringBuilder sb = new StringBuilder(modifiers(c.getModifiers() & typeMods));
        if (c.isSealed()) {
            sb.append("sealed ");
        }
        sb.append(kind).append(' ').append(c.getName().replace('$', '.'));
        sb.append(typeParams(c.getTypeParameters()));

        // Supertypes are part of the contract, minus the noise every enum/record/annotation carries.
        Type superType = c.getGenericSuperclass();
        if (superType != null && superType != Object.class && !isImpliedSuper(superType)) {
            sb.append(" extends ").append(typeName(superType));
        }
        List<String> ifaces = Arrays.stream(c.getGenericInterfaces())
                .filter(t -> !isImpliedSuper(t))
                .map(PublicApiDumper::typeName)
                .sorted()
                .toList();
        if (!ifaces.isEmpty()) {
            sb.append(c.isInterface() ? " extends " : " implements ").append(String.join(", ", ifaces));
        }
        return sb.toString();
    }

    /** {@code Enum<Self>} / {@code Record} / {@code java.lang.annotation.Annotation} — implied, so omitted. */
    private static boolean isImpliedSuper(Type t) {
        String raw = rawName(t);
        return raw.equals("java.lang.Enum")
                || raw.equals("java.lang.Record")
                || raw.equals("java.lang.annotation.Annotation");
    }

    private static String rawName(Type t) {
        if (t instanceof ParameterizedType p) {
            return rawName(p.getRawType());
        }
        if (t instanceof Class<?> c) {
            return c.getName();
        }
        return t.getTypeName();
    }

    private static Member renderField(Field f) {
        String line = "  " + modifiers(f.getModifiers() & MEMBER_MODS)
                + typeName(f.getGenericType()) + " " + f.getName();
        return new Member("F:" + f.getName(), line);
    }

    private static Member renderConstructor(Constructor<?> k) {
        String params = params(k.getGenericParameterTypes());
        String line = "  " + modifiers(k.getModifiers() & MEMBER_MODS)
                + simpleName(k.getDeclaringClass()) + "(" + params + ")"
                + throwsClause(k.getGenericExceptionTypes());
        return new Member("C:(" + params + ")", line);
    }

    private static Member renderMethod(Method m) {
        String params = params(m.getGenericParameterTypes());
        String defaultKw = m.isDefault() ? "default " : "";
        String line = "  " + modifiers(m.getModifiers() & MEMBER_MODS) + defaultKw
                + typeParams(m.getTypeParameters())
                + typeName(m.getGenericReturnType()) + " " + m.getName() + "(" + params + ")"
                + throwsClause(m.getGenericExceptionTypes());
        return new Member("M:" + m.getName() + "(" + params + ")", line);
    }

    private static String params(Type[] types) {
        return Arrays.stream(types).map(PublicApiDumper::typeName).collect(Collectors.joining(", "));
    }

    private static String throwsClause(Type[] exceptions) {
        if (exceptions.length == 0) {
            return "";
        }
        String list = Arrays.stream(exceptions).map(PublicApiDumper::typeName).sorted()
                .collect(Collectors.joining(", "));
        return " throws " + list;
    }

    /** {@code Modifier.toString} of the (already-masked) bits, with a trailing space when non-empty. */
    private static String modifiers(int masked) {
        String s = Modifier.toString(masked);
        return s.isEmpty() ? "" : s + " ";
    }

    private static String typeParams(TypeVariable<?>[] vars) {
        if (vars.length == 0) {
            return "";
        }
        String body = Arrays.stream(vars).map(v -> {
            List<String> bounds = Arrays.stream(v.getBounds())
                    .filter(b -> b != Object.class)
                    .map(PublicApiDumper::typeName)
                    .toList();
            return bounds.isEmpty() ? v.getName() : v.getName() + " extends " + String.join(" & ", bounds);
        }).collect(Collectors.joining(", "));
        return "<" + body + ">";
    }

    /** Readable, deterministic rendering of a generic {@link Type} (nested {@code $} → dotted). */
    private static String typeName(Type t) {
        if (t instanceof Class<?> c) {
            if (c.isArray()) {
                return typeName(c.getComponentType()) + "[]";
            }
            return c.getName().replace('$', '.');
        }
        if (t instanceof ParameterizedType p) {
            String args = Arrays.stream(p.getActualTypeArguments())
                    .map(PublicApiDumper::typeName)
                    .collect(Collectors.joining(", "));
            return typeName(p.getRawType()) + "<" + args + ">";
        }
        if (t instanceof GenericArrayType g) {
            return typeName(g.getGenericComponentType()) + "[]";
        }
        if (t instanceof WildcardType w) {
            if (w.getLowerBounds().length > 0) {
                return "? super " + typeName(w.getLowerBounds()[0]);
            }
            Type[] upper = w.getUpperBounds();
            if (upper.length > 0 && upper[0] != Object.class) {
                return "? extends " + typeName(upper[0]);
            }
            return "?";
        }
        if (t instanceof TypeVariable<?> v) {
            return v.getName();
        }
        return t.getTypeName();
    }

    private static String simpleName(Class<?> c) {
        String n = c.getName();
        int dot = Math.max(n.lastIndexOf('.'), n.lastIndexOf('$'));
        return dot >= 0 ? n.substring(dot + 1) : n;
    }
}
