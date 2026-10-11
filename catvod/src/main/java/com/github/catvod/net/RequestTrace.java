package com.github.catvod.net;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Deque;

import okhttp3.Request;

/**
 * Short-lived diagnostic trace for porting an Android-only Spider.  It keeps
 * transport metadata only; response bodies are deliberately never retained.
 */
public final class RequestTrace {

    private static final int LIMIT = 80;
    private static final Deque<JSONObject> ENTRIES = new ArrayDeque<>();

    private RequestTrace() {
    }

    public static synchronized void record(Request request, int status, long elapsedMs, String error) {
        try {
            JSONObject item = new JSONObject();
            item.put("at", System.currentTimeMillis());
            item.put("method", request.method());
            item.put("url", request.url().toString());
            item.put("status", status);
            item.put("elapsedMs", elapsedMs);
            if (error != null && !error.isEmpty()) item.put("error", error);
            ENTRIES.addLast(item);
            while (ENTRIES.size() > LIMIT) ENTRIES.removeFirst();
        } catch (Exception ignored) {
        }
    }

    public static synchronized JSONObject snapshot(boolean clear) {
        JSONObject result = new JSONObject();
        JSONArray entries = new JSONArray();
        for (JSONObject item : ENTRIES) entries.put(item);
        try {
            result.put("entries", entries);
            result.put("count", entries.length());
        } catch (Exception ignored) {
        }
        if (clear) ENTRIES.clear();
        return result;
    }
}
