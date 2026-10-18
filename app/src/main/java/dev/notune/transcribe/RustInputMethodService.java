package dev.notune.transcribe;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.color.MaterialColors;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RustInputMethodService extends InputMethodService {
    
    private static final String TAG = "OfflineVoiceInput";

    static {
        try {
            NativeLibraries.load();
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native libraries", e);
        }
    }

    private TextView statusView;
    private TextView hintView;
    private View recordContainer;
    private ImageView micIcon;
    private View processingRing;
    private View backspaceButton;
    private View spaceButton;
    private View enterButton;
    private View switchKeyboardButton;
    private View inputView;
    private MicLevelView micLevelView;
    private View recordKey;
    private View recordingActions;
    private ImageButton pauseResumeButton;
    private View cancelRecordingButton;
    private View transcribeKeepButton;
    private View transcribeSwitchButton;
    // Night flag the current input view was inflated with, so it can be rebuilt
    // if the theme preference changes while this process stays alive.
    private boolean viewIsNight = false;
    private Handler mainHandler;
    private boolean isRecording = false;
    private boolean recordingPaused = false;
    private boolean processingResult = false;
    private boolean pendingSwitchBack = false;
    // onStartInputView() and onWindowShown() may both request auto-start for
    // one show sequence. Claim the start synchronously on the IME main thread
    // so the second callback cannot open another native capture session.
    private boolean automaticStartInFlight = false;
    private String lastStatus = "Initializing...";
    private static final long DELETE_PREVIEW_DELAY = 180;
    private static final long SPACE_REPEAT_INITIAL_DELAY = 400;
    private static final float DELETE_CLUSTER_STEP_DP = 18f;
    private static final long REPEAT_INTERVAL = 50; // ms between repeats
    private Runnable spaceRepeatRunnable;
    private final AudioFocusPauser audioPauser = new AudioFocusPauser();
    private static final ExecutorService RECOVERY_IO = Executors.newSingleThreadExecutor(r ->
            new Thread(r, "ime-transcript-recovery"));
    private static final int DELIVERY_RETRY_LIMIT = 12;
    private static final long DELIVERY_RETRY_DELAY_MS = 125L;
    private boolean pauseAudioActive = false;
    private long recordingStartedElapsedMs = -1L;
    private volatile TroubleshootingSession troubleshooting;
    private long pauseStartedElapsedMs = -1L;
    private long accumulatedPausedMs = 0L;
    // Whether an editor is currently focused/started for input. Tracked via
    // onStartInput/onFinishInput because getCurrentInputConnection() returns a
    // non-null no-op connection when nothing is focused, so commitText would be
    // silently dropped.
    private boolean inputActive = false;
    // Advances only when Android starts a genuinely new editor. A restarting
    // editor may receive a fresh InputConnection wrapper while remaining the
    // same safe destination, so wrapper identity is not a valid session key.
    private long editorGeneration = 0L;
    // Whether the keyboard window is currently on screen. Some frameworks
    // (notably OEM builds) call onWindowShown again for events that don't
    // follow an onWindowHidden, e.g. tapping the text area to move the
    // cursor while the keyboard stays visible. Auto-record must only fire on
    // a genuine hidden -> shown transition, or a cursor tap starts a
    // recording the user never asked for.
    private boolean windowVisible = false;
    private boolean editorSafeForDictation = false;
    private final ImeRecordingSession recordingSession = new ImeRecordingSession();
    private IForegroundRecordingService longRecordingService;
    private boolean longRecordingBound;
    private final IForegroundRecordingObserver longRecordingObserver =
            new IForegroundRecordingObserver.Stub() {
                @Override public void onStateChanged(int state, long generation) {
                    if (mainHandler != null) {
                        mainHandler.post(() -> updateLongRecordingState(state));
                    }
                }
            };
    private final ServiceConnection longRecordingConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            longRecordingService = IForegroundRecordingService.Stub.asInterface(binder);
            longRecordingBound = true;
            try {
                longRecordingService.registerObserver(longRecordingObserver);
                updateLongRecordingState(longRecordingService.getState());
            } catch (Exception unavailable) {
                unbindLongRecordingState();
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            longRecordingService = null;
            if (!isRecording) updateUiState();
        }

        @Override public void onBindingDied(ComponentName name) {
            longRecordingService = null;
            if (longRecordingBound) {
                try { unbindService(this); } catch (RuntimeException ignored) { }
            }
            longRecordingBound = false;
            if (!isRecording) updateUiState();
        }
    };

    // Backspace hold-drag previews only selection; text changes on ACTION_UP.
    private Runnable beginDeletePreviewRunnable;
    private InputConnection deletePreviewConnection;
    private String deletePreviewText;
    private int deletePreviewTextOffset;
    private int deleteOriginalStart = -1;
    private int deleteOriginalEnd = -1;
    private int deletePreviewStart = -1;
    private int deletePreviewEnd = -1;
    private int deletePointerId = -1;
    private float deleteDownX;
    private float deleteCurrentX;
    private boolean deletePreviewActive = false;
    // Selection changes cross the app/IME process boundary. Keep every range
    // issued during this gesture so a delayed editor echo is not mistaken for
    // an external cursor move.
    private final Set<Long> deleteIssuedSelections = new HashSet<>();

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        DiagnosticLog.event(getFilesDir(), "ime_start",
                "process=ime,version_code=" + BuildConfig.VERSION_CODE);
        try {
            initNative(this);
        } catch (Throwable t) {
            // Native may be unavailable (e.g. wrong-ABI emulator); don't crash the IME.
            Log.e(TAG, "Error in initNative", t);
        }
    }

    @Override
    public View onCreateInputView() {
        unbindLongRecordingState();
        cancelDeletePreview();
        DiagnosticLog.event(getFilesDir(), "ime_state", "state=created");
        try {
            // The IME is a non-AppCompat Service in a separate process, so
            // AppCompat's delegate can't theme it. Build a context that is
            // night-aware (per the saved preference) and wears the Material 3
            // theme. Do not add dynamic color: it can replace the true-black
            // night surface selected by AppTheme.
            Context night = ThemePrefs.wrapForNight(this, ThemePrefs.getMode(this));
            viewIsNight = ThemePrefs.isNight(night);
            Context themed = new ContextThemeWrapper(night, R.style.AppTheme);
            View view = LayoutInflater.from(themed).inflate(R.layout.ime_layout, null);
            inputView = view;

            // Handle window insets for avoiding navigation bar overlap
            int basePaddingLeft = view.getPaddingLeft();
            int basePaddingTop = view.getPaddingTop();
            int basePaddingRight = view.getPaddingRight();
            int basePaddingBottom = view.getPaddingBottom();
            view.setOnApplyWindowInsetsListener((v, insets) -> {
                v.setPadding(basePaddingLeft, basePaddingTop, basePaddingRight,
                        basePaddingBottom + insets.getSystemWindowInsetBottom());
                v.post(v::requestLayout);
                return insets;
            });

            statusView = view.findViewById(R.id.ime_status_text);
            view.findViewById(R.id.ime_troubleshooting_banner).setVisibility(
                    DiagnosticLog.isTroubleshootingEnabled(getFilesDir()) ? View.VISIBLE : View.GONE);
            processingRing = view.findViewById(R.id.ime_processing_ring);
            recordContainer = view.findViewById(R.id.ime_record_container);
            micIcon = view.findViewById(R.id.ime_mic_icon);
            micLevelView = view.findViewById(R.id.ime_mic_level);
            recordKey = view.findViewById(R.id.ime_record_key);
            hintView = view.findViewById(R.id.ime_hint);
            recordingActions = view.findViewById(R.id.ime_recording_actions);
            pauseResumeButton = view.findViewById(R.id.ime_pause_resume);
            cancelRecordingButton = view.findViewById(R.id.ime_cancel_recording);
            transcribeKeepButton = view.findViewById(R.id.ime_transcribe_keep);
            transcribeSwitchButton = view.findViewById(R.id.ime_transcribe_switch);
            backspaceButton = view.findViewById(R.id.ime_backspace);
            spaceButton = view.findViewById(R.id.ime_space);
            enterButton = view.findViewById(R.id.ime_enter);
            switchKeyboardButton = view.findViewById(R.id.ime_switch_keyboard);

            pauseResumeButton.setOnClickListener(v -> toggleRecordingPaused());
            cancelRecordingButton.setOnClickListener(v -> stopAndDiscard());
            transcribeKeepButton.setOnClickListener(v -> stopTranscribe(false));
            transcribeSwitchButton.setOnClickListener(v -> stopTranscribe(true));
            switchKeyboardButton.setOnClickListener(v -> switchToOtherInputMethod());

            // Key repeat runnable for space
            spaceRepeatRunnable = new Runnable() {
                @Override
                public void run() {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) {
                        ic.commitText(" ", 1);
                    }
                    mainHandler.postDelayed(this, REPEAT_INTERVAL);
                }
            };

            backspaceButton.setOnClickListener(v -> sendOneTapBackspace());
            spaceButton.setOnClickListener(v -> {
                InputConnection editor = getCurrentInputConnection();
                if (editor != null) editor.commitText(" ", 1);
            });
            backspaceButton.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        prepareDeleteGesture(event);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        updateDeletePreview(event);
                        return true;
                    case MotionEvent.ACTION_UP:
                        finishDeletePreview();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        cancelDeletePreview();
                        return true;
                    case MotionEvent.ACTION_POINTER_UP:
                        if (event.getPointerId(event.getActionIndex()) == deletePointerId) {
                            cancelDeletePreview();
                        }
                        return true;
                }
                return false;
            });

            spaceButton.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        InputConnection ic = getCurrentInputConnection();
                        if (ic != null) {
                            ic.commitText(" ", 1);
                        }
                        mainHandler.postDelayed(spaceRepeatRunnable, SPACE_REPEAT_INITIAL_DELAY);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        mainHandler.removeCallbacks(spaceRepeatRunnable);
                        return true;
                }
                return false;
            });

            enterButton.setOnClickListener(v -> {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
                    EditorInfo editorInfo = getCurrentInputEditorInfo();
                    if (editorInfo == null) {
                        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
                        return;
                    }
                    int imeOptions = editorInfo.imeOptions;
                    int action = imeOptions & EditorInfo.IME_MASK_ACTION;
                    boolean noEnterAction = (imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;

                    // If the editor flags IME_FLAG_NO_ENTER_ACTION (e.g. multi-line fields in
                    // messaging apps like Signal), or if there's no meaningful action, insert a
                    // newline. Otherwise perform the editor action (Go, Search, Send, etc.).
                    if (!noEnterAction && (
                            action == EditorInfo.IME_ACTION_GO ||
                            action == EditorInfo.IME_ACTION_SEARCH ||
                            action == EditorInfo.IME_ACTION_SEND ||
                            action == EditorInfo.IME_ACTION_NEXT)) {
                        ic.performEditorAction(action);
                    } else {
                        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
                    }
                }
            });

            recordContainer.setOnClickListener(v -> {
                if (!recordContainer.isEnabled()) return;
                if (isRecording) return;

                if (hasPendingTranscriptRecovery()) {
                    openTranscriptRecovery();
                    return;
                }

                // Check microphone permission
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    if (statusView != null) statusView.setText("No mic permission - grant in app");
                    if (hintView != null) hintView.setText("Open the app to grant permission");
                    return;
                }

                if (!editorSafeForDictation || !inputActive || getCurrentInputConnection() == null) {
                    if (statusView != null) statusView.setText(R.string.ime_sensitive_field);
                    if (hintView != null) hintView.setText(R.string.ime_sensitive_field);
                    return;
                }
                if (isPauseAudioEnabled()) {
                    audioPauser.request(this);
                    pauseAudioActive = true;
                }
                recordingSession.begin(getCurrentInputConnection(), editorGeneration);
                recordingPaused = false;
                processingResult = false;
                updateRecordButtonUI(true);
                startRecording(isAutoStopEnabled(), AutoStopDelay.get(getFilesDir()));
            });

            recordContainer.setOnLongClickListener(v -> {
                if (!editorSafeForDictation || !inputActive
                        || getCurrentInputConnection() == null) {
                    if (statusView != null) statusView.setText(R.string.ime_sensitive_field);
                    if (hintView != null) hintView.setText(R.string.ime_sensitive_field);
                    return true;
                }
                // Long-form capture is explicit and never inherits the active editor.
                Intent longRecording = new Intent(this, ForegroundRecordingActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(longRecording);
                return true;
            });

            updateRecordButtonUI(isRecording);
            updateUiState();
            return view;
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreateInputView", e);
            TextView errorView = new TextView(this);
            errorView.setText("Error loading keyboard: " + e.getMessage());
            return errorView;
        }
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        boolean wasVisible = windowVisible;
        windowVisible = true;
        refreshTroubleshootingBanner();
        bindLongRecordingState();
        if (isRecording) {
            // A background recording is still running (record-in-background
            // setting): restore the recording UI.
            updateRecordButtonUI(true);
            return;
        }
        updateUiState();
        if (wasVisible) {
            // Not a real hidden -> shown transition (e.g. a cursor tap in the
            // text area); never auto-start a recording from here.
            return;
        }
        tryStartAutomaticRecording();
    }

    @Override
    public void onWindowHidden() {
        super.onWindowHidden();
        windowVisible = false;
        unbindLongRecordingState();
        if (isRecording && isStopOnHideEnabled()) {
            // Stop the microphone, but preserve recognized speech for private
            // recovery instead of treating an Android lifecycle transition as
            // the user's Cancel action.
            stopForRecovery();
        }
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        cancelDeletePreview();
        boolean sameEditorRestart = restarting && inputActive;
        if (!sameEditorRestart) editorGeneration++;
        super.onStartInput(attribute, restarting);
        inputActive = true;
        editorSafeForDictation = attribute != null
                && !ImeTextEdit.isSensitiveEditor(attribute.inputType, attribute.imeOptions);
        if (!editorSafeForDictation) unbindLongRecordingState();
        if (isRecording && (!sameEditorRestart || !editorSafeForDictation)) {
            stopForRecovery();
        }
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        inputActive = true;
        editorSafeForDictation = info != null
                && !ImeTextEdit.isSensitiveEditor(info.inputType, info.imeOptions);
        if (isRecording && !recordingSession.canCommitTo(
                getCurrentInputConnection(), editorGeneration,
                editorSafeForDictation, inputActive)) {
            stopForRecovery();
        }
        // Rebuild the keyboard if the theme preference changed while this
        // (long-lived) IME process stayed alive, so it matches the app setting.
        if (inputView != null
                && ThemePrefs.isNight(ThemePrefs.wrapForNight(this, ThemePrefs.getMode(this))) != viewIsNight) {
            View refreshedView = onCreateInputView();
            setInputView(refreshedView);
            // This window's inset pass already happened. Re-apply it to the
            // freshly inflated keyboard so a theme change cannot drop the
            // navigation-bar padding and clip the bottom key row.
            refreshedView.requestApplyInsets();
        }
        if (!isRecording) updateUiState();
        // Android can keep the same IME window visible while moving it to a
        // different app/editor. onWindowShown() then does not represent a new
        // hidden -> shown transition, so start automatic recording here for a
        // genuinely new input view as well. A restarting editor is the same
        // destination and must not start a surprise recording after a cursor
        // move or framework refresh.
        if (!restarting && windowVisible) tryStartAutomaticRecording();
    }

    private void tryStartAutomaticRecording() {
        if (!new File(getFilesDir(), "auto_record").exists()
                || automaticStartInFlight || isRecording || processingResult
                || !editorSafeForDictation || !inputActive
                || getCurrentInputConnection() == null
                || checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (hasPendingTranscriptRecovery()) {
            // "Keep for later" must remain meaningful: auto-record never traps
            // the user in a recovery-activity relaunch loop. The record key
            // remains the explicit way to open it.
            updateUiState();
            return;
        }
        automaticStartInFlight = true;
        try {
            if (isPauseAudioEnabled()) {
                audioPauser.request(this);
                pauseAudioActive = true;
            }
            recordingSession.begin(getCurrentInputConnection(), editorGeneration);
            recordingPaused = false;
            updateRecordButtonUI(true);
            startRecording(isAutoStopEnabled(), AutoStopDelay.get(getFilesDir()));
        } finally {
            automaticStartInFlight = false;
        }
    }

    @Override
    public void onFinishInput() {
        cancelDeletePreview();
        unbindLongRecordingState();
        if (isRecording) {
            stopForRecovery();
        }
        super.onFinishInput();
        inputActive = false;
        editorSafeForDictation = false;
        editorGeneration++;
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        unbindLongRecordingState();
        super.onFinishInputView(finishingInput);
    }

    private void bindLongRecordingState() {
        if (longRecordingBound || !windowVisible || !editorSafeForDictation) return;
        try {
            longRecordingBound = bindService(
                    new Intent(this, ForegroundRecordingService.class),
                    longRecordingConnection, 0);
        } catch (RuntimeException ignored) { }
    }

    private void unbindLongRecordingState() {
        if (longRecordingService != null) {
            try { longRecordingService.unregisterObserver(longRecordingObserver); }
            catch (Exception ignored) { }
        }
        longRecordingService = null;
        if (longRecordingBound) {
            try { unbindService(longRecordingConnection); } catch (RuntimeException ignored) { }
        }
        longRecordingBound = false;
    }

    private void updateLongRecordingState(int ordinal) {
        ForegroundRecordingCoordinator.State[] states =
                ForegroundRecordingCoordinator.State.values();
        if (ordinal < 0 || ordinal >= states.length
                || states[ordinal] == ForegroundRecordingCoordinator.State.IDLE) {
            if (!isRecording) updateUiState();
            return;
        }
        if (isRecording) return;
        if (recordContainer != null) {
            recordContainer.setEnabled(false);
            recordContainer.setAlpha(0.5f);
        }
        if (statusView != null) {
            switch (states[ordinal]) {
                case STARTING: statusView.setText("Preparing long recording"); break;
                case RECORDING: statusView.setText("Long recording active · use notification controls"); break;
                case TRANSCRIBING: statusView.setText("Long recording is transcribing"); break;
                default: statusView.setText("Long recording is stopping"); break;
            }
        }
        if (hintView != null) hintView.setText("Transcript returns only to private review");
    }

    private void stopAndDiscard() {
        if (!isRecording) return;
        processingResult = false;
        pendingSwitchBack = false;
        recordingSession.discard();
        finishTroubleshooting("discarded");
        try {
            cancelRecording();
        } catch (Throwable t) {
            // Stopping is the only safe fallback. The recording-session discard
            // latch keeps any late native result out of the editor.
            Log.w(TAG, "cancelRecording failed, stopping without commit", t);
            try { stopRecording(); } catch (Throwable ignored) { }
        }
        releasePauseAudio();
        updateRecordButtonUI(false);
        lastStatus = "Ready";
        if (statusView != null) statusView.setText(R.string.ime_recording_discarded);
    }

    private void toggleRecordingPaused() {
        if (!isRecording) return;
        boolean requestedPause = !recordingPaused;
        if (!setRecordingPaused(requestedPause)) {
            // If a vendor backend cannot resume its stream, preserve and
            // transcribe everything captured before Pause. A transport quirk
            // must never turn a valid dictation into data loss.
            stopForRecovery();
            return;
        }
        recordingPaused = requestedPause;
        if (recordingPaused) {
            pauseStartedElapsedMs = SystemClock.elapsedRealtime();
            releasePauseAudio();
        } else {
            if (pauseStartedElapsedMs >= 0L) {
                accumulatedPausedMs += Math.max(0L,
                        SystemClock.elapsedRealtime() - pauseStartedElapsedMs);
                pauseStartedElapsedMs = -1L;
            }
            if (isPauseAudioEnabled() && !pauseAudioActive) {
                audioPauser.request(this);
                pauseAudioActive = true;
            }
        }
        updateRecordingActionsUi();
    }

    private void stopTranscribe(boolean switchKeyboard) {
        if (!isRecording) return;
        stopForResult(switchKeyboard, false);
    }

    /** Stop capture and keep its result even when Android has removed the editor. */
    private void stopForRecovery() {
        if (!isRecording) return;
        stopForResult(false, true);
    }

    private void stopForResult(boolean switchKeyboard, boolean lifecycleHandoff) {
        pendingSwitchBack = switchKeyboard;
        boolean capturedAudio;
        try {
            capturedAudio = finishRecording();
        } catch (Throwable t) {
            Log.w(TAG, "finishRecording failed; cancelling capture", t);
            recordingSession.onError();
            try { cancelRecording(); } catch (Throwable ignored) { }
            pendingSwitchBack = false;
            updateRecordButtonUI(false);
            releasePauseAudio();
            return;
        }
        if (!capturedAudio) {
            // A new app/editor can replace the old input immediately after
            // automatic recording starts, before the native callback supplies
            // its first buffer. That is a normal lifecycle handoff, not a
            // microphone-permission failure and not a transcription result.
            processingResult = false;
            recordingSession.finish();
            lastStatus = "Ready";
            updateRecordButtonUI(false);
            releasePauseAudio();
            boolean switchAfterEmptyStop = pendingSwitchBack && !lifecycleHandoff;
            pendingSwitchBack = false;
            if (switchAfterEmptyStop) {
                switchToOtherInputMethod();
            } else if (!lifecycleHandoff && statusView != null) {
                statusView.setText(R.string.ime_no_audio_captured);
            }
            return;
        }
        processingResult = true;
        lastStatus = "Processing...";
        updateRecordButtonUI(false);
        releasePauseAudio();
    }

    private void releasePauseAudio() {
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
    }

    private void prepareDeleteGesture(MotionEvent event) {
        cancelDeletePreview();
        deletePointerId = event.getPointerId(0);
        deleteDownX = event.getX(0);
        deleteCurrentX = deleteDownX;
        if (editorSafeForDictation && inputActive) {
            InputConnection ic = getCurrentInputConnection();
            ExtractedText et = ic == null ? null
                    : ic.getExtractedText(new ExtractedTextRequest(), 0);
            if (et != null && et.text != null && et.selectionStart >= 0 && et.selectionEnd >= 0) {
                deletePreviewConnection = ic;
                deletePreviewText = et.text.toString();
                deletePreviewTextOffset = et.startOffset;
                deleteOriginalStart = et.startOffset + et.selectionStart;
                deleteOriginalEnd = et.startOffset + et.selectionEnd;
                deletePreviewStart = deleteOriginalStart;
                deletePreviewEnd = deleteOriginalEnd;
                deleteIssuedSelections.add(selectionKey(deleteOriginalStart, deleteOriginalEnd));
            }
        }
        beginDeletePreviewRunnable = this::beginDeletePreview;
        mainHandler.postDelayed(beginDeletePreviewRunnable, DELETE_PREVIEW_DELAY);
    }

    private void beginDeletePreview() {
        if (deletePointerId < 0 || deletePreviewConnection == null
                || !editorSafeForDictation || !inputActive
                || getCurrentInputConnection() != deletePreviewConnection
                || !deleteSnapshotMatchesIssuedSelection()) {
            if (backspaceButton != null) {
                backspaceButton.announceForAccessibility("Selection preview unavailable");
            }
            if (statusView != null) statusView.setText("Selection preview unavailable");
            return;
        }
        deletePreviewActive = true;
        updateDeletePreviewSelection();
    }

    private void updateDeletePreview(MotionEvent event) {
        int index = event.findPointerIndex(deletePointerId);
        if (index < 0) {
            cancelDeletePreview();
            return;
        }
        deleteCurrentX = event.getX(index);
        if (!deletePreviewActive
                && Math.abs(deleteCurrentX - deleteDownX)
                >= ViewConfiguration.get(this).getScaledTouchSlop()) {
            if (beginDeletePreviewRunnable != null) {
                mainHandler.removeCallbacks(beginDeletePreviewRunnable);
            }
            beginDeletePreview();
        } else if (deletePreviewActive) {
            updateDeletePreviewSelection();
        }
    }

    private void updateDeletePreviewSelection() {
        float delta = deleteCurrentX - deleteDownX;
        if (backspaceButton != null
                && backspaceButton.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            delta = -delta;
        }
        float density = getResources().getDisplayMetrics().density;
        float stepPx = DELETE_CLUSTER_STEP_DP * density;
        // A completed hold selects the first cluster immediately. Each short
        // movement to the left then adds exactly one cluster; moving back to
        // the right contracts the same selection without changing text.
        int steps = delta <= 0f ? -((int) (Math.abs(delta) / stepPx) + 1) : 0;

        int relativeStart = Math.max(0, Math.min(
                deleteOriginalStart - deletePreviewTextOffset, deletePreviewText.length()));
        int relativeEnd = Math.max(0, Math.min(
                deleteOriginalEnd - deletePreviewTextOffset, deletePreviewText.length()));
        ImeTextEdit.Range range = ImeTextEdit.selectionForClusterDelta(
                deletePreviewText, relativeStart, relativeEnd, steps);
        int nextStart = deletePreviewTextOffset + range.start;
        int nextEnd = deletePreviewTextOffset + range.end;
        if (nextStart == deletePreviewStart && nextEnd == deletePreviewEnd) return;
        if (deleteSnapshotMatchesIssuedSelection()) {
            deletePreviewStart = nextStart;
            deletePreviewEnd = nextEnd;
            // Use the editor's real selection highlight. A composing region is
            // rendered as an underline by many apps and does not communicate
            // the exact text that will be deleted on release.
            deleteIssuedSelections.add(selectionKey(deletePreviewStart, deletePreviewEnd));
            deletePreviewConnection.setSelection(deletePreviewStart, deletePreviewEnd);
        } else {
            cancelDeletePreview();
        }
    }

    private void finishDeletePreview() {
        if (beginDeletePreviewRunnable != null) {
            mainHandler.removeCallbacks(beginDeletePreviewRunnable);
        }
        if (deletePointerId < 0) return;
        if (!deletePreviewActive) {
            restoreDeleteSelection();
            sendOneTapBackspace();
            resetDeletePreview();
            return;
        }
        InputConnection ic = deletePreviewConnection;
        if (deletePreviewStart != deletePreviewEnd
                && deleteSnapshotMatchesIssuedSelection()) {
            // Apply the final requested range and deletion in one ordered
            // editor transaction. Text is unchanged until this ACTION_UP path.
            ic.beginBatchEdit();
            ic.setSelection(deletePreviewStart, deletePreviewEnd);
            ic.commitText("", 1);
            ic.endBatchEdit();
            resetDeletePreview();
        } else {
            cancelDeletePreview();
        }
    }

    private void cancelDeletePreview() {
        if (beginDeletePreviewRunnable != null) {
            mainHandler.removeCallbacks(beginDeletePreviewRunnable);
        }
        restoreDeleteSelection();
        resetDeletePreview();
    }

    private void restoreDeleteSelection() {
        if (deletePreviewConnection != null && inputActive
                && getCurrentInputConnection() == deletePreviewConnection
                && deleteOriginalStart >= 0 && deleteOriginalEnd >= 0) {
            if (deleteSnapshotMatchesIssuedSelection()) {
                deletePreviewConnection.setSelection(deleteOriginalStart, deleteOriginalEnd);
            }
        }
    }

    private boolean deleteSnapshotMatchesIssuedSelection() {
        if (deletePreviewConnection == null || !inputActive
                || getCurrentInputConnection() != deletePreviewConnection) return false;
        ExtractedText current = deletePreviewConnection.getExtractedText(
                new ExtractedTextRequest(), 0);
        return current != null && current.text != null
                && current.startOffset == deletePreviewTextOffset
                && deletePreviewText.equals(current.text.toString())
                && deleteIssuedSelections.contains(selectionKey(
                        current.startOffset + current.selectionStart,
                        current.startOffset + current.selectionEnd));
    }

    private static long selectionKey(int start, int end) {
        return ((long) start << 32) ^ (end & 0xffffffffL);
    }

    private void sendOneTapBackspace() {
        InputConnection ic = getCurrentInputConnection();
        if (!inputActive || ic == null) return;
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL));
    }

    private void resetDeletePreview() {
        beginDeletePreviewRunnable = null;
        deletePreviewConnection = null;
        deletePreviewText = null;
        deletePreviewTextOffset = 0;
        deleteOriginalStart = -1;
        deleteOriginalEnd = -1;
        deletePreviewStart = -1;
        deletePreviewEnd = -1;
        deletePointerId = -1;
        deletePreviewActive = false;
        deleteIssuedSelections.clear();
    }

    private void updateRecordButtonUI(boolean recording) {
        refreshTroubleshootingBanner();
        boolean wasRecording = isRecording;
        isRecording = recording;
        if (!recording) recordingPaused = false;
        if (recording && !wasRecording) {
            finishTroubleshooting("cancel");
            troubleshooting = TroubleshootingSession.start(this, "ime", "ime");
            recordingStartedElapsedMs = SystemClock.elapsedRealtime();
            pauseStartedElapsedMs = -1L;
            accumulatedPausedMs = 0L;
        } else if (!recording && wasRecording) {
            finishCaptureDiagnostics();
        }
        if (recordContainer != null) {
            recordContainer.setVisibility(recording ? View.GONE : View.VISIBLE);
        }
        if (recordingActions != null) {
            recordingActions.setVisibility(recording ? View.VISIBLE : View.GONE);
        }
        if (switchKeyboardButton != null) {
            switchKeyboardButton.setVisibility(recording ? View.GONE : View.VISIBLE);
        }
        updateRecordingActionsUi();
        tintRecordButton(recording);
        if (recording) {
            if (statusView != null) {
                statusView.setText(recordingPaused ? "Paused" : "Listening...");
            }
            if (hintView != null) hintView.setText("Pause, cancel, or insert");
        } else {
            if (micLevelView != null) micLevelView.setLevel(0f);
            updateUiState();
        }
    }

    private void updateRecordingActionsUi() {
        if (pauseResumeButton == null) return;
        pauseResumeButton.setImageResource(
                recordingPaused ? R.drawable.ic_play : R.drawable.ic_pause);
        pauseResumeButton.setContentDescription(getString(
                recordingPaused ? R.string.ime_resume_recording : R.string.ime_pause_recording));
        pauseResumeButton.setSelected(recordingPaused);
        if (isRecording && statusView != null) {
            statusView.setText(recordingPaused ? "Paused" : "Listening...");
        }
    }

    /** Tints the record key while keeping every keyboard action icon white. */
    private void tintRecordButton(boolean recording) {
        int keyAttr = recording
                ? com.google.android.material.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorPrimaryContainer;
        if (recordKey != null) {
            recordKey.setBackgroundTintList(ColorStateList.valueOf(
                    MaterialColors.getColor(recordKey, keyAttr)));
        }
        if (micIcon != null) {
            micIcon.setColorFilter(getColor(R.color.white));
        }
    }

    @Override
    public void onDestroy() {
        finishTroubleshooting("destroy");
        unbindLongRecordingState();
        cancelDeletePreview();
        DiagnosticLog.event(getFilesDir(), "ime_stop", "process=ime");
        super.onDestroy();
        cleanupNative();
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
    }

    // Native methods
    private native void initNative(RustInputMethodService service);
    private native void cleanupNative();
    private native void startRecording(boolean autoStop, int trailingPauseMillis);
    private native void stopRecording();
    private native boolean finishRecording();
    private native void cancelRecording();
    private native boolean setRecordingPaused(boolean paused);

    // Called from Rust
    public void onStatusUpdate(String status) {
        mainHandler.post(() -> {
            boolean error = NativeStatus.isError(status);
            DiagnosticLog.event(getFilesDir(), "ime_state",
                    "state=" + NativeStatus.diagnosticLabel(status));
            lastStatus = status;
            if (error) {
                finishTroubleshooting("error");
                processingResult = false;
                recordingSession.onError();
                if (isRecording) updateRecordButtonUI(false);
            }
            updateUiState();
            if (pendingSwitchBack && error) {
                pendingSwitchBack = false;
                switchToOtherInputMethod();
            }
            if (pauseAudioActive && error) {
                audioPauser.abandon(this);
                pauseAudioActive = false;
            }
        });
    }

    /** Native endpointing callback; dispatch on the IME thread and preserve the editor. */
    public void onAutoStop() {
        mainHandler.post(() -> {
            if (isRecording && !recordingPaused) {
                stopForResult(false, false);
            }
        });
    }

    private void updateUiState() {
        boolean recoveryPending = hasPendingTranscriptRecovery();
        boolean isWaiting = NativeStatus.isWaiting(lastStatus);
        boolean isTranscribing = NativeStatus.isProcessing(lastStatus);
        boolean isError = NativeStatus.isError(lastStatus);
        boolean isProcessing = processingResult || isTranscribing || isWaiting;

        // Don't show internal loading states to the user
        if (statusView != null && !isRecording) {
            if (recoveryPending) {
                statusView.setText(R.string.ime_recovery_ready);
            } else if (isError) {
                statusView.setText(lastStatus);
            } else if (isProcessing) {
                statusView.setText("Processing...");
            } else {
                statusView.setText(R.string.ime_tap_to_record);
            }
        }

        if (processingRing != null) {
            processingRing.setVisibility(
                    isProcessing && !recoveryPending ? View.VISIBLE : View.GONE);
        }

        // Disable button only during transcription/processing/waiting or fatal errors
        if (recordContainer != null) {
            boolean disable = !recoveryPending && (isProcessing || isError);
            recordContainer.setEnabled(!disable);
            recordContainer.setAlpha(disable ? 0.5f : 1.0f);
        }

        if (hintView != null && !isRecording) {
            hintView.setText(recoveryPending
                    ? R.string.ime_recovery_open
                    : (isProcessing
                            ? R.string.ime_processing_on_device : R.string.ime_tap_to_record));
        }
    }

    // Called from Rust
    public void onTextTranscribed(String text) {
        mainHandler.post(() -> {
            boolean discarded = recordingSession.isDiscarded();
            if (discarded) {
                finishTroubleshooting("discarded");
                processingResult = false;
                lastStatus = "Ready";
                recordingSession.finish();
                updateRecordButtonUI(false);
                releasePauseAudio();
                if (pendingSwitchBack) {
                    pendingSwitchBack = false;
                    switchToOtherInputMethod();
                }
                return;
            }
            if (text == null || text.trim().isEmpty()) {
                finishTroubleshooting("empty_decode");
                processingResult = false;
                lastStatus = "Ready";
                // Nothing recognized — don't insert a stray space.
                recordingSession.finish();
                updateRecordButtonUI(false);
                if (statusView != null) statusView.setText(R.string.ime_tap_to_record);
                if (pauseAudioActive) {
                    audioPauser.abandon(this);
                    pauseAudioActive = false;
                }
                if (pendingSwitchBack) {
                    pendingSwitchBack = false;
                    switchToOtherInputMethod();
                }
                return;
            }
            // Stage away from the IME main thread. A process death or editor
            // lifecycle race after the atomic write can leave a duplicate
            // recovery copy, but cannot lose the recognized text. Only
            // explicit Cancel bypasses this contract.
            RECOVERY_IO.execute(() -> {
                boolean staged = PendingImeTranscriptStore.saveIfEmpty(this, text);
                mainHandler.post(() -> deliverStagedTranscription(text, staged, 0));
            });
        });
    }

    /** Content-free timing emitted only when the user opted into diagnostics. */
    public void onTranscriptionMetrics(long audioMs, long computeMs, int threads) {
        DiagnosticLog.event(getFilesDir(), "timing",
                "process=ime,audio_ms=" + Math.max(0L, audioMs)
                        + ",duration_ms=" + Math.max(0L, computeMs)
                        + ",cpu_threads=" + Math.max(0, threads));
    }

    public long getTroubleshootingSessionId() {
        TroubleshootingSession session = troubleshooting;
        return session == null ? 0L : session.id();
    }

    public void onPipelineMetrics(long sessionId, long prepareMs, long decodeMs, long postprocessMs, long stopWaitMs, int windows, int family, int warm) {
        TroubleshootingSession session = troubleshooting;
        if (session != null && session.id() == sessionId) session.pipeline(prepareMs, decodeMs, postprocessMs, stopWaitMs, windows, family, warm);
    }

    private void refreshTroubleshootingBanner() {
        if (inputView != null) inputView.findViewById(R.id.ime_troubleshooting_banner).setVisibility(
                DiagnosticLog.isTroubleshootingEnabled(getFilesDir()) ? View.VISIBLE : View.GONE);
    }

    private void finishTroubleshooting(String reason) {
        TroubleshootingSession session = troubleshooting;
        troubleshooting = null;
        if (session != null) session.finish(reason);
    }

    private void deliverStagedTranscription(String text, boolean staged, int attempt) {
        InputConnection current = getCurrentInputConnection();
        boolean safeTarget = recordingSession.canDeliverToCurrent(
                current, editorSafeForDictation, inputActive);
        String committed = text + " ";
        boolean inserted = safeTarget && commitTranscribedText(current, committed);

        if (!inserted && attempt < DELIVERY_RETRY_LIMIT
                && !recordingSession.isDiscarded()) {
            // Compose and other reactive editors can briefly remove and replace
            // their InputConnection while keeping the same visible composer.
            // Give Android a bounded window to expose the replacement instead
            // of opening OSTT over the app or declaring the result lost.
            processingResult = true;
            lastStatus = "Processing...";
            if (statusView != null) statusView.setText(R.string.ime_waiting_for_composer);
            mainHandler.postDelayed(
                    () -> deliverStagedTranscription(text, staged, attempt + 1),
                    DELIVERY_RETRY_DELAY_MS);
            return;
        }

        processingResult = false;
        lastStatus = "Ready";
        recordingSession.finish();
        finishTroubleshooting(inserted ? "delivered" : "retained");
        releasePauseAudio();
        if (inserted && staged) {
            updateRecordButtonUI(false);
            RECOVERY_IO.execute(() -> {
                boolean cleared = PendingImeTranscriptStore.clear(this);
                mainHandler.post(() -> finishTranscriptDelivery(inserted, cleared));
            });
            return;
        }
        if (!inserted) {
            pendingSwitchBack = false;
            if (staged) {
                if (statusView != null) statusView.setText(R.string.ime_recovery_ready);
            } else {
                EmergencyTranscriptBuffer.putIfEmpty(text);
                copyEmergencyTranscript(text);
            }
        }
        finishTranscriptDelivery(inserted, true);
    }

    private void finishTranscriptDelivery(boolean inserted, boolean recoveryCleared) {
        if (inserted && !recoveryCleared) {
            String message = getString(R.string.ime_recovery_inserted_clear_failed);
            if (statusView != null) statusView.setText(message);
            Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
        }
        updateRecordButtonUI(false);
        if (inserted && statusView != null && recoveryCleared) {
            statusView.setText(R.string.ime_tap_to_record);
        }
        if (inserted && pendingSwitchBack) {
            pendingSwitchBack = false;
            switchToOtherInputMethod();
        }
    }

    private void switchToOtherInputMethod() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            if (switchToPreviousInputMethod() || switchToNextInputMethod(false)) return;
        } else if (imm != null && getWindow() != null && getWindow().getWindow() != null) {
            android.os.IBinder token = getWindow().getWindow().getAttributes().token;
            if (token != null && (imm.switchToLastInputMethod(token)
                    || imm.switchToNextInputMethod(token, false))) return;
        }
        if (imm != null) imm.showInputMethodPicker();
    }

    private void openTranscriptRecovery() {
        Intent recovery = new Intent(this, ImeTranscriptRecoveryActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            startActivity(recovery);
        } catch (RuntimeException unavailable) {
            // The keyboard keeps a visible recovery affordance, and the main
            // app exposes the same saved record. Never put transcript content
            // in this log or in the Intent.
            Log.w(TAG, "Could not open transcript recovery activity");
        }
    }

    private void copyEmergencyTranscript(String text) {
        try {
            if (TranscriptClipboard.copy(this, text)) {
                String message = getString(R.string.ime_recovery_storage_failed);
                if (statusView != null) statusView.setText(message);
                Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
            }
        } catch (RuntimeException ignored) {
            // EmergencyTranscriptBuffer still backs the recovery activity in
            // this IME process. Do not log the transcript or exception text.
        }
    }

    private boolean hasPendingTranscriptRecovery() {
        return PendingImeTranscriptStore.hasRecordFast(this)
                || EmergencyTranscriptBuffer.peek() != null;
    }

    // Commits transcribed text into the active input connection, optionally
    // selecting it afterwards (select_transcription setting).
    private boolean commitTranscribedText(InputConnection ic, String committed) {
        try {
            if (!ic.commitText(committed, 1)) return false;

            if (!pendingSwitchBack && new File(getFilesDir(), "select_transcription").exists()) {
                ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
                if (et != null) {
                    int end = et.selectionStart;
                    int start = end - committed.length();
                    if (start >= 0) {
                        ic.setSelection(start, end);
                    }
                }
            }
            return true;
        } catch (RuntimeException rejected) {
            return false;
        }
    }


    public void onAudioLevel(float level) {
        if (isRecording && !recordingPaused && micLevelView != null) {
            mainHandler.post(() -> micLevelView.setLevel(level));
        }
    }

    private void finishCaptureDiagnostics() {
        if (recordingStartedElapsedMs < 0L) return;
        if (troubleshooting != null) troubleshooting.event("processing", "stop");
        long now = SystemClock.elapsedRealtime();
        if (pauseStartedElapsedMs >= 0L) {
            accumulatedPausedMs += Math.max(0L, now - pauseStartedElapsedMs);
        }
        DiagnosticLog.event(getFilesDir(), "timing",
                "process=ime,duration_ms=" + Math.max(0L, now - recordingStartedElapsedMs)
                        + ",paused_ms=" + Math.max(0L, accumulatedPausedMs));
        recordingStartedElapsedMs = -1L;
        pauseStartedElapsedMs = -1L;
        accumulatedPausedMs = 0L;
    }

    private boolean isPauseAudioEnabled() {
        return new File(getFilesDir(), "pause_audio").exists();
    }

    private boolean isAutoStopEnabled() {
        return new File(getFilesDir(), "auto_stop").exists();
    }

    /** "Record in background" is default ON; the marker file is the opt-out. */
    private boolean isStopOnHideEnabled() {
        return new File(getFilesDir(), "stop_on_hide").exists();
    }
}
