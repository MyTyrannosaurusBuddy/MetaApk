package com.meta.app.stub;

import android.app.Activity;
import android.os.Bundle;

/**
 * Stub Activity - pre-registered in the manifest, hosting dynamic plugin UIs at runtime.
 * Different launch modes are implemented via inner classes.
 */
public class StubActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    public static class Standard extends StubActivity {}
    public static class SingleTop extends StubActivity {}
    public static class SingleTask extends StubActivity {}
}
