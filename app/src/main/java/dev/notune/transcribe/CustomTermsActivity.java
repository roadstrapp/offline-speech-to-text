package dev.notune.transcribe;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Compact editor for model-aware hints and all-model exact corrections. */
public final class CustomTermsActivity extends AppCompatActivity {
    private EditText editor;
    private TextView supportText;
    private TextView countText;
    private Button saveButton;
    private EditText correctionsEditor;
    private TextView correctionsCountText;
    private Button correctionsSaveButton;
    private MaterialSwitch correctionsEnabledSwitch;
    private boolean updatingCorrectionsSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_custom_terms);

        findViewById(R.id.custom_terms_close).setOnClickListener(view -> finish());
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
        correctionsEditor = findViewById(R.id.personal_corrections_editor);
        correctionsCountText = findViewById(R.id.personal_corrections_count);
        correctionsSaveButton = findViewById(R.id.personal_corrections_save);
        correctionsEnabledSwitch = findViewById(R.id.personal_corrections_enabled);
        correctionsSaveButton.setOnClickListener(view -> saveCorrections());
        findViewById(R.id.personal_corrections_clear)
                .setOnClickListener(view -> confirmClearCorrections());
        correctionsEditor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateCorrectionsValidation();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        load();
        loadCorrections();
        correctionsEnabledSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (updatingCorrectionsSwitch) return;
            try {
                if (checked) {
                    PersonalCorrections.Parsed parsed = PersonalCorrections.write(
                            getFilesDir(), correctionsEditor.getText().toString());
                    if (parsed.rules.isEmpty()) {
                        setCorrectionsSwitch(false);
                        snackbar(getString(R.string.personal_corrections_enable_empty));
                        return;
                    }
                    setCorrectionsEditor(parsed);
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
            // Preserve recoverability if an older or externally restored file
            // does not meet the current bounds: show its bounded raw contents
            // so the user can edit or clear it, but native code will ignore it.
            File file = new File(getFilesDir(), CustomTerms.FILE_NAME);
            try {
                if (file.isFile() && file.length() <= 4096) {
                    editor.setText(new String(
                            Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                editor.setText("");
            }
        }
        updateValidation();
    }

    private void loadCorrections() {
        try {
            PersonalCorrections.Parsed parsed = PersonalCorrections.read(getFilesDir());
            correctionsEditor.setText(parsed.storedText);
            correctionsEditor.setSelection(correctionsEditor.length());
        } catch (IOException | PersonalCorrections.InvalidCorrectionsException error) {
            File file = new File(getFilesDir(), PersonalCorrections.FILE_NAME);
            try {
                if (file.isFile() && file.length() <= 8192) {
                    correctionsEditor.setText(new String(
                            Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                correctionsEditor.setText("");
            }
        }
        try {
            correctionsEnabledSwitch.setChecked(
                    PersonalCorrections.reconcileEnabled(getFilesDir()));
        } catch (IOException error) {
            correctionsEnabledSwitch.setChecked(false);
        }
        updateCorrectionsValidation();
    }

    private void save() {
        try {
            CustomTerms.Parsed parsed = CustomTerms.write(
                    getFilesDir(), editor.getText().toString());
            editor.setText(parsed.storedText);
            editor.setSelection(editor.length());
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
        try {
            PersonalCorrections.Parsed parsed = PersonalCorrections.write(
                    getFilesDir(), correctionsEditor.getText().toString());
            setCorrectionsEditor(parsed);
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
                        correctionsEditor.setText("");
                        snackbar(getString(R.string.personal_corrections_cleared));
                    } catch (IOException | PersonalCorrections.InvalidCorrectionsException error) {
                        snackbar(getString(R.string.personal_corrections_save_failed));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void setCorrectionsEditor(PersonalCorrections.Parsed parsed) {
        correctionsEditor.setText(parsed.storedText);
        correctionsEditor.setSelection(correctionsEditor.length());
    }

    private void setCorrectionsSwitch(boolean checked) {
        updatingCorrectionsSwitch = true;
        correctionsEnabledSwitch.setChecked(checked);
        updatingCorrectionsSwitch = false;
    }

    private void updateValidation() {
        try {
            CustomTerms.Parsed parsed = CustomTerms.parse(editor.getText().toString());
            countText.setText(getString(R.string.custom_terms_count,
                    parsed.terms.size(), CustomTerms.MAX_TERMS,
                    parsed.prompt.getBytes(StandardCharsets.UTF_8).length,
                    CustomTerms.MAX_PROMPT_UTF8_BYTES));
            saveButton.setEnabled(true);
        } catch (CustomTerms.InvalidTermsException error) {
            countText.setText(problemMessage(error.problem));
            saveButton.setEnabled(false);
        }
    }

    private void updateCorrectionsValidation() {
        try {
            PersonalCorrections.Parsed parsed = PersonalCorrections.parse(
                    correctionsEditor.getText().toString());
            correctionsCountText.setText(getString(R.string.personal_corrections_count,
                    parsed.rules.size(), PersonalCorrections.MAX_RULES));
            correctionsSaveButton.setEnabled(true);
        } catch (PersonalCorrections.InvalidCorrectionsException error) {
            correctionsCountText.setText(correctionsProblemMessage(error.problem));
            correctionsSaveButton.setEnabled(false);
        }
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
            supportText.setText(R.string.custom_terms_support_unknown);
        } else if (descriptor.prompt == ModelDescriptor.Capability.SUPPORTED) {
            supportText.setText(R.string.custom_terms_support_whisper);
        } else {
            supportText.setText(R.string.custom_terms_support_unavailable);
        }
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
                return getString(R.string.custom_terms_error_budget,
                        CustomTerms.MAX_PROMPT_UTF8_BYTES);
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
