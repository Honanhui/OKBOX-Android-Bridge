package com.fongmi.android.tv.server;

import android.content.Context;
import android.content.SharedPreferences;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.Task;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.HttpUrl;

/**
 * Bridge-owned TVBox warehouse catalogue.
 *
 * Each imported subscription is fetched and normalised independently. The
 * stable /sub/okbox.json endpoint is only a multi-warehouse directory; it
 * never combines sites from unrelated subscriptions into one configuration.
 */
public final class BridgeSubscriptions {

    private static final String PREFS = "bridge_subscriptions";
    private static final String KEY_ITEMS = "items_v1";
    private static final String KEY_ACTIVE_ID = "active_id";
    private static final String KEY_REVISION = "active_revision";
    private static final String KEY_PENDING_ID = "pending_selection_id";
    private static final int MAX_ITEMS = 80;

    private static final Map<String, JSONObject> CONFIGS = new LinkedHashMap<>();
    private static final Map<String, String> STATES = new LinkedHashMap<>();
    private static final Map<String, String> ERRORS = new LinkedHashMap<>();
    private static final Map<String, Integer> SITE_COUNTS = new LinkedHashMap<>();
    private static final Map<String, Long> REQUESTS = new LinkedHashMap<>();
    private static final AtomicLong REQUEST_ID = new AtomicLong();

    private BridgeSubscriptions() {
    }

    public static synchronized JSONObject catalog() throws Exception {
        JSONArray output = new JSONArray();
        JSONArray items = read();
        String activeId = validActiveId(items);
        for (int i = 0; i < items.length(); i++) output.put(publicItem(items.getJSONObject(i), activeId));
        return new JSONObject()
                .put("format", "okbox-bridge-warehouse/v3")
                .put("activeId", activeId)
                .put("revision", revision())
                .put("sources", output)
                .put("bridge", BridgeConfig.status());
    }

    public static synchronized JSONObject save(String id, String name, String url) throws Exception {
        String address = url == null ? "" : url.trim();
        HttpUrl parsed = HttpUrl.parse(address);
        if (parsed == null || !("http".equalsIgnoreCase(parsed.scheme()) || "https".equalsIgnoreCase(parsed.scheme()))) {
            throw new IllegalArgumentException("订阅地址必须是 HTTP 或 HTTPS 地址");
        }
        JSONArray items = read();
        String sourceId = id == null ? "" : id.trim();
        if (sourceId.isEmpty()) sourceId = UUID.randomUUID().toString();
        JSONObject item = new JSONObject()
                .put("id", sourceId)
                .put("name", displayName(name, parsed))
                .put("url", parsed.toString())
                .put("updatedAt", System.currentTimeMillis());
        boolean replaced = false;
        for (int i = 0; i < items.length(); i++) {
            if (sourceId.equals(items.getJSONObject(i).optString("id"))) {
                JSONObject previous = items.getJSONObject(i);
                if (parsed.toString().equals(previous.optString("url"))) copyLastResult(previous, item);
                items.put(i, item);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            if (items.length() >= MAX_ITEMS) throw new IllegalArgumentException("订阅源最多保存 " + MAX_ITEMS + " 条");
            items.put(item);
        }
        write(items);
        CONFIGS.remove(sourceId);
        ERRORS.remove(sourceId);
        SITE_COUNTS.remove(sourceId);
        STATES.put(sourceId, "pending");
        if (validActiveId(items).isEmpty()) prefs().edit().putString(KEY_ACTIVE_ID, sourceId).apply();
        refresh(sourceId, null);
        return catalog();
    }

    public static synchronized JSONObject remove(String id) throws Exception {
        JSONArray items = read();
        JSONArray kept = new JSONArray();
        boolean deleted = false;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (id.equals(item.optString("id"))) deleted = true;
            else kept.put(item);
        }
        if (!deleted) throw new IllegalArgumentException("订阅源不存在");
        write(kept);
        CONFIGS.remove(id);
        STATES.remove(id);
        ERRORS.remove(id);
        SITE_COUNTS.remove(id);
        REQUESTS.remove(id);
        if (id.equals(prefs().getString(KEY_ACTIVE_ID, ""))) {
            String next = kept.length() == 0 ? "" : kept.getJSONObject(0).optString("id");
            prefs().edit().putString(KEY_ACTIVE_ID, next).apply();
        }
        if (kept.length() == 0) BridgeConfig.clearSourceLoad();
        return catalog();
    }

