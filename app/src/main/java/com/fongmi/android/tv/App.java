package com.fongmi.android.tv;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.os.HandlerCompat;

import com.fongmi.hook.Hook;
import com.github.catvod.Init;
import com.github.catvod.bean.Doh;
import com.github.catvod.net.OkHttp;
import com.google.gson.Gson;

public class App extends Application implements Application.ActivityLifecycleCallbacks {

    private static final String TAG = "OKBOX-Bridge";

    private static volatile App instance;

    private final Handler handler;
    private final Gson gson;
    private final long time;

    private Activity activity;
    private Hook hook;

    public App() {
        instance = this;
        gson = new Gson();
        time = System.currentTimeMillis();
        handler = HandlerCompat.createAsync(Looper.getMainLooper());
    }

    public static App get() {
        return instance;
    }

    public static Gson gson() {
        return get().gson;
    }

    public static long time() {
        return get().time;
    }

    public static Activity activity() {
        return get().activity;
    }

    public static void post(Runnable runnable) {
        get().handler.post(runnable);
    }

    public static void post(Runnable runnable, long delayMillis) {
        get().handler.removeCallbacks(runnable);
        if (delayMillis >= 0) get().handler.postDelayed(runnable, delayMillis);
    }

    public static void removeCallbacks(Runnable runnable) {
        get().handler.removeCallbacks(runnable);
    }

    public static void removeCallbacks(Runnable... runnable) {
        for (Runnable r : runnable) get().handler.removeCallbacks(r);
    }

    public void setHook(Hook hook) {
        this.hook = hook;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Init.set(base);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // The mobile Bridge intentionally removes AndroidX Startup. OkHttp 5
        // normally initializes its Android asset provider through that
        // initializer; without this call cookie/domain handling cannot open
        // PublicSuffixDatabase.list and every subscription request fails.
        okhttp3.OkHttp.INSTANCE.initialize(this);
        // The bridge APK removes the regular TVBox Startup initializer, so
        // configure the TVBox-style DoH fallback here for this server-only
        // process. It is used for the first subscription request as well.
        OkHttp.dns().setFallbackDoh(Doh.get(this));
        containBrokenSourceProxy();
        registerActivityLifecycleCallbacks(this);
    }

    /**
     * A protected warehouse may download its optional Go proxy at runtime.
     * When that upstream object has expired, the source currently saves the
     * XML error body as libwexproxy.so and throws an UnsatisfiedLinkError from
     * its own worker thread. Keep that one optional worker failure from
     * terminating the Bridge process; normal application crashes still go to
     * the original crash handler.
     */
    private void containBrokenSourceProxy() {
        Thread.UncaughtExceptionHandler original = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            if (isBrokenSourceProxy(error)) {
                Log.e(TAG, "Source GoProxy is unavailable; Bridge remains online", error);
                return;
            }
            if (original != null) original.uncaughtException(thread, error);
        });
    }

    private static boolean isBrokenSourceProxy(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (!(current instanceof UnsatisfiedLinkError)) continue;
            for (StackTraceElement frame : current.getStackTrace()) {
                if ("com.github.catvod.spider.GoProxy".equals(frame.getClassName())) return true;
            }
        }
        return false;
    }

    @Override
    public PackageManager getPackageManager() {
        return hook != null ? hook : getBaseContext().getPackageManager();
    }

    @Override
    public String getPackageName() {
        return hook != null ? hook.getPackageName() : getBaseContext().getPackageName();
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        if (activity != activity()) this.activity = activity;
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        if (activity == activity()) this.activity = null;
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
    }
}
