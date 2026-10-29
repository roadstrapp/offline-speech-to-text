package dev.notune.transcribe;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real external caller using only platform APIs and fixed public audio. */
public final class ContractResultHostActivity extends Activity {
    private String requestId;
    private static final String TARGET = "app.offlinespeechtotext.validation";
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            requestId = getIntent().getStringExtra("request_id");
            if (requestId == null || !requestId.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Missing fixture request identity");
            Files.deleteIfExists(ContractFixtureProvider.file(this, "report.json").toPath());
            Files.write(ContractFixtureProvider.file(this, "output.txt").toPath(), "stale".getBytes(StandardCharsets.UTF_8));
            grantUriPermission(TARGET, ContractFixtureProvider.uri("report.json"), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            grantUriPermission(TARGET, ContractFixtureProvider.uri("output.txt"), Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            Intent request = new Intent("app.offlinespeechtotext.action.TRANSCRIBE_AUDIO")
                    .setPackage(TARGET).setDataAndType(Uri.parse("content://app.offlinespeechtotext.validation.test.public_audio/bench.wav"), "audio/wav")
                    .putExtra("app.offlinespeechtotext.extra.OUTPUT_URI", ContractFixtureProvider.uri("output.txt"))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            request.setClipData(ClipData.newRawUri("Public fixture output", ContractFixtureProvider.uri("output.txt")));
            startActivityForResult(request, 1);
        } catch (Exception error) { publish(Activity.RESULT_CANCELED, null, error.getClass().getSimpleName()); }
    }
    @Override public void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 1) publish(result, data, null);
    }
    private void publish(int result, Intent data, String error) {
        try {
            JSONObject value = new JSONObject(); value.put("result_code", result); value.put("request_id", requestId);
            java.util.ArrayList<String> text = data == null ? null : data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            value.put("results", text == null ? new JSONArray() : new JSONArray(text));
            if (error != null) value.put("error", error);
            Files.write(ContractFixtureProvider.file(this, "report.json").toPath(), value.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { /* Missing report fails the bounded instrumentation wait. */ }
        finish();
    }
}