    /** Fetches one source into a Bridge-owned configuration without switching runtime. */
    public static void refresh(String id, RefreshListener listener) {
        final BridgeConfigMerger.Source source;
        final long requestId;
        try {
            synchronized (BridgeSubscriptions.class) {
                JSONObject item = find(id);
                if (item == null) throw new IllegalArgumentException("订阅源不存在");
                source = toSource(item);
                requestId = REQUEST_ID.incrementAndGet();
                REQUESTS.put(id, requestId);
                // Keep a previously successful row stable while it refreshes
                // in the background. The current configuration stays usable
                // until the replacement has completed.
                if (!"ready".equals(item.optString("lastState"))) STATES.put(id, "fetching");
                ERRORS.remove(id);
            }
        } catch (Throwable error) {
            if (listener != null) listener.error(readable(error));
            return;
        }
        Task.execute(() -> {
            try {
                JSONObject config = BridgeConfigMerger.single(source);
                JSONArray sites = config.optJSONArray("sites");
                int count = sites == null ? 0 : sites.length();
                synchronized (BridgeSubscriptions.class) {
                    if (!isCurrent(id, requestId)) return;
                    CONFIGS.put(id, config);
                    SITE_COUNTS.put(id, count);
                    STATES.put(id, "ready");
                    ERRORS.remove(id);
                    persistResult(id, "ready", count, "");
                }
                if (listener != null) listener.success(count);
            } catch (Throwable error) {
                String message = readable(error);
                synchronized (BridgeSubscriptions.class) {
                    if (!isCurrent(id, requestId)) return;
                    CONFIGS.remove(id);
                    SITE_COUNTS.remove(id);
                    STATES.put(id, "error");
                    ERRORS.put(id, message);
                    persistResult(id, "error", 0, message);
                }
                if (listener != null) listener.error(message);
            }
        });
    }

    /** Refreshes every warehouse independently; failures remain attached to their row. */
    public static synchronized void refreshAll() throws Exception {
        JSONArray items = read();
        for (int i = 0; i < items.length(); i++) refresh(items.getJSONObject(i).optString("id"), null);
    }

    /** Switches the Android Spider runtime to exactly one processed warehouse. */
    public static void select(String id, BridgeConfig.Listener listener) {
        final String name;
        final boolean cached;
        try {
            synchronized (BridgeSubscriptions.class) {
                JSONObject item = find(id);
                if (item == null) throw new IllegalArgumentException("订阅源不存在");
                name = item.optString("name");
                cached = CONFIGS.containsKey(id);
            }
        } catch (Throwable error) {
            if (listener != null) listener.error(readable(error));
            return;
        }
        BridgeConfig.beginSourceLoad(name, Server.get().getAddress("/sub/" + id + ".json"));
        if (cached) {
            loadSelected(id, name, listener);
            return;
        }
        refresh(id, new RefreshListener() {
            @Override public void success(int ignored) {
                loadSelected(id, name, listener);
            }

            @Override public void error(String message) {
                BridgeConfig.failSourceLoad(name, message);
                if (listener != null) listener.error(message);
            }
        });
    }

    private static void loadSelected(String id, String name, BridgeConfig.Listener listener) {
        // Keep the old warehouse durable until the new configuration itself
        // has loaded. Do not execute a third-party Spider merely to validate
        // a switch: some protected warehouses start native proxy code while
        // their Jar is opened and can terminate the whole Bridge process.
        if (!prefs().edit().putString(KEY_PENDING_ID, id).commit()) {
            String message = "无法保存订阅源切换状态";
            BridgeConfig.failSourceLoad(name, message);
            if (listener != null) listener.error(message);
            return;
        }
        BridgeConfig.load(Server.get().getAddress("/sub/" + id + ".json"), new BridgeConfig.Listener() {
            @Override public void success(int count) {
                finishSelected(id, name, count, listener);
            }

            @Override public void error(String message) {
                clearPending(id);
                BridgeConfig.failSourceLoad(name, message);
                if (listener != null) listener.error(message);
            }
        });
    }

