package dev.notune.transcribe;

import java.util.Locale;

/**
 * Public names and platform-neutral policy for transcribing an existing audio file.
 *
 * <p>Two entry points share one decoder and one native job type: a headless
 * {@code SpeechRecognizer} request carrying {@link #EXTRA_AUDIO_URI}, and an
 * activity-result request with {@link #ACTION_TRANSCRIBE_AUDIO}. Both read only a
 * {@code content:} Uri the caller granted, and optionally write the UTF-8 transcript
 * to a second granted Uri ({@link #EXTRA_OUTPUT_URI}). Kept free of Android types
 * so the host tests can execute every decision here.
 */
final class FileTranscriptionContract {
    private FileTranscriptionContract() { }

    /** Activity action: transcribe the audio named by the intent data and return the text. */
    static final String ACTION_TRANSCRIBE_AUDIO = "app.offlinespeechtotext.action.TRANSCRIBE_AUDIO";
    /** Recognition-intent extra (Uri): transcribe this audio file instead of opening a microphone. */
    static final String EXTRA_AUDIO_URI = "app.offlinespeechtotext.extra.AUDIO_URI";
    /** Optional extra (Uri) for both entry points: also write the UTF-8 transcript here. */
    static final String EXTRA_OUTPUT_URI = "app.offlinespeechtotext.extra.OUTPUT_URI";

    // Mirror of android.speech.SpeechRecognizer error codes, so tests need no android.jar.
    static final int ERROR_AUDIO = 3;
    static final int ERROR_SERVER = 4;
    static final int ERROR_CLIENT = 5;
    static final int ERROR_NO_MATCH = 7;
    /** No usable speech model; the platform's code for a missing on-device language pack. */
    static final int ERROR_LANGUAGE_UNAVAILABLE = 13;

    // Mirror of the android.speech.RecognizerIntent activity result codes.
    static final int RESULT_NO_MATCH = 1;
    static final int RESULT_CLIENT_ERROR = 2;
    static final int RESULT_SERVER_ERROR = 3;
    static final int RESULT_AUDIO_ERROR = 5;

    /** Why a file job ended without a transcript. Never derived from content. */
    enum Failure {
        /** Malformed request, inaccessible Uri, or the transcript could not be written. */
        CLIENT,
        /** The Uri opened but held no decodable audio track. */
        UNDECODABLE,
        /** Decoding completed but recognition produced no text. */
        NO_SPEECH,
        /** No speech model is installed or it failed to load. */
        NO_MODEL,
        /** Inference failed, or the job made no progress within its bound. */
        ENGINE,
    }

    static int recognizerError(Failure failure) {
        switch (failure) {
            case CLIENT: return ERROR_CLIENT;
            case UNDECODABLE: return ERROR_AUDIO;
            case NO_SPEECH: return ERROR_NO_MATCH;
            case NO_MODEL: return ERROR_LANGUAGE_UNAVAILABLE;
            default: return ERROR_SERVER;
        }
    }

    static int activityResult(Failure failure) {
        switch (failure) {
            case CLIENT: return RESULT_CLIENT_ERROR;
            case UNDECODABLE: return RESULT_AUDIO_ERROR;
            case NO_SPEECH: return RESULT_NO_MATCH;
            default: return RESULT_SERVER_ERROR;
        }
    }

    /**
     * Decides whether a request may proceed.
     *
     * @param audioScheme scheme of the audio Uri, or null when it is missing or not a Uri
     * @param audioOwnProvider the audio Uri's authority belongs to this application
     * @param outputScheme scheme of the optional output Uri; null when none was requested
     * @param outputOwnProvider the output Uri's authority belongs to this application
     * @param outputPresentButInvalid the output extra exists but is not a Uri
     * @param hasCallerAudio the request also carries {@code EXTRA_AUDIO_SOURCE}
     * @param callerIsSelf the request comes from this application's own uid
     * @return true when the request is well formed
     */
    static boolean acceptRequest(String audioScheme, boolean audioOwnProvider,
            String outputScheme, boolean outputOwnProvider, boolean outputPresentButInvalid,
            boolean hasCallerAudio, boolean callerIsSelf) {
        // Two audio sources in one request are ambiguous: refuse instead of guessing.
        if (hasCallerAudio || outputPresentButInvalid) return false;
        if (!acceptUri(audioScheme, audioOwnProvider, callerIsSelf)) return false;
        return outputScheme == null || acceptUri(outputScheme, outputOwnProvider, callerIsSelf);
    }

