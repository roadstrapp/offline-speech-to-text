package dev.notune.transcribe;

import android.Manifest;
import android.content.Intent;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.format.Formatter;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lets the user pick the speech model: the recommended Parakeet model, or any
 * transcribe.cpp GGUF model imported from a local file. The primary action
 * downloads, verifies, installs, and activates the recommended model inside
 * the app. Browser links and the system file picker remain explicit manual
 * alternatives.
 *
 * The selection is stored as marker files in filesDir (same pattern as the
 * other settings), read by the Rust engine loader:
 *   - {@code active_model}: file name of the GGUF under {@code files/models/},
 *     absent/empty = bundled model when present, otherwise no model.
 *   - {@code model_language}: optional language hint for GGUF models.
 */
public class ModelsActivity extends AppCompatActivity {
    private static final String TAG = "ModelsActivity";
    private static final int REQ_PICK_MODEL = 301;
    /** Keep this much free space beyond the model file itself. */
    private static final long FREE_SPACE_MARGIN = 64L * 1024 * 1024;
    private static final ModelDownloadLifecycle DOWNLOAD_LIFECYCLE =
            new ModelDownloadLifecycle();
    private static final String DOWNLOAD_ORIGIN_RECOMMENDED = "recommended";
    private static final String DOWNLOAD_ORIGIN_CATALOG = "catalog";

    static {
        NativeLibraries.load();
    }

    /** One entry in the curated download list. Names are proper nouns and stay
     *  untranslated; descriptions are string resources so they localize. */
    private static final class ModelLink {
        final String name;
        final int descRes;
        final String size;
        final String url;

        ModelLink(String name, int descRes, String size, String url) {
            this.name = name;
            this.descRes = descRes;
            this.size = size;
            this.url = url;
        }
    }

    private static final class CatalogRow {
        final Button action;
        final View progressArea;
        final ProgressBar progress;
        final TextView progressText;

        CatalogRow(Button action, View progressArea, ProgressBar progress,
                   TextView progressText) {
            this.action = action;
            this.progressArea = progressArea;
            this.progress = progress;
            this.progressText = progressText;
        }
    }


    private LinearLayout modelList;
    private LinearLayout downloadCatalog;
    private Spinner catalogFamilySpinner;
    private final List<Button> catalogDownloadButtons = new ArrayList<>();
    private final Map<String, CatalogRow> catalogRows = new LinkedHashMap<>();
    private final List<String> catalogFamilies = new ArrayList<>();
    private TextView statusText;
    private TextView activeSummaryText;
    private View importArea;
    private ProgressBar importBar;
    private TextView importText;
    private Button importButton;
    private Button retryButton;
    private Button downloadButton;
    private TextView recommendationText;
    private Spinner goalSpinner;
    private ModelDescriptor recommendedModel;
    private boolean recommendationUsesInstalledModel;
    private View recommendedProgressArea;
    private ProgressBar recommendedProgress;
    private TextView recommendedProgressText;
    private Spinner languageSpinner;
    private List<String> languageTags = new ArrayList<>();
    private com.google.android.material.materialswitch.MaterialSwitch translateSwitch;

