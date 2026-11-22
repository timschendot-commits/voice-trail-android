package io.github.voicetrail;

import android.content.Context;
import android.graphics.Color;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;

/** The low-frequency controls use the same native accessible widgets as the home screen. */
public final class SettingsPanel {
    private SettingsPanel() {}

    public static void show(MainActivity a) {
        LinearLayout page = a.page(a.t("更多", "More"));
        a.button(page, a.t("语言和显示模式", "Language and display"), () -> display(a));
        a.button(page, a.t("语速和音量", "Speech rate and volume"), () -> speech(a));
        a.button(page, a.t("常用微信联系人", "Trusted WeChat contact"), () -> contact(a));
        a.button(page, a.t("可选模型服务", "Optional model provider"), () -> CloudSettings.show(a));
        a.button(page, a.t("权限与隐私", "Permissions and privacy"), () -> permissions(a));
        a.button(page, a.t("回到主页", "Home"), a::home);
    }

    private static void display(MainActivity a) {
        LinearLayout page = a.page(a.t("语言和显示模式", "Language and display"));
        a.label(page, a.t("当前语言：简体中文", "Current language: English"));
        a.button(page, "简体中文", () -> a.changeLanguage(true));
        a.button(page, "English", () -> a.changeLanguage(false));
        String mode = a.prefs.getString("mode", "auto");
        TextView current = a.label(page, modeSummary(a, mode));
        for (String value : new String[]{"auto", "speech", "large"}) {
            a.button(page, modeName(a, value), () -> {
                a.prefs.edit().putString("mode", value).apply();
                String summary = modeSummary(a, value);
                current.setText(summary);
                a.say(summary, null);
            });
        }
        back(a, page);
    }

    private static String modeName(MainActivity a, String mode) {
        if ("speech".equals(mode)) return a.t("朗读优先", "Speech first");
        if ("large".equals(mode)) return a.t("大字模式", "Large text");
        return a.t("自动选择", "Automatic");
    }

    private static String modeSummary(MainActivity a, String mode) {
        String name = modeName(a, mode);
        if ("auto".equals(mode)) {
            AccessibilityManager manager = (AccessibilityManager) a.getSystemService(Context.ACCESSIBILITY_SERVICE);
            boolean touchExplore = manager != null && manager.isTouchExplorationEnabled();
            name += " · " + modeName(a, touchExplore ? "speech" : "large");
        }
        return a.t("当前模式：" + name, "Current mode: " + name);
    }

    private static void speech(MainActivity a) {
        LinearLayout page = a.page(a.t("语速和音量", "Speech rate and volume"));
        TextView rate = a.label(page, speechValue(a, true));
        a.button(page, a.t("说慢一点", "Speak slower"), () -> adjust(a, rate, true, -0.1f));
        a.button(page, a.t("说快一点", "Speak faster"), () -> adjust(a, rate, true, 0.1f));
        TextView volume = a.label(page, speechValue(a, false));
        a.button(page, a.t("助手音量降低", "Lower assistant volume"), () -> adjust(a, volume, false, -0.1f));
        a.button(page, a.t("助手音量提高", "Raise assistant volume"), () -> adjust(a, volume, false, 0.1f));
        back(a, page);
    }

    private static String speechValue(MainActivity a, boolean rate) {
        int percent = Math.round(a.prefs.getFloat(rate ? "rate" : "volume", rate ? 0.88f : 1f) * 100);
        return rate ? a.t("语速 " + percent + "%", "Speech rate " + percent + "%")
                : a.t("助手音量 " + percent + "%", "Assistant volume " + percent + "%");
    }

    private static void adjust(MainActivity a, TextView label, boolean rate, float delta) {
        String key = rate ? "rate" : "volume";
        float next = a.prefs.getFloat(key, rate ? 0.88f : 1f) + delta;
        next = Math.max(rate ? 0.55f : 0.1f, Math.min(rate ? 1.6f : 1f, next));
        a.prefs.edit().putFloat(key, next).apply();
        a.applyVoiceSettings();
        String sample = speechValue(a, rate);
        label.setText(sample);
        a.say(sample, null);
    }

