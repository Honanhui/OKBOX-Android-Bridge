package com.github.catvod.crawler;

import android.text.TextUtils;

import com.orhanobut.logger.Logger;

import org.json.JSONArray;

import java.util.ArrayDeque;
import java.util.Deque;

public class SpiderDebug {

    private static final String TAG = SpiderDebug.class.getSimpleName();
    private static final int LIMIT = 120;
    private static final int MAX_MESSAGE_LENGTH = 1600;
    private static final Deque<String> ENTRIES = new ArrayDeque<>();

    public static void log(Throwable th) {
        if (th != null) {
            record(th.getClass().getSimpleName() + ": " + String.valueOf(th.getMessage()));
            th.printStackTrace();
        }
    }

    public static void log(String msg) {
        if (!TextUtils.isEmpty(msg)) {
            record(msg);
            Logger.t(TAG).d(msg);
        }
    }

    public static void log(String tag, String msg, Object... args) {
        if (!TextUtils.isEmpty(msg)) {
            record(msg);
            Logger.t(tag).d(msg, args);
        }
    }

    /** TVBoxOS ABI hook used by some external Spider JARs. */
    public static String ec(int i) {
        return "";
    }

    /**
     * External DEX spiders commonly catch their own errors and only call
     * SpiderDebug. Keeping a short in-memory ring lets the Bridge expose the
     * same evidence through /api/trace without persisting tokens or bodies.
     */
    public static synchronized JSONArray snapshot(boolean clear) {
        JSONArray result = new JSONArray();
        for (String entry : ENTRIES) result.put(entry);
        if (clear) ENTRIES.clear();
        return result;
    }

    private static synchronized void record(String message) {
        if (TextUtils.isEmpty(message)) return;
        String item = message.length() > MAX_MESSAGE_LENGTH ? message.substring(0, MAX_MESSAGE_LENGTH) + "…" : message;
        ENTRIES.addLast(item);
        while (ENTRIES.size() > LIMIT) ENTRIES.removeFirst();
    }
}
