package com.meta.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

/**
 * Minimal 15 Hz progress bar: the stock indeterminate ProgressBar animates
 * at the display refresh rate (60/120 Hz), which burns CPU pointlessly
 * during long agent sessions. This one redraws a simple sweeping bar every
 * 66 ms and sleeps in between - visually the same "working" signal at a
 * fraction of the cost.
 */
public class ThrottledProgressBar extends View {

    private static final int TICK_MS = 66; // ~15 Hz

    private final Paint trackPaint = new Paint();
    private final Paint barPaint = new Paint();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int phase;
    private boolean running;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running || getVisibility() != VISIBLE) {
                return;
            }
            phase = (phase + 1) % 100;
            invalidate();
            handler.postDelayed(this, TICK_MS);
        }
    };

    public ThrottledProgressBar(Context context) {
        super(context);
        trackPaint.setColor(0x33FFFFFF);
        barPaint.setColor(0xFF6200EE);
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE && !running) {
            running = true;
            phase = 0;
            handler.removeCallbacks(tick);
            handler.post(tick);
        } else if (visibility != VISIBLE && running) {
            running = false;
            handler.removeCallbacks(tick);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        handler.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        canvas.drawRect(0, 0, w, h, trackPaint);
        float barWidth = w * 0.35f;
        float x = (phase / 100f) * (w + barWidth) - barWidth;
        canvas.drawRect(x, 0, x + barWidth, h, barPaint);
    }
}
