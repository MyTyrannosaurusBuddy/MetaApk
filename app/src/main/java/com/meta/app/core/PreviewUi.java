package com.meta.app.core;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.Base64;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;

/** UI-thread inspection and interaction restricted to the generated app's view tree. */
public final class PreviewUi {
    private PreviewUi() { }

    public static String dump(View root) {
        StringBuilder result = new StringBuilder();
        append(root, "0", result);
        return result.toString();
    }

    private static void append(View view, String path, StringBuilder result) {
        if (result.length() > 24 * 1024) return;
        result.append("path=").append(path).append(' ').append(view.getClass().getSimpleName());
        if (view instanceof TextView) {
            String text = String.valueOf(((TextView) view).getText());
            if (text.length() > 1024) text = text.substring(0, 1024) + "...";
            result.append(" text=").append(new com.google.gson.Gson().toJson(text));
        }
        result.append(" visible=").append(view.getVisibility() == View.VISIBLE)
            .append(" enabled=").append(view.isEnabled()).append(" clickable=").append(view.isClickable())
            .append(" size=").append(view.getWidth()).append('x').append(view.getHeight()).append('\n');
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) append(group.getChildAt(i), path + "/" + i, result);
        }
    }

    public static void perform(View root, String action, JsonObject args) {
        if ("inspect_ui".equals(action)) return;
        String path = args.get("path").getAsString();
        String[] parts = path.split("/");
        if (parts.length == 0 || !"0".equals(parts[0])) throw new IllegalArgumentException("Invalid view path: " + path);
        View target = root;
        for (int i = 1; i < parts.length; i++) {
            int child = Integer.parseInt(parts[i]);
            if (!(target instanceof ViewGroup) || child < 0 || child >= ((ViewGroup) target).getChildCount()) {
                throw new IllegalArgumentException("Stale view path; inspect_ui again: " + path);
            }
            target = ((ViewGroup) target).getChildAt(child);
        }
        if (!target.isEnabled() || target.getVisibility() != View.VISIBLE) {
            throw new IllegalArgumentException("Target is hidden or disabled: " + path);
        }
        switch (action) {
            case "click":
                // Inject a REAL touch (DOWN/UP pair) instead of performClick():
                // generated apps overwhelmingly handle interaction in
                // onTouchEvent (custom views, Canvas games), a path that
                // performClick() never reaches. Dispatching the same event
                // sequence a finger produces exercises onTouchEvent AND the
                // standard OnClickListener path.
                injectTap(target);
                break;
            case "input_text":
                if (!(target instanceof EditText)) throw new IllegalArgumentException("Target is not an editable field");
                ((EditText) target).setText(args.get("text").getAsString());
                break;
            case "scroll":
                int dy = Math.max(-4000, Math.min(4000, Integer.parseInt(args.get("dy").getAsString())));
                if (!injectSwipe(target, dy)) {
                    target.scrollBy(0, dy);
                }
                break;
            default: throw new IllegalArgumentException("Unknown preview action: " + action);
        }
    }

    /** A real finger tap: DOWN then UP at the view center, on the main thread. */
    private static void injectTap(View view) {
        long now = android.os.SystemClock.uptimeMillis();
        float x = view.getWidth() / 2f;
        float y = view.getHeight() / 2f;
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 16, MotionEvent.ACTION_UP, x, y, 0);
        try {
            view.dispatchTouchEvent(down);
            view.dispatchTouchEvent(up);
        } finally {
            down.recycle();
            up.recycle();
        }
        // A view NOT attached to a window queues its click action through
        // View.post -> RunQueue, where it never runs. For such views the tap
        // above reaches onTouchEvent but not OnClickListener, so trigger the
        // listener directly - exactly once, only when it exists.
        if (view.getHandler() == null && view.hasOnClickListeners()) {
            view.performClick();
        }
    }

    /**
     * A real vertical swipe (DOWN -> moves -> UP). Views that consume the
     * sequence in onTouchEvent (custom scrollables) get it directly; when the
     * view leaves the events untouched, returns false so the caller can fall
     * back to scrollBy() for framework scroll containers.
     */
    private static boolean injectSwipe(View view, int dy) {
        long now = android.os.SystemClock.uptimeMillis();
        float x = view.getWidth() / 2f;
        float startY = view.getHeight() / 2f;
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, startY, 0);
        boolean handled = view.dispatchTouchEvent(down);
        down.recycle();
        int steps = Math.max(2, Math.min(8, Math.abs(dy) / 40));
        float stepDy = dy / (float) steps;
        boolean consumed = handled;
        for (int i = 1; i <= steps; i++) {
            float y = startY + stepDy * i;
            MotionEvent move = MotionEvent.obtain(now + i * 8, now + i * 8, MotionEvent.ACTION_MOVE, x, y, 0);
            consumed |= view.dispatchTouchEvent(move);
            move.recycle();
        }
        MotionEvent up = MotionEvent.obtain(now + steps * 8 + 8, now + steps * 8 + 8,
            MotionEvent.ACTION_UP, x, startY + dy, 0);
        consumed |= view.dispatchTouchEvent(up);
        up.recycle();
        return consumed;
    }

    public static String screenshot(View view) {
        if (view.getWidth() <= 0 || view.getHeight() <= 0) return null;
        float scale = Math.min(1f, 960f / Math.max(view.getWidth(), view.getHeight()));
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, (int) (view.getWidth() * scale)),
            Math.max(1, (int) (view.getHeight() * scale)), Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.scale(scale, scale);
            view.draw(canvas);
            return encodeBitmap(bitmap);
        } finally {
            bitmap.recycle();
        }
    }

    /** JPEG at the model-friendly size, base64-encoded. */
    public static String encodeBitmap(Bitmap source) {
        Bitmap scaled = source;
        float scale = Math.min(1f, 960f / Math.max(1, Math.max(source.getWidth(), source.getHeight())));
        if (scale < 1f) {
            scaled = Bitmap.createScaledBitmap(source,
                Math.max(1, (int) (source.getWidth() * scale)),
                Math.max(1, (int) (source.getHeight() * scale)), true);
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 65, output);
            return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
        } finally {
            if (scaled != source) {
                scaled.recycle();
            }
        }
    }
}
