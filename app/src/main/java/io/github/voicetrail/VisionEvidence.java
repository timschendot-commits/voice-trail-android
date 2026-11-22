package io.github.voicetrail;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Answers about a single OCR result. Line numbers always refer to the same photo. */
final class VisionEvidence {
    private static final Pattern EXPIRY = Pattern.compile(
            "有效期至|有效日期|失效日期|到期日|保质期[至到]|保質期[至到]|有效期限|最佳食用期至|"
                    + "\\b(?:EXP(?:IRY|IRATION)?(?:\\s+DATE)?|USE\\s+BY|BEST\\s+BEFORE|EXPIRES)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PRODUCTION = Pattern.compile(
            "生产日期|生產日期|制造日期|製造日期|包装日期|\\b(?:MFG|MFD|DOM|MANUFACTURED|PACKED)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE = Pattern.compile(
            "(?<![0-9])((?:19|20)[0-9]{2})[.年/\\-]([0-9]{1,2})[.月/\\-]([0-9]{1,2})日?(?![0-9])");
    private static final Pattern TOTAL = Pattern.compile(
            "总计|總計|合计|合計|应付|應付|实付|實付|\\b(?:total|balance|amount due)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PRIVATE = Pattern.compile(
            "验证码|驗證碼|动态口令|動態口令|密码|密碼|"
                    + "\\b(?:OTP|passcode|password|verification code|security code|one.time code)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LINE = Pattern.compile(
            "(?:line\\s*|第\\s*)([0-9]{1,3}|[一二三四五六七八九十]{1,3})\\s*(?:行)?",
            Pattern.CASE_INSENSITIVE);
    private static final int PAGE_SIZE = 3;

    private final boolean zh;
    private final List<String> lines = new ArrayList<>();
    private boolean privateDocument;
    private int page;

    VisionEvidence(boolean chinese) { zh = chinese; }

    void add(String raw) {
        if (raw == null) return;
        String value = raw.trim();
        if (value.isEmpty()) return;
        // A code or password may be on a different line from its label.
        if (PRIVATE.matcher(value).find()) privateDocument = true;
        lines.add(value);
    }

    boolean hidden() { return privateDocument; }

    String summary() {
        if (privateDocument) return privateNotice();
        if (lines.isEmpty()) return tr("没有读清文字。请靠近、保持平稳再拍，或问我画面里有什么。",
                "I couldn't read any text. Move closer, hold still and retake, or ask what's in the picture.");
        return tr("读到 " + lines.size() + " 行。", "I found " + lines.size() + " lines. ")
                + numbered(0) + tr("。可问有效期、总额或下一行。", ". Ask for an expiry date, a total or more lines.");
    }

    String answer(String question, LocalDate today) {
        if (privateDocument) return privateNotice();
        String q = question.toLowerCase(Locale.ROOT).trim();
        if (contains(q, "过期", "有效期", "到期", "保质", "expire", "expiry", "expiration", "best before", "use by")) {
            return expiry(today);
        }
        if (contains(q, "生产", "制造", "包装日期", "manufactur", "produced", "production", "packed")) {
            return matches(PRODUCTION, tr("生产日期", "a production date"));
        }
        if (contains(q, "账单", "总额", "合计", "应付", "金额", "total", "bill", "amount", "balance")) {
            return matches(TOTAL, tr("金额合计", "a total"));
        }
        Matcher line = LINE.matcher(q);
        if (line.find()) {
            int index = lineNumber(line.group(1)) - 1;
            if (index >= 0 && index < lines.size()) return numbered(index);
            return tr("没有这一行。", "That line isn't in this photo.");
        }
        if (contains(q, "next", "继续", "下", "more", "read", "text", "读", "文字", "数字", "number", "door", "button", "门牌", "按钮")) {
            if (lines.isEmpty()) return summary();
            if (contains(q, "from the start", "重头", "从头", "从第一", "start over")) page = 0;
            if (page >= lines.size()) {
                page = 0;
                return tr("已经读完。再说“读文字”会从头开始。", "End of the text. Say read text to start again.");
            }
            int until = Math.min(page + PAGE_SIZE, lines.size());
            List<String> read = new ArrayList<>();
            while (page < until) read.add(numbered(page++));
            return String.join(zh ? "。 " : ". ", read);
        }
        return tr("你可以问“生产日期在哪一行”、说“读第 2 行”，或问“左边有什么”。",
                "Try: which line has the production date, read line 2, or what's on the left?");
    }

