package io.github.mbackschat.a12.dm.core;

import java.util.Collection;

import io.github.mbackschat.a12.dm.predicates.LocalePredicatesKt;

/**
 * A locale code as A12 writes and compares it.
 *
 * <p>A12 parses every locale it reads, a header code and the locale of every localized text, and writes the parsed
 * locale back as its {@code toString}, so {@code en-US} comes back {@code en_US} and the two name one locale
 * (KERNEL-FINDINGS KF676 for the write, KF290 for the comparison). The kits write and match a locale the same way,
 * so a model they edit reads as A12 would have written it.
 *
 * <p>A spelling A12's parser refuses has no key and is kept as given: the gate then refuses the model, which a
 * writer that repaired it would hide (KF290's {@code en_us} row).
 */
public final class A12Locales {

    private A12Locales() {
    }

    /** The spelling A12 writes for {@code code}, or {@code code} itself when its parser refuses it. */
    public static String key(String code) {
        String key = LocalePredicatesKt.a12LocaleKey(code);
        return key != null ? key : code;
    }

    /** Whether A12 reads {@code a} and {@code b} as one locale. */
    public static boolean same(String a, String b) {
        return key(a).equals(key(b));
    }

    /** Whether {@code declared} holds {@code code} as A12 reads both, for a membership test over raw spellings. */
    public static boolean declares(Collection<String> declared, String code) {
        String key = key(code);
        return declared.stream().anyMatch(each -> key(each).equals(key));
    }
}
