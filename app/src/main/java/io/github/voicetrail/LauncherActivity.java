package io.github.voicetrail;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Export only a launcher. Private command extras are never forwarded from other apps. */
public final class LauncherActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}

