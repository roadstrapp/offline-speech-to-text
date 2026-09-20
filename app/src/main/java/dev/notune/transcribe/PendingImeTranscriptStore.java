package dev.notune.transcribe;

import android.content.Context;

import java.io.File;

/**
 * One app-private, no-backup IME transcript kept only until insertion, copying,
 * or explicit deletion. A lock and atomic replacement make it safe across the
 * app and :ime processes. Transcript content is never logged.
 */
final class PendingImeTranscriptStore {
    private PendingImeTranscriptStore() { }

    static boolean saveIfEmpty(Context context, String text) {
        return PendingTranscriptFiles.saveIfEmpty(privateDirectory(context), text);
    }

    static PendingTranscriptRecord.Entry read(Context context) {
        return PendingTranscriptFiles.read(privateDirectory(context));
    }

    /** Atomic renames make existence safe for non-blocking UI status checks. */
    static boolean hasRecordFast(Context context) {
        return PendingTranscriptFiles.hasRecordFast(privateDirectory(context));
    }

    static boolean clear(Context context) {
        return PendingTranscriptFiles.clear(privateDirectory(context));
    }

    private static File privateDirectory(Context context) {
        File directory = context.getNoBackupFilesDir();
        return directory != null ? directory : context.getFilesDir();
    }

}
