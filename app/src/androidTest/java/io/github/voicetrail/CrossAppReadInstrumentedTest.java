package io.github.voicetrail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Opt-in emulator acceptance test. It never needs a real user, microphone, or account. */
@RunWith(AndroidJUnit4.class)
public class CrossAppReadInstrumentedTest {
    private static final String SERVICES = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
    private static final String ENABLED = Settings.Secure.ACCESSIBILITY_ENABLED;

    @Test public void overlayReadsTheOriginalSettingsWindowThroughTheEmbeddedCore() throws Exception {
        assumeTrue("Explicit CI opt-in required", "true".equals(
                InstrumentationRegistry.getArguments().getString("ciOverlayTest")));
        assumeTrue("Android emulator hardware required", "ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE));
        Instrumentation ins = InstrumentationRegistry.getInstrumentation();
        UiAutomation ui = ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        assumeTrue("Android emulator property required", emulatorProperty(ui, "ro.kernel.qemu") || emulatorProperty(ui, "ro.boot.qemu"));

        Context ctx = ins.getTargetContext();
        ContentResolver cr = ctx.getContentResolver();
        String oldServices = Settings.Secure.getString(cr, SERVICES);
        String oldEnabled = Settings.Secure.getString(cr, ENABLED);
        ComponentName serviceComponent = new ComponentName(ctx, PhoneService.class);
        String serviceList = appendService(oldServices, serviceComponent);
        AccessibilityServiceInfo oldInfo = ui.getServiceInfo();
        int oldFlags = oldInfo.flags;
        Activity assistant = null;
        Instrumentation.ActivityMonitor monitor = null;
        String session = null;
        try {
            writeSettings(ui, cr, serviceList, "1");
            oldInfo.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            ui.setServiceInfo(oldInfo);
            AtomicReference<PhoneService> connected = new AtomicReference<>();
            waitFor("Our accessibility service did not connect", () -> {
                ins.runOnMainSync(() -> connected.set(PhoneService.get()));
                return connected.get() != null;
            });
            PhoneService phone = connected.get();
            assistant = ins.startActivitySync(new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final Activity realActivity = assistant;
            waitFor("Assistant activity did not initialize", () -> packageActive(ui, ctx.getPackageName()));
            Intent settings = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ResolveInfo resolved = ctx.getPackageManager().resolveActivity(settings, 0);
            assertNotNull("The emulator must include Android Settings", resolved);
            String sourcePackage = resolved.activityInfo.packageName;
            assertFalse(sourcePackage.equals(ctx.getPackageName()));
            ins.runOnMainSync(() -> realActivity.startActivity(settings));
            waitFor("Settings did not become the active window", () -> packageActive(ui, sourcePackage));
            List<String> labels = new ArrayList<>();
            int window;
            AccessibilityNodeInfo sourceRoot = ui.getRootInActiveWindow();
            assertNotNull(sourceRoot);
            try (PhoneTree source = new PhoneTree(sourceRoot)) {
                window = source.windowId();
                for (PhoneTree.Item item : source.items) {
                    String s = PhoneTree.string(item.node.getText());
                    if (!item.node.isEditable() && s.length() >= 3 && s.length() <= 80) labels.add(s);
                }
            }
            assertFalse("The source page must expose readable labels", labels.isEmpty());

            // The actual button captures the source identity and brings back the existing
            // single-task activity. Replace only dictation, so the runner needs no microphone.
            CountDownLatch buttonDispatched = new CountDownLatch(1);
            ComponentName main = new ComponentName(ctx, MainActivity.class);
            monitor = new Instrumentation.ActivityMonitor() {
                @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                    if (main.equals(intent.getComponent()) && intent.getBooleanExtra("listen", false)) {
                        intent.removeExtra("listen");
                        buttonDispatched.countDown();
                    }
                    return null;
                }
            };
            ins.addMonitor(monitor);
            waitFor("The service did not expose a clickable overlay", () -> clickOverlay(ui, ctx.getPackageName()));
            assertTrue("The real overlay did not request a speech session", buttonDispatched.await(6, TimeUnit.SECONDS));
            ins.removeMonitor(monitor);
            monitor = null;
            waitFor("Assistant activity did not open", () -> packageActive(ui, ctx.getPackageName()));
            session = NativeCore.start("read_screen");
            assertFalse("Embedded core did not create a session", session.isEmpty());
            final String id = session;
            AtomicBoolean ok = new AtomicBoolean();
            AtomicBoolean matchedSource = new AtomicBoolean();
            AtomicInteger readPermits = new AtomicInteger();
            CountDownLatch finished = new CountDownLatch(1);
            PhoneService.Flow flow = new PhoneService.Flow() {
                @Override public void step(String ignored, Runnable next) { next.run(); }
                @Override public boolean allow(String tool) {
                    // This acceptance case measures the node-only, offline read path.
                    if (!"read_nodes".equals(tool)) return false;
                    if (NativeCore.dispatch(id, tool) != 1) return false;
                    readPermits.incrementAndGet();
                    return true;
                }
                @Override public void done(String text, boolean success) {
                    ok.set(success && text != null && !text.trim().isEmpty());
                    if (text != null) for (String label : labels) {
                        if (text.contains(label)) { matchedSource.set(true); break; }
                    }
                    finished.countDown();
                }
            };
            ins.runOnMainSync(() -> phone.readScreenAfterReturn(realActivity, false, false, flow));
            assertTrue("The cross-app read did not finish", finished.await(10, TimeUnit.SECONDS));
            assertTrue("The reader did not return readable source content", ok.get() && matchedSource.get());
            assertTrue("The service must request an actual native node-read permit", readPermits.get() > 0);
            assertTrue("The native session must finish", NativeCore.event(id, "done"));
            AccessibilityNodeInfo restored = ui.getRootInActiveWindow();
            assertNotNull(restored);
            try {
                assertEquals("Source window was replaced by another screen", window, restored.getWindowId());
                assertTrue("Reader must return to the original external package", sourcePackage.contentEquals(restored.getPackageName()));
            } finally { restored.recycle(); }
        } finally {
            try {
                if (monitor != null) ins.removeMonitor(monitor);
                if (assistant != null) ins.runOnMainSync(assistant::finish);
                if (session != null) NativeCore.close(session);
            } finally {
                try {
                    oldInfo.flags = oldFlags;
                    ui.setServiceInfo(oldInfo);
                } finally {
                    writeSettings(ui, cr, oldServices, oldEnabled);
                    assertEquals("Prior service list must be restored", oldServices, Settings.Secure.getString(cr, SERVICES));
                    assertEquals("Prior enabled state must be restored", oldEnabled, Settings.Secure.getString(cr, ENABLED));
                }
            }
        }
    }

    private boolean emulatorProperty(UiAutomation ui, String key) throws Exception {
        // Keys come exclusively from the two hard-coded emulator checks above.
        ParcelFileDescriptor pfd = ui.executeShellCommand("getprop " + key);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(pfd)))) {
            return "1".equals(reader.readLine());
        }
    }
    private static String appendService(String existing, ComponentName component) {
        if (existing == null || existing.isEmpty()) return component.flattenToString();
        for (String entry : existing.split(":")) {
            if (component.equals(ComponentName.unflattenFromString(entry))) return existing;
        }
        return existing + ":" + component.flattenToString();
    }
    private static void writeSettings(UiAutomation ui, ContentResolver cr, String services, String enabled) {
        ui.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS);
        try {
            boolean a = Settings.Secure.putString(cr, SERVICES, services);
            boolean b = Settings.Secure.putString(cr, ENABLED, enabled);
            assertTrue("The emulator rejected its accessibility settings", a && b);
        } finally { ui.dropShellPermissionIdentity(); }
    }
    private static void waitFor(String message, BooleanSupplier check) {
        long until = SystemClock.elapsedRealtime() + 8000;
        do {
            if (check.getAsBoolean()) return;
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < until);
        fail(message);
    }
    private static boolean packageActive(UiAutomation ui, String pkg) {
        AccessibilityNodeInfo root = ui.getRootInActiveWindow();
        if (root == null) return false;
        try { return pkg.contentEquals(PhoneTree.string(root.getPackageName())); }
        finally { root.recycle(); }
    }
    private static boolean clickOverlay(UiAutomation ui, String pkg) {
        for (AccessibilityWindowInfo win : ui.getWindows()) {
            try {
                if (win.getType() != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
                AccessibilityNodeInfo root = win.getRoot();
                if (root == null) continue;
                try (PhoneTree overlay = new PhoneTree(root)) {
                    if (!pkg.equals(overlay.pkg)) continue;
                    for (PhoneTree.Item i : overlay.items) {
                        if (i.node.isClickable() && i.node.isEnabled() && i.node.getContentDescription() != null)
                            return i.node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                }
            } finally { win.recycle(); }
        }
        return false;
    }
}
