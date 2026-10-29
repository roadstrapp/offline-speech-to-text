package dev.notune.transcribe;

import dev.notune.transcribe.FileTranscriptionContract.Failure;

/** Public names, request admission, error mapping and share-flow policy for file jobs. */
public final class FileTranscriptionContractTest {
    public static void main(String[] args) {
        // The public names are a contract with other apps: never rename them silently.
        check(FileTranscriptionContract.ACTION_TRANSCRIBE_AUDIO
                .equals("app.offlinespeechtotext.action.TRANSCRIBE_AUDIO"), "action renamed");
        check(FileTranscriptionContract.EXTRA_AUDIO_URI
                .equals("app.offlinespeechtotext.extra.AUDIO_URI"), "audio extra renamed");
        check(FileTranscriptionContract.EXTRA_OUTPUT_URI
                .equals("app.offlinespeechtotext.extra.OUTPUT_URI"), "output extra renamed");

        // SpeechRecognizer codes: a missing grant is the caller's error, bad audio is audio.
        check(FileTranscriptionContract.recognizerError(Failure.CLIENT) == 5, "client code");
        check(FileTranscriptionContract.recognizerError(Failure.UNDECODABLE) == 3, "audio code");
        check(FileTranscriptionContract.recognizerError(Failure.NO_SPEECH) == 7, "no-match code");
        check(FileTranscriptionContract.recognizerError(Failure.NO_MODEL) == 13, "model code");
        check(FileTranscriptionContract.recognizerError(Failure.ENGINE) == 4, "server code");
        // RecognizerIntent activity results; none of them is RESULT_OK (-1) or CANCELED (0).
        check(FileTranscriptionContract.activityResult(Failure.CLIENT) == 2, "client result");
        check(FileTranscriptionContract.activityResult(Failure.UNDECODABLE) == 5, "audio result");
        check(FileTranscriptionContract.activityResult(Failure.NO_SPEECH) == 1, "no-match result");
        check(FileTranscriptionContract.activityResult(Failure.NO_MODEL) == 3, "model result");
        check(FileTranscriptionContract.activityResult(Failure.ENGINE) == 3, "server result");
        for (Failure failure : Failure.values()) {
            int result = FileTranscriptionContract.activityResult(failure);
            check(result != -1 && result != 0, "failure reported as success or user cancel");
            check(FileTranscriptionContract.recognizerError(failure) > 0, "failure without error");
        }

        // Admission: caller-granted content Uris only.
        check(accept("content", false, null, false, false, false, false), "plain content refused");
        check(accept("CONTENT", false, null, false, false, false, false), "scheme case refused");
        check(accept("content", false, "content", false, false, false, false), "output refused");
        check(!accept(null, false, null, false, false, false, false), "missing audio accepted");
        check(!accept("file", false, null, false, false, false, false), "file Uri accepted");
        check(!accept("http", false, null, false, false, false, false), "remote Uri accepted");
        check(!accept("content", false, "file", false, false, false, false), "file output accepted");
        check(!accept("content", false, null, false, true, false, false), "non-Uri output accepted");
        // Two sources are ambiguous even when both look valid.
        check(!accept("content", false, null, false, false, true, false), "two sources accepted");
        // Another app may not aim this app at its own providers (read or write).
        check(!accept("content", true, null, false, false, false, false), "own audio provider");
        check(!accept("content", false, "content", true, false, false, false), "own output provider");
        // This app's own uid (its instrumentation) may use its own providers.
        check(accept("content", true, "content", true, false, false, true), "self caller refused");
        check(!accept("file", true, null, false, false, false, true), "self file Uri accepted");

        // Share flow: messenger voice notes and video notes, but not every binary share.
        for (String type : new String[] {"audio/aac", "audio/mp4", "audio/ogg", "audio/opus",
                "audio/mpeg", "audio/amr", "audio/x-wav", "AUDIO/OGG", "audio/ogg; codecs=opus",
                "application/ogg", "video/mp4", "video/3gpp", "video/webm"}) {
            check(FileTranscriptionContract.acceptSharedType(type, null), "refused " + type);
        }
        for (String name : new String[] {"PTT-20260101-WA0001.opus", "voice.OGG", "note.m4a",
                "signal-2026-01-01.aac", "rec.mp3", "audio.wav", "a.amr", "clip.mp4"}) {
            check(FileTranscriptionContract.acceptSharedType("application/octet-stream", name),
                    "refused generic " + name);
            check(FileTranscriptionContract.acceptSharedType(null, name), "refused untyped " + name);
        }
        for (String name : new String[] {null, "", "archive.zip", "photo.jpg", "notes.txt",
                "opus", "voice.", "document.pdf"}) {
            check(!FileTranscriptionContract.acceptSharedType("application/octet-stream", name),
                    "claimed generic " + name);
        }
        for (String type : new String[] {"image/jpeg", "text/plain", "application/pdf",
                "video/x-matroska"}) {
            check(!FileTranscriptionContract.acceptSharedType(type, "voice.ogg"), "claimed " + type);
        }

        // Save: default document name from the audio name, never a path or a hidden file.
        check(FileTranscriptionContract.transcriptFileName("voice note.ogg").equals("voice note.txt"),
                "extension not replaced");
        check(FileTranscriptionContract.transcriptFileName("PTT-20260101-WA0001.opus")
                .equals("PTT-20260101-WA0001.txt"), "messenger name changed");
        check(FileTranscriptionContract.transcriptFileName("a.b.m4a").equals("a.b.txt"), "inner dot");
        check(FileTranscriptionContract.transcriptFileName("../../x/../evil.mp3").equals("evil.txt"),
                "path kept");
        check(FileTranscriptionContract.transcriptFileName("c:\\dir\\rec.wav").equals("rec.txt"),
                "windows path kept");
        check(FileTranscriptionContract.transcriptFileName("a<b>:c?.ogg").equals("a_b__c_.txt"),
                "reserved characters kept");
        check(FileTranscriptionContract.transcriptFileName(".hidden").equals("hidden.txt"),
                "hidden name");
        check(FileTranscriptionContract.transcriptFileName("Hlasová správa.ogg")
                .equals("Hlasová správa.txt"), "non-ASCII name damaged");
        for (String empty : new String[] {null, "", "   ", "..", "/", "\\"}) {
            check(FileTranscriptionContract.transcriptFileName(empty).equals("transcript.txt"),
                    "fallback name for " + empty);
        }
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 300; i++) longName.append('x');
        check(FileTranscriptionContract.transcriptFileName(longName + ".ogg").length() <= 84,
                "unbounded name");
        check(FileTranscriptionContract.transcriptFileName("\uD83C\uDFB5 song.mp3")
                .equals("\uD83C\uDFB5 song.txt"), "surrogate pair split");

        // Stall bound is a progress bound, longer than a full interactive session.
        long bound = FileTranscriptionContract.STALL_TIMEOUT_MS;
        check(bound > 10 * 60_000L, "stall bound shorter than the interactive cap");
        check(!FileTranscriptionContract.stalled(1_000, 1_000 + bound - 1), "early stall");
        check(FileTranscriptionContract.stalled(1_000, 1_000 + bound), "stall not detected");
        // A 25-minute file advancing every block never stalls.
        long last = 0;
        for (long now = 0; now <= 25 * 60_000L; now += 10_000L) {
            check(!FileTranscriptionContract.stalled(last, now), "long file treated as stalled");
            last = now;
        }
        System.out.println("FileTranscriptionContractTest passed");
    }

    private static boolean accept(String audio, boolean audioOwn, String output, boolean outputOwn,
            boolean outputInvalid, boolean callerAudio, boolean self) {
        return FileTranscriptionContract.acceptRequest(
                audio, audioOwn, output, outputOwn, outputInvalid, callerAudio, self);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
