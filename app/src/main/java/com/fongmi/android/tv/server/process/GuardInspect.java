package com.fongmi.android.tv.server.process;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.server.impl.Process;
import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

import fi.iki.elonen.NanoHTTPD;

/**
 * Debug-only metadata endpoint for protected Spider loaders.
 *
 * It deliberately returns class and class-loader metadata only. It does not
 * return source content, request headers, cookies, credentials or bytecode.
 */
public final class GuardInspect implements Process {

    private static final String PATH = "/api/guard-inspect";
    private static final String MIME_JSON = "application/json; charset=utf-8";

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String url) {
        return PATH.equals(url);
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String url, java.util.Map<String, String> files) {
        if (session.getMethod() != NanoHTTPD.Method.POST) return error(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, "Only POST is supported");
        try {
            String body = files.get("postData");
            if (body == null || body.trim().isEmpty()) return error(NanoHTTPD.Response.Status.BAD_REQUEST, "Missing JSON request body");
            JSONObject request = new JSONObject(body);
            String key = request.optString("siteKey").trim();
            if (key.isEmpty()) return error(NanoHTTPD.Response.Status.BAD_REQUEST, "Missing siteKey");
            Site site = VodConfig.get().getSite(key);
            if (site.isEmpty()) return error(NanoHTTPD.Response.Status.NOT_FOUND, "Unknown siteKey: " + key);
            JSONObject result = inspect(site);
            if (request.optBoolean("syntheticProbe", false)) result.put("syntheticProbe", syntheticProbe(site));
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, MIME_JSON, result.toString());
        } catch (Throwable error) {
            return error(NanoHTTPD.Response.Status.INTERNAL_ERROR, error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()));
        }
    }

    private static JSONObject inspect(Site site) throws Exception {
        Spider guard = site.recent().spider();
        JSONObject result = new JSONObject();
        result.put("siteKey", site.getKey());
        result.put("siteApi", site.getApi());
        result.put("spider", describe(guard));
        result.put("delegates", delegates(guard));
        return result;
    }

    private static JSONObject describe(Object value) throws Exception {
        JSONObject result = new JSONObject();
        Class<?> type = value.getClass();
        result.put("className", type.getName());
        result.put("classLoader", loaders(type.getClassLoader()));
        return result;
    }

    // Fixed synthetic inputs only: no caller-controlled values or site data.
    // This identifies the cipher format for the independently written Node port.
    private static JSONObject syntheticProbe(Site site) throws Exception {
        Spider guard = site.recent().spider();
        Class<?> nativeType = Class.forName("com.github.catvod.spider.DexNative", true, guard.getClass().getClassLoader());
        java.lang.reflect.Method encrypt = nativeType.getDeclaredMethod("encrypt", String.class);
        java.lang.reflect.Method decrypt = nativeType.getDeclaredMethod("decrypt", String.class);
        String input = new String(new char[1024]).replace('\0', 'A');
        String first = String.valueOf(encrypt.invoke(null, input));
        String second = String.valueOf(encrypt.invoke(null, input));
        JSONObject result = new JSONObject();
        result.put("sample", "1024 ASCII A characters");
        result.put("encrypted", first);
        result.put("deterministic", first.equals(second));
        result.put("roundTrip", input.equals(String.valueOf(decrypt.invoke(null, first))));
        return result;
    }

    private static JSONArray loaders(ClassLoader loader) throws Exception {
        JSONArray result = new JSONArray();
        Set<ClassLoader> seen = new HashSet<>();
        while (loader != null && seen.add(loader)) {
            JSONObject item = new JSONObject();
            item.put("className", loader.getClass().getName());
            // BaseDexClassLoader's string includes the dex path without using
            // hidden APIs. It is enough to tell file-backed from in-memory
            // loading and never exposes a payload.
            item.put("description", String.valueOf(loader));
            result.put(item);
            loader = loader.getParent();
        }
        return result;
    }

    private static JSONArray delegates(Spider guard) throws Exception {
        JSONArray result = new JSONArray();
        Set<Object> seen = new HashSet<>();
        for (Class<?> type = guard.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Spider.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(guard);
                    if (value != null && value != guard && seen.add(value)) result.put(describe(value));
                } catch (Throwable ignored) {
                    // Field names are deliberately obfuscated. One blocked
                    // field must not hide metadata for another delegate.
                }
            }
        }
        return result;
    }

    private static NanoHTTPD.Response error(NanoHTTPD.Response.Status status, String message) {
        JSONObject result = new JSONObject();
        try { result.put("error", message); } catch (Exception ignored) { }
        return NanoHTTPD.newFixedLengthResponse(status, MIME_JSON, result.toString());
    }
}
