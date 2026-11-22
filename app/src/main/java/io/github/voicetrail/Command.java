package io.github.voicetrail;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses one explicitly spoken request. Only the fixed kinds below can reach the executor. */
public final class Command {
    public final String kind;
    public final String target;
    public final String detail;
    public final int hour;
    public final int minute;

    private Command(String kind, String target, String detail, int hour, int minute) {
        this.kind = kind;
        this.target = target;
        this.detail = detail;
        this.hour = hour;
        this.minute = minute;
    }

    private static Command simple(String kind) {
        return new Command(kind, "", "", -1, -1);
    }

    private static Pattern pattern(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static final Pattern UNSAFE = pattern(
            "支付|付款|转[账帐]|汇款|红包|购买|下单|密码|口令|卸载|删除|清空|恢复出厂|系统设置|"
            + "(?:填入|输入|填|提交|发送|转发|复制|粘贴).{0,16}(?:验证码|校验码|动态码)|"
            + "(?:验证码|校验码|动态码).{0,16}(?:填|输入|提交|发送|转发|复制|粘贴)|"
            + "\\b(?:pay(?:ment)?|purchase|buy|checkout|transfer|wire|password|passcode|pin code|"
            + "uninstall|delete|erase|factory reset|system settings)\\b|"
            + "\\b(?:enter|type|fill|submit|send|forward|copy|paste)\\b.{0,40}\\b"
            + "(?:otp|one[- ]time (?:code|password)|verification code|security code)\\b");
    private static final Pattern MULTI = pattern(
            "[;；\\n\\r]|然后|接着|再帮我|并且|\\band then\\b|\\bthen\\b|"
            + "\\b(?:and|or)\\s+(?:open|send|click|tap|confirm|pay|read|delete)\\b");
    private static final Pattern FIND_ZH = pattern("^(?:打开|启动)(.{1,48}?)[,、 ]*(?:找到|查找|寻找)(.{1,48})$");
    private static final Pattern FIND_EN = pattern("^(?:open|launch) (.{1,48}?)\\s*,?\\s+(?:and )?(?:find|locate) (.{1,48})$");
    private static final Pattern OPEN = pattern("^(?:(?:open|launch|start) +|打开(?:一下)? *|启动 *)(.{1,48})$");
    private static final Pattern VOICE_ZH = pattern("^(?:打开微信[, ]*)?(?:用微信|在微信里?)?(?:给|向)(.+?)(?:发|发送)(?:一条|个)?(?:微信)?语音(?:消息|信息)?$");
    private static final Pattern VOICE_EN = pattern("^(?:send|record) (?:a |an )?wechat voice (?:message|note) to (.+)$");
    private static final Pattern VOICE_EN_SUFFIX = pattern("^(?:send|record) (?:a |an )?voice (?:message|note) to (.+?) (?:on|via|in) wechat$");
    private static final Pattern TEXT_ZH = pattern("^(?:打开微信[,， ]*)?(?:用微信|在微信里?)?(?:给|向)(.+?)(?:发送|发)(?:一条|个)?(?:微信(?:文字|文本|消息|信息)?|文字(?:消息|信息)?|文本(?:消息)?)(?:[,， :：]*(?:(?:内容是|内容为|说)[:： ]*)?(.*))?$");
    private static final String BODY_SEPARATOR = "(?:\\s*[:：,，]\\s*| +(?:saying|that says) +)";
    private static final Pattern TEXT_EN = pattern("^send (?:a |an )?wechat (?:text(?: message)?|message) to (.+?)(?:" + BODY_SEPARATOR + "(.+))?$");
    private static final Pattern TEXT_EN_SUFFIX = pattern("^(?:send (?:a |an )?(?:text(?: message)?|message) to|text|message) (.+?) (?:on|via|in) wechat(?:" + BODY_SEPARATOR + "(.+))?$");
    private static final Pattern LABEL_CHARS = pattern("^[\\p{L}\\p{M}\\p{N} ._'’&()\\-·]{1,48}$");
    private static final Pattern AMBIGUOUS = pattern("[或和跟与,、/]|\\b(?:or|and|someone|somebody|them|him|her|anyone|whoever)\\b|^(?:他|她|他们|她们|某人|谁|联系人)$");
    private static final Pattern SECRET = pattern("验证码|校验码|动态码|\\b(?:otp|verification code|one[- ]time (?:code|password))\\b");
    private static final Pattern ALARM_ZH = pattern("^(?:请)?(?:设置|设定|设|定)(?:置)?(?:一个|个)? *(.+?) *(?:的)?(?:闹钟|闹铃)$");
    private static final Pattern REMIND_ZH = pattern("^(?:在)?(.+?)提醒我(.{1,100})$");
    private static final Pattern ALARM_EN = pattern("^(?:set|create) (?:an? )?alarm (?:for|at) (.+)$");
    private static final Pattern REMIND_EN = pattern("^remind me (?:at (.+?) to (.+)|to (.+) at (.+))$");
    private static final Pattern NAMED_REMINDER_EN = pattern("^(?:set|create) (?:a )?reminder (?:for|at) (.+?) to (.+)$");
    private static final Pattern VISION = pattern(
            "^(?:(?:看一看|看看|读一下|读一读)(?:这|这个|这张|这瓶|这盒|这个食品|那个)?.{0,12}"
            + "(?:药盒|说明书|食品标签|有效期|保质期|生产日期|门牌|账单|电梯按钮).{0,12}|"
            + "(?:read|describe|check) (?:this |the )?(?:medicine label|food label|expiry date|expiration date|"
            + "door number|bill|elevator buttons)(?: for me)?|"
            + "(?:has this (?:medicine|food) expired|is this (?:medicine|food) expired))$");
    private static final Pattern CLOCK = pattern("^(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?$");
    private static final Pattern CLOCK_ZH = pattern("^(凌晨|早上|早晨|上午|中午|下午|晚上)?([零〇一二两三四五六七八九十0-9]{1,3})[点时](?:钟)?(半|一刻|三刻|[零〇一二两三四五六七八九十0-9]{1,3}分?)?$");
    private static final String[] HOURS = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve"};

    public static Command parse(String spoken) {
        if (spoken == null || spoken.length() > 500 || spoken.matches("(?s).*[\\p{Cc}\\p{Cf}].*")) {
            return simple("unknown");
        }
        String original = spoken.trim()
                .replaceFirst("(?i)^(?:(?:could|can) you (?:please )?|please |请帮我|麻烦帮我|帮我|请)", "").trim();
        String s = Normalizer.normalize(original, Normalizer.Form.NFKC)
                .replaceAll(" +", " ").replaceFirst("[。.!！?？]+$", "").trim();
        String lower = s.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "read": case "read screen": case "read the screen": case "read this screen":
            case "read current screen": case "read the current screen": case "what's on my screen":
            case "读屏": case "读一下当前屏幕": case "读一下屏幕": case "读当前屏幕":
            case "朗读当前屏幕": case "朗读屏幕": case "屏幕上有什么": return simple("read_screen");
            case "read code": case "read the code": case "read the verification code": case "read verification code":
            case "read the otp": case "read otp": case "read the code on the screen":
            case "读验证码": case "读一下验证码": case "朗读验证码": case "念验证码":
            case "把验证码念给我听": case "把屏幕上的验证码念给我听": return simple("read_code");
            case "stop": case "cancel": case "never mind": case "nevermind": case "stop now":
            case "停止": case "停": case "取消": case "算了": case "别发": case "不要发送": return simple("cancel");
            case "repeat": case "say that again": case "say it again": case "repeat that":
            case "重复": case "重复一遍": case "再说一遍": case "再读一遍": return simple("repeat");
            case "faster": case "speak faster": case "talk faster": case "说快一点": case "说快点": case "快一点": return simple("faster");
            case "slower": case "speak slower": case "slow down": case "说慢一点": case "说慢点": case "慢一点": return simple("slower");
            case "confirm": case "confirm send": case "confirm sending": case "i confirm":
            case "确认": case "确认发送": case "确认继续": return simple("approve");
            case "help": case "what can you do": case "帮助": case "怎么用": case "你能做什么": return simple("help");
            case "look": case "take a look": case "look around": case "describe this": case "read this label":
            case "看一看": case "看一下": case "拍照看看": case "读取标签": case "看看这瓶药过期没有": return simple("vision");
            default: break;
        }
        if (UNSAFE.matcher(s).find()) return simple("manual_handoff");
        if (MULTI.matcher(s).find() || SECRET.matcher(s).find()) return simple("unknown");
        if (VISION.matcher(s).matches()) return simple("vision");

        Matcher match = VOICE_ZH.matcher(s);
        if (match.matches()) return contact("wechat_voice", match.group(1), "");
        for (Pattern syntax : new Pattern[]{VOICE_EN, VOICE_EN_SUFFIX}) {
            match = syntax.matcher(s);
            if (match.matches()) return contact("wechat_voice", match.group(1), "");
        }
        // Preserve dictated message content, including full-width characters and question marks.
        match = TEXT_ZH.matcher(original);
        if (match.matches()) {
            String body = match.group(2);
            if (body != null && body.matches("[。.!！?？]*")) body = "";
            return contact("wechat_text", match.group(1), body);
        }
        for (Pattern syntax : new Pattern[]{TEXT_EN, TEXT_EN_SUFFIX}) {
            match = syntax.matcher(original);
            if (match.matches()) return contact("wechat_text", match.group(1), match.group(2));
            match = syntax.matcher(s);
            if (match.matches() && match.group(2) == null) return contact("wechat_text", match.group(1), "");
        }

        for (Pattern syntax : new Pattern[]{FIND_ZH, FIND_EN}) {
            match = syntax.matcher(s);
            if (match.matches()) {
                String app = match.group(1).trim();
                String feature = match.group(2).trim();
                return label(app) && label(feature) ? new Command("find_feature", app, feature, -1, -1) : simple("unknown");
            }
        }
        match = OPEN.matcher(s);
        if (match.matches() && label(match.group(1))) return new Command("open_app", match.group(1).trim(), "", -1, -1);

        for (Pattern syntax : new Pattern[]{ALARM_ZH, ALARM_EN}) {
            match = syntax.matcher(s);
            if (match.matches()) return clock("alarm", match.group(1), "");
        }
        match = REMIND_ZH.matcher(s);
        if (match.matches()) return clock("reminder", match.group(1), match.group(2));
        match = REMIND_EN.matcher(s);
        if (match.matches()) return match.group(1) != null
                ? clock("reminder", match.group(1), match.group(2)) : clock("reminder", match.group(4), match.group(3));
        match = NAMED_REMINDER_EN.matcher(s);
        if (match.matches()) return clock("reminder", match.group(1), match.group(2));
        return simple("unknown");
    }

