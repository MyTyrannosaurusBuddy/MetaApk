package com.meta.app;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.gson.JsonObject;
import com.meta.app.core.PreviewUi;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Deterministic device checks; no external model or user application data. */
@RunWith(AndroidJUnit4.class)
public class AgentPlatformTest {
    @Test public void preview_canInspectTypeClickAndCaptureRenderedUi() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            EditText input = new EditText(context);
            Button button = new Button(context);
            button.setText("Save");
            button.setOnClickListener(v -> button.setText("Saved " + input.getText()));
            root.addView(input); root.addView(button);
            root.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 640, 800);
            assertTrue(PreviewUi.dump(root).contains("path=0/1"));
            JsonObject args = new JsonObject(); args.addProperty("path", "0/0"); args.addProperty("text", "hello");
            PreviewUi.perform(root, "input_text", args);
            args.addProperty("path", "0/1");
            PreviewUi.perform(root, "click", args);
            assertTrue(PreviewUi.dump(root).contains("Saved hello"));
            assertNotNull(PreviewUi.screenshot(root));
        });
    }
}
