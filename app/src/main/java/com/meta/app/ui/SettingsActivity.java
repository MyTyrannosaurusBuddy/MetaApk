package com.meta.app.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Filter;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.meta.app.R;
import com.meta.app.config.LlmConfig;
import com.meta.app.core.LLMClient;

import java.util.ArrayList;
import java.util.List;

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
        layout.addView(modelRow(etModel, etUrl, etKey));
        layout.addView(space(dp(16)));

        // Backup model
        layout.addView(label(R.string.label_backup_model));
        EditText etBackupModel = editText(config.getBackupModel(), R.string.hint_backup_model);
        layout.addView(modelRow(etBackupModel, etUrl, etKey));
        layout.addView(space(dp(24)));

        // Test connection (uses the current form values, without saving them)
        Button testBtn = new Button(this);
        testBtn.setText(R.string.action_test_connection);
        testBtn.setAllCaps(false);
        testBtn.setBackgroundColor(color(R.color.surface_variant));
        testBtn.setTextColor(color(R.color.on_surface));
        testBtn.setOnClickListener(v -> {
            String url = etUrl.getText().toString().trim();
            String key = etKey.getText().toString().trim();
            String model = etModel.getText().toString().trim();
            String backupModel = etBackupModel.getText().toString().trim();
            if (url.isEmpty() || key.isEmpty()) {
                Toast.makeText(this, R.string.toast_fill_required_fields, Toast.LENGTH_SHORT).show();
                return;
            }
            testBtn.setEnabled(false);
            testBtn.setText(R.string.status_testing);
            LlmConfig testConfig = new LlmConfig(url, key, model, backupModel);
            new LLMClient(this, testConfig).requestStream("Reply with OK.", new LLMClient.StreamCallback() {
                @Override
                public void onToken(String token) { }

                @Override
                public void onComplete(String fullResponse) {
                    runOnUiThread(() -> {
                        testBtn.setEnabled(true);
                        testBtn.setText(R.string.action_test_connection);
                        Toast.makeText(SettingsActivity.this,
                            getString(R.string.status_test_ok, model), Toast.LENGTH_LONG).show();
                    });
                }

                @Override
                public void onError(Exception e) {
                    runOnUiThread(() -> {
                        testBtn.setEnabled(true);
                        testBtn.setText(R.string.action_test_connection);
                        Toast.makeText(SettingsActivity.this,
                            getString(R.string.status_test_fail, e.getMessage()),
                            Toast.LENGTH_LONG).show();
                    });
                }

                @Override
                public void onModelSwitch(String from, String to) {
                    runOnUiThread(() -> Toast.makeText(SettingsActivity.this,
                        getString(R.string.status_test_ok_backup, to), Toast.LENGTH_SHORT).show());
                }
            });
        });
        layout.addView(testBtn);
        layout.addView(space(dp(12)));

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
            // Land back on the host reliably: with a bare finish() the user
            // could end up on the launcher if the host was recreated while
            // settings were open (killed task edge cases).
            Intent backToHost = new Intent(this, HostActivity.class);
            backToHost.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(backToHost);
            finish();
        });
        layout.addView(saveBtn);

        scroll.addView(layout);
        setContentView(scroll);
    }

    /** Guards against overlapping fetches from the two pick buttons. */
    private boolean modelsFetching;

    /**
     * Row wrapping a model EditText with a pick button on its right: fetches
     * the endpoint's model list and opens a searchable picker for this field.
     */
    private android.view.View modelRow(EditText field, EditText etUrl, EditText etKey) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        field.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(field);

        Button pick = new Button(this);
        pick.setText(R.string.action_pick_model);
        pick.setAllCaps(false);
        pick.setBackgroundColor(color(R.color.surface_variant));
        pick.setTextColor(color(R.color.on_surface));
        LinearLayout.LayoutParams pickLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pickLp.setMarginStart(dp(8));
        pick.setLayoutParams(pickLp);
        pick.setOnClickListener(v -> startModelPick(field, etUrl, etKey, pick));
        row.addView(pick);
        return row;
    }

    private void startModelPick(EditText targetField, EditText etUrl, EditText etKey,
                                Button pickBtn) {
        String url = etUrl.getText().toString().trim();
        String key = etKey.getText().toString().trim();
        if (url.isEmpty() || key.isEmpty()) {
            Toast.makeText(this, R.string.toast_fill_required_fields, Toast.LENGTH_SHORT).show();
            return;
        }
        if (modelsFetching) {
            return;
        }
        modelsFetching = true;
        pickBtn.setEnabled(false);
        Toast.makeText(this, R.string.status_models_fetching, Toast.LENGTH_SHORT).show();
        LlmConfig probeConfig = new LlmConfig(url, key, "", "");
        new LLMClient(this, probeConfig).fetchModels(url, key, new LLMClient.ModelsCallback() {
            @Override
            public void onModels(List<String> modelIds) {
                runOnUiThread(() -> {
                    modelsFetching = false;
                    pickBtn.setEnabled(true);
                    showSearchableModelPicker(modelIds, targetField);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    modelsFetching = false;
                    pickBtn.setEnabled(true);
                    Toast.makeText(SettingsActivity.this,
                        getString(R.string.status_models_error, message),
                        Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /**
     * Searchable model picker: a filter box on top filters the list live;
     * tapping an item writes it into the target model field.
     */
    private void showSearchableModelPicker(List<String> modelIds, EditText targetField) {
        if (modelIds == null || modelIds.isEmpty()) {
            Toast.makeText(this, R.string.status_models_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), dp(8));

        EditText search = new EditText(this);
        search.setHint(R.string.hint_search_models);
        search.setSingleLine();
        root.addView(search);

        ListView list = new ListView(this);
        // Custom filter: the default ArrayAdapter filter matches PREFIXES
        // only, so searching "free" (a suffix) or a middle fragment finds
        // nothing. This one does case-insensitive substring matching.
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_1, modelIds) {
            @Override
            public Filter getFilter() {
                return new Filter() {
                    @Override
                    protected FilterResults performFiltering(CharSequence constraint) {
                        FilterResults results = new FilterResults();
                        String query = constraint == null
                            ? "" : constraint.toString().trim().toLowerCase();
                        List<String> filtered = new ArrayList<>();
                        if (query.isEmpty()) {
                            filtered.addAll(modelIds);
                        } else {
                            for (String id : modelIds) {
                                if (id.toLowerCase().contains(query)) {
                                    filtered.add(id);
                                }
                            }
                        }
                        results.values = filtered;
                        results.count = filtered.size();
                        return results;
                    }

                    @Override
                    @SuppressWarnings("unchecked")
                    protected void publishResults(CharSequence constraint,
                                                  FilterResults results) {
                        clear();
                        if (results != null && results.values != null) {
                            addAll((List<String>) results.values);
                        }
                        notifyDataSetChanged();
                    }
                };
            }
        };
        list.setAdapter(adapter);
        list.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        final AlertDialog[] dialogHolder = new AlertDialog[1];
        list.setOnItemClickListener((parent, view, position, id) -> {
            targetField.setText(adapter.getItem(position));
            if (dialogHolder[0] != null) {
                dialogHolder[0].dismiss();
            }
        });
        root.addView(list);

        root.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(480)));

        dialogHolder[0] = new AlertDialog.Builder(this)
            .setTitle(R.string.dialog_title_models)
            .setView(root)
            .setNegativeButton(R.string.action_cancel, null)
            .create();
        dialogHolder[0].show();

        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.getFilter().filter(s.toString());
            }

            @Override
            public void afterTextChanged(android.text.Editable s) { }
        });
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
