package dev.notune.transcribe;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Content-Uri access for file transcription. Every failure is reported as a boolean or a
 * null result without logging the Uri: authorities and paths can name the caller's content.
 */
final class FileTranscriptionAccess {
    private FileTranscriptionAccess() { }

    /** True when the Uri's authority is served by this application. */
    static boolean isOwnProvider(Context context, Uri uri) {
        if (uri == null || uri.getAuthority() == null) return false;
        // A provider that granted this app access is visible to it on Android 11+, and this
        // app's own providers always are. Only "ours" matters here, so an unresolved
        // authority is simply not ours (and will fail to open if it is not granted).
        String authority = uri.getAuthority();
        int at = authority.lastIndexOf('@');
        if (at >= 0) return true; // A user-qualified authority is never a caller grant.
        try {
            ProviderInfo info = context.getPackageManager().resolveContentProvider(authority, 0);
            return info != null && context.getPackageName().equals(info.packageName);
        } catch (RuntimeException e) {
            return true; // Fail closed.
        }
    }

    /** Require the requesting uid's own access, not an unrelated grant held by OSTT. */
    static boolean callerCanAccess(Context context, Uri uri, int uid, int flags) {
        if (uri == null || uid < 0) return false;
        if (uid == android.os.Process.myUid()) return true;
        try {
            ProviderInfo provider = context.getPackageManager().resolveContentProvider(uri.getAuthority(), 0);
            if (provider != null && provider.applicationInfo.uid == uid) return true;
            return context.checkUriPermission(uri, -1, uid, flags)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException e) { return false; }
    }

    static String scheme(Uri uri) {
        return uri == null ? null : uri.getScheme();
    }

    /** Opens and closes the Uri for reading, separating a missing grant from bad audio. */
    static boolean canRead(Context context, Uri uri) {
        try (ParcelFileDescriptor descriptor =
                     context.getContentResolver().openFileDescriptor(uri, "r")) {
            return descriptor != null;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** The provider's display name, or null. Used only for the Save default name. */
    static String displayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(
                uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        } catch (RuntimeException ignored) { }
        String last = uri == null ? null : uri.getLastPathSegment();
        return last;
    }

    static String type(Context context, Uri uri) {
        try {
            return context.getContentResolver().getType(uri);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Opens a writable, truncated descriptor. Some providers reject "wt"; "w" is the only
     * fallback, and is used only after "wt" was refused outright.
     */
    static ParcelFileDescriptor openForWrite(Context context, Uri uri) {
        ContentResolver resolver = context.getContentResolver();
        try {
            ParcelFileDescriptor truncated = resolver.openFileDescriptor(uri, "wt");
            if (truncated != null) return truncated;
        } catch (IOException | RuntimeException ignored) { }
        try {
            return resolver.openFileDescriptor(uri, "w");
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Writes the complete UTF-8 transcript and closes the descriptor. */
    static boolean write(ParcelFileDescriptor descriptor, String text) {
        if (descriptor == null) return false;
        try (FileOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
            // "w" may not truncate on every provider; never leave stale trailing bytes.
            // A pipe-backed provider descriptor cannot truncate and needs no truncation.
            try { out.getChannel().truncate(0); } catch (IOException ignored) { }
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
            try { out.getFD().sync(); } catch (IOException ignored) { }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    static boolean write(Context context, Uri uri, String text) {
        return write(openForWrite(context, uri), text);
    }
}
