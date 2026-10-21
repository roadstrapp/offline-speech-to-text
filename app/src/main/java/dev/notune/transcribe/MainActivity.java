package dev.notune.transcribe;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.Checkable;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;

import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.OnBackPressedCallback;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.widget.ImageViewCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int PERM_REQ_CODE = 101;
    private static final int REQ_VOICE_TEST = 202;
    private static final int REQ_SAVE_DIAGNOSTICS = 203;
    private static final int MIN_TOUCH_TARGET_DP = 48;
    private static final int MAX_SHARED_DIAGNOSTIC_FILES = 8;

    static {
        NativeLibraries.load();
    }

    private String homeDestination = "home";
    private String nativeLoadStatus = "Waiting";
    private OnBackPressedCallback navigationBack;
    private boolean updatingNavigation;
    private TextView statusText;
    private TextView voiceStatusText;
    private ImageView voiceStatusIcon;
    private Button voiceGrantButton;
    private Button voiceTryButton;
    private TextView imeStatusText;
    private Button imePickerButton;
    private Button imeSettingsButton;
    private Button imeRecoveryButton;
    private Button startSubsButton;
    private Button benchButton;
    private TextView benchResultText;
    private volatile boolean micRequestedThisSession;
    private boolean modelSetupPromptChecked;
    private boolean nativeInitRequested;
    private TroubleshootingControls troubleshootingControls;
    private final ExecutorService diagnosticIo = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "diagnostic-export");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        modelSetupPromptChecked = savedInstanceState != null;
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.text_status);
        voiceStatusText = findViewById(R.id.text_voice_status);
        voiceStatusIcon = findViewById(R.id.img_voice_status);
        voiceGrantButton = findViewById(R.id.btn_voice_grant);
        voiceTryButton = findViewById(R.id.btn_voice_try);
        imeStatusText = findViewById(R.id.text_ime_status);
        imePickerButton = findViewById(R.id.btn_ime_picker);
        imeRecoveryButton = findViewById(R.id.btn_ime_recovery);
        startSubsButton = findViewById(R.id.btn_subs_start);
        imeSettingsButton = findViewById(R.id.btn_ime_settings);
        Button voiceHelpButton = findViewById(R.id.btn_voice_help);
        voiceGrantButton.setOnClickListener(v -> handleMicrophoneAction());
        voiceTryButton.setOnClickListener(v -> launchVoiceTest());
        findViewById(R.id.btn_foreground_record).setOnClickListener(v ->
                startActivity(new Intent(this, ForegroundRecordingActivity.class)));
        voiceHelpButton.setOnClickListener(v -> showHelpDialog());

        imeSettingsButton.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        imePickerButton.setOnClickListener(v -> handleImeAction());
        imeRecoveryButton.setOnClickListener(v ->
                startActivity(new Intent(this, ImeTranscriptRecoveryActivity.class)));

        startSubsButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, LiveSubtitleActivity.class);
            startActivity(intent);
        });

        findViewById(R.id.btn_subs_advanced).setOnClickListener(v -> showSubsAdvancedDialog());

        findViewById(R.id.btn_models).setOnClickListener(v ->
                startActivity(new Intent(this, ModelsActivity.class)));

        benchButton = findViewById(R.id.btn_benchmark);
        benchResultText = findViewById(R.id.text_bench_result);
        benchButton.setOnClickListener(v -> runBenchmark());
        ((TextView) findViewById(R.id.text_version)).setText(
                getString(R.string.about_version, BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE));

        // Settings stored as marker files in filesDir (readable from the :ime
        // process and native code without a content provider).
        bindMarkerSwitch(R.id.switch_auto_record, "auto_record", false);
        bindMarkerSwitch(R.id.switch_select_transcription, "select_transcription", false);
        bindMarkerSwitch(R.id.switch_pause_audio, "pause_audio", false);
        bindMarkerSwitch(R.id.switch_background_predecode, "background_predecode", false);
        // Record-in-background defaults to ON; its marker file is the opt-out.
        bindMarkerSwitch(R.id.switch_record_background, "stop_on_hide", true);
        setupAutoStopDelay();
        bindMarkerSwitch(R.id.switch_auto_stop, "auto_stop", false,
                findViewById(R.id.auto_stop_delay_controls));
        setupRecognizePanelPosition();

        CompoundButton diagnosticSwitch = findViewById(R.id.switch_diagnostic_logging);
        diagnosticSwitch.setChecked(DiagnosticLog.isEnabled(getFilesDir()));
        diagnosticSwitch.setOnCheckedChangeListener((buttonView, enabled) -> {
            try {
                DiagnosticLog.setEnabled(getFilesDir(), enabled);
                if (enabled) {
                    DiagnosticLog.event(getFilesDir(), "app_start",
                            "process=app,version_code=" + BuildConfig.VERSION_CODE);
                }
            } catch (IOException e) {
                snackbar(getString(R.string.diagnostic_log_error));
            }
            if (troubleshootingControls != null) troubleshootingControls.resume();
        });
        troubleshootingControls = new TroubleshootingControls(this);
        findViewById(R.id.btn_clear_diagnostic_log).setOnClickListener(v -> {
            try {
                DiagnosticLog.clear(getFilesDir());
                snackbar(getString(R.string.diagnostic_log_cleared));
            } catch (IOException e) {
                snackbar(getString(R.string.diagnostic_log_error));
            }
        });
        findViewById(R.id.btn_save_diagnostic_log).setOnClickListener(v ->
                chooseDiagnosticExportDestination());
        findViewById(R.id.btn_share_diagnostic_log).setOnClickListener(v ->
                shareDiagnosticLog());
        findViewById(R.id.btn_source).setOnClickListener(v -> openLink(
                "https://gitworkshop.dev/npub1jg2qmt3rxjksq65v3nk7dq6yaz2c4uf3qt9epdhxwms2xwd47j2s38a228/relay.ngit.dev/offline-speech-to-text"));
        findViewById(R.id.btn_license).setOnClickListener(v -> openLink(
                "https://github.com/notune/android_transcribe_app/blob/master/LICENSE"));

        // Live subtitle line limit: 2 (default), 4, or 0 = unlimited.
        RadioGroup subsLinesGroup = findViewById(R.id.rg_subtitle_lines);
        int subsLines = SubtitlePrefs.getMaxLines(this);
        if (subsLines == 4) {
            subsLinesGroup.check(R.id.rb_subs_4);
        } else if (subsLines == 0) {
            subsLinesGroup.check(R.id.rb_subs_all);
        } else {
            subsLinesGroup.check(R.id.rb_subs_2);
        }
        subsLinesGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int lines = checkedId == R.id.rb_subs_4 ? 4
                    : checkedId == R.id.rb_subs_all ? 0 : 2;
            SubtitlePrefs.setMaxLines(this, lines);
        });

        RadioGroup themeGroup = findViewById(R.id.rg_theme);
        switch (ThemePrefs.getMode(this)) {
            case AppCompatDelegate.MODE_NIGHT_NO:
                themeGroup.check(R.id.rb_theme_light);
                break;
            case AppCompatDelegate.MODE_NIGHT_YES:
                themeGroup.check(R.id.rb_theme_dark);
                break;
            default:
                themeGroup.check(R.id.rb_theme_system);
                break;
        }
        themeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int newMode;
            if (checkedId == R.id.rb_theme_light) {
                newMode = AppCompatDelegate.MODE_NIGHT_NO;
            } else if (checkedId == R.id.rb_theme_dark) {
                newMode = AppCompatDelegate.MODE_NIGHT_YES;
            } else {
                newMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            }
            if (newMode != ThemePrefs.getMode(this)) {
                ThemePrefs.setMode(this, newMode);
            }
        });

        // Initial check
        updateVoiceInputStatus();
        updateImeStatus();
        updateImeRecoveryStatus();

        configureAccessibility();
        setupNavigation(savedInstanceState);

        // Compact builds intentionally ship without a model. Do not start the
        // native loader until either an imported or retained built-in model is
        // actually present; the model manager reloads native state after a
        // verified download or import completes.
        maybeStartNativeModelLoad();
        // The home setup steps replace the old unsolicited model-choice dialog.
        updateHomeModelSummary();
    }

    private void setupNavigation(Bundle saved) {
        BottomNavigationView navigation = findViewById(R.id.main_navigation);
        navigationBack = new OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() {
                showHomeDestination(HomeNavigation.parent(homeDestination));
            }
        };
        getOnBackPressedDispatcher().addCallback(this, navigationBack);
        navigation.setItemActiveIndicatorEnabled(false);
        navigation.setOnItemReselectedListener(item -> {
            if (updatingNavigation) return;
            if (item.getItemId() == R.id.navigation_settings) showHomeDestination("settings");
            else if (item.getItemId() == R.id.navigation_home) showHomeDestination("home");
        });
        navigation.setOnItemSelectedListener(item -> {
            if (updatingNavigation) return true;
            if (item.getItemId() == R.id.navigation_dictionary) {
                startActivity(new Intent(this, CustomTermsActivity.class));
                return false;
            }
            showHomeDestination(item.getItemId() == R.id.navigation_home ? "home" : "settings");
            return true;
        });
        ((MaterialToolbar) findViewById(R.id.toolbar)).setNavigationOnClickListener(
                view -> showHomeDestination(HomeNavigation.parent(homeDestination)));
        int[] buttons = {R.id.nav_keyboard, R.id.nav_keyboard_settings, R.id.nav_recording,
                R.id.nav_subtitles, R.id.nav_appearance, R.id.nav_privacy,
                R.id.nav_advanced, R.id.nav_about};
        String[] pages = {"keyboard", "keyboard", "recording", "subtitles", "appearance",
                "privacy", "advanced", "about"};
        for (int index = 0; index < buttons.length; index++) {
            final String page = pages[index];
            findViewById(buttons[index]).setOnClickListener(view -> showHomeDestination(page));
        }
        findViewById(R.id.nav_models).setOnClickListener(
                view -> startActivity(new Intent(this, ModelsActivity.class)));
        findViewById(R.id.btn_privacy_info).setOnClickListener(view ->
                new MaterialAlertDialogBuilder(this).setTitle(R.string.home_diagnostics_info)
                        .setMessage(R.string.setting_diagnostic_logging_desc)
                        .setPositiveButton(android.R.string.ok, null).show());
        findViewById(R.id.btn_predecode_info).setOnClickListener(view ->
                new MaterialAlertDialogBuilder(this).setTitle(R.string.home_predecode_info)
                        .setMessage(R.string.background_predecode_description)
                        .setPositiveButton(android.R.string.ok, null).show());
        showHomeDestination(saved == null ? "home" : saved.getString("home_destination"));
    }

    private void showHomeDestination(String requested) {
        homeDestination = HomeNavigation.restore(requested);
        int[] ids = {R.id.page_home, R.id.page_settings, R.id.page_keyboard,
                R.id.page_recording, R.id.page_subtitles, R.id.page_appearance,
                R.id.page_privacy, R.id.page_advanced, R.id.page_about};
        int[] titles = {R.string.app_name, R.string.home_settings_title, R.string.section_ime,
                R.string.home_recording_title, R.string.section_subs,
                R.string.section_appearance, R.string.home_privacy_title,
                R.string.home_advanced_title, R.string.home_about_title};
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        for (int index = 0; index < ids.length; index++) {
            View page = findViewById(ids[index]);
            boolean selected = HomeNavigation.PAGES[index].equals(homeDestination);
            page.setVisibility(selected ? View.VISIBLE : View.GONE);
            if (selected) toolbar.setTitle(titles[index]);
        }
        boolean nested = !"home".equals(homeDestination) && !"settings".equals(homeDestination);
        if (nested) toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
        else toolbar.setNavigationIcon((android.graphics.drawable.Drawable) null);
        androidx.core.view.ViewCompat.setAccessibilityHeading(toolbar, true);
        toolbar.setNavigationContentDescription(R.string.home_back);
        navigationBack.setEnabled(!"home".equals(homeDestination));
        BottomNavigationView navigation = findViewById(R.id.main_navigation);
        updatingNavigation = true;
        navigation.setSelectedItemId("home".equals(homeDestination)
                ? R.id.navigation_home : R.id.navigation_settings);
        updatingNavigation = false;
        ((android.widget.ScrollView) findViewById(R.id.main_scroll)).scrollTo(0, 0);
    }

    private void updateHomeModelSummary() {
        String label = getString(R.string.home_choose_model);
        if (hasUsableSpeechModel()) {
            String selected = "";
            File marker = new File(getFilesDir(), "active_model");
            try {
                if (marker.isFile() && marker.length() <= 512) {
                    selected = new String(java.nio.file.Files.readAllBytes(marker.toPath()),
                            java.nio.charset.StandardCharsets.UTF_8).trim();
                }
            } catch (IOException ignored) { }
            label = getString(R.string.home_on_device_model, ModelPresentation.nameForFile(selected));
        }
        ((TextView) findViewById(R.id.text_home_model)).setText(label);
        ((Button) findViewById(R.id.btn_models)).setText(hasUsableSpeechModel()
                ? R.string.home_model_change : R.string.home_model_get);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("home_destination", homeDestination);
    }

    private void setupAutoStopDelay() {
        Spinner spinner = findViewById(R.id.spinner_auto_stop_delay);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this, R.array.auto_stop_delays, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        final int[] savedPosition = {AutoStopDelay.positionOf(AutoStopDelay.get(getFilesDir()))};
        spinner.setSelection(savedPosition[0], false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == savedPosition[0]) return;
                try {
                    AutoStopDelay.set(getFilesDir(), AutoStopDelay.atPosition(position));
                    savedPosition[0] = position;
                } catch (IOException e) {
                    spinner.setSelection(savedPosition[0], false);
                    snackbar(getString(R.string.setting_save_failed));
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private void setupRecognizePanelPosition() {
        Spinner spinner = findViewById(R.id.spinner_recognize_panel_position);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this, R.array.recognize_panel_positions,
                android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(RecognizePanelPrefs.get(this), false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                RecognizePanelPrefs.set(MainActivity.this, position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private void configureAccessibility() {
        int[] headingIds = {
                R.id.heading_models,
                R.id.heading_setup,
                R.id.heading_subtitles,
                R.id.heading_appearance,
                R.id.heading_settings,
                R.id.heading_benchmark,
                R.id.heading_privacy,
                R.id.heading_page_settings,
                R.id.heading_page_keyboard
        };
        for (int headingId : headingIds) {
            ViewCompat.setAccessibilityHeading(findViewById(headingId), true);
        }

        int minimumPixels = Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                MIN_TOUCH_TARGET_DP,
                getResources().getDisplayMetrics()));
        applyMinimumTouchTarget(findViewById(R.id.home_content), minimumPixels);
    }

    private static void applyMinimumTouchTarget(View view, int minimumPixels) {
        if (view.isClickable() || view instanceof Checkable) {
            view.setMinimumWidth(minimumPixels);
            view.setMinimumHeight(minimumPixels);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                applyMinimumTouchTarget(group.getChildAt(index), minimumPixels);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (troubleshootingControls != null) troubleshootingControls.resume();
        // Re-check on return from the keyboard chooser, settings, or a test run.
        updateVoiceInputStatus();
        updateImeStatus();
        updateImeRecoveryStatus();
        updateHomeModelSummary();
        if (nativeLoadStatus != null && nativeLoadStatus.startsWith("Error"))
            nativeInitRequested = false;
        // Returning from model management may make the first usable model
        // available without recreating this Activity.
        maybeStartNativeModelLoad();
    }

    @Override
    protected void onPause() {
        if (troubleshootingControls != null) troubleshootingControls.pause();
        super.onPause();
    }

    private void maybeStartNativeModelLoad() {
        if (!hasUsableSpeechModel()) {
            statusText.setText(R.string.status_model_required);
            startSubsButton.setEnabled(false);
            benchButton.setEnabled(false);
            return;
        }
        benchButton.setEnabled(nativeLoadStatus.startsWith("Ready"));
        if (!nativeInitRequested) {
            nativeInitRequested = true;
            initNative(this);
        }
    }

    /**
     * Reflects whether the primary voice-input flow is ready. Apps (SwiftKey,
     * Firefox, …) fire {@link RecognizerIntent#ACTION_RECOGNIZE_SPEECH} via
     * {@code startActivityForResult}, which {@link RecognizeActivity} handles.
     * The flow works when (a) the mic permission is granted and (b) our activity
     * is what Android resolves that intent to — either because it is the sole
     * handler or because the user picked us as the default. This is deliberately
     * judged on the activity path, NOT on being the default {@code RecognitionService}.
     */
    private void updateVoiceInputStatus() {
        boolean micGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        File requestedMarker = new File(getFilesDir(), "mic_permission_requested");
        boolean requestedBefore = micRequestedThisSession || requestedMarker.exists() || micGranted;
        if (micGranted && !requestedMarker.exists()) {
            persistMicrophoneRequested();
        }
        SetupState.Microphone state = SetupState.microphone(
                micGranted, requestedBefore,
                shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO));

        voiceTryButton.setEnabled(state == SetupState.Microphone.GRANTED);
        if (state == SetupState.Microphone.GRANTED && !hasUsableSpeechModel()) {
            setVoiceStatus(true, getString(R.string.home_microphone_allowed));
            voiceTryButton.setEnabled(false);
            voiceGrantButton.setVisibility(View.GONE);
            return;
        }
        if (state == SetupState.Microphone.GRANTED
                && (nativeLoadStatus.startsWith("Error")
                    || nativeLoadStatus.startsWith("Loading")
                    || nativeLoadStatus.startsWith("Waiting"))) {
            setVoiceStatus(false, getString(nativeLoadStatus.startsWith("Error")
                    ? R.string.home_model_attention : R.string.home_model_loading));
            voiceTryButton.setEnabled(false);
            voiceGrantButton.setVisibility(View.GONE);
            return;
        }
        switch (state) {
            case NOT_REQUESTED:
                setVoiceStatus(false, getString(R.string.voice_status_not_requested));
                voiceGrantButton.setText(R.string.btn_voice_grant);
                voiceGrantButton.setVisibility(View.VISIBLE);
                break;
            case DENIED:
                setVoiceStatus(false, getString(R.string.voice_status_denied));
                voiceGrantButton.setText(R.string.btn_voice_try_again);
                voiceGrantButton.setVisibility(View.VISIBLE);
                break;
            case DENIED_PERMANENTLY:
                setVoiceStatus(false, getString(R.string.voice_status_denied_permanently));
                voiceGrantButton.setText(R.string.btn_open_app_settings);
                voiceGrantButton.setVisibility(View.VISIBLE);
                break;
            case GRANTED:
                voiceGrantButton.setVisibility(View.GONE);
                if (isOurAppDefaultRecognizer()) {
                    setVoiceStatus(true, getString(R.string.voice_status_ready));
                } else {
                    setVoiceStatus(false, getString(R.string.voice_status_almost));
                }
                break;
        }
    }

    private void updateImeStatus() {
        InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        ComponentName ours = new ComponentName(this, RustInputMethodService.class);
        boolean enabled = isOurImeEnabled(manager);
        String selectedId = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        ComponentName selected = ComponentName.unflattenFromString(
                selectedId == null ? "" : selectedId);
        boolean isSelected = selected != null
                && getPackageName().equals(selected.getPackageName())
                && ours.getClassName().equals(selected.getClassName());

        switch (SetupState.ime(enabled, isSelected)) {
            case DISABLED:
                imeStatusText.setText(R.string.ime_status_disabled);
                imePickerButton.setText(R.string.btn_ime_enable);
                imeSettingsButton.setVisibility(View.GONE);
                break;
            case ENABLED:
                imeStatusText.setText(R.string.ime_status_enabled);
                imePickerButton.setText(R.string.btn_ime_choose);
                imeSettingsButton.setVisibility(View.VISIBLE);
                break;
            case SELECTED:
                imeStatusText.setText(R.string.ime_status_selected);
                imePickerButton.setText(R.string.btn_ime_switch);
                imeSettingsButton.setVisibility(View.VISIBLE);
                break;
        }
    }

    private void updateImeRecoveryStatus() {
        imeRecoveryButton.setVisibility(
                PendingImeTranscriptStore.hasRecordFast(this) ? View.VISIBLE : View.GONE);
    }

    private void showInitialModelSetupIfNeeded() {
        if (modelSetupPromptChecked || hasUsableSpeechModel()) return;
        modelSetupPromptChecked = true;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.model_setup_title)
                .setMessage(R.string.model_setup_body)
                .setPositiveButton(R.string.model_setup_action, (dialog, which) ->
                        startActivity(new Intent(this, ModelsActivity.class)))
                .setNegativeButton(R.string.model_setup_later, null)
                .show();
    }

    private boolean hasUsableSpeechModel() {
        return SpeechModelInventory.hasUsableModel(
                getFilesDir(), BuildConfig.HAS_BUNDLED_MODEL);
    }

    private void handleImeAction() {
        InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        boolean enabled = isOurImeEnabled(manager);
        if (!enabled) {
            startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
        } else if (manager != null) {
            manager.showInputMethodPicker();
        }
    }

    private boolean isOurImeEnabled(InputMethodManager manager) {
        if (manager == null) return false;
        for (InputMethodInfo info : manager.getEnabledInputMethodList()) {
            if (getPackageName().equals(info.getPackageName())
                    && RustInputMethodService.class.getName().equals(info.getServiceName())) {
                return true;
            }
        }
        return false;
    }

    /** True if our RecognizeActivity is what RECOGNIZE_SPEECH resolves to. */
    private boolean isOurAppDefaultRecognizer() {
        Intent recog = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        ResolveInfo resolved = getPackageManager()
                .resolveActivity(recog, PackageManager.MATCH_DEFAULT_ONLY);
        return resolved != null && resolved.activityInfo != null
                && getPackageName().equals(resolved.activityInfo.packageName);
    }

    private void setVoiceStatus(boolean ready, String message) {
        voiceStatusText.setText(message);
        voiceStatusIcon.setImageResource(ready ? R.drawable.ic_check_circle : R.drawable.ic_error);
        int tint = ready
                ? ContextCompat.getColor(this, R.color.status_ok)
                : themeColor(com.google.android.material.R.attr.colorError);
        ImageViewCompat.setImageTintList(voiceStatusIcon, ColorStateList.valueOf(tint));
    }

    private int themeColor(int attrRes) {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(attrRes, tv, true);
        return tv.resourceId != 0 ? ContextCompat.getColor(this, tv.resourceId) : tv.data;
    }

    /**
     * One-tap self-test: fires the exact intent a keyboard's mic does. If ours is
     * the sole handler it launches straight away; if several apps handle it the
     * system shows a chooser, where the user can pick us and "Always" — which sets
     * the default and flips the status to ready on return.
     */
    private void launchVoiceTest() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        try {
            startActivityForResult(intent, REQ_VOICE_TEST);
        } catch (android.content.ActivityNotFoundException e) {
            Log.w(TAG, "No RECOGNIZE_SPEECH handler", e);
            snackbar(getString(R.string.voice_test_failed));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SAVE_DIAGNOSTICS) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                writeDiagnosticExport(data.getData());
            }
            return;
        }
        if (requestCode == REQ_VOICE_TEST && resultCode == RESULT_OK && data != null) {
            ArrayList<String> results =
                    data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            String heard = (results != null && !results.isEmpty()) ? results.get(0) : null;
            snackbar(heard != null && !heard.trim().isEmpty()
                    ? getString(R.string.voice_test_ok, heard)
                    : getString(R.string.voice_test_empty));
        }
        // onResume() also refreshes, but do it here too for an immediate update.
        updateVoiceInputStatus();
    }

    private void chooseDiagnosticExportDestination() {
        diagnosticIo.execute(() -> {
            try {
                if (DiagnosticLog.snapshot(getFilesDir()).length == 0) {
                    postDiagnosticMessage(R.string.diagnostic_log_empty);
                    return;
                }
                runOnUiThread(() -> {
                    if (!canPresentDiagnosticUi()) return;
                    try {
                        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                                .addCategory(Intent.CATEGORY_OPENABLE)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TITLE, diagnosticExportFileName());
                        startActivityForResult(intent, REQ_SAVE_DIAGNOSTICS);
                    } catch (ActivityNotFoundException exception) {
                        snackbar(getString(R.string.diagnostic_log_export_error));
                    }
                });
            } catch (IOException | RuntimeException exception) {
                postDiagnosticMessage(R.string.diagnostic_log_export_error);
            }
        });
    }

    private void writeDiagnosticExport(Uri destination) {
        diagnosticIo.execute(() -> {
            try {
                byte[] snapshot = DiagnosticLog.snapshot(getFilesDir());
                if (snapshot.length == 0) {
                    postDiagnosticMessage(R.string.diagnostic_log_empty);
                    return;
                }
                try (OutputStream output = getContentResolver()
                        .openOutputStream(destination, "wt")) {
                    if (output == null) throw new IOException("No output stream");
                    output.write(snapshot);
                }
                postDiagnosticMessage(R.string.diagnostic_log_saved);
            } catch (IOException | RuntimeException exception) {
                postDiagnosticMessage(R.string.diagnostic_log_export_error);
            }
        });
    }

    private void shareDiagnosticLog() {
        diagnosticIo.execute(() -> {
            try {
                byte[] snapshot = DiagnosticLog.snapshot(getFilesDir());
                if (snapshot.length == 0) {
                    postDiagnosticMessage(R.string.diagnostic_log_empty);
                    return;
                }
                File directory = new File(getCacheDir(), "diagnostics");
                if (!directory.isDirectory() && !directory.mkdirs()) {
                    throw new IOException("Could not create diagnostics cache");
                }
                pruneSharedDiagnosticFiles(directory);
                // A unique immutable snapshot prevents a later share from
                // changing the bytes behind an earlier recipient's grant.
                File export = new File(directory,
                        "ostt-diagnostics-share-" + System.currentTimeMillis() + "-"
                                + System.nanoTime() + ".txt");
                try (OutputStream output = new FileOutputStream(export, false)) {
                    output.write(snapshot);
                }
                if (!export.setReadOnly()) {
                    throw new IOException("Could not protect diagnostics snapshot");
                }
                Uri uri = FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", export);
                Intent send = new Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_STREAM, uri);
                send.setClipData(ClipData.newUri(getContentResolver(), export.getName(), uri));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> {
                    if (!canPresentDiagnosticUi()) return;
                    try {
                        startActivity(Intent.createChooser(send,
                                getString(R.string.diagnostic_log_share_chooser)));
                    } catch (ActivityNotFoundException exception) {
                        snackbar(getString(R.string.diagnostic_log_export_error));
                    }
                });
            } catch (IOException | RuntimeException exception) {
                postDiagnosticMessage(R.string.diagnostic_log_export_error);
            }
        });
    }

    private static void pruneSharedDiagnosticFiles(File directory) {
        File[] files = directory.listFiles((parent, name) ->
                name.startsWith("ostt-diagnostics-share-") && name.endsWith(".txt"));
        if (files == null || files.length < MAX_SHARED_DIAGNOSTIC_FILES) return;
        java.util.Arrays.sort(files,
                java.util.Comparator.comparingLong(File::lastModified));
        int remove = files.length - MAX_SHARED_DIAGNOSTIC_FILES + 1;
        for (int index = 0; index < remove; index++) {
            // Cache cleanup is best effort; a failed delete cannot expose data
            // or prevent the fresh explicit share.
            files[index].delete();
        }
    }

    private boolean canPresentDiagnosticUi() {
        return !isFinishing() && !isDestroyed();
    }

    private void postDiagnosticMessage(int message) {
        runOnUiThread(() -> {
            if (canPresentDiagnosticUi()) snackbar(getString(message));
        });
    }

    private static String diagnosticExportFileName() {
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new Date());
        return "ostt-diagnostics-" + timestamp + ".txt";
    }

    /**
     * Explains the adb escape hatch for the MediaProjection consent dialog:
     * once the PROJECT_MEDIA app-op is set to allow, the system permission
     * activity returns RESULT_OK immediately, so subtitles start without the
     * "Start recording or casting?" sheet. No app code depends on this — the
     * normal dialog flow is the untouched fallback.
     */
    private void showSubsAdvancedDialog() {
        String allowCmd = "adb shell appops set --user 0 " + getPackageName()
                + " PROJECT_MEDIA allow";
        String resetCmd = "adb shell appops set --user 0 " + getPackageName()
                + " PROJECT_MEDIA default";
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.subs_advanced_title)
                .setMessage(getString(R.string.subs_advanced_body, allowCmd, resetCmd))
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.subs_advanced_copy, (d, w) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("adb", allowCmd));
                    snackbar(getString(R.string.subs_advanced_copied));
                })
                .show();
    }

    private void showHelpDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.voice_help_title)
                .setMessage(R.string.voice_help_body)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void snackbar(String message) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG).show();
    }

    private void openLink(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException exception) {
            snackbar(getString(R.string.models_no_browser));
        }
    }

    /**
     * Binds a switch to a marker file in filesDir. With {@code inverted}, the
     * file's presence means the switch is OFF (used for default-on settings).
     */
    private void bindMarkerSwitch(int switchId, String fileName, boolean inverted) {
        bindMarkerSwitch(switchId, fileName, inverted, null);
    }

    private void bindMarkerSwitch(int switchId, String fileName, boolean inverted,
                                  View dependentControl) {
        CompoundButton sw = findViewById(switchId);
        File marker = new File(getFilesDir(), fileName);
        sw.setChecked(marker.exists() != inverted);
        if (dependentControl != null) {
            dependentControl.setVisibility(sw.isChecked() ? View.VISIBLE : View.GONE);
        }
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            boolean shouldExist = isChecked != inverted;
            if (shouldExist) {
                try {
                    marker.createNewFile();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to create " + fileName + " file", e);
                }
            } else {
                marker.delete();
            }
            if (dependentControl != null) {
                dependentControl.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            }
        });
    }

    private void handleMicrophoneAction() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) return;
        File requested = new File(getFilesDir(), "mic_permission_requested");
        if ((micRequestedThisSession || requested.exists())
                && !shouldShowRequestPermissionRationale(
                android.Manifest.permission.RECORD_AUDIO)) {
            Intent settings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(settings);
            return;
        }
        micRequestedThisSession = true;
        persistMicrophoneRequested();
        requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, PERM_REQ_CODE);
    }

    private void persistMicrophoneRequested() {
        micRequestedThisSession = true;
        new Thread(() -> {
            File requested = new File(getFilesDir(), "mic_permission_requested");
            try {
                requested.createNewFile();
            } catch (IOException e) {
                Log.w(TAG, "Could not persist microphone request state", e);
            }
        }, "mic-permission-state").start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERM_REQ_CODE) {
            updateVoiceInputStatus();
        }
    }

    // --- Benchmark ----------------------------------------------------------

    /**
     * Transcribes the bundled test clip through the active model and shows the
     * speed as a real-time factor. The clip is a fixed English recording so
     * results are comparable across models and devices; the run uses the
     * current model settings (language hint, translate).
     */
    private void runBenchmark() {
        benchButton.setEnabled(false);
        benchResultText.setVisibility(View.VISIBLE);
        benchResultText.setText(R.string.bench_running);

        new Thread(() -> {
            float[] samples;
            try {
                samples = BenchmarkClip.read(this);
            } catch (IOException e) {
                Log.e(TAG, "Failed to read benchmark clip", e);
                runOnUiThread(() -> {
                    benchResultText.setText(getString(R.string.bench_error, e.getMessage()));
                    benchButton.setEnabled(true);
                });
                return;
            }
            benchmarkNative(this, samples, samples.length);
        }, "benchmark-load").start();
    }

    // Called from Rust when the benchmark run finishes.
    public void onBenchmarkResult(float audioSecs, float computeSecs, String error) {
        runOnUiThread(() -> {
            benchButton.setEnabled(true);
            benchResultText.setVisibility(View.VISIBLE);
            if (error != null && !error.isEmpty()) {
                benchResultText.setText(getString(R.string.bench_error, error));
            } else {
                benchResultText.setText(getString(R.string.bench_result,
                        String.format(java.util.Locale.getDefault(), "%.1f", audioSecs),
                        String.format(java.util.Locale.getDefault(), "%.1f", computeSecs),
                        String.format(java.util.Locale.getDefault(), "%.1f",
                                computeSecs > 0 ? audioSecs / computeSecs : 0)));
            }
        });
    }

    // Called from Rust
    public void onStatusUpdate(String status) {
        runOnUiThread(() -> {
            nativeLoadStatus = status == null ? "" : status;
            statusText.setText(nativeLoadStatus);
            updateHomeModelSummary();
            updateVoiceInputStatus();
            // "Ready" may carry a suffix, e.g. "Ready (this model can't translate)".
            if (nativeLoadStatus.startsWith("Ready")) {
                startSubsButton.setEnabled(true);
                benchButton.setEnabled(true);
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (troubleshootingControls != null) troubleshootingControls.destroy();
        diagnosticIo.shutdown();
        super.onDestroy();
    }

    private native void initNative(MainActivity activity);

    private native void benchmarkNative(MainActivity activity, float[] samples, int length);

    // Same-signature instrumentation only; no UI, IPC or transcript-returning API.
    private native long[] verifyPreparedWindowsNative(float[] samples);
}
