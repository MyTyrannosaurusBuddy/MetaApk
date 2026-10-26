package com.meta.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Compatible with standard Android examples: many models assume the app entry point
 * is named MainActivity. This lightweight alias Activity forwards explicit jumps
 * to the real host entry, HostActivity.
 *
 * Note: it intentionally avoids referencing HostActivity.class directly, so that
 * HostActivity does not become a hard dependency of the dynamic plugin compile classpath.
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent hostIntent = new Intent();
        hostIntent.setClassName(this, "com.meta.app.ui.HostActivity");
        hostIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        Intent source = getIntent();
        if (source != null) {
            hostIntent.setAction(source.getAction());
            hostIntent.setData(source.getData());
            if (source.getExtras() != null) {
                hostIntent.putExtras(source.getExtras());
            }
        }

        startActivity(hostIntent);
        finish();
    }
}
