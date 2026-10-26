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
        layout.setPadding(dp(16), dp(12), dp(16), dp(12));

        // Title
        TextView title = new TextView(this);
        title.setText(R.string.settings_title);
        title.setTextSize(24);
        title.setTextColor(color(R.color.on_background));
        title.setPadding(0, 0, 0, dp(10));
        layout.addView(title);

        // Base URL (fillable by hand, or pick a well-known provider preset)
        layout.addView(label(R.string.label_base_url));
        EditText etUrl = editText(config.getBaseUrl(), R.string.hint_base_url);
        layout.addView(urlRow(etUrl));
        layout.addView(space(dp(8)));

        // API Key
        layout.addView(label(R.string.label_api_key));
        EditText etKey = editText(config.getApiKey(), R.string.hint_api_key);
        etKey.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(etKey);
        layout.addView(space(dp(8)));

        // === PRIMARY MODEL card: pick + thinking + context, grouped so the
        // user sees at a glance which settings belong to which model. ===
        layout.addView(sectionTitle(R.string.label_primary_model));
        LinearLayout primaryCard = modelCard();
        EditText etModel = editText(config.getModel(), R.string.hint_model);
        primaryCard.addView(cardField(getString(R.string.caption_model), modelRow(etModel, etUrl, etKey)));
        android.widget.CheckBox cbThinkPrimary = new android.widget.CheckBox(this);
        cbThinkPrimary.setTextColor(color(R.color.on_surface));
        cbThinkPrimary.setChecked(config.getThinkingEnabled(true));
        primaryCard.addView(cardField(getString(R.string.caption_thinking), cbThinkPrimary));
        EditText etContextLength = editText(
            config.getContextLength(true) > 0 ? String.valueOf(config.getContextLength(true)) : "",
            R.string.hint_context_length);
        etContextLength.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        primaryCard.addView(cardField(getString(R.string.caption_context_length), etContextLength));
        layout.addView(primaryCard);
        layout.addView(space(dp(8)));

        // === BACKUP MODEL card: same three settings, own group. ===
        layout.addView(sectionTitle(R.string.label_backup_model));
        LinearLayout backupCard = modelCard();
        EditText etBackupModel = editText(config.getBackupModel(), R.string.hint_backup_model);
        backupCard.addView(cardField(getString(R.string.caption_model), modelRow(etBackupModel, etUrl, etKey)));
        android.widget.CheckBox cbThinkBackup = new android.widget.CheckBox(this);
        cbThinkBackup.setTextColor(color(R.color.on_surface));
        cbThinkBackup.setChecked(config.getThinkingEnabled(false));
        backupCard.addView(cardField(getString(R.string.caption_thinking), cbThinkBackup));
        EditText etContextLengthBackup = editText(
            config.getContextLength(false) > 0 ? String.valueOf(config.getContextLength(false)) : "",
            R.string.hint_context_length_backup);
        etContextLengthBackup.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        backupCard.addView(cardField(getString(R.string.caption_context_length), etContextLengthBackup));
        layout.addView(backupCard);
        layout.addView(space(dp(8)));

        // Test connection: two buttons, one per model - each hits the
        // endpoint DIRECTLY with that model (no fallback). Placed right below
        // the model cards because picking + testing belong together; the
        // profiles group (switch / save profile / save config) follows below.
        LinearLayout testRow = new LinearLayout(this);
        testRow.setOrientation(LinearLayout.HORIZONTAL);
        Button testPrimaryBtn = connectionTestButton(R.string.action_test_primary,
            etUrl, etKey, etModel, etBackupModel, false, cbThinkPrimary);
        Button testBackupBtn = connectionTestButton(R.string.action_test_backup,
            etUrl, etKey, etModel, etBackupModel, true, cbThinkBackup);
        LinearLayout.LayoutParams testPrimaryLp = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        testPrimaryBtn.setLayoutParams(testPrimaryLp);
        LinearLayout.LayoutParams testBackupLp = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        testBackupLp.setMarginStart(dp(8));
        testBackupBtn.setLayoutParams(testBackupLp);
        testRow.addView(testPrimaryBtn);
        testRow.addView(testBackupBtn);
        layout.addView(testRow);
        layout.addView(space(dp(12)));

        // Configuration profiles: save the current form as a named setup,
        // switch between saved setups with one tap.
        layout.addView(label(R.string.label_profiles));
        layout.addView(profileRow(etUrl, etKey, etModel, etBackupModel));
        layout.addView(space(dp(12)));

        // Save
        Button saveBtn = new Button(this);
        saveBtn.setText(R.string.action_save_config);
        saveBtn.setAllCaps(false);
        saveBtn.setTextColor(color(R.color.on_primary));
        // Same solid blue as the pick/preset buttons: the page's main action
        // should look like the page's main action.
        android.graphics.drawable.GradientDrawable saveBg = new android.graphics.drawable.GradientDrawable();
        saveBg.setColor(color(R.color.pick_bg));
        saveBg.setCornerRadius(dp(8));
        saveBtn.setBackground(saveBg);
        saveBtn.setPadding(0, dp(10), 0, dp(10));
        saveBtn.setOnClickListener(v -> {
            String url = etUrl.getText().toString().trim();
            String key = etKey.getText().toString().trim();
            String model = etModel.getText().toString().trim();
            String backupModel = etBackupModel.getText().toString().trim();
            if (url.isEmpty() || key.isEmpty()) {
                toast(R.string.toast_fill_required_fields);
                return;
            }
            config.setBaseUrl(url);
            config.setApiKey(key);
            if (!model.isEmpty()) config.setModel(model);
            config.setBackupModel(backupModel);
            String contextLengthText = etContextLength.getText().toString().trim();
            config.setContextLength(true, contextLengthText.isEmpty()
                ? 0 : Integer.parseInt(contextLengthText));
            String contextLengthBackupText = etContextLengthBackup.getText().toString().trim();
            config.setContextLength(false, contextLengthBackupText.isEmpty()
                ? 0 : Integer.parseInt(contextLengthBackupText));
            config.setThinkingEnabled(true, cbThinkPrimary.isChecked());
            config.setThinkingEnabled(false, cbThinkBackup.isChecked());
            toast(R.string.toast_config_saved);
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
        // Edge-to-edge (targetSdk 35): consume system-bar insets so the
        // title never slides under the status bar.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            int top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).top;
            int bottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom;
            v.setPadding(0, top, 0, bottom);
            return insets;
        });
        setContentView(scroll);
    }

    /** Row with the profile actions: switch between saved profiles, or save
     *  the current form as one. */
    private android.view.View profileRow(EditText etUrl, EditText etKey,
                                         EditText etModel, EditText etBackupModel) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        Button switchBtn = new Button(this);
        switchBtn.setText(R.string.action_switch_profile);
        switchBtn.setAllCaps(false);
        switchBtn.setBackgroundColor(color(R.color.surface_variant));
        switchBtn.setTextColor(color(R.color.on_surface));
        switchBtn.setOnClickListener(v ->
            showProfilePicker(etUrl, etKey, etModel, etBackupModel));
        row.addView(switchBtn, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button saveBtn = new Button(this);
        saveBtn.setText(R.string.action_save_profile);
        saveBtn.setAllCaps(false);
        saveBtn.setBackgroundColor(color(R.color.surface_variant));
        saveBtn.setTextColor(color(R.color.on_surface));
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        saveLp.setMarginStart(dp(8));
        saveBtn.setLayoutParams(saveLp);
        saveBtn.setOnClickListener(v ->
            showSaveProfileDialog(etUrl, etKey, etModel, etBackupModel));
        row.addView(saveBtn);
        return row;
    }

    private void showProfilePicker(EditText etUrl, EditText etKey,
                                   EditText etModel, EditText etBackupModel) {
        LlmConfig config = new LlmConfig(this);
        final java.util.List<LlmConfig.Profile> profiles = config.getProfiles();
        if (profiles.isEmpty()) {
            toast(R.string.toast_no_profiles);
            return;
        }
        String[] names = new String[profiles.size()];
        for (int i = 0; i < profiles.size(); i++) {
            names[i] = profiles.get(i).name;
        }
        final ListView list = new ListView(this);
        list.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_list_item_1, names));
        final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
            .setTitle(R.string.title_profiles)
            .setView(list)
            .setNegativeButton(R.string.action_cancel, null)
            .create();
        // Tap: apply the profile and refresh the form.
        list.setOnItemClickListener((parent, view, position, id) -> {
            LlmConfig.Profile p = profiles.get(position);
            config.applyProfile(p);
            etUrl.setText(config.getBaseUrl());
            etKey.setText(config.getApiKey());
            etModel.setText(config.getModel());
            etBackupModel.setText(config.getBackupModel());
            toast(R.string.toast_profile_applied, p.name);
            dialog.dismiss();
        });
        // Long-press: delete the saved profile (the active config is untouched).
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            LlmConfig.Profile p = profiles.get(position);
            new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.action_delete_profile)
                .setMessage(getString(R.string.msg_delete_profile, p.name))
                .setPositiveButton(R.string.action_delete, (d, w) -> {
                    config.deleteProfile(p.name);
                    toast(R.string.toast_profile_deleted);
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
            return true;
        });
        dialog.show();
    }

    private void showSaveProfileDialog(EditText etUrl, EditText etKey,
                                       EditText etModel, EditText etBackupModel) {
        final EditText nameInput = new EditText(this);
        nameInput.setHint(R.string.hint_profile_name);
        nameInput.setSingleLine();
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(16), 0, dp(16), 0);
        wrap.addView(nameInput, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));
        new android.app.AlertDialog.Builder(this)
            .setTitle(R.string.action_save_profile)
            .setView(wrap)
            .setPositiveButton(R.string.action_save, (d, w) -> {
                String name = nameInput.getText().toString().trim();
                if (name.isEmpty()) {
                    return;
                }
                LlmConfig config = new LlmConfig(this);
                config.saveProfile(new LlmConfig.Profile(name,
                    etUrl.getText().toString().trim(),
                    etKey.getText().toString().trim(),
                    etModel.getText().toString().trim(),
                    etBackupModel.getText().toString().trim()));
                toast(R.string.toast_profile_saved);
            })
            .setNegativeButton(R.string.action_cancel, null)
            .show();
    }

    /** Guards against overlapping fetches from the two pick buttons. */
    private boolean modelsFetching;

    /** Well-known OpenAI-compatible endpoints, offered as one-tap presets. */
    private static final String[][] PRESET_PROVIDERS = {
        {"OpenRouter", "https://openrouter.ai/api/v1"},
        {"NVIDIA NIM", "https://integrate.api.nvidia.com/v1"},
        {"OpenAI", "https://api.openai.com/v1"},
        {"Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai"},
        {"Anthropic Claude", "https://api.anthropic.com/v1"},
        {"DeepSeek", "https://api.deepseek.com/v1"},
        {"Moonshot AI (Kimi)", "https://api.moonshot.cn/v1"},
        {"Zhipu GLM", "https://open.bigmodel.cn/api/paas/v4"},
        {"Alibaba DashScope", "https://dashscope.aliyuncs.com/compatible-mode/v1"},
        {"SiliconFlow", "https://api.siliconflow.cn/v1"},
        {"xAI (Grok)", "https://api.x.ai/v1"},
        {"Mistral AI", "https://api.mistral.ai/v1"},
        {"Groq", "https://api.groq.com/openai/v1"},
        {"书生 InternLM", "https://discovery-api.intern-ai.org.cn/v1"},
        {"Azure OpenAI", "https://YOUR_RESOURCE.openai.azure.com/openai"},
    };

    /** The URL row: input field plus a preset picker button. */
    private android.view.View urlRow(EditText field) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        field.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(field);

        Button presets = new Button(this);
        presets.setText(R.string.action_provider_presets);
        presets.setAllCaps(false);
        presets.setTextColor(color(R.color.on_primary));
        // Same solid blue style as the model pick button, stretched to the
        // input's height so the URL row stays coordinated.
        android.graphics.drawable.GradientDrawable presetBg = new android.graphics.drawable.GradientDrawable();
        presetBg.setColor(color(R.color.pick_bg));
        presetBg.setCornerRadius(dp(8));
        presets.setBackground(presetBg);
        presets.setMinHeight(0);
        presets.setMinimumHeight(0);
        presets.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams presetLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT);
        presetLp.setMarginStart(dp(8));
        presets.setLayoutParams(presetLp);
        presets.setOnClickListener(v -> showPresetPicker(field));
        row.addView(presets);
        return row;
    }

    private void showPresetPicker(EditText field) {
        String[] names = new String[PRESET_PROVIDERS.length];
        for (int i = 0; i < PRESET_PROVIDERS.length; i++) {
            names[i] = PRESET_PROVIDERS[i][0];
        }
        new android.app.AlertDialog.Builder(this)
            .setTitle(R.string.title_provider_presets)
            .setItems(names, (dialog, which) -> {
                field.setText(PRESET_PROVIDERS[which][1]);
                if ("Azure OpenAI".equals(PRESET_PROVIDERS[which][0])) {
                    // The Azure endpoint embeds the resource name; point the
                    // user at the placeholder so they replace it first.
                    toastLong(R.string.toast_azure_placeholder);
                }
            })
            .show();
    }

    /**
     * A labeled field row inside a model card: a small caption on the left
     * so every control says what it is and that it is interactive.
     */
    private android.view.View cardField(String caption, android.view.View control) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView captionView = new TextView(this);
        captionView.setText(caption);
        captionView.setTextColor(color(R.color.on_surface));
        captionView.setLayoutParams(new LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(captionView);
        control.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(control);
        return row;
    }

    /**
     * Model row: an EDITABLE input (so the user sees it can be changed) plus
     * an explicit "pick" button that opens the searchable picker.
     */
    private android.view.View modelRow(final EditText field, final EditText etUrl, final EditText etKey) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        field.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(field);

        Button pick = new Button(this);
        pick.setText(R.string.action_pick_model);
        pick.setAllCaps(false);
        pick.setTextColor(color(R.color.on_primary));
        // Solid rounded button in a rich blue (the default purple read as a
        // ghostly glow on the dark card), stretched to exactly the input's
        // height so the row looks coordinated.
        android.graphics.drawable.GradientDrawable pickBg = new android.graphics.drawable.GradientDrawable();
        pickBg.setColor(color(R.color.pick_bg));
        pickBg.setCornerRadius(dp(8));
        pick.setBackground(pickBg);
        pick.setMinHeight(0);
        pick.setMinimumHeight(0);
        pick.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams pickLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT);
        pickLp.setMarginStart(dp(8));
        pick.setLayoutParams(pickLp);
        pick.setOnClickListener(v -> startModelPick(field, etUrl, etKey, pick));
        row.addView(pick);
        return row;
    }

    /**
     * A button testing connectivity for ONE model (primary or backup): the
     * tested model becomes the primary of a fresh, fallback-less config, so
     * the request really hits THAT model. The old single button only ever
     * exercised the primary.
     */
    private Button connectionTestButton(int labelResId, final EditText etUrl, final EditText etKey,
                                        final EditText etModel, final EditText etBackupModel,
                                        final boolean testBackup,
                                        final android.widget.CheckBox thinkingBox) {
        final Button btn = new Button(this);
        btn.setText(labelResId);
        btn.setAllCaps(false);
        btn.setBackgroundColor(color(R.color.surface_variant));
        btn.setTextColor(color(R.color.on_surface));
        btn.setOnClickListener(v -> {
            String url = etUrl.getText().toString().trim();
            String key = etKey.getText().toString().trim();
            String model = testBackup
                ? etBackupModel.getText().toString().trim()
                : etModel.getText().toString().trim();
            if (url.isEmpty() || key.isEmpty()) {
                toast(R.string.toast_fill_required_fields);
                return;
            }
            if (model.isEmpty()) {
                toast(testBackup ? R.string.toast_backup_model_missing
                    : R.string.toast_primary_model_missing);
                return;
            }
            btn.setEnabled(false);
            btn.setText(R.string.status_testing);
            LlmConfig testConfig = new LlmConfig(url, key, model, "");
            testConfig.setThinkingEnabled(true, thinkingBox.isChecked());
            // The same request path the agent loop uses (tool-calling
            // protocol), so a successful test really proves generation will
            // work. Fallback disabled: this button tests exactly one model.
            new LLMClient(this, testConfig, 60, false).requestTools(
                java.util.Collections.singletonList(LLMClient.ChatMessage.user("Reply with OK.")),
                com.meta.app.core.MetaEngine.agentTools(),
                new LLMClient.ToolsCallback() {
                    @Override
                    public void onComplete(java.util.List<LLMClient.ChatMessage> assistantMessages) {
                        btn.setEnabled(true);
                        btn.setText(labelResId);
                        toastLong(R.string.status_test_ok, model);
                    }

                    @Override
                    public void onError(Exception e) {
                        btn.setEnabled(true);
                        btn.setText(labelResId);
                        android.util.Log.e("SettingsActivity", "Connection test failed: " + e.getMessage(), e);
                        toastLong(R.string.status_test_fail, e.getMessage());
                    }

                    @Override
                    public void onModelSwitch(String from, String to) {
                        // Never fired: this client has fallback disabled.
                    }
                });
        });
        return btn;
    }

    private void startModelPick(EditText targetField, EditText etUrl, EditText etKey,
                                Button pickBtn) {
        String url = etUrl.getText().toString().trim();
        String key = etKey.getText().toString().trim();
        if (url.isEmpty() || key.isEmpty()) {
            toast(R.string.toast_fill_required_fields);
            return;
        }
        if (modelsFetching) {
            return;
        }
        modelsFetching = true;
        pickBtn.setEnabled(false);
        toast(R.string.status_models_fetching);
        LlmConfig probeConfig = new LlmConfig(url, key, "", "");
        new LLMClient(this, probeConfig).fetchModels(url, key, new LLMClient.ModelsCallback() {
            @Override
            public void onModels(List<LLMClient.ModelInfo> models) {
                runOnUiThread(() -> {
                    modelsFetching = false;
                    pickBtn.setEnabled(true);
                    List<LLMClient.ModelInfo> chatModels = new ArrayList<>();
                    for (LLMClient.ModelInfo info : models) {
                        // Non-chat entries (embedding, tts, asr, rerank,
                        // image generation...) can't serve chat requests.
                        if (info.chatCapable()) {
                            chatModels.add(info);
                        }
                    }
                    showSearchableModelPicker(chatModels, targetField, url, key);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    modelsFetching = false;
                    pickBtn.setEnabled(true);
                    android.util.Log.e("SettingsActivity", "Model list fetch failed: " + message);
                    toastLong(R.string.status_models_error, message);
                });
            }
        });
    }

    /**
     * Searchable model picker: a filter box on top filters the list live;
     * tapping an item writes the endpoint-verified model name into the
     * target field. The listed id alone may not be routable (gateways that
     * need a vendor segment, e.g. "deepseek-flash" vs "deepseek/deepseek-
     * flash"), so the tapped entry is probed against the endpoint: the id as
     * listed first, then the owned_by-qualified form if the id is bare.
     */
    private void showSearchableModelPicker(List<LLMClient.ModelInfo> modelInfos,
                                           EditText targetField, String url, String key) {
        if (modelInfos == null || modelInfos.isEmpty()) {
            toast(R.string.status_models_empty);
            return;
        }
        // Display "id — owner" so entries from different vendors sharing one
        // model name (e.g. deepseek-v4-flash under deepseek AND under
        // volcengine_maas) stay distinguishable. Qualified ids already carry
        // the vendor segment (OpenRouter style) and are shown as-is.
        final java.util.Map<String, LLMClient.ModelInfo> byDisplay = new java.util.HashMap<>();
        final List<String> displays = new ArrayList<>();
        for (LLMClient.ModelInfo info : modelInfos) {
            String display = info.id.contains("/") || info.ownedBy.isEmpty()
                ? info.id : info.id + "  —  " + info.ownedBy;
            byDisplay.put(display, info);
            displays.add(display);
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
        // nothing. This one does case-insensitive substring matching. The
        // search covers the display text, so an owner name matches too.
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_1,
            // IMPORTANT: a copy - ArrayAdapter holds the given list BY
            // REFERENCE and publishResults() calls clear(), which would wipe
            // the original displays and turn every search into a filter of
            // the previous result.
            new ArrayList<>(displays)) {
            @Override
            public Filter getFilter() {
                return new Filter() {
                    @Override
                    protected FilterResults performFiltering(CharSequence constraint) {
                        FilterResults results = new FilterResults();
                        String query = constraint == null
                            ? "" : constraint.toString().trim().toLowerCase(java.util.Locale.ROOT);
                        List<String> filtered = new ArrayList<>();
                        if (query.isEmpty()) {
                            filtered.addAll(displays);
                        } else {
                            for (String display : displays) {
                                if (display.toLowerCase(java.util.Locale.ROOT).contains(query)) {
                                    filtered.add(display);
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
            String tappedDisplay = adapter.getItem(position);
            LLMClient.ModelInfo info = byDisplay.get(tappedDisplay);
            if (dialogHolder[0] != null) {
                dialogHolder[0].dismiss();
            }
            if (info == null) {
                targetField.setText(tappedDisplay);
                return;
            }
            targetField.setText(info.id);
            verifyModelName(info, targetField, url, key);
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

    /**
     * Probes candidate name forms against the endpoint and writes the first
     * accepted one into the field. The field gets the listed id immediately
     * (no waiting); if the endpoint rejects it and accepts the qualified
     * form, the field is corrected and the user is told. If every candidate
     * fails, the field keeps the listed id and the gateway's reason is
     * surfaced verbatim - no silent guessing.
     */
    private void verifyModelName(LLMClient.ModelInfo info, EditText targetField,
                                 String url, String key) {
        final List<String> candidates = info.candidates();
        final int[] index = {0};
        new LLMClient(this, new LlmConfig(url, key, "", "")).probeModel(
            url, key, candidates.get(0),
            new LLMClient.ProbeCallback() {
                @Override
                public void onAccepted(String name) {
                    if (index[0] > 0) {
                        // The bare id was rejected; the qualified form works.
                        targetField.setText(name);
                        toastLong(R.string.toast_model_qualified, name);
                    }
                }

                @Override
                public void onRejected(String detail) {
                    index[0]++;
                    if (index[0] < candidates.size()) {
                        new LLMClient(SettingsActivity.this,
                            new LlmConfig(url, key, "", "")).probeModel(
                                url, key, candidates.get(index[0]), this);
                        return;
                    }
                    android.util.Log.e("SettingsActivity",
                        "Model name verification failed for all candidates of "
                            + info.id + ": " + detail);
                    toastLong(R.string.status_test_fail, detail);
                }
            });
    }

    /**
     * Toast + logcat in one: every user-visible message is also written to
     * the log, so results can be verified from logcat instead of racing a
     * short-lived toast on screen.
     */
    private void toast(int resId, Object... args) {
        showToast(true, resId, args);
    }

    private void toastLong(int resId, Object... args) {
        showToast(false, resId, args);
    }

    private void showToast(boolean shortDuration, int resId, Object... args) {
        String msg = getString(resId, args);
        android.util.Log.i("SettingsActivity", "Toast: " + msg);
        Toast.makeText(this, msg,
            shortDuration ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
    }

    private TextView label(int textResId) {
        TextView tv = new TextView(this);
        tv.setText(textResId);
        tv.setTextColor(color(R.color.on_surface));
        tv.setPadding(0, 0, 0, dp(4));
        return tv;
    }

    /** Bold section heading above a card group. */
    private TextView sectionTitle(int textResId) {
        TextView tv = new TextView(this);
        tv.setText(textResId);
        tv.setTextSize(15);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setTextColor(color(R.color.on_background));
        tv.setPadding(0, 0, 0, dp(6));
        return tv;
    }

    /** A rounded, tinted container grouping one model's settings
     *  (name + pick, thinking toggle, context length). */
    private LinearLayout modelCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(6), dp(10), dp(8));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(color(R.color.surface_variant));
        bg.setCornerRadius(dp(12));
        card.setBackground(bg);
        return card;
    }

    private EditText editText(String value, int hintResId) {
        EditText et = new EditText(this);
        et.setText(value);
        et.setHint(hintResId);
        et.setTextColor(color(R.color.on_background));
        et.setHintTextColor(color(R.color.on_surface_variant));
        // An outlined box reads as "editable input" even on the card's tinted
        // background - a flat same-color field looked like read-only text.
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(color(R.color.input_bg));
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), color(R.color.outline));
        et.setBackground(bg);
        et.setPadding(dp(12), dp(8), dp(12), dp(8));
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
