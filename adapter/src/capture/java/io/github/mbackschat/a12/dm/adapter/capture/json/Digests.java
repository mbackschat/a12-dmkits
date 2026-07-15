package io.github.mbackschat.a12.dm.adapter.capture.json;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 over bytes, rendered as 64 lowercase hex chars — built-in Java hashing only (the proposal's rule). */
public final class Digests {

    private Digests() {
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Whether {@code s} is a well-formed lowercase-hex SHA-256 digest. */
    public static boolean isSha256(String s) {
        return s != null && s.length() == 64 && s.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'));
    }
}
