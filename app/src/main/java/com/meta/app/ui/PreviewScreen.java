package com.meta.app.ui;

import android.app.Presentation;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.os.Bundle;
import android.view.Display;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * An off-screen surface for the agent's interactive preview: a VirtualDisplay
 * with a Presentation hosting the plugin's view tree. Everything the plugin
 * creates - including popups and dialogs - attaches to the virtual display,
 * so the physical screen can never leak preview UI. The agent interacts with
 * the views in-process (performClick / setText / scrollBy) and captures
 * screenshots by drawing the container, exactly as before.
 */
public class PreviewScreen {

    private final DisplayManager displayManager;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private PreviewPresentation presentation;

    public PreviewScreen(Context context) {
        this.displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
    }

    /** (Re)creates the virtual display at the given size and density. */
    public void start(Context host, int width, int height) {
        stop();
        int density = host.getResources().getDisplayMetrics().densityDpi;
        imageReader = ImageReader.newInstance(width, height,
            android.graphics.PixelFormat.RGBA_8888, 2);
        virtualDisplay = displayManager.createVirtualDisplay(
            "meta-preview", width, height, density, imageReader.getSurface(), 0);
        presentation = new PreviewPresentation(host, virtualDisplay.getDisplay());
        presentation.show();
    }

    public ViewGroup getContainer() {
        return presentation != null ? presentation.getContainer() : null;
    }

    /** The display-scoped context: plugin popups/dialogs land here, invisible. */
    public Context getContext() {
        return presentation != null ? presentation.getContext() : null;
    }

    public void stop() {
        if (presentation != null) {
            try {
                presentation.dismiss();
            } catch (Throwable ignored) {
                // the window may already be gone
            }
            presentation = null;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    /**
     * Captures the REAL rendered frame of the virtual display (includes
     * everything the plugin drew: views, custom surfaces, popups), or null
     * when no frame is available yet. The fallback is drawing the container
     * directly, which misses surface-rendered content.
     */
    public android.graphics.Bitmap captureFrame() {
        if (imageReader == null) {
            return null;
        }
        android.media.Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image == null) {
                return null;
            }
            android.media.Image.Plane plane = image.getPlanes()[0];
            int width = image.getWidth();
            int height = image.getHeight();
            java.nio.ByteBuffer buffer = plane.getBuffer();
            buffer.rewind();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * width;
            android.graphics.Bitmap full = android.graphics.Bitmap.createBitmap(
                width + rowPadding / pixelStride, height,
                android.graphics.Bitmap.Config.ARGB_8888);
            full.copyPixelsFromBuffer(buffer);
            if (rowPadding == 0) {
                return full;
            }
            android.graphics.Bitmap cropped = android.graphics.Bitmap.createBitmap(
                full, 0, 0, width, height);
            full.recycle();
            return cropped;
        } catch (Throwable t) {
            android.util.Log.w("PreviewScreen", "Frame capture failed", t);
            return null;
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    private static final class PreviewPresentation extends Presentation {
        private FrameLayout container;

        PreviewPresentation(Context outer, Display display) {
            super(outer, display);
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            container = new FrameLayout(getContext());
            setContentView(container);
        }

        FrameLayout getContainer() {
            return container;
        }
    }
}