    private String matches(Pattern label, String name) {
        List<String> matches = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (label.matcher(lines.get(i)).find()) matches.add(numbered(i));
        }
        if (matches.isEmpty()) return tr("没读到标明" + name + "的一行。请靠近标签重拍。",
                "I couldn't find a line marked with " + name + ". Move closer to the label and retake.");
        return String.join(zh ? "。 " : ". ", matches.subList(0, Math.min(3, matches.size())));
    }

    private String expiry(LocalDate today) {
        int index = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (!EXPIRY.matcher(lines.get(i)).find()) continue;
            if (index >= 0) return unclearExpiry();
            index = i;
        }
        if (index < 0) return unclearExpiry();
        String value = lines.get(index);
        Matcher expiryLabel = EXPIRY.matcher(value);
        expiryLabel.find();
        // Require the explicit date immediately after its label. A lot or production date
        // elsewhere on the same line must never be taken as the expiry.
        if (PRODUCTION.matcher(value).find()) return numbered(index) + ". " + unclearExpiry();
        Matcher date = DATE.matcher(value);
        if (!date.find() || date.start() < expiryLabel.end()
                || !value.substring(expiryLabel.end(), date.start()).matches("[\\s.:：=\\-]*")) {
            return numbered(index) + ". " + unclearExpiry();
        }
        try {
            LocalDate printed = LocalDate.of(Integer.parseInt(date.group(1)),
                    Integer.parseInt(date.group(2)), Integer.parseInt(date.group(3)));
            if (date.find()) return numbered(index) + ". " + unclearExpiry();
            String relation = printed.isBefore(today) ? tr("早于今天", "before today") :
                    printed.isAfter(today) ? tr("晚于今天", "after today") : tr("就是今天", "today");
            return tr("识读的标签日期是 " + printed + "，" + relation + "。手机日期 " + today + "。 ",
                    "The recognized label date is " + printed + ", " + relation + ". Device date " + today + ". ")
                    + numbered(index) + tr("。涉及用药时请核对包装或询问药师。",
                    ". For medication, verify the packaging or ask a pharmacist.");
        } catch (DateTimeException | NumberFormatException ignored) {
            return numbered(index) + ". " + unclearExpiry();
        }
    }

    private String unclearExpiry() {
        return tr("无法确定有效期。请把有效期标签和完整年月日一起拍清。",
                "I can't establish the expiry. Retake with the expiry label and the full date together.");
    }

    private String numbered(int i) {
        String text = lines.get(i);
        if (text.length() > 360) text = text.substring(0, 360) + "…";
        return tr("第 " + (i + 1) + " 行，", "Line " + (i + 1) + ", ") + text;
    }

    private String privateNotice() {
        return tr("照片里可能有验证码或密码，已隐藏这张照片的识读内容。可避开这些信息再拍。",
                "This photo may contain a code or password. Its reading is hidden. Retake without that information.");
    }

    private static int lineNumber(String value) {
        if (value.matches("[0-9]+")) return Integer.parseInt(value);
        String digits = "零一二三四五六七八九";
        int ten = value.indexOf('十');
        if (ten < 0) return value.length() == 1 ? digits.indexOf(value.charAt(0)) : -1;
        int tens = ten == 0 ? 1 : digits.indexOf(value.charAt(0));
        int units = ten == value.length() - 1 ? 0 : digits.indexOf(value.charAt(ten + 1));
        return tens * 10 + units;
    }

    static boolean contains(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private String tr(String chinese, String english) { return zh ? chinese : english; }
}
