package io.github.voicetrail;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.graphics.Bitmap;
import android.graphics.Picture;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Read-only, on-device guesses for four common symbols. All boxes are window-local pixels. */
public final class IconHints {
    private IconHints() {}
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final int MAX_SIDE = 384, N = 28;
    private static final String[] EN = {"back", "close", "search", "add"};
    private static final String[] ZH = {"返回", "关闭", "搜索", "添加"};

    public static void describe(PhoneService service, int windowId, List<Rect> bounds, boolean zh, Consumer<String> append) {
        if (Build.VERSION.SDK_INT < 34 || bounds == null || bounds.isEmpty() || !current(service, windowId)) {
            append.accept("");
            return;
        }
        List<Rect> targets = new ArrayList<>();
        for (Rect box : bounds) {
            if (box != null && box.left >= 0 && box.top >= 0 && box.width() >= 12 && box.height() >= 12
                    && box.width() <= MAX_SIDE && box.height() <= MAX_SIDE) targets.add(new Rect(box));
            if (targets.size() == 4) break;
        }
        if (targets.isEmpty()) { append.accept(""); return; }
        Handler main = new Handler(Looper.getMainLooper());
        AtomicBoolean finished = new AtomicBoolean();
        String uncertain = zh ? "有未命名按钮，图案无法确定。" : "There are unlabeled buttons. Their symbols could not be identified.";
        Runnable timeout = () -> {
            if (finished.compareAndSet(false, true)) append.accept(current(service, windowId) ? uncertain : "");
        };
        main.postDelayed(timeout, 1800);
        Consumer<String> complete = text -> main.post(() -> {
            if (!finished.compareAndSet(false, true)) return;
            main.removeCallbacks(timeout);
            append.accept(current(service, windowId) ? text : "");
        });
        try {
            service.takeScreenshotOfWindow(windowId, WORK, new AccessibilityService.TakeScreenshotCallback() {
                @Override public void onFailure(int errorCode) { complete.accept(uncertain); }
                @Override public void onSuccess(AccessibilityService.ScreenshotResult result) {
                    Bitmap image = null;
                    // Late platform callbacks still own a hardware buffer and must release it.
                    try (HardwareBuffer buffer = result.getHardwareBuffer()) {
                        if (finished.get()) return;
                        image = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                        if (image == null) { complete.accept(uncertain); return; }
                        StringBuilder names = new StringBuilder();
                        boolean unknown = false;
                        for (Rect target : targets) {
                            if (finished.get()) return;
                            int symbol = identify(image, target);
                            if (symbol < 0) { unknown = true; continue; }
                            String place = location(target, image.getWidth(), image.getHeight(), zh);
                            if (zh) names.append(place).append("的图标可能是").append(ZH[symbol]).append("。");
                            else names.append("The ").append(place).append(" icon may mean ").append(EN[symbol]).append(". ");
                        }
                        if (unknown) names.append(uncertain);
                        complete.accept(names.toString().trim());
                    } catch (RuntimeException | OutOfMemoryError captureUnavailable) {
                        complete.accept(uncertain);
                    } finally {
                        if (image != null) image.recycle();
                    }
                }
            });
        } catch (RuntimeException unavailable) { complete.accept(uncertain); }
    }

