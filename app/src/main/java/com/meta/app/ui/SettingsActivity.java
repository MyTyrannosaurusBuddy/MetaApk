package com.meta.app.ui;

import android.os.Bundle;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.meta.app.R;
import com.meta.app.config.LlmConfig;

/**
 * Settings screen - LLM API configuration.
 */
public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.title_settings);

        LlmConfig config = new LlmConfig(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(color(R.color.background));

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(24), dp(24), dp(24));

        // Title
        TextView title = new TextView(this);
        title.setText(R.string.settings_title);
        title.setTextSize(24);
        title.setTextColor(color(R.color.on_background));
        title.setPadding(0, 0, 0, dp(24));
        layout.addView(title);

        // Base URL
        layout.addView(label(R.string.label_base_url));
        EditText etUrl = editText(config.getBaseUrl(), R.string.hint_base_url);
        layout.addView(etUrl);
        layout.addView(space(dp(16)));

        // API Key
        layout.addView(label(R.string.label_api_key));
        EditText etKey = editText(config.getApiKey(), R.string.hint_api_key);
        etKey.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(etKey);
        layout.addView(space(dp(16)));

        // Model
        layout.addView(label(R.string.label_model));
        EditText etModel = editText(config.getModel(), R.string.hint_model);
        layout.addView(etModel);
        layout.addView(space(dp(16)));

        // Backup model
        layout.addView(label(R.string.label_backup_model));
        EditText etBackupModel = editText(config.getBackupModel(), R.string.hint_backup_model);
        layout.addView(etBackupModel);
        layout.addView(space(dp(32)));

        // Save
        Button saveBtn = new Button(this);
        saveBtn.setText(R.string.action_save_config);
        saveBtn.setAllCaps(false);
        saveBtn.setBackgroundColor(color(R.color.user_bubble));
        saveBtn.setTextColor(color(R.color.on_primary));
        saveBtn.setOnClickListener(v -> {
            String url = etUrl.getText().toString().trim();
            String key = etKey.getText().toString().trim();
            String model = etModel.getText().toString().trim();
            String backupModel = etBackupModel.getText().toString().trim();
            if (url.isEmpty() || key.isEmpty()) {
                Toast.makeText(this, R.string.toast_fill_required_fields, Toast.LENGTH_SHORT).show();
                return;
            }
            config.setBaseUrl(url);
            config.setApiKey(key);
            if (!model.isEmpty()) config.setModel(model);
            config.setBackupModel(backupModel);
            Toast.makeText(this, R.string.toast_config_saved, Toast.LENGTH_SHORT).show();
            finish();
        });
        layout.addView(saveBtn);

        scroll.addView(layout);
        setContentView(scroll);
    }

    private TextView label(int textResId) {
        TextView tv = new TextView(this);
        tv.setText(textResId);
        tv.setTextColor(color(R.color.on_surface));
        tv.setPadding(0, 0, 0, dp(4));
        return tv;
    }

    private EditText editText(String value, int hintResId) {
        EditText et = new EditText(this);
        et.setText(value);
        et.setHint(hintResId);
        et.setTextColor(color(R.color.on_background));
        et.setHintTextColor(color(R.color.on_surface_variant));
        et.setBackgroundColor(color(R.color.surface_variant));
        et.setPadding(dp(12), dp(12), dp(12), dp(12));
        et.setSingleLine();
        return et;
    }

    private android.view.View space(int height) {
        android.view.View v = new android.view.View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, height));
        return v;
    }

    private int color(int resId) {
        return ContextCompat.getColor(this, resId);
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
