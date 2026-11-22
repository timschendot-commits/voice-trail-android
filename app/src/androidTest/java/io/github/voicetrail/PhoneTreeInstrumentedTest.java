package io.github.voicetrail;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic Android nodes. These tests need no accessibility permission or real messages. */
@RunWith(AndroidJUnit4.class)
public class PhoneTreeInstrumentedTest {
    private AccessibilityNodeInfo node(String text) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setPackageName("example.fixture");
        node.setText(text);
        node.setVisibleToUser(true);
        return node;
    }

    @Test public void chineseCodeIsPrivateByDefaultAndKeepsItsLeadingZeroOnRequest() {
        try (PhoneTree page = new PhoneTree(node("您的验证码为：034012"))) {
            assertFalse(page.read(false, true).contains("034012"));
            assertTrue(page.read(true, true).endsWith("0 3 4 0 1 2"));
        }
    }

    @Test public void multipleOrUnlabelledNumbersCannotBecomeAReportedCode() {
        for (String text : new String[]{"Invoice 4312", "Your code is 1234. Your code is 9988."}) {
            try (PhoneTree page = new PhoneTree(node(text))) {
                String response = page.read(true, false);
                assertTrue(response.contains("couldn't identify"));
                assertFalse(response.contains("1 2 3 4"));
            }
        }
    }

    @Test public void passwordAndEditableFieldsAreExcludedEvenOnExplicitCodeRead() {
        for (boolean password : new boolean[]{true, false}) {
            AccessibilityNodeInfo item = node("Verification code: 006611");
            item.setPassword(password);
            item.setEditable(!password);
            try (PhoneTree page = new PhoneTree(item)) {
                assertFalse(page.read(false, false).contains("006611"));
                assertFalse(page.read(true, false).contains("0 0 6 6 1 1"));
            }
        }
    }
    @Test public void accountProofRequiresExactCaseAndAnExplicitIdLabel() {
        try (PhoneTree page = new PhoneTree(node("WeChat ID: Test_290"))) {
            assertTrue(page.profileIdMatches("Test_290"));
            assertFalse(page.profileIdMatches("Test_29"));
            assertFalse(page.profileIdMatches("test_290"));
        }
        try (PhoneTree page = new PhoneTree(node("Test_290"))) {
            assertFalse(page.profileIdMatches("Test_290"));
        }
    }

    @Test public void duplicateContactLabelsNeverTriggerEitherButton() {
        Instrumentation ins = InstrumentationRegistry.getInstrumentation();
        Activity activity = ins.startActivitySync(new Intent(ins.getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        AtomicInteger clicked = new AtomicInteger();
        String name = "Fixture Contact";
        try {
            ins.runOnMainSync(() -> {
                LinearLayout list = new LinearLayout(activity);
                list.setOrientation(LinearLayout.VERTICAL);
                for (int i = 0; i < 2; i++) {
                    Button duplicate = new Button(activity);
                    duplicate.setText(name);
                    duplicate.setAllCaps(false);
                    duplicate.setOnClickListener(v -> clicked.incrementAndGet());
                    list.addView(duplicate);
                }
                LinearLayout idRow = new LinearLayout(activity);
                idRow.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                for (String value : new String[]{"微信号", "Fixture_Test23"}) {
                    TextView field = new TextView(activity);
                    field.setText(value);
                    field.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                    idRow.addView(field);
                }
                list.addView(idRow);
                TextView unrelated = new TextView(activity);
                unrelated.setText("Nearby_Unrelated");
                list.addView(unrelated);
                activity.setContentView(list);
            });
            boolean checked = false;
            long until = SystemClock.elapsedRealtime() + 6000;
            while (!checked && SystemClock.elapsedRealtime() < until) {
                AccessibilityNodeInfo root = ins.getUiAutomation().getRootInActiveWindow();
                if (root != null) {
                    try (PhoneTree page = new PhoneTree(root)) {
                        int count = 0;
                        for (PhoneTree.Item item : page.items) if (item.matches(name)) count++;
                        if (count == 2) {
                            assertFalse(page.click(name));
                            assertTrue(page.profileIdMatches("Fixture_Test23"));
                            assertFalse(page.profileIdMatches("Nearby_Unrelated"));
                            checked = true;
                        }
                    }
                }
                if (!checked) SystemClock.sleep(100);
            }
            assertTrue("The fixture must expose two distinct names", checked);
            ins.waitForIdleSync();
            assertEquals(0, clicked.get());
        } finally { ins.runOnMainSync(activity::finish); }
    }
}
