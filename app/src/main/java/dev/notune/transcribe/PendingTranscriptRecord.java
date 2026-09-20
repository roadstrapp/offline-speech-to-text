package dev.notune.transcribe;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Versioned, checksummed encoding for the one pending IME recovery result. */
final class PendingTranscriptRecord {
    private static final int MAGIC = 0x4f535452; // OSTR
    private static final int VERSION = 1;
    private static final int HASH_BYTES = 32;
    private static final int MAX_TEXT_BYTES = 16 * 1024 * 1024;

    static final class Entry {
        final String text;
        final long savedAtMillis;

        Entry(String text, long savedAtMillis) {
            this.text = text;
            this.savedAtMillis = savedAtMillis;
        }
    }

    private PendingTranscriptRecord() { }

    static byte[] encode(String text, long savedAtMillis) throws IOException {
        if (text == null || text.trim().isEmpty()) {
            throw new IOException("empty transcript");
        }
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > MAX_TEXT_BYTES) throw new IOException("transcript too large");
        byte[] digest = sha256(utf8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(
                4 + 4 + 8 + 4 + utf8.length + HASH_BYTES);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeLong(savedAtMillis);
            output.writeInt(utf8.length);
            output.write(utf8);
            output.write(digest);
        }
        return bytes.toByteArray();
    }

    static Entry decode(byte[] encoded) throws IOException {
        if (encoded == null) throw new IOException("missing record");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                throw new IOException("unsupported record");
            }
            long savedAtMillis = input.readLong();
            int length = input.readInt();
            if (length <= 0 || length > MAX_TEXT_BYTES
                    || encoded.length != 4 + 4 + 8 + 4 + length + HASH_BYTES) {
                throw new IOException("invalid record length");
            }
            byte[] utf8 = new byte[length];
            input.readFully(utf8);
            byte[] expected = new byte[HASH_BYTES];
            input.readFully(expected);
            if (!MessageDigest.isEqual(expected, sha256(utf8))) {
                throw new IOException("record checksum mismatch");
            }
            String text = new String(utf8, StandardCharsets.UTF_8);
            if (text.trim().isEmpty()) throw new IOException("empty record");
            return new Entry(text, savedAtMillis);
        }
    }

    private static byte[] sha256(byte[] value) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 unavailable", impossible);
        }
    }
}
