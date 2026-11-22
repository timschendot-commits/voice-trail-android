package io.github.voicetrail;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.ImageLabel;
import com.google.mlkit.vision.label.ImageLabeler;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Camera photos, OCR and scene labels stay in RAM for the lifetime of this activity. */
public final class VisionActivity extends ComponentActivity {
    private static final int CAMERA_REQUEST = 70;
    private static final int MICROPHONE_REQUEST = 71;
    private static final int APPROVAL_MIC_REQUEST = 72;
    private static final int PICK_IMAGE = 73;
    private final ExecutorService images = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean zh, autoCapture, busy, startingCamera, shuttingDown, foreground;
    private int version, answerVersion;
    private String initialQuestion, last = "";
    private ProcessCameraProvider provider;
    private ImageCapture shutter;
    private TextRecognizer ocr;
    private ImageLabeler labeler;
    private VoiceIO voice;
    private PreviewView preview;
    private TextView status;
    private EditText input;
    private Button take;
    private Bitmap image;
    private VisionEvidence evidence;
    private AlertDialog approval;
    private String cloudQuestion, cloudHost;
    private String progress;
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (!foreground || progress == null || shuttingDown) return;
            say(progress);
            main.postDelayed(this, 15000);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        String lang = getIntent().getStringExtra("language");
        zh = (lang == null ? Locale.getDefault().getLanguage() : lang).startsWith("zh");
        autoCapture = saved == null && getIntent().getBooleanExtra("autoCapture", false);
        initialQuestion = saved == null ? getIntent().getStringExtra("question") : null;
        voice = new VoiceIO(this, zh);
        voice.setRate(getIntent().getFloatExtra("rate", 0.92f));
        voice.setVolume(getIntent().getFloatExtra("volume", 1f));
        ocr = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        labeler = ImageLabeling.getClient(new ImageLabelerOptions.Builder().setConfidenceThreshold(0.78f).build());
        buildScreen();
        voice.setOnSpeechError(error -> {
            if (shuttingDown) return;
            status.setText(last + "\n" + error);
            if (foreground) status.announceForAccessibility(status.getText());
        });
        if (hasCamera()) startCamera();
        else if (autoCapture) requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setFocusableInTouchMode(true);
        page.setPadding(dp(16), dp(12), dp(16), dp(28));
        scroll.addView(page);
        TextView title = new TextView(this);
        title.setText(tr("看一看", "Look around"));
        title.setTextSize(32);
        title.setTextColor(Color.BLACK);
        title.setAccessibilityHeading(true);
        page.addView(title);
        status = new TextView(this);
        status.setTextSize(22);
        status.setTextColor(Color.BLACK);
        status.setText(tr("将手机后方对准物品，然后拍照。照片仅在本机分析。",
                "Point the back camera towards the object, then take a photo. Analysis happens on this device."));
        last = status.getText().toString();
        page.addView(status);
        preview = new PreviewView(this);
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        preview.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        page.addView(preview, new LinearLayout.LayoutParams(-1, dp(120)));
        take = button(page, tr("拍照并描述", "Take photo and describe"), this::capture);
        button(page, tr("选择现有图片或截图", "Choose a photo or screenshot"), this::pickImage);
        button(page, tr("用语音追问", "Ask by voice"), this::listen);
        button(page, tr("读下一段文字", "Read next lines"), () -> answer("read next"));
        button(page, tr("查有效期", "Find expiry date"), () -> answer("expiry"));
        button(page, tr("描述左边", "Describe the left"), () -> answer("left"));
        button(page, tr("描述右边", "Describe the right"), () -> answer("right"));
        input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(20);
        input.setTextColor(Color.BLACK);
        input.setHintTextColor(Color.DKGRAY);
        input.setHint(tr("输入关于照片的问题", "Type a question about the photo"));
        input.setMinHeight(dp(52));
        input.setSaveEnabled(false);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        input.setOnEditorActionListener((v, action, key) -> {
            if (action != EditorInfo.IME_ACTION_SEND) return false;
            answer(input.getText().toString());
            return true;
        });
        page.addView(input);
        button(page, tr("回答问题", "Answer question"), () -> answer(input.getText().toString()));
        button(page, tr("向在线模型提问", "Ask online model"), () -> {
            String question = input.getText().toString().trim();
            requestCloud(question.isEmpty() ? tr("请描述这张照片。", "Describe this photo.") : question);
        });
        button(page, tr("再说一遍", "Repeat answer"), () -> say(last));
        button(page, tr("停止", "Stop"), this::stopWork);
        button(page, tr("结束并返回", "Finish and return"), this::finish);
        setContentView(scroll);
    }

    private Button button(LinearLayout page, String label, Runnable action) {
        Button button = new Button(this);
        button.setTextSize(30);
        button.setText(label);
        button.setContentDescription(label);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff17455a));
        button.setMinHeight(dp(54));
        button.setOnClickListener(v -> action.run());
        page.addView(button);
        return button;
    }

    private boolean hasCamera() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void startCamera() {
        if (startingCamera || shutter != null || shuttingDown) return;
        startingCamera = true;
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            startingCamera = false;
            if (shuttingDown) return;
            try {
                provider = future.get();
                Preview feed = new Preview.Builder().build();
                feed.setSurfaceProvider(preview.getSurfaceProvider());
                // Bound resolution also bounds the JPEG allocation on memory-constrained phones.
                ImageCapture capture = new ImageCapture.Builder().setTargetResolution(new Size(1600, 1200))
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, feed, capture);
                shutter = capture;
                if (autoCapture) {
                    say(tr("将手机对准物品，提示结束后拍照。", "Point the phone at the item. I'll take the photo after this prompt."),
                            this::captureIfPending);
                    // The button remains available on devices without a working speech engine.
                } else {
                    say(tr("相机已就绪。将后置摄像头对准物品，然后选择拍照。", "Camera ready. Aim the back camera and choose Take photo."));
                }
            } catch (Exception unavailable) {
                say(tr("后置相机未能打开。请关闭其他相机应用，然后点拍照重试。",
                        "Couldn't open the back camera. Close other camera apps, then choose Take photo to retry."));
            }
        }, getMainExecutor());
    }

    private void captureIfPending() {
        if (autoCapture && foreground && !shuttingDown) capture();
    }

    private void capture() {
        if (busy || shuttingDown) return;
        if (!hasCamera()) {
            autoCapture = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
            return;
        }
        if (shutter == null) { autoCapture = true; startCamera(); return; }
        autoCapture = false;
        cancelCloud();
        if (!allowed("capture_photo")) return;
        busy = true;
        take.setEnabled(false);
        final int token = ++version;
        ++answerVersion;
        image = null;
        evidence = null;
        setResult(RESULT_CANCELED);
        say(tr("正在拍照。", "Taking the photo."));
        progress(tr("仍在处理这张照片。", "Still processing this photo."));
        shutter.setTargetRotation(preview.getDisplay() == null ? 0 : preview.getDisplay().getRotation());
        shutter.takePicture(images, new ImageCapture.OnImageCapturedCallback() {
            @Override public void onCaptureSuccess(ImageProxy photo) {
                try {
                    ByteBuffer buffer = photo.getPlanes()[0].getBuffer();
                    byte[] jpeg = new byte[buffer.remaining()];
                    buffer.get(jpeg);
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
                    options.inSampleSize = 1;
                    while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 2200) {
                        options.inSampleSize *= 2;
                    }
                    options.inJustDecodeBounds = false;
                    Bitmap raw = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
                    if (raw == null) throw new IllegalArgumentException();
                    Matrix rotation = new Matrix();
                    rotation.setRotate(photo.getImageInfo().getRotationDegrees());
                    Bitmap upright = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), rotation, true);
                    runOnUiThread(() -> recognise(token, upright));
                } catch (RuntimeException decode) {
                    runOnUiThread(() -> captureFailed(token));
                } finally {
                    photo.close();
                }
            }
            @Override public void onError(ImageCaptureException error) {
                runOnUiThread(() -> captureFailed(token));
            }
        });
    }

    private void captureFailed(int token) {
        if (!current(token)) return;
        endProgress();
        busy = false;
        take.setEnabled(true);
        say(tr("拍摄失败。请握稳手机，再点拍照。", "Capture failed. Hold the phone steady and take another photo."));
    }

    private void recognise(int token, Bitmap photo) {
        if (!current(token)) return;
        if (!allowed("read_photo")) { endProgress(); busy = false; take.setEnabled(true); return; }
        image = photo;
        say(tr("图片已就绪，正在本机识读。", "Image ready. Reading it on this device."));
        ocr.process(InputImage.fromBitmap(photo, 0))
                .addOnSuccessListener(result -> {
                    if (!current(token)) return;
                    VisionEvidence found = new VisionEvidence(zh);
                    List<Text.Line> lines = new ArrayList<>();
                    for (Text.TextBlock block : result.getTextBlocks()) lines.addAll(block.getLines());
                    lines.sort(Comparator.comparingInt((Text.Line line) -> top(line.getBoundingBox()))
                            .thenComparingInt(line -> left(line.getBoundingBox())));
                    for (Text.Line line : lines) found.add(line.getText());
                    evidence = found;
                    endProgress();
                    busy = false;
                    take.setEnabled(true);
                    setResult(RESULT_OK);
                    String q = initialQuestion;
                    initialQuestion = null;
                    if (q != null && !q.trim().isEmpty() && !isGeneralRequest(q)) answer(q);
                    else if (lines.isEmpty()) describe("scene");
                    else say(evidence.summary());
                })
                .addOnFailureListener(error -> {
                    if (!current(token)) return;
                    endProgress();
                    busy = false;
                    take.setEnabled(true);
                    initialQuestion = null;
                    say(tr("这张照片的文字识别失败。请重拍。", "Text recognition failed for this photo. Please retake."));
                });
    }

    private static boolean isGeneralRequest(String q) {
        return q.trim().toLowerCase(Locale.ROOT).matches("看一看|看看|拍照|look|take a look|take a photo|look around");
    }

    private void pickImage() {
        if (busy || shuttingDown || !allowed("read_photo")) return;
        autoCapture = false;
        cancelCloud();
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        try { startActivityForResult(picker, PICK_IMAGE); }
        catch (ActivityNotFoundException unavailable) {
            say(tr("设备没有可用的文件选择器，可以拍照代替。", "A file picker is unavailable. You can take a photo instead."));
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_IMAGE || result != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData();
        if (!"content".equals(uri.getScheme()) || !allowed("read_photo")) return;
        final int token = ++version;
        ++answerVersion;
        setResult(RESULT_CANCELED);
        busy = true;
        take.setEnabled(false);
        image = null;
        evidence = null;
        initialQuestion = null;
        progress(tr("仍在读取所选图片。", "Still reading the selected image."));
        images.execute(() -> {
            try {
                Bitmap selected = ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(), uri),
                        (decoder, info, source) -> {
                            Size size = info.getSize();
                            float scale = Math.min(1f, 2000f / Math.max(size.getWidth(), size.getHeight()));
                            decoder.setTargetSize(Math.max(1, Math.round(size.getWidth() * scale)),
                                    Math.max(1, Math.round(size.getHeight() * scale)));
                            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                        });
                runOnUiThread(() -> recognise(token, selected));
            } catch (IOException | RuntimeException unreadable) {
                runOnUiThread(() -> {
                    if (!current(token)) return;
                    busy = false;
                    endProgress();
                    take.setEnabled(true);
                    say(tr("所选图片未能读取。请重选一张图片，或拍照。",
                            "Couldn't read that image. Choose another image or take a photo."));
                });
            }
        });
    }

    private static int top(Rect box) { return box == null ? Integer.MAX_VALUE : box.top; }
    private static int left(Rect box) { return box == null ? Integer.MAX_VALUE : box.left; }

    private void listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE_REQUEST);
            return;
        }
        status.setText(tr("正在听你的问题。", "Listening for your question."));
        voice.listen(this::answer, this::say);
    }

    private void answer(String raw) {
        String q = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        answerVersion++;
        endProgress();
        if (q.matches("stop|cancel|停止|取消")) { stopWork(); return; }
        if (q.matches("repeat|repeat answer|再说一遍|重复")) { say(last); return; }
        if (q.matches("back|finish|go back|返回|结束")) { finish(); return; }
        if (q.matches("retake|take (a )?(new )?photo|重拍|重新拍照|拍照")) { capture(); return; }
        if (VisionEvidence.contains(q, "online", "cloud", "在线", "云端")) { requestCloud(raw.trim()); return; }
        if (image == null || evidence == null) {
            say(busy ? tr("正在识读，请稍等。", "I'm reading the photo. Please wait.")
                    : tr("请先点拍照，或说“拍照”。", "Choose Take photo, or say take a photo first."));
            return;
        }
        if (!allowed("read_photo")) return;
        if (!evidence.hidden() && isSceneQuestion(q)) describe(q);
        else say(evidence.answer(q, LocalDate.now()));
    }

    private static boolean isSceneQuestion(String q) {
        if (VisionEvidence.contains(q, "日期", "有效期", "过期", "文字", "数字", "多少钱", "总额", "哪个按钮",
                "expiry", "expir", "date", "total", "number", "read", "line", "text")) return false;
        return VisionEvidence.contains(q, "left", "right", "scene", "picture", "object", "what is this",
                "what's this", "what is it", "左", "右", "场景", "有什么", "什么东西", "是什么", "描述", "识物", "look around");
    }

    private void describe(String q) {
        Bitmap photo = image;
        if (photo == null) return;
        boolean left = VisionEvidence.contains(q, "left", "左");
        boolean right = VisionEvidence.contains(q, "right", "右");
        String area = left ? tr("左半幅", "left half") : right ? tr("右半幅", "right half") : tr("全图", "full picture");
        boolean asksColour = VisionEvidence.contains(q, "红", "绿", "蓝", "黄", "red ", "green ", "blue ", "yellow ");
        int width = Math.max(1, photo.getWidth() / 2);
        Bitmap region = left || right ? Bitmap.createBitmap(photo,
                left ? 0 : photo.getWidth() - width, 0, width, photo.getHeight()) : photo;
        int token = ++answerVersion;
        say(tr("正在描述" + area + "。", "Describing the " + area + "."));
        progress(tr("仍在分析画面。", "Still examining the picture."));
        labeler.process(InputImage.fromBitmap(region, 0))
                .addOnSuccessListener(labels -> {
                    if (shuttingDown || token != answerVersion) return;
                    endProgress();
                    List<String> names = new ArrayList<>();
                    for (ImageLabel label : labels) {
                        if (names.size() == 3) break;
                        names.add(zh ? VisionLabels.chinese(label.getText()) : label.getText());
                    }
                    String reply = names.isEmpty()
                            ? tr(area + "没有识别出明确类别。可将物品放在画面中央重拍。",
                            "I couldn't identify a clear category in the " + area + ". Center the object and retake.")
                            : tr(area + "可能包含：" + String.join("、", names) + "。",
                            "Possible categories in the " + area + ": " + String.join(", ", names) + ".");
                    if (asksColour) reply += tr("我不能确定其中哪个是你说的颜色。", " I can't establish which item has that color.");
                    say(reply);
                })
                .addOnFailureListener(error -> {
                    if (!shuttingDown && token == answerVersion) {
                        endProgress();
                        say(tr("图像描述失败。请重拍或问我读到哪些文字。",
                                "Image description failed. Retake, or ask what text I could read."));
                    }
                });
    }

    private void requestCloud(String question) {
        if (approval != null || busy) return;
        if (evidence == null || image == null) {
            say(tr("请先拍照，并等待本机识读完成。", "Take a photo and wait for the local reading first."));
            return;
        }
        VisionEvidence questionPrivacy = new VisionEvidence(zh);
        questionPrivacy.add(question);
        if (evidence.hidden() || questionPrivacy.hidden()) {
            say(tr("照片或问题可能含有密码、验证码，已停用在线发送。",
                    "The photo or question may contain a password or code. Online sharing is disabled."));
            return;
        }
        if (VisionEvidence.contains(question.toLowerCase(Locale.ROOT), "剂量", "用药", "吃药", "有效期", "过期", "能不能吃",
                "dosage", "dose", "medicine", "medication", "expir", "safe to take")) {
            say(tr("请用本机“查有效期”读取标签。用药前请向药师核对。",
                    "Use Find expiry date to read the label locally. Ask a pharmacist to verify medication details."));
            return;
        }
        if (!CloudAssist.ready()) {
            say(tr("未配置在线模型。仍可在本机识读，或在主页“更多”中配置服务。",
                    "No online model is configured. Local reading is available. Configure a provider in More on the home screen."));
            return;
        }
        if (question.trim().isEmpty() || question.length() > 300) {
            say(tr("请提供 1 到 300 字的问题。", "Enter a question of 1 to 300 characters."));
            return;
        }
        cancelCloud();
        String id = getIntent().getStringExtra("session");
        if (id == null || NativeCore.dispatch(id, "share_photo") != 2) {
            say(tr("当前任务无法申请分享照片。请重新发起看一看。",
                    "This task cannot request photo sharing. Start Look around again."));
            return;
        }
        cloudQuestion = question;
        cloudHost = CloudAssist.host();
        String phrase = tr("确认发送照片", "confirm send photo");
        String notice = tr("即将把当前整张照片和问题发送给 " + cloudHost + "。问题是：“" + question
                        + "”。如同意，请说或输入“" + phrase + "”。也可以取消。",
                "Send the entire current photo and this question to " + cloudHost + "? Your question is: " + question
                        + ". To agree, say or type " + phrase + ". You can cancel.");
        EditText explicit = new EditText(this);
        explicit.setSingleLine(true);
        explicit.setHint(phrase);
        explicit.setSaveEnabled(false);
        explicit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        explicit.setTextSize(22);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(tr("确认分享这一张照片", "Share this photo?"))
                .setMessage(notice).setView(explicit)
                .setPositiveButton(tr("语音确认或取消", "Answer by voice"), null)
                .setNeutralButton(tr("核对确认词", "Check typed phrase"), null)
                .setNegativeButton(tr("取消", "Cancel"), (d, w) -> {
                    cancelCloud();
                    say(tr("已取消分享。", "Sharing canceled."));
                }).create();
        dialog.setOnCancelListener(d -> {
            cancelCloud();
            say(tr("已取消分享。", "Sharing canceled."));
        });
        approval = dialog;
        dialog.show();
        for (int choice : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE}) {
            dialog.getButton(choice).setMinHeight(dp(52));
            dialog.getButton(choice).setTextSize(18);
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                cancelCloud();
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, APPROVAL_MIC_REQUEST);
            } else {
                voice.listen(reply -> checkApproval(reply, phrase), this::say);
            }
        });
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> checkApproval(explicit.getText().toString(), phrase));
        say(notice);
    }

    private void checkApproval(String reply, String phrase) {
        if (approval == null) return;
        String value = reply.trim().replaceAll("[。.!！]$", "");
        if (!phrase.equalsIgnoreCase(value)) {
            cancelCloud();
            say(tr("已取消，本次没有发送照片。", "Canceled. The photo wasn't sent."));
            return;
        }
        String id = getIntent().getStringExtra("session");
        String question = cloudQuestion;
        String host = cloudHost;
        if (id == null || !CloudAssist.ready() || host == null || !host.equals(CloudAssist.host())
                || !NativeCore.event(id, "approved") || NativeCore.dispatch(id, "share_photo") != 1) {
            cancelCloud();
            say(tr("确认已失效，照片没有发送。", "Approval expired. The photo wasn't sent."));
            return;
        }
        approval.dismiss();
        approval = null;
        cloudQuestion = cloudHost = null;
        Bitmap current = image;
        int token = ++answerVersion;
        say(tr("已确认。正在准备照片，等待在线回答。", "Confirmed. Preparing the photo for online description."));
        progress(tr("仍在等待在线回答。", "Still waiting for an online answer."));
        images.execute(() -> {
            int longest = Math.max(current.getWidth(), current.getHeight());
            float scale = Math.min(1f, 1200f / longest);
            Bitmap reduced = Bitmap.createScaledBitmap(current, Math.max(1, Math.round(current.getWidth() * scale)),
                    Math.max(1, Math.round(current.getHeight() * scale)), true);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            boolean encoded = reduced.compress(Bitmap.CompressFormat.JPEG, 78, output);
            byte[] jpeg = encoded ? output.toByteArray() : null;
            runOnUiThread(() -> {
                if (!foreground || shuttingDown || token != answerVersion || image != current) return;
                if (jpeg == null || jpeg.length > 1536 * 1024 || !host.equals(CloudAssist.host())) {
                    endProgress();
                    say(tr("照片准备失败，未发送。请重拍后再试。", "Couldn't prepare the photo. It wasn't sent. Retake and retry."));
                    return;
                }
                CloudAssist.describe(jpeg, question, zh,
                        replyText -> { if (foreground && !shuttingDown && token == answerVersion) { endProgress(); say(replyText); } },
                        error -> { if (foreground && !shuttingDown && token == answerVersion) { endProgress(); say(error); } });
            });
        });
    }

    private void cancelCloud() {
        CloudAssist.cancel();
        if (approval != null) {
            String id = getIntent().getStringExtra("session");
            if (id != null) NativeCore.event(id, "declined");
            approval.dismiss();
            approval = null;
        }
        cloudQuestion = cloudHost = null;
    }

    private void stopWork() {
        endProgress();
        version++;
        answerVersion++;
        autoCapture = busy = false;
        initialQuestion = null;
        cancelCloud();
        take.setEnabled(true);
        voice.stop();
        say(tr("已停止。", "Stopped."));
    }

    private void say(String message) { say(message, null); }

    private void say(String message, Runnable after) {
        if (shuttingDown) return;
        last = message;
        status.setText(message);
        if (foreground) voice.say(message, after);
    }

    private boolean current(int token) { return !shuttingDown && token == version; }
    private void progress(String message) {
        endProgress();
        progress = message;
        main.postDelayed(heartbeat, 10000);
    }
    private void endProgress() {
        progress = null;
        main.removeCallbacks(heartbeat);
    }
    private boolean allowed(String tool) {
        String id = getIntent().getStringExtra("session");
        if (id != null && NativeCore.dispatch(id, tool) == 1) return true;
        say(tr("本次任务不能使用相机工具。请返回主页重新发起看一看。",
                "This task can't use the camera tool. Return to the home screen and start Look around again."));
        return false;
    }
    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
    private String tr(String chinese, String english) { return zh ? chinese : english; }

    @Override public void onRequestPermissionsResult(int request, String[] names, int[] grants) {
        super.onRequestPermissionsResult(request, names, grants);
        boolean granted = grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED;
        if (request == CAMERA_REQUEST) {
            if (granted) startCamera();
            else say(tr("需要相机权限。可在应用权限中开启相机，再回来点拍照。",
                    "Camera permission is needed. Enable Camera in app permissions, then return and choose Take photo."));
        } else if (request == APPROVAL_MIC_REQUEST) {
            say(tr("若要分享照片，请重新发起在线提问；也可以输入确认词。",
                    "Request online help again to share a photo. You can also type the confirmation phrase."));
        } else if (request == MICROPHONE_REQUEST) {
            if (granted) listen();
            else say(tr("没有麦克风权限。可以使用读文字、查有效期等按钮，或输入问题。",
                    "Microphone permission is off. Use the reading buttons or type a question."));
        }
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        if (autoCapture && shutter != null && !busy) say(last, this::captureIfPending);
    }

    @Override protected void onPause() {
        foreground = false;
        endProgress();
        ++answerVersion;
        cancelCloud();
        voice.stop();
        super.onPause();
    }

    @Override protected void onDestroy() {
        shuttingDown = true;
        if (provider != null) provider.unbindAll();
        images.shutdown();
        labeler.close();
        ocr.close();
        voice.destroy();
        image = null;
        evidence = null;
        super.onDestroy();
    }
}
