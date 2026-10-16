package com.gls.sim1;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

public class ConsentActivity extends Activity {
    private static final int REQ_PROJECTION = 4411;
    private String type = "screen";
    private String facing = "front";
    private final Handler timeout = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        if (in != null) {
            if (in.hasExtra("type")) type = in.getStringExtra("type");
            if (in.hasExtra("facing")) facing = in.getStringExtra("facing");
        }
        android.util.Log.i("SIM1", "ConsentActivity onCreate type=" + type);

        if (ScreenStreamer.getInstance().hasProjection()) {
            ScreenStreamer.getInstance().onConsentResolved(this, type, facing);
            finish();
            return;
        }

        timeout.postDelayed(this::finish, 30000);

        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
            android.util.Log.i("SIM1", "projection dialog launched");
        } catch (Exception e) {
            android.util.Log.e("SIM1", "projection launch failed: " + e);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        android.util.Log.i("SIM1", "ConsentActivity result rc=" + resultCode + " hasData=" + (data != null));
        if (requestCode == REQ_PROJECTION) {
            timeout.removeCallbacksAndMessages(null);
            if (resultCode == RESULT_OK && data != null) {
                ScreenStreamer.getInstance().onConsentResolved(this, type, facing, resultCode, data);
            }
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        timeout.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
