package io.github.voicetrail;

import android.graphics.Color;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The access key is never populated from storage or included in view-state restoration. */
public final class CloudSettings {
    private CloudSettings() {}

    public static void show(MainActivity a) {
        LinearLayout page = a.page(a.t("可选模型服务", "Optional model provider"));
        a.label(page, a.t("离线功能可直接使用。配置兼容 Chat Completions 的服务后，每次联网前都会询问。服务商可能收费。",
                "Offline features work immediately. To use a Chat Completions provider, configure it here. You will be asked before each request. Your provider may charge."));
        a.label(page, a.t("地址、模型名和密钥仅保留到应用进程结束。重新保存时请输入该服务自己的密钥。",
                "The address, models and key stay in memory until the app process ends. Re-enter the key for this provider each time you save."));
        TextView current = a.label(page, state(a));
        EditText address = field(a, page, a.t("服务地址（HTTPS）", "Provider address (HTTPS)"),
                CloudAssist.endpoint(), InputType.TYPE_TEXT_VARIATION_URI, 512);
        EditText text = field(a, page, a.t("文字模型 ID", "Text model ID"),
                CloudAssist.textModel(), InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, 160);
        EditText vision = field(a, page, a.t("视觉模型 ID", "Vision model ID"),
                CloudAssist.visionModel(), InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, 160);
        EditText key = field(a, page, a.t("API 密钥", "API key"), "", InputType.TYPE_TEXT_VARIATION_PASSWORD, 1024);
        a.button(page, a.t("本次使用此服务", "Use provider for this session"), () -> {
            boolean ok = CloudAssist.configure(address.getText().toString(), text.getText().toString(),
                    vision.getText().toString(), key.getText().toString());
            key.getText().clear();
            String message = ok ? state(a) : a.t("配置未保存。请检查 HTTPS 地址、两个模型名和密钥。",
                    "Settings were not saved. Check the HTTPS address, both model IDs and the key.");
            current.setText(message);
            a.say(message, null);
        });
        a.button(page, a.t("清除本次服务配置", "Clear this session’s provider"), () -> {
            CloudAssist.clear();
            key.getText().clear();
            text.getText().clear();
            vision.getText().clear();
            address.setText(CloudAssist.endpoint());
            current.setText(state(a));
            a.say(state(a), null);
        });
        a.button(page, a.t("返回更多", "Back to More"), () -> {
            key.getText().clear();
            SettingsPanel.show(a);
        });
    }

    private static String state(MainActivity a) {
        return CloudAssist.ready() ? a.t("当前服务：" + CloudAssist.host(), "Configured provider: " + CloudAssist.host())
                : a.t("当前使用离线功能", "Offline features active");
    }

    private static EditText field(MainActivity a, LinearLayout page, String caption, String value, int variation, int max) {
        TextView label = a.label(page, caption);
        EditText input = new EditText(a);
        input.setId(View.generateViewId());
        label.setLabelFor(input.getId());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | variation | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        input.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS);
        input.setSaveEnabled(false);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_ACTION_NEXT);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
        input.setTextColor(Color.BLACK);
        input.setBackgroundColor(Color.WHITE);
        input.setTextSize(20);
        input.setMinHeight(Math.round(56 * a.getResources().getDisplayMetrics().density));
        input.setText(value);
        page.addView(input);
        return input;
    }
}
