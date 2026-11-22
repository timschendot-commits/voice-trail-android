package io.github.voicetrail;

import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One short-lived, local snapshot of the active window. Never cache or log these nodes. */
final class PhoneTree implements AutoCloseable {
    private static final int LIMIT = 1400;
    private static final String CODE_WORD = "(?:验证码|驗證碼|校验码|动态码|动态密码|verification code|security code|one[- ]time (?:password|code)|login code|passcode|\\bOTP\\b|\\bcode\\b)";
    private static final Pattern CODE_CONTEXT = Pattern.compile(CODE_WORD, Pattern.CASE_INSENSITIVE);
    private static final Pattern CODE_AFTER = Pattern.compile(CODE_WORD +
            "(?:\\s*(?:是|为|為|is))?[\\s:：为是為【(（\\-]*([0-9]{4,8})(?![0-9])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CODE_BEFORE = Pattern.compile("(?<![0-9])([0-9]{4,8})[)）】\\s]*(?:is|是|为|為)\\s*(?:your |您的|你的)?" +
            CODE_WORD, Pattern.CASE_INSENSITIVE);
    private static final Pattern OTP_LENGTH = Pattern.compile("(?<![0-9])[0-9]{4,8}(?![0-9])");
    private static final Pattern LONG_NUMBER = Pattern.compile("(?<![0-9])(?:[0-9][ -]?){12,19}(?![0-9])");
    private static final Pattern PRIVATE_SCREEN = Pattern.compile(
            "密码|密碼|支付|付款|银行|銀行|转账|轉賬|银行卡|隐私|password|passphrase|credit card|payment|transfer|banking|\\bpin\\b",
            Pattern.CASE_INSENSITIVE);

    static final class Item {
        final AccessibilityNodeInfo node;
        final Item parent;
        Item(AccessibilityNodeInfo node, Item parent) { this.node = node; this.parent = parent; }

        boolean matches(String... names) {
            String text = string(node.getText());
            String description = string(node.getContentDescription());
            for (String name : names) {
                if (name.equalsIgnoreCase(text) || name.equalsIgnoreCase(description)) return true;
            }
            return false;
        }
        Item clickable() {
            for (Item at = this; at != null; at = at.parent) {
                if (at.node.isEnabled() && at.node.isClickable() && at.node.isVisibleToUser()) return at;
            }
            return null;
        }
    }

    final String pkg;
    final List<Item> items = new ArrayList<>();
    private final List<AccessibilityNodeInfo> owned = new ArrayList<>();
    private boolean truncated;
    private boolean privateScreen;

    PhoneTree(AccessibilityNodeInfo root) {
        pkg = string(root.getPackageName());
        collect(root, null, 0);
    }
    private void collect(AccessibilityNodeInfo node, Item parent, int level) {
        owned.add(node);
        if (level > 60 || owned.size() >= LIMIT) { truncated = true; return; }
        if (!pkg.equals(string(node.getPackageName()))) return;
        if (secret(node)) { privateScreen = true; return; } // Exclude the entire subtree.
        String visibleText = string(node.getText()) + " " + string(node.getContentDescription());
        if (node.isEditable() || CODE_CONTEXT.matcher(visibleText).find() || LONG_NUMBER.matcher(visibleText).find()
                || PRIVATE_SCREEN.matcher(visibleText).find()) privateScreen = true;
        Item item = new Item(node, parent);
        if (node.isVisibleToUser()) items.add(item);
        for (int i = 0; i < node.getChildCount(); i++) {
            if (owned.size() >= LIMIT) { truncated = true; break; }
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collect(child, item, level + 1);
        }
    }
    private static boolean secret(AccessibilityNodeInfo n) {
        if (n.isPassword() || (Build.VERSION.SDK_INT >= 34 && n.isAccessibilityDataSensitive())) return true;
        String id = string(n.getViewIdResourceName()).toLowerCase(Locale.ROOT);
        if (id.contains("password") || id.contains("pass_word") || id.contains("credit_card") ||
                id.contains("cardnumber") || id.contains("card_number") || id.contains("cvv")) return true;
        int type = n.getInputType(), variation = type & InputType.TYPE_MASK_VARIATION;
        return ((type & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
                (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                 variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                 variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
               ((type & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER &&
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    }
    static String string(CharSequence text) { return text == null ? "" : text.toString().trim(); }
    int windowId() { return owned.get(0).getWindowId(); }
    List<Rect> unlabeledBounds() {
        List<Rect> bounds = new ArrayList<>();
        if (Build.VERSION.SDK_INT < 34 || privateScreen || truncated) return bounds;
        for (Item item : items) {
            AccessibilityNodeInfo n = item.node;
            if (!n.isEnabled() || !n.isClickable() || n.getChildCount() != 0 || !string(n.getText()).isEmpty() ||
                    !string(n.getContentDescription()).isEmpty() || !string(n.getHintText()).isEmpty()) continue;
            AccessibilityNodeInfo labelled = n.getLabeledBy();
            if (labelled != null) { labelled.recycle(); continue; }
            if (item.parent != null && !string(item.parent.node.getContentDescription()).isEmpty()) continue;
            Rect rect = new Rect();
            n.getBoundsInWindow(rect);
            int w = rect.width(), h = rect.height();
            if (w < 12 || h < 12 || w > 360 || h > 360 || w > 3 * h || h > 3 * w) continue;
            bounds.add(rect);
            if (bounds.size() == 4) break;
        }
        return bounds;
    }
    private static final Pattern PROFILE_ID = Pattern.compile("^(?:微信号|微信號|WeChat ID)\\s*[:：]\\s*([a-zA-Z0-9_-]+)$",
            Pattern.CASE_INSENSITIVE);
    boolean profileIdMatches(String expected) {
        if (truncated || expected == null || expected.isEmpty()) return false;
        Set<String> values = new LinkedHashSet<>();
        for (Item item : items) {
            if (item.node.isEditable()) continue;
            for (CharSequence field : new CharSequence[]{item.node.getText(), item.node.getContentDescription()}) {
                Matcher m = PROFILE_ID.matcher(string(field));
                if (m.matches()) values.add(m.group(1));
            }
            if (!item.matches("微信号", "微信號", "WeChat ID", "微信号：", "微信號：", "WeChat ID:") ||
                    item.parent == null || item.parent.node.getChildCount() != 2) continue;
            // Exactly two visible sibling leaves in the same labelled row. Never scan nearby rows for an ID.
            if (item.node.getChildCount() != 0) continue;
            for (Item child : items) {
                if (child == item || child.parent != item.parent || child.node.isEditable() || child.node.getChildCount() != 0) continue;
                String text = string(child.node.getText());
                if (text.isEmpty()) text = string(child.node.getContentDescription());
                if (text.matches("[a-zA-Z0-9_-]+")) values.add(text);
            }
        }
        return values.size() == 1 && values.contains(expected);
    }
    boolean has(String... names) {
        for (Item item : items) if (item.matches(names)) return true;
        return false;
    }
    private Item uniqueTarget(String... labels) {
        if (truncated) return null;
        Item found = null;
        for (Item item : items) {
            if (!item.matches(labels)) continue;
            Item target = item.clickable();
            if (target == null) continue;
            if (found != null && found != target) return null;
            found = target;
        }
        return found;
    }
    boolean click(String... labels) {
        Item target = uniqueTarget(labels);
        return target != null && target.node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }
    boolean scrollList() {
        Item scroll = null;
        for (Item item : items) {
            if (!item.node.isScrollable() || !item.node.isEnabled() ||
                    !item.node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)) continue;
            if (scroll != null) return false;
            scroll = item;
        }
        return scroll != null && scroll.node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
    }
    int visibleFingerprint() {
        int signature = 1;
        for (Item item : items) {
            signature = signature * 31 + string(item.node.getText()).hashCode();
            signature = signature * 31 + string(item.node.getContentDescription()).hashCode();
        }
        return signature;
    }
    boolean selected(String... labels) {
        for (Item item : items) {
            if (!item.matches(labels)) continue;
            for (Item at = item; at != null; at = at.parent) {
                if (at.node.isSelected() || at.node.isChecked()) return true;
                if (at.node.isClickable()) break;
            }
        }
        return false;
    }
    boolean contactHeading(String name) {
        if (owned.isEmpty()) return false;
        Rect root = new Rect();
        owned.get(0).getBoundsInScreen(root);
        int candidates = 0;
        for (Item item : items) {
            if (!item.matches(name) || item.node.isEditable()) continue;
            Rect bounds = new Rect();
            item.node.getBoundsInScreen(bounds);
            if (bounds.isEmpty() || bounds.centerY() < root.top || bounds.centerY() > root.top + root.height() / 7) continue;
            String id = string(item.node.getViewIdResourceName()).toLowerCase(Locale.ROOT);
            boolean inToolbar = false;
            for (Item p = item.parent; p != null; p = p.parent) {
                if (string(p.node.getClassName()).endsWith("Toolbar")) { inToolbar = true; break; }
            }
            boolean centred = Math.abs(bounds.centerX() - root.centerX()) <= root.width() / 8;
            if (item.node.isHeading() || id.endsWith("title") || inToolbar || centred) candidates++;
        }
        return candidates == 1;
    }
    private Item editor() {
        if (truncated) return null;
        Item match = null;
        for (Item item : items) {
            if (!item.node.isEditable() || !item.node.isEnabled()) continue;
            if (match != null) return null;
            match = item;
        }
        return match;
    }
    boolean hasEditor() { return editor() != null; }
    boolean draftMatches(String text) {
        Item e = editor();
        return e != null && text.contentEquals(e.node.getText() == null ? "" : e.node.getText());
    }
    boolean writeEmptyDraft(String text) {
        Item e = editor();
        if (e == null || (!e.node.isShowingHintText() && !string(e.node.getText()).isEmpty())) return false;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return e.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }
    /** Restrict a Send action to one enabled labelled button on the same row as the composer. */
    Item sendButton() {
        Item send = uniqueTarget("发送", "發送", "傳送", "Send");
        Item edit = editor();
        if (send == null || edit == null) return null;
        String role = string(send.node.getClassName());
        String id = string(send.node.getViewIdResourceName()).toLowerCase(Locale.ROOT);
        if (!(role.endsWith("Button") || id.endsWith("/send") || id.endsWith("/send_button") || id.endsWith("/btn_send"))) return null;
        Rect button = new Rect(), field = new Rect();
        send.node.getBoundsInScreen(button);
        edit.node.getBoundsInScreen(field);
        if (button.isEmpty() || field.isEmpty() || button.bottom <= field.top || field.bottom <= button.top) return null;
        return send;
    }
    int conversationCopies(String text) {
        if (truncated || owned.isEmpty()) return -1;
        Rect window = new Rect(), field = new Rect();
        owned.get(0).getBoundsInScreen(window);
        Item edit = editor();
        if (edit == null) return -1;
        edit.node.getBoundsInScreen(field);
        int count = 0;
        for (Item item : items) {
            if (item.node.isEditable() || item.node.getChildCount() != 0 ||
                    !text.contentEquals(string(item.node.getText()))) continue;
            Rect bounds = new Rect();
            item.node.getBoundsInScreen(bounds);
            if (bounds.centerY() > window.top + window.height() / 7 && bounds.centerY() < field.top) count++;
        }
        return count;
    }
    boolean editorCleared() {
        Item e = editor();
        return e != null && (e.node.isShowingHintText() || string(e.node.getText()).isEmpty());
    }
    String read(boolean otp, boolean zh) {
        Set<String> labels = new LinkedHashSet<>();
        Set<String> codes = new LinkedHashSet<>();
        List<String> unnamed = new ArrayList<>();
        boolean codeScreen = false;
        for (Item item : items) {
            AccessibilityNodeInfo n = item.node;
            if (n.isEditable()) continue; // Includes text in private drafts / credential editors.
            for (CharSequence value : new CharSequence[]{n.getText(), n.getContentDescription()}) {
                String label = string(value);
                if (label.isEmpty()) continue;
                if (CODE_CONTEXT.matcher(label).find()) codeScreen = true;
                if (otp) {
                    for (Pattern p : new Pattern[]{CODE_AFTER, CODE_BEFORE}) {
                        Matcher m = p.matcher(label);
                        while (m.find()) codes.add(m.group(1));
                    }
                } else labels.add(label);
            }
            if (n.isClickable() && n.getChildCount() == 0 && string(n.getText()).isEmpty() &&
                    string(n.getContentDescription()).isEmpty() && unnamed.size() < 4 && !otp)
                unnamed.add(describeControl(item, unnamed.size() + 1, zh));
        }
        if (otp) {
            if (codes.size() != 1 || truncated) return zh ? "没有找到唯一且明确标注的验证码。请打开含验证码的那条消息后再试。" :
                    "I couldn't identify one clearly labelled code. Open the message containing the code and try again.";
            String code = codes.iterator().next();
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < code.length(); i++) digits.append(code.charAt(i)).append(' ');
            return (zh ? "验证码是 " : "The verification code is ") + digits.toString().trim();
        }
        StringBuilder spoken = new StringBuilder();
        int count = 0;
        for (String original : labels) {
            if (CODE_CONTEXT.matcher(original).find()) continue;
            String hidden = zh ? "（数字已隐藏）" : " (number hidden) ";
            String label = LONG_NUMBER.matcher(original).replaceAll(hidden);
            // A code may be exposed in a separate node from its label.
            if (codeScreen) label = label.replaceAll("[0-9]+", hidden);
            else if (label.matches("[0-9]{4,8}")) label = OTP_LENGTH.matcher(label).replaceAll(hidden);
            if (spoken.length() + label.length() > 650) {
                int space = 650 - spoken.length();
                if (space > 12) spoken.append(label, 0, Math.min(space, label.length()));
                spoken.append(zh ? "。还有更多内容。" : ". More content follows.");
                break;
            }
            spoken.append(label).append(zh ? "。" : ". ");
            if (++count >= 24) { spoken.append(zh ? "还有更多内容。" : "More content may follow."); break; }
        }
        if (codeScreen) spoken.append(zh ? "验证码已隐藏。要听，请说读验证码。" : "Code hidden. To hear it, say read the code. ");
        for (String description : unnamed) spoken.append(description);
        if (spoken.length() == 0) return zh ? "此页面没有可朗读的标签。请换一个页面，再说读屏。" :
                "This screen has no readable labels. Open another screen and ask me to read it.";
        return spoken.toString().trim();
    }
    private String describeControl(Item item, int ordinal, boolean zh) {
        AccessibilityNodeInfo n = item.node;
        String label = string(n.getHintText());
        AccessibilityNodeInfo linked = n.getLabeledBy();
        if (linked != null) {
            try {
                if (!secret(linked) && !linked.isEditable() && pkg.equals(string(linked.getPackageName()))) {
                    String linkedText = string(linked.getText());
                    if (!linkedText.isEmpty()) label = linkedText;
                }
            } finally { linked.recycle(); }
        }
        if (label.isEmpty() && item.parent != null && item.parent.node.getChildCount() == 1) {
            label = string(item.parent.node.getContentDescription());
        }
        // Use concise non-numeric labels only. These hints never feed the action selectors.
        if (!label.isEmpty() && label.length() <= 40 && !Pattern.compile("[0-9]").matcher(label).find()
                && !CODE_CONTEXT.matcher(label).find()) {
            return zh ? "第" + ordinal + "个无标题控件，关联提示：" + label + "。" :
                    "Unlabelled control " + ordinal + ", associated hint: " + label + ". ";
        }
        String id = string(n.getViewIdResourceName()).toLowerCase(Locale.ROOT);
        String[] keys = {"back", "search", "close", "play", "pause", "menu", "settings"};
        String[] cn = {"返回", "搜索", "关闭", "播放", "暂停", "菜单", "设置"};
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            if (id.endsWith("/" + key) || id.endsWith("/btn_" + key) || id.endsWith("/button_" + key)) {
                return zh ? "第" + ordinal + "个无标题按钮，根据控件名称可能是" + cn[i] + "。" :
                        "Unlabelled button " + ordinal + ", possibly " + key + " based on its control name. ";
            }
        }
        Rect bounds = new Rect(), root = new Rect();
        n.getBoundsInScreen(bounds);
        owned.get(0).getBoundsInScreen(root);
        int row = root.height() == 0 ? 1 : Math.max(0, Math.min(2, (bounds.centerY() - root.top) * 3 / root.height()));
        int col = root.width() == 0 ? 1 : Math.max(0, Math.min(2, (bounds.centerX() - root.left) * 3 / root.width()));
        String[] rows = zh ? new String[]{"上", "中", "下"} : new String[]{"top", "middle", "bottom"};
        String[] cols = zh ? new String[]{"左", "中", "右"} : new String[]{"left", "centre", "right"};
        return zh ? "第" + ordinal + "个无标题按钮在" + rows[row] + "部" + cols[col] + "侧，用途不明。" :
                "Unlabelled button " + ordinal + " at " + rows[row] + " " + cols[col] + ". Its purpose is unknown. ";
    }
    @Override public void close() {
        for (AccessibilityNodeInfo n : owned) n.recycle();
    }
}
