package com.meta.app.stub;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.meta.app.core.RuntimeCompat;

/**
 * Stub BroadcastReceiver.
 */
public class StubReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        RuntimeCompat.dispatchBroadcast(context, intent);
    }

    public static class R01 extends StubReceiver {}
}
