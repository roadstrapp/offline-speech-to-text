package dev.notune.transcribe;

import android.inputmethodservice.InputMethodService;
import android.view.LayoutInflater;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ProgressBar;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.view.MotionEvent;
import android.view.inputmethod.EditorInfo;
import android.content.res.ColorStateList;
import android.view.ContextThemeWrapper;
import java.io.File;
import java.util.HashSet;
import java.util.Set;

import com.google.android.material.color.MaterialColors;

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
    private android.widget.ImageView micIcon;
    private ProgressBar progressBar;
    private View backspaceButton;
    private View spaceButton;
    private View enterButton;
    private View switchKeyboardButton;
    private View inputView;
    private MicLevelView micLevelView;
    private View recordCircle;
    private View recordingActions;
    private android.widget.ImageButton pauseResumeButton;
    private View cancelRecordingButton;
    private View transcribeKeepButton;
    private View transcribeSwitchButton;
    // Night flag the current input view was inflated with, so it can be rebuilt
    // if the theme preference changes while this process stays alive.
    private boolean viewIsNight = false;
    private Handler mainHandler;
    private boolean isRecording = false;
    private boolean recordingPaused = false;
    private boolean pendingSwitchBack = false;
    private String lastStatus = "Initializing...";
    private static final long DELETE_PREVIEW_DELAY = 180;
    private static final long SPACE_REPEAT_INITIAL_DELAY = 400;
    private static final float DELETE_CLUSTER_STEP_DP = 18f;
    private static final long REPEAT_INTERVAL = 50; // ms between repeats
    private Runnable spaceRepeatRunnable;
    private final AudioFocusPauser audioPauser = new AudioFocusPauser();
    private boolean pauseAudioActive = false;
    // Whether an editor is currently focused/started for input. Tracked via
    // onStartInput/onFinishInput because getCurrentInputConnection() returns a
    // non-null no-op connection when nothing is focused, so commitText would be
    // silently dropped.
    private boolean inputActive = false;
    // Whether the keyboard window is currently on screen. Some frameworks
    // (notably OEM builds) call onWindowShown again for events that don't
    // follow an onWindowHidden, e.g. tapping the text area to move the
    // cursor while the keyboard stays visible. Auto-record must only fire on
    // a genuine hidden -> shown transition, or a cursor tap starts a
    // recording the user never asked for.
    private boolean windowVisible = false;
    private boolean editorSafeForDictation = false;
    private final ImeRecordingSession recordingSession = new ImeRecordingSession();
    private InputConnection transcriptionTargetConnection;
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
        DiagnosticLog.event(getFilesDir(), "ime_start", "process=ime");
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
            view.setOnApplyWindowInsetsListener((v, insets) -> {
                int paddingBottom = insets.getSystemWindowInsetBottom();
                int originalPaddingBottom = v.getPaddingTop();
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), originalPaddingBottom + paddingBottom);
                return insets;
            });

            statusView = view.findViewById(R.id.ime_status_text);
            progressBar = view.findViewById(R.id.ime_progress);
            recordContainer = view.findViewById(R.id.ime_record_container);
            micIcon = view.findViewById(R.id.ime_mic_icon);
            micLevelView = view.findViewById(R.id.ime_mic_level);
            recordCircle = view.findViewById(R.id.ime_record_circle);
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
            switchKeyboardButton.setOnClickListener(v -> switchToPreviousInputMethod());

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
                    android.view.inputmethod.EditorInfo editorInfo = getCurrentInputEditorInfo();
                    if (editorInfo == null) {
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER));
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER));
                        return;
                    }
                    int imeOptions = editorInfo.imeOptions;
                    int action = imeOptions & android.view.inputmethod.EditorInfo.IME_MASK_ACTION;
                    boolean noEnterAction = (imeOptions & android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;

                    // If the editor flags IME_FLAG_NO_ENTER_ACTION (e.g. multi-line fields in
                    // messaging apps like Signal), or if there's no meaningful action, insert a
                    // newline. Otherwise perform the editor action (Go, Search, Send, etc.).
                    if (!noEnterAction && (
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT)) {
                        ic.performEditorAction(action);
                    } else {
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER));
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER));
                    }
                }
            });

            recordContainer.setOnClickListener(v -> {
                if (!recordContainer.isEnabled()) return;
                if (isRecording) return;

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
                transcriptionTargetConnection = null;
                recordingSession.begin(getCurrentInputConnection());
                recordingPaused = false;
                startRecording();
                updateRecordButtonUI(true);
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
        bindLongRecordingState();
        if (isRecording) {
            // A background recording is still running (record-in-background
            // setting): restore the recording UI.
            updateRecordButtonUI(true);
            return;
        }
        if (wasVisible) {
            // Not a real hidden -> shown transition (e.g. a cursor tap in the
            // text area); never auto-start a recording from here.
            return;
        }
        if (new File(getFilesDir(), "auto_record").exists()) {
            if (editorSafeForDictation && inputActive
                    && getCurrentInputConnection() != null
                    && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                if (isPauseAudioEnabled()) {
                    audioPauser.request(this);
                    pauseAudioActive = true;
                }
                recordingSession.begin(getCurrentInputConnection());
                transcriptionTargetConnection = null;
                recordingPaused = false;
                startRecording();
                updateRecordButtonUI(true);
            }
        }
    }

    @Override
    public void onWindowHidden() {
        super.onWindowHidden();
        windowVisible = false;
        unbindLongRecordingState();
        if (isRecording && isStopOnHideEnabled()) {
            // Focus loss is always fail-closed: do not leave an invisible microphone capture active.
            stopAndDiscard();
        }
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        cancelDeletePreview();
        super.onStartInput(attribute, restarting);
        inputActive = true;
        editorSafeForDictation = attribute != null
                && !ImeTextEdit.isSensitiveEditor(attribute.inputType, attribute.imeOptions);
        if (!editorSafeForDictation) unbindLongRecordingState();
        if (isRecording && !editorSafeForDictation) stopAndDiscard();
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        inputActive = true;
        editorSafeForDictation = info != null
                && !ImeTextEdit.isSensitiveEditor(info.inputType, info.imeOptions);
        if (isRecording && (!editorSafeForDictation
                || getCurrentInputConnection() != recordingSession.origin())) {
            stopAndDiscard();
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

    }

    @Override
    public void onFinishInput() {
        cancelDeletePreview();
        unbindLongRecordingState();
        if (isRecording) {
            stopAndDiscard();
        }
        super.onFinishInput();
        inputActive = false;
        editorSafeForDictation = false;
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
        pendingSwitchBack = false;
        recordingSession.discard();
        transcriptionTargetConnection = null;
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
            // A UI/native disagreement could leave a live microphone behind.
            // Fail closed and discard instead of pretending pause succeeded.
            stopAndDiscard();
            return;
        }
        recordingPaused = requestedPause;
        if (recordingPaused) {
            releasePauseAudio();
        } else if (isPauseAudioEnabled() && !pauseAudioActive) {
            audioPauser.request(this);
            pauseAudioActive = true;
        }
        updateRecordingActionsUi();
    }

    private void stopTranscribe(boolean switchKeyboard) {
        if (!isRecording) return;
        pendingSwitchBack = switchKeyboard;
        InputConnection current = getCurrentInputConnection();
        transcriptionTargetConnection = recordingSession.canCommitTo(
                current, editorSafeForDictation, inputActive) ? current : null;
        if (transcriptionTargetConnection == null) {
            recordingSession.discard();
            try { cancelRecording(); } catch (Throwable ignored) { }
            updateRecordButtonUI(false);
            releasePauseAudio();
            pendingSwitchBack = false;
            if (switchKeyboard) switchToPreviousInputMethod();
            return;
        }
        try {
            stopRecording();
        } catch (Throwable t) {
            Log.w(TAG, "stopRecording failed; discarding and switching", t);
            recordingSession.discard();
            transcriptionTargetConnection = null;
            try { cancelRecording(); } catch (Throwable ignored) { }
            pendingSwitchBack = false;
            updateRecordButtonUI(false);
            releasePauseAudio();
            if (switchKeyboard) switchToPreviousInputMethod();
            return;
        }
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
        isRecording = recording;
        if (!recording) recordingPaused = false;
        // Keep the screen awake while recording so it never sleeps mid-capture
        // and cuts the recording short. Cleared automatically once we stop.
        if (inputView != null) {
            inputView.setKeepScreenOn(recording);
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
            if (statusView != null) statusView.setText("Processing...");
            if (hintView != null) hintView.setText("Tap to Record");
            if (micLevelView != null) micLevelView.setLevel(0f);
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

    /** Tints the round record button + mic: idle = primary, recording = error. */
    private void tintRecordButton(boolean recording) {
        int circleAttr = recording
                ? com.google.android.material.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorPrimaryContainer;
        int iconAttr = recording
                ? com.google.android.material.R.attr.colorOnPrimary
                : com.google.android.material.R.attr.colorOnPrimaryContainer;
        if (recordCircle != null) {
            recordCircle.setBackgroundTintList(ColorStateList.valueOf(
                    MaterialColors.getColor(recordCircle, circleAttr)));
        }
        if (micIcon != null) {
            micIcon.setColorFilter(MaterialColors.getColor(micIcon, iconAttr));
        }
    }

    @Override
    public void onDestroy() {
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
    private native void startRecording();
    private native void stopRecording();
    private native void cancelRecording();
    private native boolean setRecordingPaused(boolean paused);

    // Called from Rust
    public void onStatusUpdate(String status) {
        mainHandler.post(() -> {
            DiagnosticLog.event(getFilesDir(), "ime_state", "state=" + diagnosticState(status));
            lastStatus = status;
            if (status != null && status.startsWith("Error")) {
                transcriptionTargetConnection = null;
                recordingSession.onError();
                if (isRecording) updateRecordButtonUI(false);
            }
            updateUiState();
            if (pendingSwitchBack && status != null && status.startsWith("Error")) {
                pendingSwitchBack = false;
                switchToPreviousInputMethod();
            }
            if (pauseAudioActive && status != null && status.startsWith("Error")) {
                audioPauser.abandon(this);
                pauseAudioActive = false;
            }
        });
    }

    private static String diagnosticState(String status) {
        if (status == null) return "unknown";
        if (status.startsWith("Error")) return "error";
        if (status.contains("Loading") || status.contains("Initializing")) return "loading";
        if (status.contains("Transcribing") || status.contains("Processing")) return "processing";
        if (status.contains("Waiting")) return "idle";
        if (status.equals("Ready")) return "ready";
        return "unknown";
    }

    private void updateUiState() {
        boolean isLoading = lastStatus.contains("Loading") || lastStatus.contains("Initializing");
        boolean isWaiting = lastStatus.contains("Waiting");
        boolean isTranscribing = lastStatus.contains("Transcribing") || lastStatus.contains("Processing");
        boolean isError = lastStatus.startsWith("Error");
        boolean isReady = lastStatus.equals("Ready");

        // Don't show internal loading states to the user
        if (statusView != null && !isRecording) {
            if (isError) {
                statusView.setText(lastStatus);
            } else if (isTranscribing || isWaiting) {
                statusView.setText("Processing...");
            } else {
                statusView.setText("Tap to Record");
            }
        }

        // Hide progress bar - don't expose model loading to user
        if (progressBar != null) {
            progressBar.setVisibility(View.GONE);
        }

        // Disable button only during transcription/processing/waiting or fatal errors
        if (recordContainer != null) {
            boolean disable = isTranscribing || isWaiting || isError;
            recordContainer.setEnabled(!disable);
            recordContainer.setAlpha(disable ? 0.5f : 1.0f);
        }

        if (hintView != null && !isRecording) {
            hintView.setText("Tap to Record");
        }
    }

    // Called from Rust
    public void onTextTranscribed(String text) {
        mainHandler.post(() -> {
            InputConnection requestedTarget = transcriptionTargetConnection;
            transcriptionTargetConnection = null;
            boolean safeTarget = editorSafeForDictation && inputActive
                    && requestedTarget != null
                    && getCurrentInputConnection() == requestedTarget
                    && recordingSession.canCommitTo(requestedTarget, true, true);
            boolean discarded = recordingSession.isDiscarded();
            if (discarded || !safeTarget) {
                recordingSession.finish();
                updateRecordButtonUI(false);
                releasePauseAudio();
                if (!discarded) {
                    String message = getString(R.string.ime_editor_changed);
                    if (statusView != null) statusView.setText(message);
                    // A toast remains visible after the switch action hides
                    // this IME, so a fail-closed result is never silent.
                    Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
                }
                if (pendingSwitchBack) {
                    pendingSwitchBack = false;
                    switchToPreviousInputMethod();
                }
                return;
            }
            if (text == null || text.trim().isEmpty()) {
                // Nothing recognized — don't insert a stray space.
                recordingSession.finish();
                updateRecordButtonUI(false);
                if (statusView != null) statusView.setText("Tap to Record");
                if (pauseAudioActive) {
                    audioPauser.abandon(this);
                    pauseAudioActive = false;
                }
                if (pendingSwitchBack) {
                    pendingSwitchBack = false;
                    switchToPreviousInputMethod();
                }
                return;
            }
            String committed = text + " ";
            commitTranscribedText(requestedTarget, committed);
            recordingSession.finish();
            if (pauseAudioActive) {
                audioPauser.abandon(this);
                pauseAudioActive = false;
            }
            updateRecordButtonUI(false);
            if (statusView != null) statusView.setText("Tap to Record");
            if (pendingSwitchBack) {
                pendingSwitchBack = false;
                switchToPreviousInputMethod();
            }
        });
    }

    // Commits transcribed text into the active input connection, optionally
    // selecting it afterwards (select_transcription setting).
    private void commitTranscribedText(InputConnection ic, String committed) {
        ic.commitText(committed, 1);

        if (!pendingSwitchBack && new File(getFilesDir(), "select_transcription").exists()) {
            android.view.inputmethod.ExtractedText et = ic.getExtractedText(
                new android.view.inputmethod.ExtractedTextRequest(), 0);
            if (et != null) {
                int end = et.selectionStart;
                int start = end - committed.length();
                if (start >= 0) {
                    ic.setSelection(start, end);
                }
            }
        }
    }


    public void onAudioLevel(float level) {
        if (micLevelView != null) {
            mainHandler.post(() -> micLevelView.setLevel(level));
        }
    }

    private boolean isPauseAudioEnabled() {
        return new File(getFilesDir(), "pause_audio").exists();
    }

    /** "Record in background" is default ON; the marker file is the opt-out. */
    private boolean isStopOnHideEnabled() {
        return new File(getFilesDir(), "stop_on_hide").exists();
    }
}
