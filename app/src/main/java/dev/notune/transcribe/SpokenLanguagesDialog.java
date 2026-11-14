package dev.notune.transcribe;

import androidx.appcompat.app.AppCompatActivity;
import android.widget.ProgressBar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Pick the languages you speak; with two or more, offer the language detector download. */
final class SpokenLanguagesDialog {
    private SpokenLanguagesDialog() {}

    /** Summary for a button label, such as "English, German"; null when fewer than two are set. */
    static String summary(File filesDir) {
        List<String> languages = SpokenLanguages.read(filesDir);
        if (!SpokenLanguages.needsDetector(languages)) return null;
        List<String> names = new ArrayList<>();
        for (String code : languages) names.add(displayName(code));
        return String.join(", ", names);
    }

    /**
     * @param candidates primary language codes to offer (any order, duplicates ignored)
     * @param onChanged runs on the UI thread once the selection, and any detector it needs, is in
     *                  place, so the engine can reload
     */
    static void show(AppCompatActivity activity, File filesDir, List<String> candidates,
                     Runnable onChanged) {
        List<String> codes = new ArrayList<>();
        for (String code : candidates) if (!codes.contains(code)) codes.add(code);
        Collections.sort(codes, (a, b) -> displayName(a).compareToIgnoreCase(displayName(b)));
        List<String> selected = SpokenLanguages.read(filesDir);
        String[] names = new String[codes.size()];
        boolean[] checked = new boolean[codes.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = displayName(codes.get(i));
            checked[i] = selected.contains(codes.get(i));
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.spoken_languages_title)
                .setMultiChoiceItems(names, checked, (dialog, which, isChecked) -> {
                    checked[which] = isChecked;
                    int count = 0;
                    for (boolean on : checked) if (on) count++;
                    if (count > SpokenLanguages.MAX) {
                        checked[which] = false;
                        ((androidx.appcompat.app.AlertDialog) dialog).getListView()
                                .setItemChecked(which, false);
                    }
                })
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    List<String> chosen = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) {
                        if (checked[i]) chosen.add(codes.get(i));
                    }
                    save(activity, filesDir, chosen, onChanged);
                })
                .setNeutralButton(R.string.spoken_languages_clear, (dialog, which) ->
                        save(activity, filesDir, new ArrayList<>(), onChanged))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void save(AppCompatActivity activity, File filesDir, List<String> chosen,
                             Runnable onChanged) {
        Runnable persist = () -> {
            try {
                SpokenLanguages.write(filesDir, chosen);
                onChanged.run();
            } catch (IOException e) {
                new MaterialAlertDialogBuilder(activity)
                        .setMessage(R.string.spoken_languages_save_failed)
                        .setPositiveButton(android.R.string.ok, null).show();
            }
        };
        if (SpokenLanguages.needsDetector(chosen) && !SpokenLanguages.detectorInstalled(filesDir)) {
            offerDetector(activity, filesDir, persist);
        } else {
            persist.run();
        }
    }

    private static void offerDetector(AppCompatActivity activity, File filesDir, Runnable onChanged) {
        ModelDescriptor detector = ModelCatalog.languageId();
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.spoken_languages_detector_title)
                .setMessage(activity.getString(R.string.spoken_languages_detector_body,
                        android.text.format.Formatter.formatShortFileSize(
                                activity, detector.byteSize)))
                .setPositiveButton(R.string.spoken_languages_download, (dialog, which) ->
                        download(activity, filesDir, detector, onChanged))
                .setNegativeButton(R.string.spoken_languages_later, (dialog, which) -> { })
                .setCancelable(false)
                .show();
    }

    private static void download(AppCompatActivity activity, File filesDir, ModelDescriptor detector,
                                 Runnable onChanged) {
        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(false);
        bar.setMax(1000);
        int pad = (int) (24 * activity.getResources().getDisplayMetrics().density);
        bar.setPadding(pad, pad, pad, 0);
        ModelDownloadTask.Handle[] handle = new ModelDownloadTask.Handle[1];
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        androidx.lifecycle.LifecycleOwner owner = (androidx.lifecycle.LifecycleOwner) activity;
        androidx.lifecycle.DefaultLifecycleObserver observer = new androidx.lifecycle.DefaultLifecycleObserver() {
            @Override public void onDestroy(androidx.lifecycle.LifecycleOwner ignored) {
                cancelled.set(true);
                if (handle[0] != null) handle[0].cancel();
            }
        };
        owner.getLifecycle().addObserver(observer);
        androidx.appcompat.app.AlertDialog progress = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.spoken_languages_downloading)
                .setView(bar)
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    cancelled.set(true);
                    if (handle[0] != null) handle[0].cancel();
                })
                .setCancelable(false)
                .show();
        ModelDownloadTask.start(filesDir, detector, new ModelDownloadTask.Listener() {
            @Override public void onStarted(ModelDownloadTask.Handle started) {
                handle[0] = started;
                if (cancelled.get()) started.cancel();
            }

            @Override public void onProgress(long downloaded, long total) {
                activity.runOnUiThread(() -> {
                    if (!activity.isDestroyed() && !activity.isFinishing() && !cancelled.get()) {
                        bar.setProgress((int) (downloaded * 1000 / Math.max(1, total)));
                    }
                });
            }

            @Override public void onComplete(String installedName) {
                activity.runOnUiThread(() -> {
                    owner.getLifecycle().removeObserver(observer);
                    if (activity.isDestroyed() || activity.isFinishing() || cancelled.get()) return;
                    progress.dismiss();
                    onChanged.run();
                });
            }

            @Override public void onFailure(String reason) {
                activity.runOnUiThread(() -> {
                    owner.getLifecycle().removeObserver(observer);
                    if (activity.isDestroyed() || activity.isFinishing() || cancelled.get()) return;
                    progress.dismiss();
                    new MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.spoken_languages_detector_title)
                            .setMessage(activity.getString(
                                    R.string.spoken_languages_download_failed, reason))
                            .setPositiveButton(R.string.spoken_languages_retry, (dialog, which) ->
                                    download(activity, filesDir, detector, onChanged))
                            .setNegativeButton(R.string.spoken_languages_later, (dialog, which) -> { })
                            .show();
                });
            }
        });
    }

    private static String displayName(String code) {
        String name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault());
        return name.isEmpty() ? code : name.substring(0, 1).toUpperCase(Locale.getDefault())
                + name.substring(1);
    }
}
