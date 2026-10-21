package dev.notune.transcribe;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ImageButton;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Compact editor for model-aware hints and all-model exact corrections. */
public final class CustomTermsActivity extends AppCompatActivity {
    private EditText editor;
    private TextView supportText;
    private TextView countText;
    private Button saveButton;
    private boolean whisperPromptActive;
    private LinearLayout correctionsRows;
    private TextView correctionsCountText;
    private Button correctionsAddButton;
    private Button correctionsSaveButton;
    private MaterialSwitch correctionsEnabledSwitch;
    private View correctionsContent;
    private Button correctionsToggle;
    private boolean updatingCorrectionsSwitch;
    private final List<PersonalCorrections.Rule> correctionRules = new ArrayList<>();
    private String savedWordsText = "";
    private int section;
    private AlertDialog correctionDialog;
    private TextInputEditText draftHeard;
    private TextInputEditText draftWritten;
    private int draftIndex = -1;
    private boolean correctionsLoadFailed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { attemptFinish(); }
        });
        setContentView(R.layout.activity_custom_terms);

        findViewById(R.id.custom_terms_close).setOnClickListener(view -> attemptFinish());
        findViewById(R.id.dictionary_tab_words).setOnClickListener(view -> showSection(0));
        findViewById(R.id.dictionary_tab_corrections).setOnClickListener(view -> showSection(1));
        findViewById(R.id.dictionary_info).setOnClickListener(view -> showSectionInfo());
        editor = findViewById(R.id.custom_terms_editor);
        supportText = findViewById(R.id.custom_terms_support);
        countText = findViewById(R.id.custom_terms_count);
        saveButton = findViewById(R.id.custom_terms_save);
        saveButton.setOnClickListener(view -> save());
        findViewById(R.id.custom_terms_clear).setOnClickListener(view -> confirmClear());
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateValidation();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        correctionsRows = findViewById(R.id.personal_corrections_rows);
        correctionsCountText = findViewById(R.id.personal_corrections_count);
        correctionsAddButton = findViewById(R.id.personal_corrections_add);
        correctionsSaveButton = findViewById(R.id.personal_corrections_save);
        correctionsEnabledSwitch = findViewById(R.id.personal_corrections_enabled);
        correctionsContent = findViewById(R.id.personal_corrections_content);
        correctionsToggle = findViewById(R.id.personal_corrections_toggle);
        correctionsToggle.setOnClickListener(view -> showSection(1));
        correctionsAddButton.setOnClickListener(view -> addEmptyCorrectionRow());
        correctionsSaveButton.setOnClickListener(view -> saveCorrections());
        findViewById(R.id.personal_corrections_clear)
                .setOnClickListener(view -> confirmClearCorrections());
        load();
        loadCorrections();
        showSection(savedInstanceState == null ? 0 : savedInstanceState.getInt("dictionary_section", 0));
        if (savedInstanceState != null && savedInstanceState.getBoolean("dictionary_draft_open")) {
            showCorrectionEditor(savedInstanceState.getInt("dictionary_draft_index", -1),
                    savedInstanceState.getString("dictionary_draft_heard", ""),
                    savedInstanceState.getString("dictionary_draft_written", ""));
        }
        correctionsEnabledSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (updatingCorrectionsSwitch) return;
            try {
                if (checked) {
                    if (correctionsLoadFailed || correctionRules.isEmpty()) {
                        setCorrectionsSwitch(false);
                        snackbar(getString(correctionsLoadFailed
                                ? R.string.dictionary_corrections_unreadable
                                : R.string.personal_corrections_enable_empty));
                        return;
                    }
                    // Enabling already-saved pairs never rewrites their source file.
                    PersonalCorrections.read(getFilesDir());
                }
                PersonalCorrections.setEnabled(getFilesDir(), checked);
                snackbar(getString(checked
                        ? R.string.personal_corrections_enabled_notice
                        : R.string.personal_corrections_disabled_notice));
            } catch (PersonalCorrections.InvalidCorrectionsException error) {
                setCorrectionsSwitch(false);
                snackbar(correctionsProblemMessage(error.problem));
            } catch (IOException error) {
                setCorrectionsSwitch(!checked);
                snackbar(getString(R.string.personal_corrections_save_failed));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateSupport();
    }

    private void load() {
        try {
            CustomTerms.Parsed parsed = CustomTerms.read(getFilesDir());
            editor.setText(parsed.storedText);
            editor.setSelection(editor.length());
        } catch (IOException | CustomTerms.InvalidTermsException error) {
            snackbar(getString(R.string.custom_terms_load_failed));
            // Preserve recoverability if an older or externally restored file
            // does not meet the current bounds: show its bounded raw contents
            // so the user can edit or clear it, but native code will ignore it.
            File file = new File(getFilesDir(), CustomTerms.FILE_NAME);
            try {
                if (file.isFile() && file.length() <= 65536) {
                    editor.setText(DictionaryFiles.read(file, 65536));
                }
            } catch (IOException ignored) {
                editor.setText("");
            }
        }
        savedWordsText = editor.getText().toString();
        updateValidation();
    }

    private void loadCorrections() {
        boolean hasSavedRules = false;
        try {
            PersonalCorrections.Parsed parsed = PersonalCorrections.read(getFilesDir());
            setCorrectionRows(parsed.rules);
            hasSavedRules = !parsed.rules.isEmpty();
            correctionsLoadFailed = false;
        } catch (IOException | PersonalCorrections.InvalidCorrectionsException error) {
            correctionsLoadFailed = true;
            snackbar(getString(R.string.dictionary_corrections_unreadable));
            // Preserve unreadable bytes until the explicit Clear confirmation.
            setCorrectionRows(Collections.emptyList());
        }
        try {
            correctionsEnabledSwitch.setChecked(
                    PersonalCorrections.reconcileEnabled(getFilesDir()));
        } catch (IOException error) {
            correctionsEnabledSwitch.setChecked(false);
        }
        updateCorrectionsValidation();
    }

    private void setCorrectionsVisible(boolean visible) {
        showSection(visible ? 1 : 0);
    }

    private void showSection(int selected) {
        section = selected == 1 ? 1 : 0;
        findViewById(R.id.dictionary_preferred_section).setVisibility(
                section == 0 ? View.VISIBLE : View.GONE);
        correctionsContent.setVisibility(section == 1 ? View.VISIBLE : View.GONE);
        com.google.android.material.button.MaterialButton words =
                findViewById(R.id.dictionary_tab_words);
        com.google.android.material.button.MaterialButton corrections =
                findViewById(R.id.dictionary_tab_corrections);
        words.setCheckable(true);
        corrections.setCheckable(true);
        words.setChecked(section == 0);
        words.setSelected(section == 0);
        corrections.setChecked(section == 1);
        corrections.setSelected(section == 1);
    }

    private void showSectionInfo() {
        String message = section == 0
                ? getString(R.string.dictionary_words_explanation) + "\n\n" + supportText.getText()
                : getString(R.string.dictionary_correction_explanation);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dictionary_how_it_works)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null).show();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt("dictionary_section", section);
        if (correctionDialog != null && correctionDialog.isShowing()) {
            outState.putBoolean("dictionary_draft_open", true);
            outState.putInt("dictionary_draft_index", draftIndex);
            outState.putString("dictionary_draft_heard", draftHeard.getText().toString());
            outState.putString("dictionary_draft_written", draftWritten.getText().toString());
        }
        super.onSaveInstanceState(outState);
    }


    @Override
    protected void onDestroy() {
        if (correctionDialog != null) correctionDialog.dismiss();
        super.onDestroy();
    }

    private void attemptFinish() {
        if (editor != null && !savedWordsText.equals(editor.getText().toString())) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.dictionary_discard_title)
                    .setMessage(R.string.dictionary_discard_message)
                    .setNegativeButton(R.string.dictionary_keep_editing, null)
                    .setPositiveButton(R.string.dictionary_discard, (dialog, which) -> finish())
                    .show();
        } else {
            finish();
        }
    }

    private void save() {
        try {
            CustomTerms.Parsed parsed = CustomTerms.write(
                    getFilesDir(), editor.getText().toString());
            editor.setText(parsed.storedText);
            editor.setSelection(editor.length());
            savedWordsText = parsed.storedText;
            snackbar(getString(R.string.custom_terms_saved, parsed.terms.size()));
            updateSupport();
        } catch (CustomTerms.InvalidTermsException error) {
            snackbar(problemMessage(error.problem));
        } catch (IOException error) {
            snackbar(getString(R.string.custom_terms_save_failed));
        }
    }

    private void confirmClear() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.custom_terms_clear_title)
                .setMessage(R.string.custom_terms_clear_body)
                .setPositiveButton(R.string.custom_terms_clear, (dialog, which) -> {
                    try {
                        CustomTerms.write(getFilesDir(), "");
                        editor.setText("");
                        savedWordsText = "";
                        snackbar(getString(R.string.custom_terms_cleared));
                        updateSupport();
                    } catch (IOException | CustomTerms.InvalidTermsException error) {
                        snackbar(getString(R.string.custom_terms_save_failed));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void saveCorrections() {
        if (correctionsLoadFailed) { snackbar(getString(R.string.dictionary_corrections_unreadable)); return; }
        try {
            PersonalCorrections.Parsed parsed = PersonalCorrections.writePairs(
                    getFilesDir(), collectCorrectionPairs());
            setCorrectionRows(parsed.rules);
            if (parsed.rules.isEmpty()) {
                PersonalCorrections.setEnabled(getFilesDir(), false);
                setCorrectionsSwitch(false);
            }
            snackbar(getString(R.string.personal_corrections_saved, parsed.rules.size()));
        } catch (PersonalCorrections.InvalidCorrectionsException error) {
            snackbar(correctionsProblemMessage(error.problem));
        } catch (IOException error) {
            snackbar(getString(R.string.personal_corrections_save_failed));
        }
    }

    private void confirmClearCorrections() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.personal_corrections_clear_title)
                .setMessage(R.string.personal_corrections_clear_body)
                .setPositiveButton(R.string.custom_terms_clear, (dialog, which) -> {
                    try {
                        PersonalCorrections.write(getFilesDir(), "");
                        PersonalCorrections.setEnabled(getFilesDir(), false);
                        setCorrectionsSwitch(false);
                        correctionsLoadFailed = false;
                        setCorrectionRows(Collections.emptyList());
                        snackbar(getString(R.string.personal_corrections_cleared));
                    } catch (IOException | PersonalCorrections.InvalidCorrectionsException error) {
                        snackbar(getString(R.string.personal_corrections_save_failed));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void setCorrectionRows(List<PersonalCorrections.Rule> rules) {
        correctionRules.clear();
        correctionRules.addAll(rules);
        correctionsRows.removeAllViews();
        for (int index = 0; index < correctionRules.size(); index++) {
            final int rowIndex = index;
            PersonalCorrections.Rule rule = correctionRules.get(index);
            View row = getLayoutInflater().inflate(
                    R.layout.item_personal_correction, correctionsRows, false);
            Button edit = row.findViewById(R.id.personal_correction_edit);
            edit.setText(getString(R.string.dictionary_correction_item, rule.heard, rule.written));
            edit.setOnClickListener(view -> showCorrectionEditor(rowIndex, rule.heard, rule.written));
            ImageButton delete = row.findViewById(R.id.personal_correction_delete);
            delete.setContentDescription(getString(R.string.personal_corrections_remove)
                    + ": " + rule.heard);
            delete.setOnClickListener(view -> confirmRemoveCorrection(rowIndex));
            correctionsRows.addView(row);
        }
        updateCorrectionsValidation();
    }

    private void addEmptyCorrectionRow() {
        if (correctionRules.size() >= PersonalCorrections.MAX_RULES) return;
        showCorrectionEditor(-1, "", "");
    }

    private void showCorrectionEditor(int requestedIndex, String originalValue, String replacementValue) {
        final int index = requestedIndex >= 0 && requestedIndex < correctionRules.size()
                ? requestedIndex : -1;
        draftIndex = index;
        final String savedHeard = index < 0 ? "" : correctionRules.get(index).heard;
        final String savedWritten = index < 0 ? "" : correctionRules.get(index).written;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        form.setPadding(padding, padding, padding, 0);
        TextInputLayout heardLayout = new TextInputLayout(this);
        heardLayout.setHint(getString(R.string.personal_corrections_original_hint));
        draftHeard = new TextInputEditText(heardLayout.getContext());
        draftHeard.setId(R.id.personal_correction_original);
        draftHeard.setSingleLine(true);
        draftHeard.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(160)});
        draftHeard.setText(originalValue);
        heardLayout.addView(draftHeard);
        form.addView(heardLayout);
        TextInputLayout writtenLayout = new TextInputLayout(this);
        writtenLayout.setHint(getString(R.string.personal_corrections_replacement_hint));
        draftWritten = new TextInputEditText(writtenLayout.getContext());
        draftWritten.setId(R.id.personal_correction_replacement);
        draftWritten.setSingleLine(true);
        draftWritten.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(160)});
        draftWritten.setText(replacementValue);
        writtenLayout.addView(draftWritten);
        form.addView(writtenLayout);
        correctionDialog = new MaterialAlertDialogBuilder(this)
                .setBackground(androidx.appcompat.content.res.AppCompatResources.getDrawable(
                        this, R.drawable.bg_dialog_outline))
                .setTitle(index < 0 ? R.string.dictionary_add_correction
                        : R.string.dictionary_edit_correction)
                .setView(form)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.dictionary_save_correction, null)
                .setCancelable(false)
                .create();
        correctionDialog.show();
        correctionDialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)
                .setOnClickListener(view -> {
            boolean changed = !savedHeard.equals(draftHeard.getText().toString())
                    || !savedWritten.equals(draftWritten.getText().toString());
            if (changed) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.dictionary_discard_title)
                        .setMessage(R.string.dictionary_discard_message)
                        .setNegativeButton(R.string.dictionary_keep_editing, null)
                        .setPositiveButton(R.string.dictionary_discard,
                                (dialog, which) -> correctionDialog.dismiss()).show();
            } else {
                correctionDialog.dismiss();
            }
        });
        correctionDialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
            if (correctionsLoadFailed) {
                heardLayout.setError(getString(R.string.dictionary_corrections_unreadable));
                return;
            }
            heardLayout.setError(null);
            writtenLayout.setError(null);
            List<PersonalCorrections.Rule> next = collectCorrectionPairs();
            PersonalCorrections.Rule edited = new PersonalCorrections.Rule(
                    draftHeard.getText().toString(), draftWritten.getText().toString());
            if (index < 0) next.add(edited);
            else next.set(index, edited);
            try {
                PersonalCorrections.Parsed parsed = PersonalCorrections.writePairs(
                        getFilesDir(), next);
                setCorrectionRows(parsed.rules);
                correctionDialog.dismiss();
                snackbar(getString(R.string.personal_corrections_saved, parsed.rules.size()));
            } catch (PersonalCorrections.InvalidCorrectionsException error) {
                String message = correctionsProblemMessage(error.problem);
                heardLayout.setError(message);
                snackbar(message);
            } catch (IOException error) {
                snackbar(getString(R.string.personal_corrections_save_failed));
            }
        });
    }

    private void confirmRemoveCorrection(int index) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.personal_corrections_remove)
                .setMessage(getString(R.string.dictionary_correction_item,
                        correctionRules.get(index).heard, correctionRules.get(index).written))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.personal_corrections_remove, (dialog, which) -> {
                    List<PersonalCorrections.Rule> next = collectCorrectionPairs();
                    next.remove(index);
                    try {
                        PersonalCorrections.Parsed parsed = PersonalCorrections.writePairs(
                                getFilesDir(), next);
                        setCorrectionRows(parsed.rules);
                        if (parsed.rules.isEmpty()) {
                            PersonalCorrections.setEnabled(getFilesDir(), false);
                            setCorrectionsSwitch(false);
                        }
                    } catch (PersonalCorrections.InvalidCorrectionsException | IOException error) {
                        snackbar(getString(R.string.personal_corrections_save_failed));
                    }
                }).show();
    }

    private List<PersonalCorrections.Rule> collectCorrectionPairs() {
        return new ArrayList<>(correctionRules);
    }

    private void setCorrectionsSwitch(boolean checked) {
        updatingCorrectionsSwitch = true;
        correctionsEnabledSwitch.setChecked(checked);
        updatingCorrectionsSwitch = false;
    }

    private void updateValidation() {
        try {
            CustomTerms.Parsed parsed = CustomTerms.parse(editor.getText().toString());
            countText.setText(whisperPromptActive
                    ? getString(R.string.custom_terms_count_whisper,
                            parsed.terms.size(), CustomTerms.MAX_TERMS,
                            parsed.prompt.getBytes(StandardCharsets.UTF_8).length,
                            CustomTerms.MAX_PROMPT_UTF8_BYTES)
                    : getString(R.string.custom_terms_count,
                            parsed.terms.size(), CustomTerms.MAX_TERMS));
            saveButton.setEnabled(true);
        } catch (CustomTerms.InvalidTermsException error) {
            countText.setText(problemMessage(error.problem));
            saveButton.setEnabled(false);
        }
    }

    private void updateCorrectionsValidation() {
        correctionsEnabledSwitch.setEnabled(!correctionsLoadFailed);
        if (correctionsLoadFailed) {
            correctionsCountText.setText(R.string.dictionary_corrections_unreadable);
            correctionsAddButton.setEnabled(false);
            correctionsSaveButton.setEnabled(false);
            return;
        }
        try {
            PersonalCorrections.Parsed parsed = PersonalCorrections.fromPairs(
                    collectCorrectionPairs());
            correctionsCountText.setText(getString(R.string.personal_corrections_count,
                    parsed.rules.size(), PersonalCorrections.MAX_RULES));
            correctionsSaveButton.setEnabled(true);
        } catch (PersonalCorrections.InvalidCorrectionsException error) {
            correctionsCountText.setText(correctionsProblemMessage(error.problem));
            correctionsSaveButton.setEnabled(false);
        }
        correctionsAddButton.setEnabled(
                correctionsRows.getChildCount() < PersonalCorrections.MAX_RULES);
    }

    private void updateSupport() {
        String active = readSmallFile(new File(getFilesDir(), "active_model"));
        ModelDescriptor descriptor = active.isEmpty()
                ? (BuildConfig.HAS_BUNDLED_MODEL ? ModelCatalog.verifiedDefault() : null)
                : ModelCatalog.findByFileName(active);
        if (!active.isEmpty() && descriptor != null && !VerifiedModelProvenance.isVerified(
                getFilesDir(), new File(new File(getFilesDir(), "models"), active), descriptor)) {
            descriptor = null;
        }
        if (descriptor == null || descriptor.prompt == ModelDescriptor.Capability.UNKNOWN) {
            whisperPromptActive = false;
            supportText.setText(R.string.custom_terms_support_unknown);
        } else if (descriptor.prompt == ModelDescriptor.Capability.SUPPORTED) {
            whisperPromptActive = true;
            supportText.setText(R.string.custom_terms_support_whisper);
        } else {
            whisperPromptActive = false;
            supportText.setText(R.string.custom_terms_support_unavailable);
        }
        updateValidation();
    }

    private String correctionsProblemMessage(PersonalCorrections.Problem problem) {
        switch (problem) {
            case TOO_MANY_RULES:
                return getString(R.string.personal_corrections_error_count,
                        PersonalCorrections.MAX_RULES);
            case MISSING_SEPARATOR:
                return getString(R.string.personal_corrections_error_separator);
            case EMPTY_SIDE:
                return getString(R.string.personal_corrections_error_empty);
            case SIDE_TOO_LONG:
                return getString(R.string.personal_corrections_error_length,
                        PersonalCorrections.MAX_SIDE_CODE_POINTS);
            case INVALID_CONTROL:
                return getString(R.string.personal_corrections_error_unsafe);
            case AMBIGUOUS_SOURCE:
                return getString(R.string.personal_corrections_error_duplicate);
            case NUMBER_MISMATCH:
            default:
                return getString(R.string.personal_corrections_error_number);
        }
    }

    private String problemMessage(CustomTerms.Problem problem) {
        switch (problem) {
            case TOO_MANY_TERMS:
                return getString(R.string.custom_terms_error_count, CustomTerms.MAX_TERMS);
            case TERM_TOO_LONG:
                return getString(R.string.custom_terms_error_length,
                        CustomTerms.MAX_TERM_CODE_POINTS);
            case SPECIAL_TOKEN:
                return getString(R.string.custom_terms_error_special);
            case INVALID_CONTROL:
                return getString(R.string.custom_terms_error_control);
            case PROMPT_TOO_LONG:
            default:
                return getString(R.string.custom_terms_error_budget);
        }
    }

    private static String readSmallFile(File file) {
        try {
            if (!file.isFile() || file.length() > 4096) return "";
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException error) {
            return "";
        }
    }

    private void snackbar(String message) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG).show();
    }
}