    private static void finishSelected(String id, String name, int count, BridgeConfig.Listener listener) {
        try {
            synchronized (BridgeSubscriptions.class) {
                SharedPreferences preferences = prefs();
                if (!id.equals(preferences.getString(KEY_PENDING_ID, ""))) return;
                if (!preferences.edit()
                        .putString(KEY_ACTIVE_ID, id)
                        .putLong(KEY_REVISION, revision() + 1L)
                        .remove(KEY_PENDING_ID)
                        .commit()) {
                    throw new IllegalStateException("无法保存当前订阅源");
                }
            }
            if (listener != null) listener.success(count);
        } catch (Throwable error) {
            String message = readable(error);
            synchronized (BridgeSubscriptions.class) {
                clearPending(id);
                STATES.put(id, "error");
                ERRORS.put(id, message);
            }
            BridgeConfig.failSourceLoad(name, message);
            if (listener != null) listener.error(message);
        }
    }

    private static void clearPending(String id) {
        SharedPreferences preferences = prefs();
        if (id.equals(preferences.getString(KEY_PENDING_ID, ""))) {
            preferences.edit().remove(KEY_PENDING_ID).commit();
        }
    }

    /** Restores the selected warehouse, then checks the remaining rows in background. */
    public static synchronized boolean loadActive(BridgeConfig.Listener listener) throws Exception {
        JSONArray items = read();
        String activeId = validActiveId(items);
        String interrupted = prefs().getString(KEY_PENDING_ID, "");
        if (!interrupted.isEmpty()) {
            // The previous process ended after beginning this selection but
            // before configuration loading completed. Keep the old active
            // source when possible; an
            // old build may already have persisted the bad source, in which
            // case choose the first different warehouse as the recovery.
            if (interrupted.equals(activeId)) {
                for (int i = 0; i < items.length(); i++) {
                    String candidate = items.getJSONObject(i).optString("id");
                    if (!candidate.isEmpty() && !candidate.equals(interrupted)) {
                        activeId = candidate;
                        break;
                    }
                }
            }
            prefs().edit().putString(KEY_ACTIVE_ID, activeId).remove(KEY_PENDING_ID).commit();
            STATES.put(interrupted, "error");
            ERRORS.put(interrupted, "上次加载该源时 Bridge 意外退出，已恢复到其他订阅源");
        }
        if (activeId.isEmpty()) return false;
        select(activeId, listener);
        for (int i = 0; i < items.length(); i++) {
            String id = items.getJSONObject(i).optString("id");
            if (!activeId.equals(id)) refresh(id, null);
        }
        return true;
    }

