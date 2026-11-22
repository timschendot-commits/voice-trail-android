package io.github.voicetrail;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/** Activates real native widgets using the same ACTION_CLICK exposed to accessibility services. */
@RunWith(AndroidJUnit4.class)
public class HomeInstrumentedTest {
    private SharedPreferences preferences;
    private boolean previouslySet, previousLanguage;

    @Before public void startInEnglish() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        preferences = context.getSharedPreferences("voice_trail", Context.MODE_PRIVATE);
        previouslySet = preferences.contains("zh");
        previousLanguage = preferences.getBoolean("zh", false);
        assertTrue(preferences.edit().putBoolean("zh", false).commit());
    }

    @After public void restoreLanguage() {
        SharedPreferences.Editor edit = preferences.edit();
        if (previouslySet) edit.putBoolean("zh", previousLanguage);
        else edit.remove("zh");
        assertTrue(edit.commit());
    }

    @Test public void homeControlsAndLanguageSwitchWorkViaAccessibilityActions() {
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            screen.onActivity(a -> {
                assertHeading(a, "Voice Trail");
                for (String name : new String[]{"Speak", "Read the screen", "Look around", "Stop", "Repeat", "More"}) {
                    control(a, name);
                }
                click(a, "Stop");
                click(a, "More");
                assertHeading(a, "More");
                click(a, "Language and display");
                assertHeading(a, "Language and display");
                click(a, "简体中文");
                assertHeading(a, "声迹助手");
                for (String name : new String[]{"说句话", "读当前屏幕", "看一看", "停止", "再说一遍", "更多"}) {
                    control(a, name);
                }
                click(a, "停止");
            });
            screen.recreate();
            screen.onActivity(a -> {
                assertHeading(a, "声迹助手");
                click(a, "更多");
                click(a, "语言和显示模式");
                click(a, "English");
                assertHeading(a, "Voice Trail");
            });
        }
    }

    @Test public void typedReadHasAnActionableRecoveryWhenTheServiceIsDisabled() {
        // Preserve settings on devices actively using Voice Trail. A fresh emulator exercises this path.
        Assume.assumeTrue("Requires Voice Trail screen access to be disabled", PhoneService.get() == null);
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
            screen.onActivity(a -> {
                control(a, "Enable screen access");
                click(a, "Type a command");
                EditText editor = find(a, EditText.class, v -> true);
                assertNotNull("Typed command field", editor);
                editor.setText("read the screen");
                click(a, "Run");
                assertHeading(a, "Voice Trail");
            });
            // The handset may have a local English voice. Without one, spoken execution
            // must fail visibly before continuing. Both recovery paths have action text.
            final AtomicBoolean recovery = new AtomicBoolean();
            long until = SystemClock.elapsedRealtime() + 45000;
            do {
                screen.onActivity(a -> recovery.set(find(a, TextView.class, v -> {
                    String message = v.getText().toString();
                    return (message.contains("Screen access is off") && message.contains("Enable screen access"))
                            || (message.contains("Speech paused") && message.contains("offline voice"));
                }) != null));
                if (!recovery.get()) SystemClock.sleep(120);
            } while (!recovery.get() && SystemClock.elapsedRealtime() < until);
            assertTrue("Expected guidance for enabling screen access or installing an offline voice", recovery.get());
            screen.onActivity(a -> {
                click(a, "Enable screen access");
                assertHeading(a, "Enable screen access");
                control(a, "Open Android accessibility settings");
                click(a, "Back");
                assertHeading(a, "Voice Trail");
            });
        }
    }

    private static void assertHeading(MainActivity activity, String title) {
        TextView heading = find(activity, TextView.class,
                v -> title.contentEquals(v.getText()) && v.isAccessibilityHeading());
        assertNotNull("Accessibility heading: " + title, heading);
    }

    private static Button control(MainActivity activity, String name) {
        Button button = find(activity, Button.class, v -> name.contentEquals(v.getText()));
        assertNotNull("Button: " + name, button);
        assertEquals(name, button.getContentDescription());
        assertTrue(name + " must be enabled and focusable", button.isEnabled() && button.isFocusable());
        int requiredPixels = Math.round(activity.getResources().getDisplayMetrics().density * 48);
        assertTrue(name + " must have a 48 dp target", Math.max(button.getHeight(), button.getMinHeight()) >= requiredPixels);
        AccessibilityNodeInfo info = button.createAccessibilityNodeInfo();
        try {
            assertTrue(name + " must advertise ACTION_CLICK",
                    info.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK));
        } finally {
            info.recycle();
        }
        return button;
    }

    private static void click(MainActivity activity, String name) {
        assertTrue(name + " must accept ACTION_CLICK",
                control(activity, name).performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
    }

    private static <T extends View> T find(MainActivity activity, Class<T> type, Predicate<T> check) {
        View content = activity.findViewById(android.R.id.content);
        return find(content, type, check);
    }

    private static <T extends View> T find(View view, Class<T> type, Predicate<T> check) {
        if (type.isInstance(view)) {
            T item = type.cast(view);
            if (check.test(item)) return item;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = find(group.getChildAt(i), type, check);
                if (found != null) return found;
            }
        }
        return null;
    }
}
