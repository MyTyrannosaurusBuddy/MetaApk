package com.meta.app;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static com.meta.app.UiTestUtils.UI_TIMEOUT_MS;
import static com.meta.app.UiTestUtils.clickIfPresent;
import static com.meta.app.UiTestUtils.generateApp;
import static com.meta.app.UiTestUtils.launchHost;
import static com.meta.app.UiTestUtils.waitForDisplayValue;

/**
 * Runtime permission round trip: a generated app requests the camera through
 * the ApiBridge, the host shows the system permission dialog (cross-app
 * UIAutomator coverage), and granting it reaches the plugin callback (the
 * app reports GRANTED / DENIED).
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class PermissionBridgeTest {

    private static final String ALLOW_BUTTON_RES =
        "com.android.permissioncontroller:id/permission_allow_foreground_only_button";

    private UiDevice device;
    private Context targetContext;

    @Before
    public void setUp() throws Exception {
        targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        launchHost(device, targetContext);
    }

    @Test
    public void requestCameraPermission_bridgeCallbackReceivesResult() throws Exception {
        // The app requests the camera on startup, so the permission chain
        // runs without depending on any model-chosen button label.
        generateApp(device, targetContext,
            "an app that requests camera permission immediately in onCreate via "
                + "bridge.requestPermissions and shows whether it was granted");

        // Best-effort: if the model added a permission button anyway, click it.
        String[] candidates = {"RequestCamera", "Camera", "Request", "Grant", "Ask"};
        for (String candidate : candidates) {
            if (clickIfPresent(device,
                    By.clazz(android.widget.Button.class).textContains(candidate), 5_000)) {
                break;
            }
        }

        // Fresh requests show the system dialog; an earlier grant makes the
        // bridge fire the callback immediately.
        UiObject2 allow = device.wait(Until.findObject(By.res(ALLOW_BUTTON_RES)), 15_000);
        if (allow != null) {
            allow.click();
        }
        // The plugin reports the outcome (model words it freely:
        // granted/denied/allowed, English or Chinese).
        waitForDisplayValue(device,
            "(?i).*(grant|deni|allow|已授权|已拒绝|允许|拒绝).*", 90_000);
    }
}
