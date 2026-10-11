package com.fongmi.android.tv.server;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.view.Choreographer;
import android.view.View;
import android.view.WindowManager;
import android.view.inspector.WindowInspector;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** The server APK has one status window. All account UI belongs to the browser. */
public final class BridgeWindowGuard implements Choreographer.FrameCallback {
    private static final AtomicInteger SUPPRESSED = new AtomicInteger();
    private final Activity activity;
    private final WeakHashMap<View, Boolean> observed = new WeakHashMap<>();
    private boolean running;

    public BridgeWindowGuard(Activity activity) {
        this.activity = activity;
    }

    public static int suppressedWindows() { return SUPPRESSED.get(); }

    public void start() {
        if (running) return;
        running = true;
        suppressNow();
        Choreographer.getInstance().postFrameCallback(this);
    }

    public void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
        observed.clear();
    }

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        // Animation callbacks run before traversal/drawing, including the
        // first frame of a newly attached third-party Spider dialog.
        suppressNow();
        if (running) Choreographer.getInstance().postFrameCallback(this);
    }

    public void suppressNow() {
        if (activity.isFinishing() || activity.isDestroyed() || activity.getWindow() == null) return;
        View main = activity.getWindow().getDecorView();
        for (View root : roots()) {
            if (root == main || root.getRootView() == main || !root.isAttachedToWindow()) continue;
            if (!(root.getLayoutParams() instanceof WindowManager.LayoutParams params)) continue;
            // Only this Activity's application/sub-windows, never another
            // app, IME, system permission dialog, or system overlay.
            if (params.type < WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW
                    || params.type > WindowManager.LayoutParams.LAST_SUB_WINDOW) continue;
            root.setAlpha(0f);
            int flags = (params.flags | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                    & ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            if (params.alpha != 0f || params.dimAmount != 0f || params.flags != flags) {
                params.alpha = 0f;
                params.dimAmount = 0f;
                params.flags = flags;
                try {
                    ((WindowManager) activity.getSystemService(Context.WINDOW_SERVICE)).updateViewLayout(root, params);
                } catch (IllegalArgumentException ignored) {
                    // The Spider may have dismissed the window in this frame.
                }
            }
            if (observed.put(root, true) == null) SUPPRESSED.incrementAndGet();
            // Do not dismiss it: cancelling the Spider's dialog can cancel
            // its request or callback. Browser auth uses independent sessions.
        }
    }

    @SuppressWarnings("unchecked")
    private static List<View> roots() {
        if (Build.VERSION.SDK_INT >= 29) return WindowInspector.getGlobalWindowViews();
        try {
            Class<?> type = Class.forName("android.view.WindowManagerGlobal");
            Object manager = type.getMethod("getInstance").invoke(null);
            Field field = type.getDeclaredField("mViews");
            field.setAccessible(true);
            return new ArrayList<>((List<View>) field.get(manager));
        } catch (ReflectiveOperationException ignored) {
            return Collections.emptyList();
        }
    }
}