    /** Standard TVBox multi-warehouse directory, backed only by local Bridge configs. */
    public static synchronized JSONObject manifest(String baseUrl) throws Exception {
        JSONArray items = read();
        String activeId = validActiveId(items);
        JSONObject result = new JSONObject()
                .put("format", "okbox-bridge-warehouse/v3")
                .put("name", "OKBOX Bridge 多仓")
                .put("bridge", baseUrl)
                .put("activeId", activeId)
                .put("revision", revision());
        JSONArray urls = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            urls.put(new JSONObject()
                    .put("id", item.optString("id"))
                    .put("name", item.optString("name"))
                    .put("url", baseUrl + "/sub/" + item.optString("id") + ".json"));
        }
        return result.put("urls", urls);
    }

    public static synchronized JSONObject config(String id) throws Exception {
        JSONObject item = find(id);
        if (item == null) throw new IllegalArgumentException("订阅源不存在");
        JSONObject cached = CONFIGS.get(id);
        if (cached != null) return new JSONObject(cached.toString());
        try {
            JSONObject result = BridgeConfigMerger.single(toSource(item));
            CONFIGS.put(id, result);
            JSONArray sites = result.optJSONArray("sites");
            SITE_COUNTS.put(id, sites == null ? 0 : sites.length());
            STATES.put(id, "ready");
            ERRORS.remove(id);
            persistResult(id, "ready", sites == null ? 0 : sites.length(), "");
            return new JSONObject(result.toString());
        } catch (Throwable error) {
            String message = readable(error);
            STATES.put(id, "error");
            ERRORS.put(id, message);
            persistResult(id, "error", 0, message);
            throw new IllegalStateException(message);
        }
    }

    public interface RefreshListener {
        void success(int count);
        void error(String message);
    }

    private static JSONObject publicItem(JSONObject item, String activeId) throws Exception {
        String id = item.optString("id");
        String persistedState = item.optString("lastState", "pending");
        int persistedCount = item.optInt("lastSiteCount", 0);
        String persistedError = item.optString("lastError", "");
        return new JSONObject()
                .put("id", id)
                .put("name", item.optString("name"))
                .put("url", item.optString("url"))
                .put("updatedAt", item.optLong("updatedAt"))
                .put("active", id.equals(activeId))
                .put("state", STATES.getOrDefault(id, persistedState))
                .put("siteCount", SITE_COUNTS.getOrDefault(id, persistedCount))
                .put("error", ERRORS.getOrDefault(id, persistedError));
    }

    private static void copyLastResult(JSONObject source, JSONObject target) throws Exception {
        if (source.has("lastState")) target.put("lastState", source.optString("lastState"));
        if (source.has("lastSiteCount")) target.put("lastSiteCount", source.optInt("lastSiteCount"));
        if (source.has("lastError")) target.put("lastError", source.optString("lastError"));
        if (source.has("lastFetchedAt")) target.put("lastFetchedAt", source.optLong("lastFetchedAt"));
    }

    /** Persists the last completed fetch so process restarts do not reset every row to pending. */
    private static void persistResult(String id, String state, int count, String error) {
        try {
            JSONArray items = read();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                if (!id.equals(item.optString("id"))) continue;
                item.put("lastState", state)
                        .put("lastSiteCount", count)
                        .put("lastError", error == null ? "" : error)
                        .put("lastFetchedAt", System.currentTimeMillis());
                write(items);
                return;
            }
        } catch (Throwable ignored) {
            // In-memory status remains authoritative for this process. A
            // storage failure must not turn a successful fetch into failure.
        }
    }

    private static boolean isCurrent(String id, long requestId) {
        return Long.valueOf(requestId).equals(REQUESTS.get(id));
    }

    private static JSONObject find(String id) throws Exception {
        JSONArray items = read();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (id.equals(item.optString("id"))) return item;
        }
        return null;
    }

    private static String validActiveId(JSONArray items) throws Exception {
        String activeId = prefs().getString(KEY_ACTIVE_ID, "");
        for (int i = 0; i < items.length(); i++) {
            if (activeId.equals(items.getJSONObject(i).optString("id"))) return activeId;
        }
        if (items.length() == 0) return "";
        activeId = items.getJSONObject(0).optString("id");
        prefs().edit().putString(KEY_ACTIVE_ID, activeId).apply();
        return activeId;
    }

    private static String displayName(String name, HttpUrl url) {
        String value = name == null ? "" : name.trim();
        return value.isEmpty() ? url.host() : value;
    }

    private static long revision() {
        return prefs().getLong(KEY_REVISION, 0L);
    }

    private static JSONArray read() throws Exception {
        String value = prefs().getString(KEY_ITEMS, "");
        return value.isEmpty() ? new JSONArray() : new JSONArray(value);
    }

    private static void write(JSONArray items) {
        if (!prefs().edit().putString(KEY_ITEMS, items.toString()).commit()) throw new IllegalStateException("无法保存订阅源");
    }

    private static SharedPreferences prefs() {
        return App.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static BridgeConfigMerger.Source toSource(JSONObject item) {
        return new BridgeConfigMerger.Source(item.optString("id"), item.optString("name"), item.optString("url"));
    }

    private static String readable(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message.trim();
    }
}
