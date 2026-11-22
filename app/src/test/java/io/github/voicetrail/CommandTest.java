package io.github.voicetrail;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class CommandTest {
    private static void expect(String kind, String... utterances) {
        for (String s : utterances) assertEquals(s, kind, Command.parse(s).kind);
    }

    @Test public void accessibleControlsAreStandAlone() {
        expect("read_screen", "读一下当前屏幕", "Please read the screen.");
        expect("read_code", "把屏幕上的验证码念给我听", "Read the verification code");
        expect("vision", "看一看", "Take a look");
        expect("approve", "确认发送", "Confirm send");
        expect("cancel", "停止", "Cancel");
        expect("slower", "说慢一点", "Slow down");
        expect("faster", "说快一点", "Speak faster");
        expect("repeat", "再说一遍", "Say that again");
        expect("help", "帮助", "What can you do?");
        expect("unknown", "do not confirm", "don't send", "他让我说确认发送", "yes", "嗯");
    }

    @Test public void namedAppAndFeatureUseSeparateSlots() {
        Command open = Command.parse("请帮我打开微信");
        assertEquals("open_app", open.kind);
        assertEquals("微信", open.target);
        for (String s : new String[]{"Open WeChat and find Favorites", "打开WeChat，找到Favorites"}) {
            Command find = Command.parse(s);
            assertEquals("find_feature", find.kind);
            assertEquals("WeChat", find.target);
            assertEquals("Favorites", find.detail);
        }
    }

    @Test public void voiceAndTextRetainRecipientAndUnmodifiedBody() {
        Command voice = Command.parse("打开微信给女儿发语音");
        assertEquals("wechat_voice", voice.kind);
        assertEquals("女儿", voice.target);
        Command english = Command.parse("Send a voice message to Ada on WeChat");
        assertEquals("wechat_voice", english.kind);
        assertEquals("Ada", english.target);
        Command text = Command.parse("给女儿发微信文字，内容是你到①号门了吗？");
        assertEquals("wechat_text", text.kind);
        assertEquals("女儿", text.target);
        assertEquals("你到①号门了吗？", text.detail);
        for (String s : new String[]{"Send a WeChat message to Joy saying Are you OK?",
                                     "Text Joy on WeChat: Are you OK?"}) {
            Command draft = Command.parse(s);
            assertEquals("wechat_text", draft.kind);
            assertEquals("Joy", draft.target);
            assertEquals("Are you OK?", draft.detail);
        }
        assertEquals("", Command.parse("给女儿发微信").detail);
        expect("unknown", "给她发微信", "给妈妈或爸爸发微信", "Send a WeChat voice note to him",
                "text Ada saying WeChat", "send a voice note to Ada");
    }

    @Test public void explicitBilingualClockTimesAreParsed() {
        for (String s : new String[]{"设置一个下午三点半的闹钟", "Set an alarm for 3:30 pm", "Set an alarm for 15:30"}) {
            Command alarm = Command.parse(s);
            assertEquals("alarm", alarm.kind);
            assertEquals(15, alarm.hour);
            assertEquals(30, alarm.minute);
        }
        assertEquals(0, Command.parse("Set an alarm for 12 AM").hour);
        assertEquals(12, Command.parse("Set an alarm for 12 PM").hour);
        assertEquals(7, Command.parse("Set an alarm for seven am").hour);
        assertEquals(5, Command.parse("设置一个早上七点零五分的闹钟").minute);
        for (String s : new String[]{"Remind me at 9:05 pm to call Ada", "Remind me to call Ada at 9:05 pm",
                                     "晚上九点零五分提醒我call Ada"}) {
            Command reminder = Command.parse(s);
            assertEquals("reminder", reminder.kind);
            assertEquals(21, reminder.hour);
            assertEquals(5, reminder.minute);
            assertEquals("call Ada", reminder.detail);
        }
    }

    @Test public void invalidAmbiguousOrUnsupportedTimesNeedClarification() {
        expect("unknown", "set an alarm for 7", "set an alarm for 7:10", "定个七点的闹钟",
                "set an alarm for 24:10", "set an alarm for 11:99 pm", "设置个下午17点的闹钟",
                "设置一个中午三点的闹钟", "set an alarm for tomorrow at 2 pm", "明天早上七点提醒我上学");
    }

    @Test public void sensitiveTextCannotBecomeNavigationOrSend() {
        expect("manual_handoff", "Open WeChat and find payment", "输入验证码123456", "帮我删除数据",
                "给女儿发微信转账", "Fill the verification code", "Pay Alice", "Open the password manager");
        for (String s : new String[]{"输入验证码123456", "text Ada on WeChat: my password is 123456"}) {
            assertEquals("", Command.parse(s).target);
            assertEquals("", Command.parse(s).detail);
        }
        expect("unknown", "点击确认", "click the button labelled Send", "Open WeChat then click Send",
                "Open WeChat; tap send", "读屏\n确认", "open We\u200BChat", null, "");
    }
}
