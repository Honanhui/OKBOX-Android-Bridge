package com.github.catvod.crawler;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.util.Base64;
import android.view.Surface;
import android.view.WindowManager;

import com.github.catvod.Proxy;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import okhttp3.FormBody;
import okhttp3.Headers;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * TVBoxOS-compatible host callbacks for modern external JAR/DEX spiders.
 * This lives in catvod so a child DexClassLoader resolves the exact ABI it
 * was compiled against, without depending on Bridge application classes.
 */
public class SpiderApi {

    public String getAddress(boolean local) {
        String url = Proxy.getUrl(local);
        return url.endsWith("/proxy") ? url.substring(0, url.length() - 6) : url;
    }

    public String getPort() {
        int port = Proxy.getPort();
        return port > 0 ? String.valueOf(port) : "";
    }

    public void log(String msg) {
        SpiderDebug.log(msg);
    }

    public int getScreenOrientation() {
        try {
            Context context = Spider.mContext;
            if (context == null) return ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
            int orientation = context.getResources().getConfiguration().orientation;
            int rotation = Surface.ROTATION_0;
            WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (manager != null && manager.getDefaultDisplay() != null) rotation = manager.getDefaultDisplay().getRotation();
            if (orientation == Configuration.ORIENTATION_PORTRAIT) return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                return rotation == Surface.ROTATION_90 ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
            }
        } catch (Throwable ignored) {
        }
        return ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
    }

    public String multiReq(JsonArray array) {
        if (array == null || array.isEmpty()) return "";
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(array.size(), 6));
        try {
            ArrayList<Future<String>> futures = new ArrayList<>();
            for (JsonElement element : array) if (element.isJsonObject()) futures.add(executor.submit(() -> request(element.getAsJsonObject())));
            JsonArray result = new JsonArray();
            for (Future<String> future : futures) result.add(toResult(future.get()));
            return result.toString();
        } catch (Throwable ignored) {
            return "";
        } finally {
            executor.shutdownNow();
        }
    }

    public String webParse(String url, String flag) {
        try {
            if (url == null || url.isEmpty()) return "";
            String encoded = Base64.encodeToString(url.getBytes("UTF-8"), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP);
            return "proxy://go=SuperParse&flag=" + (flag == null ? "" : flag) + "&url=" + encoded;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String request(JsonObject object) {
        try {
            String url = string(object, "url");
            if (url.isEmpty()) return "";
            Request.Builder builder = new Request.Builder().url(url).headers(headers(object.get("headers")));
            if ("POST".equalsIgnoreCase(string(object, "method"))) builder.post(body(object));
            try (Response response = OkHttp.client().newCall(builder.build()).execute()) {
                return response.body() == null ? "" : response.body().string();
            }
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static JsonElement toResult(String text) {
        if (text == null) return new JsonPrimitive("");
        try {
            String value = text.trim();
            if (value.startsWith("{") || value.startsWith("[")) return JsonParser.parseString(value);
        } catch (Throwable ignored) {
        }
        return new JsonPrimitive(text);
    }

    private static RequestBody body(JsonObject object) {
        JsonElement data = object.get("data");
        if (data == null || data.isJsonNull()) return RequestBody.create(new byte[0]);
        if ("form".equalsIgnoreCase(string(object, "postType")) && data.isJsonObject()) {
            FormBody.Builder form = new FormBody.Builder();
            for (Map.Entry<String, JsonElement> entry : data.getAsJsonObject().entrySet()) form.add(entry.getKey(), entry.getValue().getAsString());
            return form.build();
        }
        return RequestBody.create(data.isJsonPrimitive() ? data.getAsString() : data.toString(), null);
    }

    private static Headers headers(JsonElement element) {
        try {
            if (element == null || element.isJsonNull() || !element.isJsonObject()) return new Headers.Builder().build();
            HashMap<String, String> values = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) values.put(entry.getKey(), entry.getValue().getAsString());
            return Headers.of(values);
        } catch (Throwable ignored) {
            return new Headers.Builder().build();
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }
}