    private boolean recommendationUsesBundledModel;
    private final ModelDownloadLifecycle.Observer downloadObserver = this::renderDownloadState;
    private final AtomicBoolean importInFlight = new AtomicBoolean(false);
    private final AtomicBoolean manualReloadInFlight = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_models);

        modelList = findViewById(R.id.model_list);
        downloadCatalog = findViewById(R.id.download_catalog);
        catalogFamilySpinner = findViewById(R.id.spinner_catalog_family);
        statusText = findViewById(R.id.txt_model_status);
        activeSummaryText = findViewById(R.id.text_active_model_summary);
        importArea = findViewById(R.id.import_area);
        importBar = findViewById(R.id.import_progress);
        importText = findViewById(R.id.txt_import);
        importButton = findViewById(R.id.btn_import);
        retryButton = findViewById(R.id.btn_model_retry);
        downloadButton = findViewById(R.id.btn_download_recommended);
        recommendationText = findViewById(R.id.text_model_recommendation);
        goalSpinner = findViewById(R.id.spinner_model_goal);
        recommendedProgressArea = findViewById(R.id.recommended_download_progress_area);
        recommendedProgress = findViewById(R.id.recommended_download_progress);
        recommendedProgressText = findViewById(R.id.txt_recommended_download_progress);
        languageSpinner = findViewById(R.id.spinner_language);

        translateSwitch = findViewById(R.id.switch_translate);

        importButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQ_PICK_MODEL);
        });

        findViewById(R.id.btn_model_links).setOnClickListener(v -> showLinksDialog());
        // Bundled builds switch locally. Small developer builds use this tap
        // as explicit consent for a verified download; browsing websites stays
        // a separate, clearly secondary action below manual import.
        downloadButton.setOnClickListener(v -> {
            if (recommendedModel == null) return;
            if (recommendationUsesBundledModel) {
                selectModel(null);
            } else if (recommendationUsesInstalledModel) {
                selectModel(recommendedModel.fileName);
            } else {
                beginDownload(recommendedModel, DOWNLOAD_ORIGIN_RECOMMENDED);
            }
        });
        findViewById(R.id.btn_cancel_recommended_download)
                .setOnClickListener(v -> DOWNLOAD_LIFECYCLE.cancel());
        retryButton.setOnClickListener(v -> {
            if (!manualReloadInFlight.compareAndSet(false, true)) return;
            retryButton.setEnabled(false);
            showModelStatus(getString(R.string.models_loading));
            // JNI captures the activity reference and performs model reset/load
            // on its own Rust worker thread; this call itself returns promptly.
            reloadModelNative(this);
        });

        setupLanguageSpinner();
        setupThreadsSpinner();
        setupGoalSpinner();
        setupDownloadCatalog();

        translateSwitch.setChecked(!readConfig("model_translate").isEmpty());
        translateSwitch.setOnCheckedChangeListener((btn, checked) -> {
            writeConfig("model_translate", checked ? "1" : "");
            updateRecommendation();
            showModelStatus(getString(R.string.models_loading));
            reloadModelNative(this);
        });
        refreshList();
        updateRecommendation();
    }

    @Override
    protected void onStart() {
        super.onStart();
        DOWNLOAD_LIFECYCLE.attach(downloadObserver);
    }

    @Override
    protected void onStop() {
        DOWNLOAD_LIFECYCLE.detach(downloadObserver);
        super.onStop();
    }

    private void setupDownloadCatalog() {
        catalogFamilies.clear();
        catalogFamilies.add(getString(R.string.models_catalog_all));
        LinkedHashSet<String> families = new LinkedHashSet<>();
        for (ModelDescriptor model : ModelCatalog.verifiedDownloads()) {
            families.add(model.architecture);
        }
        catalogFamilies.addAll(families);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, catalogFamilies);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        catalogFamilySpinner.setAdapter(adapter);
        int defaultFamily = catalogFamilies.indexOf("Parakeet");
        if (defaultFamily >= 0) catalogFamilySpinner.setSelection(defaultFamily, false);
        catalogFamilySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(
                    AdapterView<?> parent, View view, int position, long id) {
                renderDownloadCatalog();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        renderDownloadCatalog();
    }

    private void setupGoalSpinner() {
        List<String> labels = Arrays.asList(
                getString(R.string.models_goal_balanced),
                getString(R.string.models_goal_compact),
                getString(R.string.models_goal_multilingual),
                getString(R.string.models_goal_translate),
                getString(R.string.models_goal_quality));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        goalSpinner.setAdapter(adapter);
        goalSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(
                    AdapterView<?> parent, View view, int position, long id) {
                updateRecommendation();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private ModelRecommendation.Goal selectedGoal() {
        if (goalSpinner == null) return ModelRecommendation.Goal.BALANCED;
        switch (goalSpinner.getSelectedItemPosition()) {
            case 1: return ModelRecommendation.Goal.COMPACT;
            case 2: return ModelRecommendation.Goal.MULTILINGUAL;
            case 3: return ModelRecommendation.Goal.TRANSLATE;
            case 4: return ModelRecommendation.Goal.QUALITY;
            default: return ModelRecommendation.Goal.BALANCED;
        }
    }

    private void renderDownloadCatalog() {
        if (downloadCatalog == null || catalogFamilySpinner == null) return;
        downloadCatalog.removeAllViews();
        catalogDownloadButtons.clear();
        catalogRows.clear();
        int position = catalogFamilySpinner.getSelectedItemPosition();
        String selected = position > 0 && position < catalogFamilies.size()
                ? catalogFamilies.get(position) : null;
        boolean busy = DOWNLOAD_LIFECYCLE.isRunning() || importInFlight.get();
        ModelRecommendation.DeviceProfile device = currentDeviceProfile();
        ModelDownloadLifecycle.Snapshot download = DOWNLOAD_LIFECYCLE.snapshot();
        for (ModelDescriptor model : ModelCatalog.verifiedDownloads()) {
            boolean isRunningModel = download.state == ModelDownloadLifecycle.State.RUNNING
                    && model.id.equals(download.modelId);
            if (selected != null && !selected.equals(model.architecture) && !isRunningModel) continue;
            View row = getLayoutInflater().inflate(
                    R.layout.item_model_download, downloadCatalog, false);
            ((TextView) row.findViewById(R.id.txt_download_model_name))
                    .setText(model.displayName);
            ((TextView) row.findViewById(R.id.txt_download_model_meta)).setText(
                    getString(R.string.models_catalog_meta, model.architecture,
                            Formatter.formatShortFileSize(this, model.byteSize)));
            ((TextView) row.findViewById(R.id.txt_download_model_guidance)).setText(
                    getString(R.string.models_catalog_guidance,
                            sizeTierLabel(model.sizeTier()),
                            Formatter.formatShortFileSize(this, model.minimumRamBytes),
                            deviceFitLabel(ModelGuidance.fit(model, device))));
            Button action = row.findViewById(R.id.btn_download_model);
            File installed = new File(modelsDir(), model.fileName);
            boolean bundled = BuildConfig.HAS_BUNDLED_MODEL
                    && DefaultModelContract.FILE_NAME.equals(model.fileName);
            boolean verified = VerifiedModelProvenance.isVerified(
                    getFilesDir(), installed, model);
            boolean cpuCompatible = device.arm64
                    && (!model.requiresDotProduct || device.dotProduct)
                    && (!model.requiresFp16 || device.fp16);
            boolean enoughRam = device.totalRamBytes >= model.minimumRamBytes;
            boolean enoughStorage = device.freeStorageBytes >= model.minimumFreeBytes;
            ModelDownloadLifecycle.Snapshot downloadSnapshot = DOWNLOAD_LIFECYCLE.snapshot();
            boolean failedHere = downloadSnapshot.state == ModelDownloadLifecycle.State.FAILED
                    && model.id.equals(downloadSnapshot.modelId);
            if (bundled) {
                action.setText(R.string.models_included);
            } else if (verified) {
                action.setText(R.string.models_installed);
            } else if (!cpuCompatible) {
                action.setText(R.string.models_device_unsupported);
            } else if (!enoughRam) {
                action.setText(R.string.models_needs_memory);
            } else if (!enoughStorage) {
                action.setText(R.string.models_needs_storage);
            } else if (failedHere) {
                action.setText(R.string.models_retry_download);
            } else {
                action.setText(R.string.models_download);
            }
            action.setEnabled(!bundled && !verified && !busy
                    && cpuCompatible && enoughRam && enoughStorage);
            action.setOnClickListener(v -> beginDownload(model, DOWNLOAD_ORIGIN_CATALOG));
            row.findViewById(R.id.btn_cancel_model_download)
                    .setOnClickListener(v -> DOWNLOAD_LIFECYCLE.cancel());
            catalogDownloadButtons.add(action);
            catalogRows.put(model.id, new CatalogRow(
                    action,
                    row.findViewById(R.id.model_download_progress_area),
                    row.findViewById(R.id.model_download_progress),
                    row.findViewById(R.id.txt_model_download_progress)));
            downloadCatalog.addView(row);
        }
        renderProgress(DOWNLOAD_LIFECYCLE.snapshot());
    }

    private String sizeTierLabel(ModelDescriptor.SizeTier tier) {
        switch (tier) {
            case COMPACT: return getString(R.string.models_size_compact);
            case PHONE_FRIENDLY: return getString(R.string.models_size_phone);
            case LARGE: return getString(R.string.models_size_large);
            default: return getString(R.string.models_size_very_large);
        }
    }

    private String deviceFitLabel(ModelGuidance.DeviceFit fit) {
        switch (fit) {
            case GOOD: return getString(R.string.models_fit_good);
            case DEMANDING: return getString(R.string.models_fit_demanding);
            case NOT_ENOUGH_RAM: return getString(R.string.models_fit_memory);
            case NOT_ENOUGH_STORAGE: return getString(R.string.models_fit_storage);
            default: return getString(R.string.models_fit_unsupported);
        }
    }

    // --- Language selection -------------------------------------------------

    /**
     * Locales offered in the language dropdown. The empty tag is the model's
     * automatic mode; display names come from {@link Locale#getDisplayName()},
     * so they follow the device language without needing translations. Tags a
     * model doesn't support are never presented as an automatic recommendation.
     */
    private static final String[] LANGUAGE_TAGS = {
            "", "bg-BG", "hr-HR", "cs-CZ", "da-DK", "nl-NL", "en-US", "en-GB",
            "et-EE", "fi-FI", "fr-FR", "de-DE", "el-GR", "hu-HU", "it-IT",
            "lv-LV", "lt-LT", "mt-MT", "pl-PL", "pt-PT", "pt-BR", "ro-RO",
            "ru-RU", "sk-SK", "sl-SI", "es-ES", "sv-SE", "uk-UA",
            "ar-SA", "zh-CN", "hi-IN", "ja-JP", "ko-KR", "tr-TR", "ca", "id",
            "vi", "he", "ms", "ta", "no", "th", "ur", "la", "mi", "ml", "cy",
            "te", "fa", "bn", "sr", "az", "kn", "mk", "br", "eu", "is", "hy",
            "ne", "mn", "bs", "kk", "sq", "sw", "gl", "mr", "pa", "si", "km",
            "sn", "yo", "so", "af", "oc", "ka", "be", "tg", "sd", "gu", "am",
            "yi", "lo", "uz", "fo", "ht", "ps", "tk", "nn", "sa", "lb", "my",
            "bo", "tl", "mg", "as", "tt", "haw", "ln", "ha", "ba", "jw", "su",
            "yue",
    };

    private void setupLanguageSpinner() {
        String stored = readConfig("model_language");

        List<String> tags = new ArrayList<>(Arrays.asList(LANGUAGE_TAGS));
        // A value from an older version (free-text field) stays selectable.
        if (!stored.isEmpty() && !tags.contains(stored)) {
            tags.add(stored);
        }
        languageTags = tags;

        List<String> labels = new ArrayList<>(tags.size());
        for (String tag : tags) {
            labels.add(tag.isEmpty()
                    ? getString(R.string.models_language_auto)
                    : Locale.forLanguageTag(tag).getDisplayName() + " (" + tag + ")");
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languageSpinner.setAdapter(adapter);
        languageSpinner.setSelection(Math.max(0, tags.indexOf(stored)), false);
        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String tag = tags.get(position);
                updateRecommendation();
                if (tag.equals(readConfig("model_language"))) return;
                writeConfig("model_language", tag);
                ModelDescriptor activeModel = activeKnownModel();
                snackbar(getString(!tag.isEmpty() && activeModel != null
                        && !activeModel.supportsLanguage(tag)
                        ? R.string.models_language_requires_model
                        : R.string.models_language_saved));
                showModelStatus(getString(R.string.models_loading));
                reloadModelNative(ModelsActivity.this);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    // --- Inference threads --------------------------------------------------

    /**
     * CPU thread count for inference, stored in {@code model_threads}. Empty =
     * automatic (the engine derives it from the CPU topology). Offered values
     * cover the realistic range on phones; the engine treats anything invalid
     * as automatic.
     */
    private void setupThreadsSpinner() {
        Spinner spinner = findViewById(R.id.spinner_threads);
        String stored = readConfig("model_threads");

        List<String> values = new ArrayList<>(
                Arrays.asList("", "1", "2", "3", "4", "5", "6", "8"));
        if (!stored.isEmpty() && !values.contains(stored)) {
            values.add(stored);
        }

        List<String> labels = new ArrayList<>(values.size());
        for (String v : values) {
            labels.add(v.isEmpty() ? getString(R.string.models_threads_auto) : v);
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, values.indexOf(stored)), false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String value = values.get(position);
                if (value.equals(readConfig("model_threads"))) return;
                writeConfig("model_threads", value);
                showModelStatus(getString(R.string.models_loading));
                reloadModelNative(ModelsActivity.this);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    // --- Model list -------------------------------------------------------

    private File modelsDir() {
        File dir = new File(getFilesDir(), "models");
        dir.mkdirs();
        return dir;
    }

    private ModelDescriptor activeKnownModel() {
        String active = readConfig("active_model");
        if (active.isEmpty() && BuildConfig.HAS_BUNDLED_MODEL) {
            return ModelCatalog.verifiedDefault();
        }
        return ModelCatalog.findByFileName(active);
    }

    private String readConfig(String name) {
        File f = new File(getFilesDir(), name);
        if (!f.exists()) return "";
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private void writeConfig(String name, String value) {
        File f = new File(getFilesDir(), name);
        if (value == null || value.isEmpty()) {
            f.delete();
            return;
        }
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(value.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.e(TAG, "Failed to write " + name, e);
        }
    }

    private void refreshList() {
        modelList.removeAllViews();
        String active = readConfig("active_model");

        List<String> names = new ArrayList<>();
        File[] files = modelsDir().listFiles(
                (dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".gguf"));
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) names.add(f.getName());
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);

        // If the active model's file has disappeared, clear the selection.
        if (!active.isEmpty() && !names.contains(active)) {
            try {
                ImportedModelSelection.deactivate(getFilesDir());
            } catch (IOException error) {
                snackbar(getString(R.string.models_select_failed));
            }
            active = "";
        }

        if (BuildConfig.HAS_BUNDLED_MODEL) {
            addModelRow(getString(R.string.models_builtin),
                    getString(R.string.models_builtin_sub), null, active.isEmpty());
        }
        for (String name : names) {
            String size = Formatter.formatShortFileSize(
                    this, new File(modelsDir(), name).length());
            addModelRow(name, size, name, name.equals(active));
        }
        updateActiveSummary(active, names);
        updateCapabilityControls(active);
        renderDownloadCatalog();
    }

    private void updateCapabilityControls(String active) {
        boolean knownUnsupported = isVerifiedDefault(new File(modelsDir(), active))
                || (active.isEmpty() && BuildConfig.HAS_BUNDLED_MODEL);
        findViewById(R.id.translate_row).setVisibility(
                knownUnsupported ? View.GONE : View.VISIBLE);
    }

    private void updateRecommendation() {
        if (recommendationText == null || languageSpinner == null || goalSpinner == null) return;
        recommendationUsesBundledModel = false;
        recommendationUsesInstalledModel = false;
        recommendedModel = null;
        String languageTag = "";
        int position = languageSpinner.getSelectedItemPosition();
        if (position >= 0 && position < languageTags.size()) languageTag = languageTags.get(position);
        boolean wantsTranslation = selectedGoal() == ModelRecommendation.Goal.TRANSLATE
                || (translateSwitch != null && translateSwitch.isChecked());
        ModelRecommendation.Result result = ModelRecommendation.recommend(
                currentDeviceProfile(), new ModelRecommendation.Request(
                        languageTag, wantsTranslation, selectedGoal()));
        if (result.status == ModelRecommendation.Status.RECOMMENDED) {
            recommendedModel = result.model;
            boolean isDefault = DefaultModelContract.FILE_NAME.equals(result.model.fileName);
            if (BuildConfig.HAS_BUNDLED_MODEL && isDefault) {
                recommendationUsesBundledModel = true;
                boolean bundledActive = readConfig("active_model").isEmpty();
                recommendationText.setText(bundledActive
                        ? R.string.models_recommendation_included
                        : R.string.models_recommendation_included_available);
                downloadButton.setText(bundledActive
                        ? R.string.models_included : R.string.models_use_included);
                downloadButton.setEnabled(!bundledActive && !DOWNLOAD_LIFECYCLE.isRunning());
                return;
            }
            File installed = new File(modelsDir(), result.model.fileName);
            boolean verified = VerifiedModelProvenance.isVerified(
                    getFilesDir(), installed, result.model);
            boolean active = result.model.fileName.equals(readConfig("active_model"));
            if (verified && active) {
                recommendationUsesInstalledModel = true;
                recommendationText.setText(getString(
                        R.string.models_recommendation_installed, result.model.displayName));
                downloadButton.setText(R.string.models_installed);
                downloadButton.setEnabled(false);
                return;
            }
            recommendationText.setText(getString(R.string.models_recommendation_ready_details,
                    result.model.displayName, Formatter.formatShortFileSize(this, result.model.byteSize),
                    sizeTierLabel(result.model.sizeTier()),
                    Formatter.formatShortFileSize(this, result.model.minimumRamBytes)));
            downloadButton.setEnabled(!DOWNLOAD_LIFECYCLE.isRunning());
            if (verified) {
                recommendationUsesInstalledModel = true;
                downloadButton.setText(R.string.models_use_installed);
            } else if (DOWNLOAD_LIFECYCLE.snapshot().state
                    == ModelDownloadLifecycle.State.FAILED
                    && result.model.id.equals(DOWNLOAD_LIFECYCLE.snapshot().modelId)) {
                downloadButton.setText(R.string.models_retry_download);
            } else {
                downloadButton.setText(R.string.models_download_and_use);
            }
        } else {
            recommendationText.setText(getString(R.string.models_recommendation_none,
                    recommendationReason(result)));
            downloadButton.setText(R.string.models_download_and_use);
            downloadButton.setEnabled(false);
        }
        renderProgress(DOWNLOAD_LIFECYCLE.snapshot());
    }

    private ModelRecommendation.DeviceProfile currentDeviceProfile() {
        boolean arm64 = false;
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) arm64 = true;
        }
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(memory);
        boolean optimizedBackend = NativeLibraries.supportsOptimizedBackend();
        return new ModelRecommendation.DeviceProfile(
                arm64,
                optimizedBackend,
                optimizedBackend,
                memory.totalMem,
                getFilesDir().getUsableSpace(),
                null);
    }

    private String recommendationReason(ModelRecommendation.Result result) {
        if (result.reasons.contains(ModelRecommendation.Reason.LANGUAGE_UNSUPPORTED)) {
            return getString(R.string.models_reason_language);
        }
        if (result.reasons.contains(ModelRecommendation.Reason.INSUFFICIENT_STORAGE)) {
            return getString(R.string.models_reason_storage);
        }
        if (result.reasons.contains(ModelRecommendation.Reason.INSUFFICIENT_RAM)) {
            return getString(R.string.models_reason_memory);
        }
        return getString(R.string.models_reason_device);
    }

    private void beginDownload(ModelDescriptor model, String origin) {
        if (!hasUsableNetworkAccess()) {
            showNetworkAccessDialog(model, origin, null);
            return;
        }
        startDownload(model, origin);
    }

    private void startDownload(ModelDescriptor model, String origin) {
        if (DOWNLOAD_LIFECYCLE.isRunning()) return;
        downloadButton.setEnabled(false);
        importButton.setEnabled(false);
        for (Button button : catalogDownloadButtons) button.setEnabled(false);
        catalogFamilySpinner.setEnabled(false);
        setModelChoiceControlsEnabled(false);
        DOWNLOAD_LIFECYCLE.start(model.id, origin, listener ->
                ModelDownloadTask.start(getFilesDir(), model, listener));
    }

    private void renderDownloadState(ModelDownloadLifecycle.Snapshot snapshot) {
        runOnUiThread(() -> {
            if (isFinishing() || (Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
            if (snapshot.state == ModelDownloadLifecycle.State.RUNNING) {
                downloadButton.setEnabled(false);
                importButton.setEnabled(false);
                catalogFamilySpinner.setEnabled(false);
                setModelChoiceControlsEnabled(false);
                for (Button button : catalogDownloadButtons) button.setEnabled(false);
                renderProgress(snapshot);
                return;
            }
            if (snapshot.state == ModelDownloadLifecycle.State.IDLE) return;

            importButton.setEnabled(true);
            catalogFamilySpinner.setEnabled(true);
            setModelChoiceControlsEnabled(true);
            renderProgress(DOWNLOAD_LIFECYCLE.snapshot());
            if (snapshot.state == ModelDownloadLifecycle.State.COMPLETE) {
                DOWNLOAD_LIFECYCLE.clearTerminal();
                snackbar(getString(R.string.models_download_done));
                refreshList();
                updateRecommendation();
                showModelStatus(getString(R.string.models_loading));
                reloadModelNative(ModelsActivity.this);
                showRemoveNetworkAccessDialog();
            } else {
                String failure = snapshot.failure == null || snapshot.failure.trim().isEmpty()
                        ? getString(R.string.models_download_failed_unknown)
                        : snapshot.failure;
                if (DOWNLOAD_LIFECYCLE.consumeFailureAnnouncement()) {
                    snackbar(getString(R.string.models_download_failed, failure));
                    if (!hasUsableNetworkAccess()) {
                        ModelDescriptor model = ModelCatalog.findById(snapshot.modelId);
                        if (model != null) {
                            showNetworkAccessDialog(model, snapshot.origin, failure);
                        }
                    }
                }
                updateRecommendation();
                renderDownloadCatalog();
            }
        });
    }

    private void renderProgress(ModelDownloadLifecycle.Snapshot snapshot) {
        recommendedProgressArea.setVisibility(View.GONE);
        for (CatalogRow row : catalogRows.values()) row.progressArea.setVisibility(View.GONE);
        if (snapshot.state != ModelDownloadLifecycle.State.RUNNING) return;
        ProgressBar bar;
        TextView text;
        View area;
        if (DOWNLOAD_ORIGIN_RECOMMENDED.equals(snapshot.origin)
                && recommendedModel != null
                && snapshot.modelId.equals(recommendedModel.id)) {
            area = recommendedProgressArea;
            bar = recommendedProgress;
            text = recommendedProgressText;
        } else {
            CatalogRow row = catalogRows.get(snapshot.modelId);
            if (row == null) return;
            area = row.progressArea;
            bar = row.progress;
            text = row.progressText;
        }
        area.setVisibility(View.VISIBLE);
        bar.setIndeterminate(snapshot.total <= 0L);
        if (snapshot.total > 0L) {
            int percent = (int) Math.min(100L, snapshot.downloaded * 100L / snapshot.total);
            bar.setProgress(percent);
            text.setText(getString(R.string.models_downloading_progress, percent,
                    Formatter.formatShortFileSize(this, snapshot.downloaded),
                    Formatter.formatShortFileSize(this, snapshot.total)));
        } else {
            text.setText(R.string.models_downloading);
        }
    }

    private void setModelChoiceControlsEnabled(boolean enabled) {
        goalSpinner.setEnabled(enabled);
        languageSpinner.setEnabled(enabled);
        translateSwitch.setEnabled(enabled);
    }

    private boolean hasUsableNetworkAccess() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET)
                != PackageManager.PERMISSION_GRANTED) return false;
        ConnectivityManager manager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return false;
        Network active = manager.getActiveNetwork();
        NetworkCapabilities capabilities = active == null
                ? null : manager.getNetworkCapabilities(active);
        // VALIDATED is intentionally not required: Android validation can lag
        // or be suppressed by a VPN/proxy even when the explicit HTTPS
        // download will work. Let the transport report those failures.
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void showNetworkAccessDialog(ModelDescriptor model, String origin, String failure) {
        String message = getString(R.string.models_network_body);
        if (failure != null && !failure.trim().isEmpty()) {
            message += "\n\n" + getString(R.string.models_download_failed, failure);
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.models_network_title)
                .setMessage(message)
                .setPositiveButton(R.string.models_open_app_settings,
                        (dialog, which) -> openAppSettings())
                .setNegativeButton(R.string.models_retry_download,
                        (dialog, which) -> beginDownload(model, origin))
                .setNeutralButton(android.R.string.cancel, null)
                .show();
    }

    private void showRemoveNetworkAccessDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.models_remove_network_title)
                .setMessage(R.string.models_remove_network_body)
                .setPositiveButton(R.string.models_remove_network_action,
                        (dialog, which) -> openAppSettings())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openAppSettings() {
        Intent settings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        startActivity(settings);
    }

    @Override
    protected void onDestroy() {
        if (DOWNLOAD_LIFECYCLE.isRunning()
                && ModelDownloadLifecycle.shouldCancelOnDestroy(isChangingConfigurations())) {
            DOWNLOAD_LIFECYCLE.cancel();
        }
        super.onDestroy();
    }

    private void updateActiveSummary(String active, List<String> names) {
        final String title;
        final String size;
        final String provenance;
        final String language;
        final String integrity;
        if (active.isEmpty() && BuildConfig.HAS_BUNDLED_MODEL) {
            title = getString(R.string.models_builtin);
            size = Formatter.formatShortFileSize(this, DefaultModelContract.BYTE_SIZE);
            provenance = getString(R.string.models_provenance_bundled);
            language = getString(R.string.models_language_parakeet);
            integrity = getString(R.string.models_integrity_loaded);
        } else if (!active.isEmpty() && names.contains(active)) {
            File file = new File(modelsDir(), active);
            ModelDescriptor catalogModel = ModelCatalog.findByFileName(active);
            boolean verified = catalogModel != null
                    && VerifiedModelProvenance.isVerified(getFilesDir(), file, catalogModel);
            title = active;
            size = Formatter.formatShortFileSize(this, file.length());
            provenance = getString(verified
                    ? R.string.models_provenance_verified
                    : R.string.models_provenance_imported);
            language = isVerifiedDefault(file)
                    ? getString(R.string.models_language_parakeet)
                    : getString(R.string.models_language_unknown);
            // Provenance records that installation was hash-verified. The
            // active summary does not imply that every screen refresh rehashes
            // a multi-gigabyte model; readiness is reported separately below.
            integrity = getString(R.string.models_integrity_loaded);
        } else {
            activeSummaryText.setText(R.string.models_active_none);
            return;
        }
        activeSummaryText.setText(getString(R.string.models_active_summary,
                title, size, provenance, language, integrity));
    }

    /** Adds one selectable row; {@code fileName} is null for the built-in model. */
    private void addModelRow(String title, String subtitle, String fileName, boolean checked) {
        View row = getLayoutInflater().inflate(R.layout.item_model, modelList, false);
        ((TextView) row.findViewById(R.id.txt_model_name)).setText(title);
        ((TextView) row.findViewById(R.id.txt_model_sub)).setText(subtitle);
        RadioButton radio = row.findViewById(R.id.radio_model);
        radio.setChecked(checked);
        row.setOnClickListener(v -> {
            if (DOWNLOAD_LIFECYCLE.isRunning() || importInFlight.get()) {
                snackbar(getString(R.string.models_busy));
                return;
            }
            if (!checked) selectModel(fileName);
        });

        ImageButton delete = row.findViewById(R.id.btn_model_delete);
        if (fileName != null) {
            delete.setContentDescription(getString(R.string.models_delete_named, title));
            delete.setOnClickListener(v -> confirmDelete(fileName));
        } else {
            delete.setVisibility(View.GONE);
        }

        modelList.addView(row);
    }

    private void selectModel(String fileNameOrNull) {
        try {
            if (fileNameOrNull == null) {
                ImportedModelSelection.deactivate(getFilesDir());
            } else {
                ImportedModelSelection.activate(getFilesDir(), fileNameOrNull);
            }
        } catch (IOException error) {
            snackbar(getString(R.string.models_select_failed));
            return;
        }
        refreshList();
        updateRecommendation();
        showModelStatus(getString(R.string.models_loading));
        reloadModelNative(this);
    }

    private void confirmDelete(String fileName) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.models_delete)
                .setMessage(getString(R.string.models_delete_confirm, fileName))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    new File(modelsDir(), fileName).delete();
                    try {
                        VerifiedModelProvenance.forget(getFilesDir(), fileName);
                    } catch (IOException ignored) {
                        // A marker alone cannot verify the now-missing model.
                    }
                    if (fileName.equals(readConfig("active_model"))) {
                        selectModel(null);
                    } else {
                        refreshList();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private boolean isVerifiedDefault(File model) {
        return VerifiedModelProvenance.isVerified(
                getFilesDir(), model, ModelCatalog.verifiedDefault());
    }


    // --- Download links ----------------------------------------------------

    private void showLinksDialog() {
        List<ModelLink> links = new ArrayList<>();
        for (ModelDescriptor model : ModelCatalog.verifiedDownloads()) {
            links.add(new ModelLink(model.displayName, R.string.model_desc_source,
                    Formatter.formatShortFileSize(this, model.byteSize), model.sourceUrl));
        }
        links.add(new ModelLink("huggingface.co/handy-computer",
                R.string.model_desc_browse, "", "https://huggingface.co/handy-computer"));
        ArrayAdapter<ModelLink> adapter = new ArrayAdapter<ModelLink>(
                this, R.layout.item_model_link, links) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View row = convertView != null ? convertView
                        : getLayoutInflater().inflate(R.layout.item_model_link, parent, false);
                ModelLink link = links.get(position);
                ((TextView) row.findViewById(R.id.txt_link_name)).setText(link.name);
                ((TextView) row.findViewById(R.id.txt_link_size)).setText(link.size);
                ((TextView) row.findViewById(R.id.txt_link_desc)).setText(link.descRes);
                return row;
            }
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.models_links_title)
                .setAdapter(adapter, (d, which) -> {
                    Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(links.get(which).url));
                    try {
                        startActivity(view);
                    } catch (android.content.ActivityNotFoundException e) {
                        snackbar(getString(R.string.models_no_browser));
                    }
                })
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // --- Import ------------------------------------------------------------

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_MODEL || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;

        String name = queryDisplayName(uri);
        long size = querySize(uri);

        if (!ModelInstaller.isSafeModelName(name)) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.models_import_bad_title)
                    .setMessage(R.string.models_import_bad_body)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        if (size > 0 && getFilesDir().getUsableSpace() < size + FREE_SPACE_MARGIN) {
            snackbar(getString(R.string.models_import_no_space));
            return;
        }

        importModel(uri, name, size);
    }

    private void importModel(Uri uri, String name, long size) {
        if (DOWNLOAD_LIFECYCLE.isRunning()
                || !importInFlight.compareAndSet(false, true)) return;
        importButton.setEnabled(false);
        downloadButton.setEnabled(false);
        importArea.setVisibility(View.VISIBLE);
        importBar.setIndeterminate(size <= 0);
        importBar.setMax(100);
        importBar.setProgress(0);
        importText.setText(getString(R.string.models_importing, name));

        File tmp = new File(modelsDir(), name + ".part");

        new Thread(() -> {
            boolean ok = false;
            long copied = 0;
            String sha256 = "";
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(tmp)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buf = new byte[1024 * 1024];
                int read;
                while (in != null && (read = in.read(buf)) != -1) {
                    out.write(buf, 0, read);
                    digest.update(buf, 0, read);
                    copied += read;
                    if (size > 0) {
                        int pct = (int) (copied * 100 / size);
                        runOnUiThread(() -> importBar.setProgress(pct));
                    }
                }
                sha256 = toHex(digest.digest());
                ok = true;
            } catch (IOException | NoSuchAlgorithmException | RuntimeException e) {
                Log.e(TAG, "Model import failed", e);
            }

            boolean defaultMismatch = ok
                    && DefaultModelContract.FILE_NAME.equals(name)
                    && !DefaultModelContract.matches(name, copied, sha256);
            boolean moved = false;
            boolean activated = false;
            String installedName = name;
            if (ok && !defaultMismatch) {
                try {
                    installedName = ModelInstaller.installImported(getFilesDir(), tmp, name);
                    moved = true;
                    activated = true;
                } catch (IOException | RuntimeException e) {
                    Log.e(TAG, "Imported model could not be activated", e);
                }
            }
            if (!moved) tmp.delete();
            final boolean success = moved && activated;
            final String completedName = installedName;
            runOnUiThread(() -> {
                importInFlight.set(false);
                importButton.setEnabled(true);
                updateRecommendation();
                importArea.setVisibility(View.GONE);
                if (success) {
                    snackbar(getString(R.string.models_import_done, completedName));
                    refreshList();
                    showModelStatus(getString(R.string.models_loading));
                    reloadModelNative(this);
                } else if (defaultMismatch) {
                    snackbar(getString(R.string.models_import_default_mismatch));
                } else {
                    snackbar(getString(R.string.models_import_failed));
                }
            });
        }, "model-import").start();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to query display name", e);
        }
        return uri.getLastPathSegment();
    }

    private long querySize(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to query size", e);
        }
        return -1;
    }

    private void snackbar(String message) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG).show();
    }

    // Called from Rust with load progress ("Loading model...", "Ready", "Error: ...").
    public void onStatusUpdate(String status) {
        runOnUiThread(() -> {
            showModelStatus(status);
            if (!status.startsWith("Loading") && manualReloadInFlight.getAndSet(false)) {
                retryButton.setEnabled(true);
            }
        });
    }

    private void showModelStatus(String status) {
        boolean ready = "Ready".equals(status);
        boolean failed = status.startsWith("Error:");
        statusText.setText(status);
        statusText.setVisibility(ready ? View.GONE : View.VISIBLE);
        retryButton.setVisibility(failed ? View.VISIBLE : View.GONE);
    }

    private native void reloadModelNative(ModelsActivity activity);
}
