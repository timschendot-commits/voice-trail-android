package io.github.voicetrail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class NativeCoreInstrumentedTest {
    @Test
    public void packagedNativeLibraryGovernsAReadSession() {
        String id = NativeCore.start("read_screen");
        assertFalse(id == null || id.isEmpty());
        try {
            assertTrue(NativeCore.event(id, "step_start"));
            assertEquals(1, NativeCore.dispatch(id, "read_nodes"));
            assertEquals(0, NativeCore.dispatch(id, "share_photo"));
            assertTrue(NativeCore.event(id, "step_done"));
            assertTrue(NativeCore.event(id, "done"));
        } finally {
            NativeCore.close(id);
        }
        assertEquals(0, NativeCore.dispatch(id, "read_nodes"));
    }

    @Test
    public void sensitiveRequestsStayInUserHands() {
        String id = NativeCore.start("manual_handoff");
        assertFalse(id == null || id.isEmpty());
        try {
            assertEquals(3, NativeCore.dispatch(id, "pay"));
        } finally {
            NativeCore.close(id);
        }
    }
}