    private static boolean label(String s) {
        return LABEL_CHARS.matcher(s.trim()).matches();
    }

    private static Command contact(String kind, String name, String message) {
        String recipient = name.trim();
        if (!label(recipient) || AMBIGUOUS.matcher(recipient).find()) return simple("unknown");
        return new Command(kind, recipient, message == null ? "" : message.trim(), -1, -1);
    }

    private static Command clock(String kind, String when, String label) {
        String time = when.trim().toLowerCase(Locale.ROOT).replace("a.m", "am").replace("p.m", "pm").replaceAll("(?<=m)\\.$", "");
        for (int i = 1; i < HOURS.length; i++) {
            if (time.startsWith(HOURS[i] + " ")) {
                time = i + time.substring(HOURS[i].length());
                break;
            }
        }
        int h, m;
        Matcher match = CLOCK.matcher(time);
        if (match.matches()) {
            h = Integer.parseInt(match.group(1));
            m = match.group(2) == null ? 0 : Integer.parseInt(match.group(2));
            String period = match.group(3);
            if (period != null) {
                if (h < 1 || h > 12) return simple("unknown");
                h = h % 12 + (period.equals("pm") ? 12 : 0);
            } else if (match.group(2) == null || (h >= 1 && h <= 12 && match.group(1).length() < 2)) {
                return simple("unknown");
            }
        } else {
            match = CLOCK_ZH.matcher(time);
            if (!match.matches()) return simple("unknown");
            h = chineseNumber(match.group(2));
            String minutePart = match.group(3);
            if (minutePart == null) m = 0;
            else if (minutePart.equals("半")) m = 30;
            else if (minutePart.equals("一刻")) m = 15;
            else if (minutePart.equals("三刻")) m = 45;
            else m = chineseNumber(minutePart.replace("分", ""));
            String period = match.group(1);
            if (period != null) {
                if (h < 1 || h > 12) return simple("unknown");
                if (period.equals("中午") && h != 12 && h != 1) return simple("unknown");
                if (period.equals("下午") || period.equals("晚上") || period.equals("中午")) {
                    if (period.equals("晚上") && h == 12) h = 0;
                    else h = h % 12 + 12;
                } else if (h == 12) {
                    if (!period.equals("凌晨")) return simple("unknown");
                    h = 0;
                }
            } else if (h >= 1 && h <= 12) return simple("unknown");
        }
        if (h < 0 || h > 23 || m < 0 || m > 59 || label.length() > 100) return simple("unknown");
        return new Command(kind, "", label.trim(), h, m);
    }

    private static int chineseNumber(String text) {
        if (text.matches("[0-9]{1,2}")) return Integer.parseInt(text);
        String n = text.replace('两', '二').replace('〇', '零');
        String digits = "零一二三四五六七八九";
        if (n.length() == 1 && !n.equals("十")) return digits.indexOf(n);
        if (n.matches("零[一二三四五六七八九]")) return digits.indexOf(n.charAt(1));
        if (!n.matches("[一二三四五六七八九]?十[一二三四五六七八九]?")) return -1;
        int ten = n.indexOf('十');
        return (ten == 0 ? 1 : digits.indexOf(n.charAt(0))) * 10
                + (ten == n.length() - 1 ? 0 : digits.indexOf(n.charAt(ten + 1)));
    }
}
