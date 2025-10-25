package com.limelight.utils;

import android.app.Activity;
import android.view.SurfaceView;
import android.view.TextureView;

import java.io.Closeable;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

/**
 * FSRSizerInstaller — facade to attach a size keeper to a TextureView/SurfaceView
 * and optionally notify your renderer when the presentation size changes.
 *
 * The goal is to keep the post-FSR render target at the display's pixel size
 * to avoid a second scale in SurfaceFlinger.
 */
public final class FSRSizerInstaller implements Closeable {

    /** Simple callback used to forward size changes to a renderer. */
    public interface SizeListener {
        void onPresentationSizeChanged(int width, int height);
    }

    // Reflection bridge for users passing a renderer instance directly.
    private static final String[] KNOWN_METHODS = new String[] {
            "onPresentationSizeChanged",
            "setPresentationSize",
            "setOutputSize",
            "setRenderTargetSize"
    };

    private final WeakReference<Activity> activityRef;
    private final WeakReference<Object> rendererRef; // optional
    private final SizeListener sizeListener;         // optional

    private Closeable delegate; // TextureViewSizer or SurfaceViewSizer

    private FSRSizerInstaller(Activity activity, Object renderer, SizeListener listener) {
        this.activityRef = new WeakReference<>(activity);
        this.rendererRef = new WeakReference<>(renderer);
        this.sizeListener = listener;
    }

    public static FSRSizerInstaller attach(Activity activity, TextureView tv, Object rendererOrNull) {
        FSRSizerInstaller inst = new FSRSizerInstaller(activity, rendererOrNull, null);
        inst.delegate = inst.installFor(tv);
        return inst;
    }

    public static FSRSizerInstaller attach(Activity activity, SurfaceView sv, Object rendererOrNull) {
        FSRSizerInstaller inst = new FSRSizerInstaller(activity, rendererOrNull, null);
        inst.delegate = inst.installFor(sv);
        return inst;
    }

    public static FSRSizerInstaller attach(Activity activity, TextureView tv, SizeListener listener) {
        FSRSizerInstaller inst = new FSRSizerInstaller(activity, null, listener);
        inst.delegate = inst.installFor(tv);
        return inst;
    }

    public static FSRSizerInstaller attach(Activity activity, SurfaceView sv, SizeListener listener) {
        FSRSizerInstaller inst = new FSRSizerInstaller(activity, null, listener);
        inst.delegate = inst.installFor(sv);
        return inst;
    }

    private Closeable installFor(TextureView tv) {
        final SizeListener cb = buildCallback();
        TextureViewSizer sizer = new TextureViewSizer(tv, (TextureViewSizer.OnPresentationSizeChanged) cb);
        sizer.start();
        return sizer;
    }

    private Closeable installFor(SurfaceView sv) {
        final SizeListener cb = buildCallback();
        SurfaceViewSizer sizer = new SurfaceViewSizer(sv, (SurfaceViewSizer.OnPresentationSizeChanged) cb);
        sizer.start();
        return sizer;
    }

    private SizeListener buildCallback() {
        if (sizeListener != null) return sizeListener;

        // If renderer instance present, create a reflection-based bridge.
        final Object renderer = rendererRef.get();
        if (renderer == null) {
            return (w, h) -> { /* no-op */ };
        }
        final Method m = findRendererMethod(renderer);
        if (m == null) {
            return (w, h) -> { /* no-op */ };
        }
        return (w, h) -> {
            try {
                m.invoke(renderer, w, h);
            } catch (Throwable ignored) { }
        };
    }

    private static Method findRendererMethod(Object renderer) {
        for (String name : KNOWN_METHODS) {
            try {
                Method m = renderer.getClass().getMethod(name, int.class, int.class);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        if (delegate != null) {
            delegate.close();
            delegate = null;
        }
    }
}