    /**
     * Only caller-granted content Uris are accepted. A file Uri would name a path this app
     * cannot and should not read on the caller's behalf. Another app may not direct this
     * app at its own providers (a confused deputy reading or overwriting private files);
     * only this app's own uid, e.g. its instrumentation, may.
     */
    static boolean acceptUri(String scheme, boolean ownProvider, boolean callerIsSelf) {
        if (scheme == null || !"content".equals(scheme.toLowerCase(Locale.ROOT))) return false;
        return callerIsSelf || !ownProvider;
    }

    // --- Share flow -----------------------------------------------------------------------

    /** Extensions of audio or audio-bearing containers that messengers share as octet-stream. */
    private static final String[] AUDIO_EXTENSIONS = {
            "aac", "amr", "awb", "flac", "m4a", "mka", "mp3", "mp4", "oga", "ogg", "opus",
            "wav", "weba", "webm", "3gp", "3gpp", "mpga", "m4b",
    };

    /**
     * Shared types accepted without probing: audio, the video containers voice/video notes
     * use, and Ogg. Generic binary shares carry no type information, so they must also
     * carry an audio file name; the decoder then still has to find an audio track.
     */
    static boolean acceptSharedType(String mimeType, String displayName) {
        String type = baseType(mimeType);
        if (type.startsWith("audio/")) return true;
        if (type.equals("application/ogg") || type.equals("video/mp4")
                || type.equals("video/3gpp") || type.equals("video/webm")) return true;
        if (type.isEmpty() || type.equals("application/octet-stream")) {
            return hasAudioExtension(displayName);
        }
        return false;
    }

    static boolean hasAudioExtension(String displayName) {
        String extension = extension(displayName);
        if (extension.isEmpty()) return false;
        for (String known : AUDIO_EXTENSIONS) if (known.equals(extension)) return true;
        return false;
    }

    /**
     * Default name offered by Save: the audio file's name with a .txt extension, limited to
     * characters every document provider accepts. Falls back to a fixed name.
     */
    static String transcriptFileName(String audioDisplayName) {
        String name = audioDisplayName == null ? "" : audioDisplayName.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < name.length() && safe.length() < 80; ) {
            int c = name.codePointAt(i);
            i += Character.charCount(c);
            boolean reserved = c < 0x20 || c == 0x7f || "<>:\"/\\|?*".indexOf(c) >= 0;
            safe.appendCodePoint(reserved ? '_' : c);
        }
        String stem = safe.toString().trim();
        while (stem.startsWith(".")) stem = stem.substring(1);
        if (stem.isEmpty()) stem = "transcript";
        return stem + ".txt";
    }

    private static String baseType(String mimeType) {
        if (mimeType == null) return "";
        int semicolon = mimeType.indexOf(';');
        String type = semicolon >= 0 ? mimeType.substring(0, semicolon) : mimeType;
        return type.trim().toLowerCase(Locale.ROOT);
    }

    private static String extension(String displayName) {
        if (displayName == null) return "";
        String name = displayName.trim();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    // --- Bounded progress -------------------------------------------------------------------

    /**
     * A job fails when neither the decoder nor the model has advanced for this long. It is
     * a stall bound, not a duration cap: long files keep advancing every block. It exceeds
     * a full interactive session (ten minutes, which takes inference precedence) plus one
     * slow window, so a dictation in progress cannot fail a file job.
     */
    static final long STALL_TIMEOUT_MS = 15 * 60_000L;

    static boolean stalled(long lastProgressAtMs, long nowMs) {
        return nowMs - lastProgressAtMs >= STALL_TIMEOUT_MS;
    }
}
