package dev.notune.transcribe;

/**
 * Activity-result contract for {@link FileTranscriptionContract#ACTION_TRANSCRIBE_AUDIO}.
 *
 * <p>The caller passes a granted {@code content:} audio Uri as the intent data and receives
 * {@code RESULT_OK} with {@code RecognizerIntent.EXTRA_RESULTS}; failures return the
 * {@code RecognizerIntent.RESULT_*} codes and leave a content-free message on screen. Declared
 * with the standard launch mode, separate from the singleTask share screen, so a result
 * always returns to the requesting activity.
 */
public class TranscribeAudioActivity extends TranscribeFileActivity {
    @Override
    protected boolean returnsResult() {
        return true;
    }
}
