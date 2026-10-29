package dev.notune.transcribe;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;

/** Test APK provider: serves two fixed public benchmark clips, never app-private data. */
public final class PublicAudioProvider extends ContentProvider {
    private static final String AUTHORITY = "app.offlinespeechtotext.validation.test.public_audio";
    @Override public boolean onCreate() { return true; }
    private String name(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (!AUTHORITY.equals(uri.getAuthority()) || !"content".equals(uri.getScheme())
                || !("bench.wav".equals(name) || "fleurs-sk.wav".equals(name))
                || !uri.getPath().equals("/" + name)) throw new FileNotFoundException("Unknown public fixture");
        return name;
    }
    @Override public synchronized ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = name(uri);
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only public fixture");
        try {
            AssetManager assets = "bench.wav".equals(name)
                    ? getContext().getPackageManager().getResourcesForApplication("app.offlinespeechtotext.validation").getAssets()
                    : getContext().getAssets();
            File file = File.createTempFile("public-clip-", ".wav", getContext().getCacheDir());
            byte[] bytes;
            try (InputStream input = assets.open(name); java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                byte[] block = new byte[8192]; int count;
                while ((count = input.read(block)) != -1) output.write(block, 0, count);
                bytes = output.toByteArray();
            }
            String expected = "bench.wav".equals(name)
                    ? "59dfb9a4acb36fe2a2affc14bacbee2920ff435cb13cc314a08c13f66ba7860e"
                    : "87bb277cd46efdae0747510be7c6c63cd2a0bc37fcd31645fc09113887d45a9b";
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            if (!expected.contentEquals(hex)) throw new FileNotFoundException("Public fixture digest mismatch");
            Files.write(file.toPath(), bytes);
            ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            file.delete(); // The open descriptor retains immutable bytes until its reader closes.
            return descriptor;
        } catch (Exception error) { throw new FileNotFoundException("Public fixture unavailable: " + error.getClass().getSimpleName()); }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        try {
            String name = name(uri);
            String[] columns = projection == null ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor result = new MatrixCursor(columns);
            Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = name;
            result.addRow(values); return result;
        } catch (FileNotFoundException error) { return null; }
    }
    @Override public String getType(Uri uri) { return "audio/wav"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
