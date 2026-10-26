package com.meta.app.stub;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * Stub Service - pre-registered in the manifest.
 */
public class StubService extends Service {
    @Override
    public IBinder onBind(Intent intent) { return null; }

    public static class S01 extends StubService {}
    public static class S02 extends StubService {}
}
