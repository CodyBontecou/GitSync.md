package com.bontecou.syncmd;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONObject;
import java.nio.file.StandardCopyOption;

/** Test APK process has no target-app Kotlin runtime. Use Android/JDK APIs only. */
public final class CallbackResultFixtureActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        receive(getIntent());
    }
    @Override public void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); receive(intent); }
    private void receive(Intent intent) {
        Uri uri = intent.getData();
        if (!Intent.ACTION_VIEW.equals(intent.getAction()) || uri == null
                || !"gitsync-callback-fixture".equals(uri.getScheme()) || !"result".equals(uri.getHost())) {
            finish(); return;
        }
        String nonce = uri.getQueryParameter("nonce");
        try {
            if (nonce == null || !UUID.fromString(nonce).toString().equals(nonce)) { finish(); return; }
            File receipt = new File(getFilesDir(), "callback-result-" + nonce + ".txt");
            int count = receipt.exists() ? new JSONObject(new String(Files.readAllBytes(receipt.toPath()), StandardCharsets.UTF_8)).getInt("count") : 0;
            File temporary = new File(getFilesDir(), "callback-result-" + nonce + ".tmp");
            JSONObject record = new JSONObject().put("count", count + 1).put("uri", uri.toString());
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(record.toString().getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            Files.move(temporary.toPath(), receipt.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception failure) { throw new IllegalStateException("Fixture result could not be recorded", failure); }
        TextView view = new TextView(this);
        view.setText("Callback result received\n\n" + uri);
        view.setPadding(24, 48, 24, 24);
        view.setTextSize(18f);
        setContentView(view);
    }
}
