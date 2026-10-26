package com.meta.app.ui;

import android.content.Context;
import android.widget.FrameLayout;

import com.meta.app.core.PluginRuntimeGuard;

/**
 * Container that hosts a plugin's view tree at the HOST boundary.
 *
 * Exceptions thrown by plugin code inside measure / layout / draw run on the
 * main thread as part of a ViewRootImpl traversal. Without this wrapper they
 * only surface in the process-wide uncaught handler, where containment means
 * re-entering {@code Looper.loop()} with the frame-callback state machine in
 * an unknown state. Catching at the boundary keeps the traversal alive, lets
 * the host detach the broken app immediately and feeds the error back to the
 * model for a fix.
 *
 * Host bugs are never swallowed: exceptions that do not originate in plugin
 * code are rethrown and follow the normal crash path.
 */
public class SafePluginContainer extends FrameLayout {

    public SafePluginContainer(Context context) {
        super(context);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        try {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        } catch (Throwable t) {
            handleBoundaryThrowable(t);
            setMeasuredDimension(0, 0);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        try {
            super.onLayout(changed, left, top, right, bottom);
        } catch (Throwable t) {
            handleBoundaryThrowable(t);
        }
    }

    @Override
    protected void dispatchDraw(android.graphics.Canvas canvas) {
        try {
            super.dispatchDraw(canvas);
        } catch (Throwable t) {
            handleBoundaryThrowable(t);
        }
    }

    @Override
    public void draw(android.graphics.Canvas canvas) {
        try {
            super.draw(canvas);
        } catch (Throwable t) {
            handleBoundaryThrowable(t);
        }
    }

    private static void handleBoundaryThrowable(Throwable t) {
        if (PluginRuntimeGuard.isFromPlugin(t)) {
            PluginRuntimeGuard.reportPluginError(t);
        } else {
            throw new RuntimeException("Host drawing failure", t);
        }
    }
}
