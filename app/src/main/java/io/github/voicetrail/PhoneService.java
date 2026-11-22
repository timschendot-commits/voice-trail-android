package io.github.voicetrail;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local Android tools. Every device operation belongs to one cancellable goal. */
public final class PhoneService extends AccessibilityService {
    public interface Flow {
        /** Continue after narration finishes. All methods are invoked on the main thread. */
        void step(String text, Runnable next);
        void done(String text, boolean success);
        default void handoff(String text) { done(text, true); }
        default void readDone(String finalPart, String replay) { done(finalPart, true); }
        default boolean allow(String tool) { return false; }
    }
    private static final String WECHAT = "com.tencent.mm";
    private static final String[] CONTACTS = {"通讯录", "通訊錄", "Contacts"};
    private static final String[] NEW_FRIENDS = {"新的朋友", "新的好友", "New Friends", "New Friends Notification"};
    private static final String[] MESSAGE = {"发消息", "發訊息", "發消息", "Send Message", "Messages"};
    private static final String[] HOLD = {"按住 说话", "按住说话", "按住 說話", "Hold to Talk", "Hold to record"};
    private static final String[] VOICE = {"切换到按住说话", "切换到语音", "切换到语音输入", "Switch to Hold to Talk", "Switch to Voice"};
    private static final String[] KEYBOARD = {"切换到键盘", "切换到键盘输入", "Switch to Keyboard"};
    private static final String PREFS = "voice_trail_phone";
    private static PhoneService active;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Button overlay;
    private Run current;
    private String entryPackage;
    private int entryWindow = -1;
    private long entryTime;

