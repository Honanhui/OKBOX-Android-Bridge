package com.fongmi.android.tv.server;

import android.content.Context;
import android.content.SharedPreferences;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.impl.Callback;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.HttpUrl;

/** Shared configuration state for the Android screen and LAN dashboard. */
public final class BridgeConfig {

    private static final String PREFS = "bridge";
    private static final String KEY_URL = "config_url";
    private static final String KEY_HISTORY = "config_history";
    private static final int MAX_HISTORY = 20;
    private static final AtomicLong REQUEST_ID = new AtomicLong();
    private static final List<StatusListener> STATUS_LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile String state = "idle";
    private static volatile String message = "尚未加载配置";
    private static volatile String activeUrl = "";
    private static volatile int sourceCount;

    private BridgeConfig() {
    }

    public interface Listener {
        void success(int count);

        void error(String message);
    }

    /** Receives changes initiated from either the Android screen or the LAN dashboard. */
    public interface StatusListener {
        void changed(String state, String message, String url, int sourceCount);
    }

    public static void addStatusListener(StatusListener listener) {
        if (listener != null && !STATUS_LISTENERS.contains(listener)) STATUS_LISTENERS.add(listener);
    }

    public static void removeStatusListener(StatusListener listener) {
        STATUS_LISTENERS.remove(listener);
    }

    public static String lastUrl() {
        return preferences().getString(KEY_URL, "").trim();
    }

    public static synchronized List<String> history() {
        List<String> entries = new ArrayList<>();
        String raw = preferences().getString(KEY_HISTORY, "");
        try {
            JSONArray array = new JSONArray(raw);
            for (int index = 0; index < array.length(); index++) {
                String value = array.optString(index).trim();
                if (!value.isEmpty() && !entries.contains(value)) entries.add(value);
            }
        } catch (JSONException ignored) {
            String current = lastUrl();
            if (!current.isEmpty()) entries.add(current);
        }
        return entries;
    }

    public static synchronized void deleteHistory(String value) {
        List<String> entries = history();
        entries.remove(value);
        saveHistory(entries);
    }

    public static void load(String value, Listener listener) {
        String url = value == null ? "" : value.trim();
        if (url.isEmpty()) {
            if (listener != null) listener.error("请输入配置地址");
            return;
        }
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null) {
            state = "error";
            message = "配置地址格式无效";
            activeUrl = url;
            sourceCount = 0;
            notifyStatus();
            if (listener != null) listener.error(message);
            return;
        }
        final long id = REQUEST_ID.incrementAndGet();
        preferences().edit().putString(KEY_URL, url).apply();
        remember(url);
        state = "loading";
        message = "正在获取配置";
        activeUrl = url;
        sourceCount = 0;
        notifyStatus();

        loadCandidate(candidates(parsed), 0, id, listener);
    }

    static void beginSourceLoad(String name, String url) {
        state = "loading";
        message = "正在抓取订阅：" + name;
        activeUrl = url;
        notifyStatus();
    }

    static void failSourceLoad(String name, String value) {
        state = "error";
        String reason = value == null || value.trim().isEmpty() ? "配置请求失败" : value.trim();
        message = name + "：" + reason;
        notifyStatus();
    }

    static void clearSourceLoad() {
        REQUEST_ID.incrementAndGet();
        VodConfig.get().clear();
        state = "idle";
        message = "尚未添加订阅源";
        activeUrl = Server.get().getAddress("/sub/okbox.json");
        sourceCount = 0;
        notifyStatus();
    }

    /**
     * Some long-lived TVBox aliases are regularly retired while users keep the
     * old address in their subscription list. Preserve the entered address,
     * but transparently try maintained mirrors when its host is gone or its
     * edge service is unavailable. This keeps the Bridge hands-free and does
     * not expose DNS or mirror controls in its management page.
     */
    private static List<String> candidates(HttpUrl primary) {
        List<String> items = new ArrayList<>();
        items.add(primary.toString());
        String host = primary.host().toLowerCase(Locale.ROOT);
        if ("fty.xxooo.cf".equals(host)) {
            items.add("https://raw.githubusercontent.com/samisold/FongmiTVMod/main/conf/fan.json");
            items.add("https://raw.githubusercontent.com/DodgeZhang/tvbox/main/fantaiying/fty.json");
        } else if ("xn--z7x900a.net".equals(host) || "xn--z7x900a.com".equals(host)) {
            items.add("https://raw.githubusercontent.com/samisold/FongmiTVMod/main/conf/feimao.json");
            items.add("https://raw.githubusercontent.com/zzq1234567890/tvbox1/main/feimao.json");
        }
        return items;
    }

    private static void loadCandidate(List<String> candidates, int index, long id, Listener listener) {
        if (REQUEST_ID.get() != id) return;
        String candidate = candidates.get(index);
        Config config = Config.vod();
        config.setUrl(candidate);
        config.setName("Spider Bridge");
        VodConfig.load(config, new Callback() {
            @Override
            public void success() {
                if (REQUEST_ID.get() != id) return;
                sourceCount = VodConfig.get().getSites().size();
                state = "ready";
                message = index == 0 ? "配置已加载" : "配置已加载（已自动切换备用地址）";
                notifyStatus();
                if (listener != null) listener.success(sourceCount);
            }

            @Override
            public void error(String error) {
                if (REQUEST_ID.get() != id) return;
                if (index + 1 < candidates.size()) {
                    state = "loading";
                    message = "正在自动切换备用地址";
                    notifyStatus();
                    loadCandidate(candidates, index + 1, id, listener);
                    return;
                }
                state = "error";
                message = error == null || error.trim().isEmpty() ? "配置请求失败" : error;
                notifyStatus();
                if (listener != null) listener.error(message);
            }
        });
    }

    public static JSONObject status() {
        JSONObject result = new JSONObject();
        try {
            result.put("state", state);
            result.put("message", message);
            result.put("url", activeUrl.isEmpty() ? lastUrl() : activeUrl);
            result.put("sources", sourceCount);
        } catch (Exception ignored) {
        }
        return result;
    }

    public static JSONArray historyJson() {
        JSONArray result = new JSONArray();
        for (String value : history()) result.put(value);
        return result;
    }

    private static synchronized void remember(String value) {
        List<String> entries = history();
        entries.remove(value);
        entries.add(0, value);
        while (entries.size() > MAX_HISTORY) entries.remove(entries.size() - 1);
        saveHistory(entries);
    }

    private static void saveHistory(List<String> entries) {
        JSONArray array = new JSONArray();
        for (String value : entries) array.put(value);
        preferences().edit().putString(KEY_HISTORY, array.toString()).apply();
    }

    private static SharedPreferences preferences() {
        return App.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void notifyStatus() {
        String url = activeUrl.isEmpty() ? lastUrl() : activeUrl;
        for (StatusListener listener : STATUS_LISTENERS) listener.changed(state, message, url, sourceCount);
    }
}