    private static boolean current(PhoneService service, int windowId) {
        if (service == null || PhoneService.get() != service || windowId < 0) return false;
        try {
            KeyguardManager lock = service.getSystemService(KeyguardManager.class);
            if (lock == null || lock.isKeyguardLocked()) return false;
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) return false;
            try {
                CharSequence pkg = root.getPackageName();
                return root.getWindowId() == windowId && pkg != null && !service.getPackageName().contentEquals(pkg);
            } finally { root.recycle(); }
        } catch (RuntimeException closed) { return false; }
    }

    private static String location(Rect box, int width, int height, boolean zh) {
        int col = Math.min(2, box.centerX() * 3 / width), row = Math.min(2, box.centerY() * 3 / height);
        String[] chinese = {"左上", "上方中央", "右上", "左侧", "正中央", "右侧", "左下", "下方中央", "右下"};
        String[] english = {"upper left", "upper center", "upper right", "middle left", "center",
                "middle right", "lower left", "lower center", "lower right"};
        return (zh ? chinese : english)[row * 3 + col];
    }

    private static int identify(Bitmap image, Rect box) {
        if (box.right > image.getWidth() || box.bottom > image.getHeight()) return -1;
        Bitmap cpu = null;
        try {
            // Replay only this rectangle into a small software bitmap. Avoid a full-window CPU copy.
            Rect dest = new Rect(0, 0, box.width(), box.height());
            Picture picture = new Picture();
            try { picture.beginRecording(box.width(), box.height()).drawBitmap(image, box, dest, null); }
            finally { picture.endRecording(); }
            cpu = Bitmap.createBitmap(picture, box.width(), box.height(), Bitmap.Config.ARGB_8888);
            if (cpu == null) return -1;
            int w = cpu.getWidth(), h = cpu.getHeight();
            int[] pixels = new int[w*h];
            cpu.getPixels(pixels, 0, w, 0, 0, w, h);
            return symbol(pixels, w, h);
        } finally {
            if (cpu != null) cpu.recycle();
        }
    }

    /** Segment a contrasting glyph on a mostly uniform control. Reject text, gradients and full-bleed artwork. */
    static int symbol(int[] pixels, int w, int h) {
        if (w < 12 || h < 12 || w > MAX_SIDE || h > MAX_SIDE || pixels.length != w*h) return -1;
        int[] bins = new int[256];
        for (int c : pixels) bins[luma(c)]++;
        int bg = 0;
        for (int i = 1; i < 256; i++) if (bins[i] > bins[bg]) bg = i;
        int stable = 0;
        for (int i = Math.max(0, bg - 12); i <= Math.min(255, bg + 12); i++) stable += bins[i];
        if (stable < pixels.length * .56) return -1;
        int left = w, right = -1, top = h, bottom = -1, count = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            if (Math.abs(luma(pixels[y*w+x]) - bg) < 72) continue;
            left = Math.min(left, x); right = Math.max(right, x);
            top = Math.min(top, y); bottom = Math.max(bottom, y); count++;
        }
        int gw = right-left+1, gh = bottom-top+1;
        if (count < 16 || gw < 7 || gh < 7 || left < 1 || top < 1 || right >= w-1 || bottom >= h-1
                || gw > gh * 1.4 || gh > gw * 2.1 || count > gw*gh*.68) return -1;
        // Sample just the ink box; leave original aspect ratio intact.
        boolean[] mask = new boolean[N*N];
        double span = Math.max(gw, gh), cx = (right+left+1)/2.0, cy = (bottom+top+1)/2.0;
        for (int y=0; y<N; y++) for (int x=0; x<N; x++) {
            int sx = (int)Math.floor(cx + (x+.5-N/2.0)*span/N);
            int sy = (int)Math.floor(cy + (y+.5-N/2.0)*span/N);
            mask[y*N+x] = sx >= left && sx <= right && sy >= top && sy <= bottom
                    && Math.abs(luma(pixels[sy*w+sx])-bg) >= 72;
        }
        int best = -1;
        double first = 0, second = 0;
        for (int kind=0; kind<4; kind++) {
            double score = 0;
            for (boolean[] template : TEMPLATES[kind]) score = Math.max(score, overlap(mask,template));
            if (score > first) { second=first; first=score; best=kind; }
            else second=Math.max(second,score);
        }
        return first >= .68 && first-second >= .16 ? best : -1;
    }

    private static int luma(int c) { return (77*((c>>16)&255) + 150*((c>>8)&255) + 29*(c&255)) >> 8; }
    private static double overlap(boolean[] a, boolean[] b) {
        int intersection = 0, union = 0;
        for (int i=0; i<a.length; i++) {
            if (a[i] || b[i]) union++;
            if (a[i] && b[i]) intersection++;
        }
        return union == 0 ? 0 : intersection/(double)union;
    }

    // Original geometric templates, including two common back-symbol styles. No image assets.
    private static final boolean[][][] TEMPLATES = makeTemplates();
    private static boolean[][][] makeTemplates() {
        boolean[][][] all = new boolean[4][][];
        double[] widths = {.075,.10,.13,.17,.20};
        for (int k=0; k<4; k++) {
            all[k] = new boolean[k==0 ? 2*widths.length : widths.length][];
            for (int t=0; t<all[k].length; t++) {
                double radius=widths[t%widths.length], scale=1-radius;
                boolean[] mask = new boolean[N*N];
                for (int y=0; y<N; y++) for (int x=0; x<N; x++) {
                    double a=((x+.5)*2/N-1)/scale, b=((y+.5)*2/N-1)/scale;
                    double d;
                    switch (k) {
                        case 0:
                            if (t < widths.length) {
                                d=Math.min(line(a,b,-1,0,0,-1),line(a,b,-1,0,0,1));
                                d=Math.min(d,line(a,b,-1,0,1,0));
                            } else {
                                d=Math.min(line(a,b,-.5,0,.5,-1),line(a,b,-.5,0,.5,1));
                            }
                            break;
                        case 1: d=Math.min(line(a,b,-1,-1,1,1),line(a,b,-1,1,1,-1)); break;
                        case 2: d=Math.min(Math.abs(Math.hypot(a+.26,b+.26)-.73),line(a,b,.26,.26,1,1)); break;
                        default: d=Math.min(line(a,b,0,-1,0,1),line(a,b,-1,0,1,0));
                    }
                    mask[y*N+x]=d<=radius/scale;
                }
                all[k][t]=mask;
            }
        }
        return all;
    }
    private static double line(double x,double y,double x1,double y1,double x2,double y2) {
        double dx=x2-x1, dy=y2-y1;
        double t=Math.max(0,Math.min(1,((x-x1)*dx+(y-y1)*dy)/(dx*dx+dy*dy)));
        return Math.hypot(x-x1-t*dx,y-y1-t*dy);
    }
}