    public static PhoneService get() { return active; }
    public void setLanguage(boolean zh) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("zh", zh).apply();
        if (overlay != null) labelOverlay(zh);
    }
    @Override protected void onServiceConnected() { super.onServiceConnected(); active = this; addOverlay(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void labelOverlay(boolean zh) {
        overlay.setText(zh ? "声迹" : "Voice Trail");
        overlay.setContentDescription(zh ? "声迹助手，说出指令" : "Voice Trail, speak a command");
    }
    private void addOverlay() {
        if (overlay != null) return;
        Button b = new Button(this);
        overlay = b;
        labelOverlay(getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("zh",
                Locale.getDefault().getLanguage().equals("zh")));
        b.setAllCaps(false);
        b.setTextSize(20);
        b.setMinHeight(dp(56));
        b.setMinWidth(dp(64));
        b.setPadding(dp(12), dp(4), dp(12), dp(4));
        b.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(19, 57, 66));
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(2), Color.WHITE);
        b.setBackground(bg);
        b.setOnClickListener(v -> {
            if (locked()) return;
            // Save only the origin identity on a deliberate press, never its contents.
            rememberOrigin();
            Intent intent = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP |
                            Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("listen", true);
            startActivity(intent);
        });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        lp.setTitle("Voice Trail");
        try { getSystemService(WindowManager.class).addView(b, lp); }
        catch (RuntimeException e) { overlay = null; } // Launcher remains available.
    }
    private void rememberOrigin() {
        entryPackage = null;
        entryWindow = -1;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            String name = PhoneTree.string(root.getPackageName());
            if (!name.isEmpty() && !name.equals(getPackageName())) {
                entryPackage = name;
                entryWindow = root.getWindowId();
                entryTime = SystemClock.elapsedRealtime();
            }
        } finally { root.recycle(); }
    }
    private boolean locked() {
        KeyguardManager km = getSystemService(KeyguardManager.class);
        return km != null && km.isKeyguardLocked();
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        // Only window identity is inspected here. Unrelated foreground changes end the run.
        if (current != null && e != null &&
                (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                        e.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED)) current.checkForeground();
        if (overlay == null || e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName();
        if (pkg == null) return;
        if (locked()) { overlay.setVisibility(android.view.View.GONE); return; }
        if (getPackageName().contentEquals(pkg)) {
            // Window events for our button itself must not make the entry point disappear.
            String type = PhoneTree.string(e.getClassName());
            if (MainActivity.class.getName().equals(type) || VisionActivity.class.getName().equals(type))
                overlay.setVisibility(android.view.View.GONE);
        } else overlay.setVisibility(android.view.View.VISIBLE);
    }
    @Override public void onInterrupt() { cancel(); }
    @Override public void onDestroy() {
        cancel();
        if (active == this) active = null;
        if (overlay != null) getSystemService(WindowManager.class).removeViewImmediate(overlay);
        overlay = null;
        super.onDestroy();
    }
    public void cancel() {
        current = null;
        main.removeCallbacksAndMessages(null);
    }
    public boolean hasUnverifiedSend() { return current != null && current.sendAttempted; }
    private Run begin(boolean zh, Flow callback) {
        cancel();
        Run run = new Run(zh, callback);
        current = run;
        return run;
    }
    private interface ScreenTest { boolean matches(PhoneTree tree); }
    private interface ScreenUse { void accept(PhoneTree tree); }
    private final class Run {
        final boolean zh;
        final Flow callback;
        String foregroundPackage;
        String launchTarget;
        String launchOrigin;
        boolean sendAttempted;
        String profileProof;
        int provenChatWindow = -1;
        Run(boolean zh, Flow callback) { this.zh = zh; this.callback = callback; }
        String l(String chinese, String english) { return zh ? chinese : english; }
        boolean alive() { return current == this; }
        void end(String text, boolean ok) {
            if (!alive()) return;
            current = null;
            callback.done(text, ok);
        }
        void handoff(String text) {
            if (!alive()) return;
            current = null;
            callback.handoff(text);
        }
        void fail(String text) {
            end(sendAttempted ? l("已尝试点发送，但无法核实结果。请在微信中查看与该联系人的聊天，先不要重发。",
                    "I attempted Send but couldn't verify the result. Check the conversation in WeChat before sending anything again.") : text, false);
        }
        void unavailable() { fail(l("屏幕已变化或标签不唯一。请回到目标页面，再说重试或取消。",
                "The screen changed or the label was ambiguous. Return to the intended page and retry, or cancel.")); }
        private void checkForeground() {
            if (foregroundPackage == null && launchTarget == null) return;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return; // A transition may briefly expose no root; the poll timeout still applies.
            try {
                String actual = PhoneTree.string(root.getPackageName());
                if (foregroundPackage != null && !foregroundPackage.equals(actual)) { unavailable(); return; }
                if (provenChatWindow >= 0 && provenChatWindow != root.getWindowId()) { unavailable(); return; }
                if (launchTarget != null && !launchTarget.equals(actual) && !actual.equals(launchOrigin)) unavailable();
            } finally { root.recycle(); }
        }
        private int narrationWindow() {
            if (foregroundPackage == null) return -1;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return -2;
            try {
                if (!foregroundPackage.equals(PhoneTree.string(root.getPackageName()))) return -2;
                return root.getWindowId();
            } finally { root.recycle(); }
        }
        boolean allowed(String tool) {
            if (!alive()) return false;
            if (locked()) {
                fail(l("手机已锁定。请亲自解锁，再说重试。", "The phone is locked. Unlock it yourself and retry."));
                return false;
            }
            if (callback.allow(tool)) return true;
            fail(l("本次任务未获准使用这项操作。你可以取消，或亲自完成。", "This action isn't approved for this task. Cancel or finish it yourself."));
            return false;
        }
        void say(String text, Runnable next) {
            if (!alive()) return;
            final int startingWindow = narrationWindow();
            if (startingWindow == -2) { unavailable(); return; }
            AtomicBoolean once = new AtomicBoolean();
            callback.step(text, () -> main.post(() -> {
                if (!alive() || !once.compareAndSet(false, true)) return;
                if (startingWindow >= 0 && startingWindow != narrationWindow()) { unavailable(); return; }
                next.run();
            }));
        }
        void waitScreen(String pkg, int window, ScreenTest test, ScreenUse use, String failure) {
            if (!allowed("read_nodes")) return;
            long deadline = SystemClock.elapsedRealtime() + 5500;
            probe(pkg, window, test, use, failure, deadline);
        }
        void probe(String pkg, int window, ScreenTest test, ScreenUse use, String failure, long deadline) {
            if (!alive()) return;
            if (locked()) { unavailable(); return; }
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                String name = PhoneTree.string(root.getPackageName());
                if (!name.isEmpty() && !name.equals(getPackageName()) && (pkg == null || pkg.equals(name))
                        && (window < 0 || window == root.getWindowId())) {
                    try (PhoneTree tree = new PhoneTree(root)) {
                        if (test.matches(tree)) {
                            foregroundPackage = tree.pkg;
                            launchTarget = null;
                            launchOrigin = null;
                            use.accept(tree);
                            return;
                        }
                    }
                } else {
                    root.recycle();
                    if (foregroundPackage != null && !foregroundPackage.equals(name)) { unavailable(); return; }
                }
            }
            if (SystemClock.elapsedRealtime() >= deadline) { fail(failure); return; }
            main.postDelayed(() -> probe(pkg, window, test, use, failure, deadline), 260);
        }
        void act(String pkg, String tool, ScreenTest precondition, ScreenTest action, Runnable next) {
            if (!allowed("read_nodes") || !allowed(tool)) return;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) { unavailable(); return; }
            try (PhoneTree t = new PhoneTree(root)) {
                if (!pkg.equals(t.pkg) || !precondition.matches(t) || !action.matches(t)) { unavailable(); return; }
            }
            next.run();
        }
        void launch(String label, String pkg, Intent intent, Runnable next) {
            say(l("正在打开", "Opening ") + label, () -> {
                if (!allowed("launch_app") || !allowed("read_nodes")) return;
                launchTarget = pkg;
                AccessibilityNodeInfo starting = getRootInActiveWindow();
                if (starting != null) {
                    launchOrigin = PhoneTree.string(starting.getPackageName());
                    starting.recycle();
                } else launchOrigin = getPackageName();
                try { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (RuntimeException e) {
                    fail(l("无法打开该应用。请确认它已安装并可启动，再说重试。", "I couldn't open the app. Check that it's installed and available, then retry."));
                    return;
                }
                waitScreen(pkg, -1, t -> true, t -> next.run(), l("已请求打开，但没有确认目标应用出现在前台。请重试。", "I requested the app but couldn't verify it came to the foreground. Please retry."));
            });
        }
    }

    /** Read the previous screen after the session UI leaves it visible. Codes never enter storage. */
    public void readScreenAfterReturn(Activity activity, boolean otp, boolean zh, Flow callback) {
        Run run = begin(zh, callback);
        final boolean fresh = SystemClock.elapsedRealtime() - entryTime < 90000;
        final String expected = fresh ? entryPackage : null;
        final int window = fresh ? entryWindow : -1;
        entryPackage = null;
        entryWindow = -1;
        if (otp && expected == null) {
            run.fail(run.l("请先打开含验证码的消息，再用悬浮按钮说读验证码。", "Open the message containing the code, then activate the floating button and say read the code."));
            return;
        }
        run.say(otp ? run.l("我将只朗读当前页面中明确标注的验证码。", "I'll read the clearly labelled code on this screen.") :
                run.l("我来读刚才的屏幕。", "I'll read the previous screen."), () -> {
            if (!run.allowed(otp ? "read_code" : "read_nodes")) return;
            run.launchOrigin = getPackageName();
            run.launchTarget = expected;
            activity.moveTaskToBack(true);
            run.waitScreen(expected, window, t -> true, t -> {
                String result = t.read(otp, zh);
                boolean ok = !otp || result.startsWith(zh ? "验证码是 " : "The verification code is ");
                List<Rect> bounds = otp ? java.util.Collections.emptyList() : t.unlabeledBounds();
                if (bounds.isEmpty() || !run.callback.allow("read_icons")) run.end(result, ok);
                else readWithIconHints(run, t.windowId(), bounds, result);
            }, run.l("没有找到刚才的页面。请回到要读的页面，用悬浮按钮再试。", "I couldn't find the previous screen. Return to it and try again from the floating button."));
        });
    }
    private void readWithIconHints(Run run, int window, List<Rect> bounds, String primary) {
        // The normal text starts immediately. Only the supplementary description has a deadline.
        class Readout {
            boolean spoken, resolved;
            String hints = "";
            void finish() {
                if (!run.alive() || !spoken || !resolved) return;
                if (run.narrationWindow() != window) { run.unavailable(); return; }
                current = null;
                run.callback.readDone(hints.isEmpty() ? run.l("读屏结束。", "Finished reading.") : hints,
                        hints.isEmpty() ? primary : primary + " " + hints);
            }
            void resolve(String result) {
                if (resolved || !run.alive()) return;
                resolved = true;
                hints = result == null ? "" : result;
                finish();
            }
        }
        Readout result = new Readout();
        run.say(primary, () -> { result.spoken = true; result.finish(); });
        main.postDelayed(() -> result.resolve(""), 2000);
        if (run.alive() && run.narrationWindow() == window)
            IconHints.describe(this, window, bounds, run.zh, result::resolve);
    }
    private static String clean(String input) { return input == null ? "" : input.trim(); }
    private Intent launcher(Run run, String name) {
        if (name.isEmpty()) { run.fail(run.l("请说出要打开的应用名称。", "Please name the app you want to open.")); return null; }
        PackageManager pm = getPackageManager();
        if ("WeChat".equalsIgnoreCase(name) || "微信".equals(name)) {
            Intent intent = pm.getLaunchIntentForPackage(WECHAT);
            if (intent == null) run.fail(run.l("找不到微信。请先安装微信，再说重试。", "I couldn't find WeChat. Install WeChat, then retry."));
            return intent;
        }
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Map<String, ComponentName> matches = new LinkedHashMap<>();
        for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
            String label = PhoneTree.string(info.loadLabel(pm));
            String appLabel = PhoneTree.string(info.activityInfo.applicationInfo.loadLabel(pm));
            if (name.equalsIgnoreCase(label) || name.equalsIgnoreCase(appLabel)) {
                String pkg = info.activityInfo.packageName;
                if (!getPackageName().equals(pkg)) matches.put(pkg, new ComponentName(pkg, info.activityInfo.name));
            }
        }
        if (matches.size() != 1) {
            run.fail(matches.isEmpty() ? run.l("没有找到这个应用。请说手机上显示的完整应用名称。", "I couldn't find that app. Say its full name as listed on your phone.") :
                    run.l("有多个同名应用。请从手机的应用列表亲自打开目标应用。", "Several apps share that name. Choose the intended app in your app list."));
            return null;
        }
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(matches.values().iterator().next());
    }
    public void openApp(String label, boolean zh, Flow callback) {
        Run run = begin(zh, callback);
        String name = clean(label);
        Intent intent = launcher(run, name);
        if (intent == null) return;
        String pkg = intent.getComponent().getPackageName();
        run.launch(name, pkg, intent, () -> run.end(run.l("已打开", "Opened ") + name, true));
    }

    public void findFeature(String app, String feature, boolean zh, Flow callback) {
        Run run = begin(zh, callback);
        String name = clean(app), target = clean(feature);
        Intent intent = launcher(run, name);
        if (intent == null) return;
        String pkg = intent.getComponent().getPackageName();
        run.launch(name, pkg, intent, () -> run.say(run.l("已打开应用。我来查找", "The app is open. Looking for ") + target, () -> {
            if (!run.allowed("find_label")) return;
            if (WECHAT.equals(pkg) && oneOf(target, CONTACTS)) {
                ensureContacts(run, () -> run.end(run.l("已进入微信通讯录。", "WeChat Contacts is open."), true));
                return;
            }
            if (target.isEmpty()) { run.fail(run.l("请说出功能名称。", "Please name the feature.")); return; }
            run.waitScreen(pkg, -1, t -> t.has(target), t ->
                run.end(run.l("在当前页面找到了“", "I found the label ") + target +
                    run.l("”。可以用屏幕阅读器定位并打开。", ". Use your screen reader to select and open it."), true),
                run.l("当前页面没有找到该标签。请换到该功能所在的页面，再说重试。", "I couldn't find that label. Navigate to the page containing the feature and retry."));
        }));
    }
    private static boolean oneOf(String name, String... labels) {
        for (String s : labels) if (name.equalsIgnoreCase(s)) return true;
        return false;
    }
    private static boolean contactsPage(PhoneTree t) {
        return t.has(CONTACTS) && (t.selected(CONTACTS) || t.has(NEW_FRIENDS));
    }
    private void ensureContacts(Run run, Runnable next) {
        run.waitScreen(WECHAT, -1, t -> t.has(CONTACTS), t -> {
            if (contactsPage(t)) { next.run(); return; }
            run.say(run.l("正在打开微信通讯录。", "Opening WeChat Contacts."), () ->
                run.act(WECHAT, "open_contacts", v -> v.has(CONTACTS), v -> v.click(CONTACTS), () ->
                    run.waitScreen(WECHAT, -1, PhoneService::contactsPage, v -> next.run(),
                        run.l("无法确认已进入通讯录。请在微信中打开通讯录后重试。", "I couldn't verify Contacts. Open Contacts in WeChat and retry."))));
        }, run.l("没有找到通讯录标签。请回到微信主页面后重试。", "I couldn't find the Contacts tab. Return to the main WeChat page and retry."));
    }

    public void weChat(String contact, String message, boolean voice, boolean preset, boolean zh, Flow callback) {
        Run run = begin(zh, callback);
        String name = clean(contact), draft = clean(message);
        if (name.isEmpty() || name.length() > 80 || (!voice && (draft.isEmpty() || draft.length() > 2000))) {
            run.fail(run.l("请给出完整的联系人姓名，以及要发送的文字或语音类型。", "Please give the full contact name and your message or say voice message."));
            return;
        }
        Intent intent = launcher(run, "WeChat");
        if (intent == null) return;
        run.launch(zh ? "微信" : "WeChat", WECHAT, intent, () -> run.say(
            run.l("微信已打开。正在通讯录中查找", "WeChat is open. Looking in Contacts for ") + name, () -> {
                if (!run.allowed("find_label")) return;
                // Retry from an existing conversation or profile only when its title matches.
                run.waitScreen(WECHAT, -1, t -> true, t -> {
                    if (chat(t, name)) { prepare(run, name, draft, voice, preset); return; }
                    if (profile(t, name)) { enterChat(run, name, draft, voice, preset); return; }
                    ensureContacts(run, () -> chooseContact(run, name, draft, voice, preset));
                }, run.l("请打开微信后重试。", "Open WeChat and retry."));
            }));
    }
    private void chooseContact(Run run, String name, String draft, boolean voice, boolean preset) {
        if (oneOf(name, CONTACTS) || oneOf(name, NEW_FRIENDS) ||
                oneOf(name, "标签", "Tags", "群聊", "Group Chats", "公众号", "Official Accounts", "微信", "WeChat",
                    "发现", "Discover", "我", "Me", "企业微信联系人", "添加朋友", "Add Contacts", "搜索", "Search")) {
            run.fail(run.l("这个姓名与微信功能重名。请亲自打开该联系人的聊天后重试。", "This name matches a WeChat control. Open the intended conversation yourself and retry."));
            return;
        }
        run.say(run.l("已进入通讯录。正在查找", "Contacts is open. Looking for ") + name,
            () -> locateContact(run, name, draft, voice, preset, 0));
    }
    private void locateContact(Run run, String name, String draft, boolean voice, boolean preset, int pages) {
        run.waitScreen(WECHAT, -1, PhoneService::contactsPage, t -> {
            if (t.has(name)) {
                run.say(run.l("找到匹配姓名，正在打开", "Found a matching name. Opening ") + name, () ->
                    run.act(WECHAT, "open_chat", PhoneService::contactsPage, v -> v.click(name), () ->
                        run.waitScreen(WECHAT, -1, v -> profile(v, name) || chat(v, name), v -> {
                            if (chat(v, name)) prepare(run, name, draft, voice, preset);
                            else enterChat(run, name, draft, voice, preset);
                        }, run.l("无法核实联系人身份。请打开正确的聊天后重试。", "I couldn't verify the contact. Open the correct conversation and retry."))));
                return;
            }
            String missing = run.l("没有找到该联系人。请在微信通讯录搜索完整备注名，打开聊天后再试。",
                "I couldn't find that contact. Search the full name in WeChat Contacts, open the chat, then retry.");
            if (pages >= 6) { run.fail(missing); return; }
            // Only a one-way semantic list scroll, never arbitrary gestures or search submission.
            int previous = t.visibleFingerprint();
            run.say(run.l("这页没有匹配姓名。正在查看下一页联系人。", "No match on this page. Checking the next page of contacts."), () ->
                run.act(WECHAT, "open_contacts", v -> contactsPage(v) && previous == v.visibleFingerprint(), PhoneTree::scrollList,
                    () -> run.waitScreen(WECHAT, -1, v -> contactsPage(v) && previous != v.visibleFingerprint(),
                        v -> locateContact(run, name, draft, voice, preset, pages + 1), missing)));
        }, run.l("通讯录页面已变化，请打开正确的聊天后重试。", "The Contacts page changed. Open the correct chat, then retry."));
    }
    private static boolean profile(PhoneTree t, String name) { return t.has(name) && t.has(MESSAGE) && !t.hasEditor() && !t.has(CONTACTS); }
    private static boolean chat(PhoneTree t, String name) {
        boolean composer = t.has(HOLD) || (t.hasEditor() && (t.has(VOICE) ||
                t.has("表情", "表情按钮", "更多功能按钮，已折叠", "更多功能按钮,已折叠", "Stickers", "Emoji", "Emoticons", "More functions, collapsed")));
        return t.contactHeading(name) && composer && !t.has(CONTACTS) && !t.has(MESSAGE);
    }
    private void enterChat(Run run, String name, String draft, boolean voice, boolean preset) {
        run.say(run.l("已找到联系人，正在打开与", "Found the contact. Opening the chat with ") + name +
            run.l("的聊天。", "."), () -> run.act(WECHAT, "open_chat", t -> profile(t, name), t -> {
                String id = getSharedPreferences("voice_trail", MODE_PRIVATE).getString("presetId", "");
                run.profileProof = preset && !voice && t.profileIdMatches(id) ? id : null;
                return t.click(MESSAGE);
            },
                () -> run.waitScreen(WECHAT, -1, t -> chat(t, name), t -> {
                    if (run.profileProof != null) run.provenChatWindow = t.windowId();
                    prepare(run, name, draft, voice, preset);
                },
                    run.l("无法确认聊天对象。请打开正确的聊天后重试。", "I couldn't verify the conversation. Open the correct chat and retry."))));
    }
    private void prepare(Run run, String name, String draft, boolean voice, boolean preset) {
        run.say(run.l("已核对聊天标题，当前联系人是", "I verified the conversation title. The contact is ") + name +
            (voice ? run.l("。正在查找录音控件。", ". Looking for the recording control.") : run.l("。正在检查输入框。", ". Checking the message field.")), () -> {
            run.waitScreen(WECHAT, -1, t -> chat(t, name), t -> {
                if (voice) {
                    if (t.has(HOLD)) { voiceHandoff(run, preset); return; }
                    run.act(WECHAT, "prepare_voice", v -> chat(v, name), v -> v.click(VOICE),
                        () -> run.waitScreen(WECHAT, -1, v -> chat(v, name) && v.has(HOLD),
                            v -> voiceHandoff(run, preset), run.l("没有确认录音控件。请在微信中切换到语音后重试。", "I couldn't verify the recording control. Switch to voice in WeChat and retry.")));
                } else if (t.hasEditor()) {
                    writeDraft(run, name, draft, preset);
                } else {
                    run.act(WECHAT, "prepare_text", v -> chat(v, name), v -> v.click(KEYBOARD),
                        () -> run.waitScreen(WECHAT, -1, v -> chat(v, name) && v.hasEditor(),
                            v -> writeDraft(run, name, draft, preset), run.l("没有确认输入框。请打开微信键盘后重试。", "I couldn't verify the message box. Open the keyboard in WeChat and retry.")));
                }
            }, run.l("聊天对象已经变化。请返回正确的聊天后重试。", "The conversation changed. Return to the correct chat and retry."));
        });
    }
    private void writeDraft(Run run, String name, String draft, boolean preset) {
        run.say(run.l("正在准备文字草稿。", "Preparing the text draft."), () -> run.act(WECHAT, "prepare_text",
            t -> chat(t, name), t -> t.draftMatches(draft) || t.writeEmptyDraft(draft),
            () -> run.waitScreen(WECHAT, -1, t -> chat(t, name) && t.draftMatches(draft), t -> {
                if (preset) verifyAndSend(run, name, draft);
                else run.handoff(run.l("此人不是预设联系人。已核对草稿，尚未发送。请听清收件人，用微信的发送按钮亲自确认。",
                    "This is not a preset contact. The draft is ready and unsent. Check the recipient, then activate Send yourself in WeChat."));
            },
                run.l("无法核实草稿。请亲自检查微信输入框。", "I couldn't verify the draft. Please check the message box in WeChat."))));
    }
    private void verifyAndSend(Run run, String name, String draft) {
        run.say(run.l("已核对草稿。正在检查聊天对象和发送按钮。", "The draft matches. Checking the recipient and Send button."), () -> {
            String manual = run.l("我没有点发送。请用微信核实收件人和草稿，亲自决定是否发送。",
                    "I haven't pressed Send. Check the recipient and draft in WeChat and decide whether to send it yourself.");
            if (!run.allowed("read_nodes")) return;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) { run.handoff(manual); return; }
            int previous;
            try (PhoneTree t = new PhoneTree(root)) {
                android.content.SharedPreferences trusted = getSharedPreferences("voice_trail", MODE_PRIVATE);
                if (!WECHAT.equals(t.pkg) || !name.equals(trusted.getString("preset", "")) || run.profileProof == null ||
                        !run.profileProof.equals(trusted.getString("presetId", "")) || run.provenChatWindow != t.windowId() ||
                        !chat(t, name) || !t.draftMatches(draft)) { run.handoff(manual); return; }
                PhoneTree.Item send = t.sendButton();
                previous = t.conversationCopies(draft);
                if (send == null || previous < 0 || run.sendAttempted) { run.handoff(manual); return; }
                if (!run.allowed("send_preset_text")) return;
                // Consume once, before making the Android call. A timeout is never retried.
                run.sendAttempted = true;
                try { send.node.performAction(AccessibilityNodeInfo.ACTION_CLICK); }
                catch (RuntimeException e) { run.fail(""); return; }
            }
            run.waitScreen(WECHAT, -1, t -> chat(t, name) && t.editorCleared() && t.conversationCopies(draft) > previous,
                    t -> run.end(run.l("微信聊天里出现了这条新消息，输入框也已清空。", "The new message appeared in the WeChat conversation and the input field cleared."), true), "");
        });
    }
    private void voiceHandoff(Run run, boolean preset) {
        run.handoff((preset ? "" : run.l("此人不是预设联系人。", "This is not a preset contact. ")) +
            run.l("已找到按住说话。请亲自录音，松开会发送。使用 TalkBack 时，聚焦按住说话，双击并按住，听到录音提示后说话。", "Hold to Talk is ready. Record yourself. Releasing may send the recording. With TalkBack, focus Hold to Talk, double-tap and hold, then speak after the recording cue."));
    }
}
