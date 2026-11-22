package io.github.voicetrail;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.*;
import java.util.Locale;
import java.util.function.Consumer;

public final class MainActivity extends Activity {
    private static final int MIC = 1, NOTIFICATIONS = 2, LOOK = 3;
    private static final int INK = Color.rgb(18,36,49), BLUE = Color.rgb(17,66,96);
    private final Handler main = new Handler(Looper.getMainLooper());
    SharedPreferences prefs;
    boolean zh;
    private VoiceIO voice;
    private TextView status;
    private String display = "", repeatable = "", session = "", resultId = "", resultSummary = "";
    private Command active, retry;
    private Runnable consent;
    private String consentTool = "", consentQuestion = "";
    private Consumer<String> microphoneRequest;
    private boolean listening, showingHome, waitingForVision;
    private long latestSpeech;
    private final Runnable consentTimeout = () -> {
        if (consent != null) cancelWith(t("确认已超时，本次任务已取消。","Confirmation expired. The task was cancelled."));
    };
    private final Runnable heartbeat = new Runnable() {
        public void run() {
            if (active == null || waitingForVision || consent != null) return;
            if (!listening && android.os.SystemClock.elapsedRealtime() - latestSpeech > 12500)
                say(t("还在处理。","Still working."), null);
            main.postDelayed(this, 15000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        prefs = getSharedPreferences("voice_trail",MODE_PRIVATE);
        zh = prefs.getBoolean("zh",Locale.getDefault().getLanguage().equals("zh"));
        initVoice();
        TaskAlerts.channels(this,zh);
        home();
        handleIntent(getIntent());
    }
    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); handleIntent(i); }
    private void handleIntent(Intent i) {
        String id = i.getStringExtra("task");
        if (id != null) {
            if ("cancel".equals(i.getAction()) && id.equals(session)) cancelWith(t("已取消。","Cancelled."));
            else if ("retry".equals(i.getAction()) && id.equals(resultId) && active == null && retry != null) start(retry);
            else if ("details".equals(i.getAction())) {
                home();
                say(id.equals(session) ? currentSummary() : id.equals(resultId) ? resultSummary :
                        t("这条任务记录已结束。","This task record has ended."),null);
            }
        } else if (i.getBooleanExtra("listen",false)) {
            i.removeExtra("listen");
            home(); beginListening();
        }
    }
    private void initVoice() {
        voice = new VoiceIO(this,zh);
        voice.setOnSpeechError(msg -> {
            clearActive("failed");
            listening = false;
            setDisplay(msg);
            if (status != null) status.announceForAccessibility(msg);
        });
        applyVoiceSettings();
        if (PhoneService.get()!=null) PhoneService.get().setLanguage(zh);
    }
    void applyVoiceSettings() {
        voice.setRate(prefs.getFloat("rate",0.88f)); voice.setVolume(prefs.getFloat("volume",1f));
    }
    void setPreset(String name) { prefs.edit().putString("preset",name).apply(); }
    void changeLanguage(boolean chinese) {
        clearActive("cancel"); voice.destroy();
        zh = chinese; prefs.edit().putBoolean("zh",zh).apply();
        repeatable = ""; display = ""; resultSummary = "";
        initVoice(); TaskAlerts.channels(this,zh); home();
        say(t("已切换为简体中文。","English selected."),null);
    }
    String t(String chinese,String english) { return zh ? chinese : english; }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    LinearLayout page(String title) {
        showingHome = false; status = null;
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true); scroll.setBackgroundColor(Color.WHITE);
        scroll.setOnApplyWindowInsetsListener((view,insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom);
            return insets;
        });
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20),dp(12),dp(20),dp(24)); scroll.addView(panel);
        setContentView(scroll);
        TextView heading = label(panel,title); heading.setTextSize(34); heading.setTypeface(null,Typeface.BOLD);
        heading.setAccessibilityHeading(true); scroll.requestApplyInsets();
        return panel;
    }
    TextView label(LinearLayout parent,String text) {
        TextView v = new TextView(this); v.setText(text); v.setTextSize(20); v.setTextColor(INK);
        v.setPadding(0,dp(8),0,dp(8)); parent.addView(v); return v;
    }
    Button button(LinearLayout parent,String title,Runnable action) {
        Button b = new Button(this); b.setAllCaps(false); b.setText(title); b.setContentDescription(title);
        b.setTextSize(large() ? 30 : 22); b.setMinHeight(dp(60)); b.setTextColor(Color.WHITE);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(BLUE));
        b.setOnClickListener(v -> action.run()); parent.addView(b); return b;
    }
    private boolean large() {
        String mode = prefs.getString("mode","auto");
        return "large".equals(mode) || ("auto".equals(mode) && !getSystemService(AccessibilityManager.class).isTouchExplorationEnabled());
    }
    void home() {
        LinearLayout p = page(t("声迹助手","Voice Trail")); showingHome = true;
        button(p,t("说句话","Speak"),this::beginListening);
        button(p,t("读当前屏幕","Read the screen"),() -> start(Command.parse("read the screen")));
        button(p,t("看一看","Look around"),() -> start(Command.parse("看一看")));
        button(p,t("停止","Stop"),() -> cancelWith(t("已停止。","Stopped.")));
        status = label(p,display.isEmpty() ? t("准备好了。","Ready.") : display);
        button(p,t("再说一遍","Repeat"),this::repeat);
        button(p,t("更多","More"),() -> { clearActive("cancel"); voice.stop(); SettingsPanel.show(this); });
        if (PhoneService.get()==null) button(p,t("启用读屏服务","Enable screen access"),this::openServiceSettings);
        button(p,t("键入指令","Type a command"),this::typeCommand);
    }
    private void typeCommand() {
        clearActive("cancel"); voice.stop();
        LinearLayout p = page(t("键入指令","Type a command"));
        EditText input = new EditText(this); input.setHint(t("例如：读一下屏幕","For example, read the screen"));
        input.setTextSize(24); input.setMinHeight(dp(56)); input.setSingleLine(); p.addView(input);
        button(p,t("执行","Run"),() -> { String s=input.getText().toString(); home(); interpret(s); });
        button(p,t("返回","Back"),this::home);
    }
    private void setDisplay(String msg) { display = msg; if (status!=null) status.setText(msg); }
    void say(String msg,Runnable after) {
        latestSpeech=android.os.SystemClock.elapsedRealtime();
        setDisplay(msg);
        voice.say(msg,after);
    }
    void dictate(Consumer<String> use) {
        microphoneRequest = use;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},MIC); return;
        }
        captureSpeech();
    }
    private void captureSpeech() {
        Consumer<String> target=microphoneRequest; microphoneRequest=null;
        if (target==null) return;
        listening=true; setDisplay(t("正在听。","Listening."));
        voice.listen(words -> { listening=false; target.accept(words); }, err -> {
            listening=false; say(err,null);
        });
    }
    private void beginListening() {
        if (consent==null && active!=null) clearActive("cancel");
        voice.stop();
        if (consent!=null) dictate(this::answerConsent); else dictate(this::interpret);
    }
    void askNotifications() {
        if (Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATIONS);
        else {
            Intent i=new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName());
            startActivity(i);
        }
    }
    void openServiceSettings() {
        String s=t("接下来是安卓无障碍设置。请找到声迹助手，自己选择是否开启。","Android accessibility settings will open. Find Voice Trail and choose whether to enable it.");
        say(s,() -> { try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
            catch(ActivityNotFoundException e) { say(t("请在系统设置搜索无障碍。","Search for accessibility in system settings."),null); } });
    }
    @Override public void onRequestPermissionsResult(int request,String[] names,int[] results) {
        super.onRequestPermissionsResult(request,names,results);
        boolean ok=results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED;
        if (request==MIC && ok) captureSpeech();
        else if (request==MIC) { microphoneRequest=null; say(t("麦克风权限未开启。可从更多进入系统设置，也可键入指令。","Microphone permission is off. Use app settings to enable it, or type a command."),null); }
        else if (request==NOTIFICATIONS) say(ok?t("通知已开启。","Notifications enabled."):t("通知未开启。","Notifications are off."),null);
    }
    private void interpret(String words) {
        Command c=Command.parse(words);
        switch(c.kind) {
            case "cancel": cancelWith(t("已取消。","Cancelled.")); break;
            case "repeat": repeat(); break;
            case "faster": case "slower":
                float rate=Math.max(.55f,Math.min(1.6f,prefs.getFloat("rate",.88f)+("faster".equals(c.kind)?.1f:-.1f)));
                prefs.edit().putFloat("rate",rate).apply(); applyVoiceSettings();
                say(t("现在的语速是","Speech rate is ")+Math.round(rate*100)+"%",null); break;
            case "help": help(); break;
            case "approve": say(t("现在没有等待确认的任务。","There is no pending confirmation."),null); break;
            case "unknown":
                if (CloudAssist.ready()) offerInterpretation(words);
                else clarify();
                break;
            default: start(c);
        }
    }
    private void help() {
        say(t("可以说：读一下当前屏幕、看看这瓶药的有效期、打开微信给女儿发语音，或设置一个下午三点半的闹钟。","Try: read the screen, look at the expiry date, send a voice message to Ada on WeChat, or set an alarm for 3:30 pm."),null);
    }
    private void clarify() {
        say(t("我还没听清要做什么。请说一件事，例如“打开微信”或“读屏幕”。","I didn't catch the task. Try one request, such as “open WeChat” or “read the screen”."),null);
    }
    private void repeat() {
        if (consent!=null) { promptConsent(); return; }
        say(repeatable.isEmpty()?t("还没有可重复的结果。","There is no result to repeat yet."):repeatable,null);
    }
    private boolean begin(String kind) {
        clearActive("cancel"); voice.stop(); resultId=""; retry=null;
        try { session=NativeCore.start(kind); }
        catch(LinkageError e) { session=""; }
        if (session==null || session.isEmpty()) {
            session=""; say(t("本机任务引擎未能启动。请安装包含本机架构的测试包。","The on-device engine could not start. Install a build for this CPU."),null); return false;
        }
        return true;
    }
    private void start(Command c) {
        if (!begin(c.kind)) return;
        active=c; final String id=session;
        String goal=summary(c); main.postDelayed(heartbeat,15000);
        TaskAlerts.status(this,zh,id,true,false,t("任务正在进行。","Task in progress."));
        say(goal,() -> { if(id.equals(session)) execute(c,id); });
    }
    private String summary(Command c) {
        switch(c.kind) {
            case "read_screen": return t("我来读原应用的屏幕。","I'll read the screen of the previous app.");
            case "read_code": return t("我会从当前屏幕找验证码，只朗读一次。请注意周围的人。","I'll read the on-screen code aloud once. Check who can hear it.");
            case "open_app": return t("我来打开","I'll open ")+c.target;
            case "find_feature": return t("我来打开","I'll open ")+c.target+t("，查找"," and find ")+c.detail;
            case "wechat_text": return t("我来为","I'll prepare a message for ")+c.target+t("准备微信文字。内容是：",". Message: ")+c.detail;
            case "wechat_voice": return t("我来打开与","I'll open the WeChat voice controls for ")+c.target+t("的微信录音界面。",".");
            case "alarm": case "reminder":
                return ("alarm".equals(c.kind)?t("我来打开闹钟，时间","I'll open the alarm for "):t("我来安排提醒，时间","I'll schedule a reminder for "))
                        +String.format(Locale.ROOT,"%02d:%02d",c.hour,c.minute)+(c.detail.isEmpty()?"":" "+c.detail);
            case "vision": return t("我来拍照，在本机识别。","I'll take a photo and examine it on this phone.");
            default: return t("这项操作需要你亲自完成。","This action requires your direct control.");
        }
    }
    private String currentSummary() { return active==null?t("准备好了。","Ready."):summary(active); }
    private void execute(Command c,String id) {
        if ("manual_handoff".equals(c.kind)) {
            finishTask("handoff",t("已暂停。请使用原应用和 TalkBack 亲自完成此操作。","Paused. Complete this action in the original app using TalkBack.")); return;
        }
        if ("vision".equals(c.kind)) {
            waitingForVision=true;
            startActivityForResult(new Intent(this,VisionActivity.class).putExtra("language",zh?"zh":"en")
                    .putExtra("session",id).putExtra("question",c.detail).putExtra("autoCapture",true)
                    .putExtra("rate",prefs.getFloat("rate",.88f)).putExtra("volume",prefs.getFloat("volume",1f)),LOOK); return;
        }
        if ("alarm".equals(c.kind) || "reminder".equals(c.kind)) { timeTask(c); return; }
        PhoneService phone=PhoneService.get();
        if(phone==null) { finishTask("failed",t("读屏服务未开启。请在首页选择“启用读屏服务”，再重试。","Screen access is off. Choose Enable screen access on Home, then retry.")); return; }
        phone.setLanguage(zh);
        PhoneService.Flow flow=new PhoneService.Flow() {
            public boolean allow(String tool) { return id.equals(session) && NativeCore.dispatch(id,tool)==1; }
            public void step(String msg,Runnable next) { if(id.equals(session)) say(msg,() -> { if(id.equals(session)) next.run(); }); }
            public void done(String msg,boolean ok) {
                if(id.equals(session)) finishTask(!ok?"failed":c.kind.startsWith("wechat_")?"handoff":"done",msg);
            }
        };
        switch(c.kind) {
            case "read_code": case "read_screen": phone.readScreenAfterReturn(this,"read_code".equals(c.kind),zh,flow); break;
            case "open_app": phone.openApp(c.target,zh,flow); break;
            case "find_feature": phone.findFeature(c.target,c.detail,zh,flow); break;
            case "wechat_text": case "wechat_voice":
                boolean preset=c.target.equals(prefs.getString("preset",""));
                Runnable prepare=() -> phone.weChat(c.target,c.detail,"wechat_voice".equals(c.kind),preset,zh,flow);
                if(preset) prepare.run();
                else requestConsent("review_recipient",t("对方不是常用联系人。听到的名字是","This is not your trusted contact. I heard ")+c.target+
                        t("。确认后只打开聊天，发送由你完成。",". Confirmation opens the chat. You control sending."),prepare);
                break;
            default: finishTask("failed",t("没有找到对应工具。","No matching tool is available."));
        }
    }
    private void timeTask(Command c) {
        boolean alarm="alarm".equals(c.kind);
        if(NativeCore.dispatch(session,alarm?"open_alarm":"open_reminder")!=1) { deny(); return; }
        if(alarm) {
            Intent i=new Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR,c.hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES,c.minute).putExtra(AlarmClock.EXTRA_SKIP_UI,false);
            if(!c.detail.isEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE,c.detail);
            try { startActivity(i); finishTask("handoff",t("时间已交给系统时钟，请听取时钟提示并核对闹钟。","The time was passed to your clock. Check the alarm in the clock app.")); }
            catch(ActivityNotFoundException|SecurityException e) { finishTask("failed",t("未找到能设置闹钟的时钟应用。","No clock app could accept this alarm.")); }
        } else {
            boolean ok=ReminderReceiver.schedule(this,c.hour,c.minute,c.detail,zh);
            finishTask(ok?"done":"failed",ok?t("本次本地提醒已安排。省电模式下可能延后。","One local reminder is scheduled. Battery saving may delay it."):
                    t("通知未开启。请到更多、权限与隐私开启通知后重试。","Notifications are off. Enable them in More, Permissions and privacy, then retry."));
        }
    }
    private void offerInterpretation(String words) {
        if (!begin("classify")) return;
        String id=session;
        requestConsent("share_words",t("可以请 ","May I ask ")+CloudAssist.host()+
                t(" 帮忙理解？仅发送匹配到的功能关键词。"," to interpret matched feature keywords?"),() -> {
            say(t("正在思考。","Thinking."),null);
            CloudAssist.classify(words,zh,k -> {
                if(!id.equals(session)) return;
                clearActive("done");
                switch(k) {
                    case "read_screen": start(Command.parse("read the screen")); break;
                    case "vision": start(Command.parse("看一看")); break;
                    case "help": help(); break;
                    case "repeat": repeat(); break;
                    default: clarify();
                }
            }, err -> { if(id.equals(session)) { clearActive("failed"); say(err,null); }});
        });
    }
    private void requestConsent(String tool,String question,Runnable next) {
        if(NativeCore.dispatch(session,tool)!=2) { deny(); return; }
        consentTool=tool; consent=next; consentQuestion=question;
        getSystemService(Vibrator.class).vibrate(VibrationEffect.createOneShot(180,VibrationEffect.DEFAULT_AMPLITUDE));
        main.removeCallbacks(consentTimeout); main.postDelayed(consentTimeout,60000);
        promptConsent();
    }
    private void promptConsent() {
        String id=session;
        say(consentQuestion+t("。请说“确认”或“取消”。",". Say “confirm” or “cancel”."),
                () -> { if(id.equals(session) && consent!=null) dictate(this::answerConsent); });
    }
    private void answerConsent(String answer) {
        if(consent==null) return;
        String kind=Command.parse(answer).kind;
        if(!"approve".equals(kind)) {
            cancelWith("cancel".equals(kind)?t("已取消。","Cancelled."):t("没有听到明确确认。本次已取消。","I didn't hear a clear confirmation. This task was cancelled.")); return;
        }
        if(!NativeCore.event(session,"approved") || NativeCore.dispatch(session,consentTool)!=1) { deny(); return; }
        Runnable next=consent; consent=null; consentQuestion=""; consentTool=""; main.removeCallbacks(consentTimeout);
        next.run();
    }
    private void deny() { finishTask("failed",t("这一步没有获得许可，任务已停止。","This step did not receive permission. Task stopped.")); }
    private void finishTask(String outcome,String msg) {
        Command c=active; String id=session;
        boolean code=c!=null && "read_code".equals(c.kind);
        boolean repeatSafe=c!=null && !code;
        clearActive(outcome);
        retry=c!=null && ("read_screen".equals(c.kind)||"open_app".equals(c.kind))?c:null;
        resultId=id;
        resultSummary="done".equals(outcome)?t("任务已完成。","Task complete."):"handoff".equals(outcome)?
                t("等待在原应用中处理。","Continue in the original app."):t("任务未完成。","Task did not finish.");
        TaskAlerts.status(this,zh,id,false,retry!=null,resultSummary);
        if(repeatSafe) repeatable=msg; else repeatable="";
        if(code) { setDisplay(resultSummary); voice.say(msg,null); } else say(msg,null);
    }
    private void clearActive(String outcome) {
        main.removeCallbacks(consentTimeout); main.removeCallbacks(heartbeat);
        microphoneRequest=null; listening=false;
        consent=null; consentTool=""; consentQuestion="";
        if(!session.isEmpty()) {
            NativeCore.event(session,outcome); NativeCore.close(session); session="";
            if(PhoneService.get()!=null) PhoneService.get().cancel();
            CloudAssist.cancel();
        }
        active=null; waitingForVision=false;
    }
    private void cancelWith(String msg) {
        clearActive("cancel"); voice.stop(); TaskAlerts.clear(this); say(msg,null);
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==LOOK && waitingForVision && active!=null && "vision".equals(active.kind)) {
            finishTask(result==RESULT_OK?"done":"cancel",result==RESULT_OK?
                t("已结束看一看。","Photo session finished."):t("已退出相机。","Camera closed."));
        }
    }
    @Override public void onBackPressed() {
        if(!showingHome) { voice.stop(); microphoneRequest=null; home(); }
        else { clearActive("cancel"); voice.stop(); super.onBackPressed(); }
    }
    @Override protected void onDestroy() {
        clearActive("cancel"); main.removeCallbacksAndMessages(null); voice.destroy(); super.onDestroy();
    }
}