    private static void contact(MainActivity a) {
        LinearLayout page = a.page(a.t("常用微信联系人", "Trusted WeChat contact"));
        String saved = a.prefs.getString("preset", "");
        a.label(page, saved.isEmpty() ? a.t("尚未设置联系人", "No contact saved")
                : a.t("已保存：" + saved, "Saved: " + saved));
        a.label(page, a.t("请使用微信中显示的完整名称。", "Use the exact name shown in WeChat."));
        a.button(page, a.t("说出联系人姓名", "Dictate contact name"), () ->
                a.say(a.t("提示音后，请说出微信中的完整姓名。",
                        "After the tone, say the full name shown in WeChat."), () -> {
                    if (active(a, page)) a.dictate(name -> {
                        if (active(a, page)) review(a, name, true, false);
                    });
                }));
        TextView nameLabel = a.label(page, a.t("输入联系人姓名", "Type contact name"));
        EditText input = new EditText(a);
        input.setId(View.generateViewId());
        nameLabel.setLabelFor(input.getId());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
        input.setSingleLine(true);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        input.setTextColor(Color.BLACK);
        input.setBackgroundColor(Color.WHITE);
        input.setTextSize(24);
        input.setMinHeight(Math.round(56 * a.getResources().getDisplayMetrics().density));
        page.addView(input);
        a.button(page, a.t("核对输入的姓名", "Review typed name"), () -> {
            String name = input.getText().toString();
            if (name.trim().isEmpty()) {
                input.setError(a.t("请输入姓名", "Enter a name"));
                return;
            }
            review(a, name, false, false);
        });
        if (!saved.isEmpty()) {
            a.button(page, a.t("移除常用联系人", "Remove saved contact"), () -> review(a, saved, false, true));
        }
        back(a, page);
    }

    private static void review(MainActivity a, String proposed, boolean fromSpeech, boolean remove) {
        String name = proposed == null ? "" : proposed.trim();
        if (name.isEmpty() || name.length() > 80 || name.codePoints().anyMatch(cp ->
                Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT)) {
            a.say(a.t("姓名无效。请重新输入微信中的完整姓名。",
                    "That name could not be used. Enter the exact name shown in WeChat again."), null);
            return;
        }
        LinearLayout page = a.page(a.t("核对联系人", "Review contact"));
        String question = remove ? a.t("移除常用联系人“" + name + "”？", "Remove " + name + " as the trusted contact?")
                : a.t("将“" + name + "”设为常用联系人？", "Save " + name + " as the trusted contact?");
        a.label(page, question);
        String yes = remove ? a.t("确认移除", "Confirm removal") : a.t("确认保存", "Confirm save");
        Runnable commit = () -> {
            if (!active(a, page)) return;
            a.setPreset(remove ? "" : name);
            contact(a);
            a.say(remove ? a.t("已移除常用联系人。", "Trusted contact removed.")
                    : a.t("已保存联系人“" + name + "”。", "Saved " + name + " as the trusted contact."), null);
        };
        Runnable listen = () -> {
            if (!active(a, page)) return;
            a.dictate(phrase -> {
                if (!active(a, page)) return;
                String answer = phrase.trim().toLowerCase(Locale.ROOT).replaceAll("[.!?。！？]+$", "");
                if (answer.equals(yes.toLowerCase(Locale.ROOT))) commit.run();
                else if (answer.equals(a.t("取消", "cancel"))) {
                    contact(a);
                    a.say(a.t("已取消。", "Cancelled."), null);
                } else {
                    a.say(a.t("没有听到“" + yes + "”。已保留原设置。", "I didn't hear “" + yes + "”. Your setting is unchanged."), null);
                }
            });
        };
        a.button(page, yes, commit);
        a.button(page, a.t("用语音回答", "Answer by voice"), listen);
        a.button(page, a.t("取消", "Cancel"), () -> contact(a));
        String prompt = question + a.t("请说“" + yes + "”或“取消”。", " Say “" + yes + "” or “Cancel”.");
        a.say(prompt, fromSpeech ? listen : null);
    }

    private static boolean active(MainActivity a, View view) {
        return !a.isFinishing() && !a.isDestroyed() && view.isAttachedToWindow();
    }

    private static void permissions(MainActivity a) {
        LinearLayout page = a.page(a.t("权限与隐私", "Permissions and privacy"));
        a.label(page, a.t("开启无障碍服务后，可以朗读其他应用并显示说话按钮。你可以随时在系统设置中关闭它。",
                "Accessibility access lets the assistant read other apps and show the Speak button. You can turn it off in system settings."));
        a.button(page, a.t("系统无障碍设置", "System accessibility settings"), a::openServiceSettings);
        a.button(page, a.t("允许任务结果通知", "Allow task result notifications"), a::askNotifications);
        a.label(page, a.t("语音识别和默认照片分析在手机上进行。可选云端描述会在发送照片和问题前询问。常用联系人保存在本应用。仅在你明确要求时朗读验证码。",
                "Speech recognition and default photo analysis run on your phone. Optional cloud descriptions ask before sending your photo and question. Your trusted contact stays in this app. Codes are read only when you explicitly ask."));
        back(a, page);
    }

    private static void back(MainActivity a, LinearLayout page) {
        a.button(page, a.t("返回更多", "Back to More"), () -> show(a));
    }
}
