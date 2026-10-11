package com.fongmi.android.tv.server.process;

import com.fongmi.android.tv.server.BridgeConfig;
import com.fongmi.android.tv.server.BridgeSubscriptions;

import com.fongmi.android.tv.server.RequestAddress;
import com.fongmi.android.tv.server.impl.Process;

import org.json.JSONObject;

import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/** LAN API for the Bridge-owned source catalogue and its stable local manifest. */
public final class Subscriptions implements Process {

    private static final String API = "/api/subscriptions";
    private static final String SUB_INDEX = "/sub/okbox.json";
    private static final String SUB_PREFIX = "/sub/";
    private static final String MIME_JSON = "application/json; charset=utf-8";

    @Override public boolean isRequest(NanoHTTPD.IHTTPSession session, String url) {
        return API.equals(url) || url.startsWith(API + "/") || SUB_INDEX.equals(url) || (url.startsWith(SUB_PREFIX) && url.endsWith(".json"));
    }

    @Override public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String url, Map<String, String> files) {
        try {
            if (SUB_INDEX.equals(url)) return getManifest(session);
            if (url.startsWith(SUB_PREFIX)) return getSourceConfig(session, url);
            if (session.getMethod() == NanoHTTPD.Method.GET) return json(BridgeSubscriptions.catalog());
            if (session.getMethod() != NanoHTTPD.Method.POST) return error(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, "Only GET and POST are supported");
            return action(session, url, files);
        } catch (IllegalArgumentException error) {
            return error(NanoHTTPD.Response.Status.BAD_REQUEST, error.getMessage());
        } catch (Exception error) {
            return error(NanoHTTPD.Response.Status.INTERNAL_ERROR, "无法处理订阅源请求");
        }
    }

    private NanoHTTPD.Response getManifest(NanoHTTPD.IHTTPSession session) throws Exception {
        if (session.getMethod() != NanoHTTPD.Method.GET) return error(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, "Only GET is supported");
        return json(BridgeSubscriptions.manifest(baseUrl(session)));
    }

    private NanoHTTPD.Response getSourceConfig(NanoHTTPD.IHTTPSession session, String url) throws Exception {
        if (session.getMethod() != NanoHTTPD.Method.GET) return error(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, "Only GET is supported");
        String id = url.substring(SUB_PREFIX.length(), url.length() - ".json".length());
        if (id.isBlank()) throw new IllegalArgumentException("订阅源标识无效");
        return json(BridgeSubscriptions.config(id));
    }

    private NanoHTTPD.Response action(NanoHTTPD.IHTTPSession session, String url, Map<String, String> files) throws Exception {
        JSONObject body = new JSONObject(files.getOrDefault("postData", "{}"));
        String action = body.optString("action").trim();
        if ("save".equals(action)) {
            String id = body.optString("id").trim();
            JSONObject result = BridgeSubscriptions.save(id, body.optString("name"), body.optString("url"));
            if (!id.isEmpty() && id.equals(result.optString("activeId"))) select(id);
            else if (id.isEmpty() && result.optJSONArray("sources").length() == 1) select(result.optString("activeId"));
            return json(result.put("accepted", true));
        }
        if ("delete".equals(action)) {
            String oldActive = BridgeSubscriptions.catalog().optString("activeId");
            String id = body.optString("id");
            JSONObject result = BridgeSubscriptions.remove(body.optString("id"));
            if (id.equals(oldActive) && !result.optString("activeId").isEmpty()) select(result.optString("activeId"));
            return json(result.put("accepted", true));
        }
        if ("reload".equals(action)) {
            BridgeSubscriptions.refreshAll();
            return json(new JSONObject().put("accepted", true).put("message", "正在重新抓取全部订阅"));
        }
        String id = sourceId(url, body);
        if ("select".equals(action) || url.endsWith("/select")) {
            select(id);
            return json(new JSONObject().put("accepted", true).put("message", "正在抓取并切换订阅源").put("id", id));
        }
        throw new IllegalArgumentException("未知订阅源操作");
    }

    private static String sourceId(String url, JSONObject body) {
        String value = body.optString("id").trim();
        if (!value.isEmpty()) return value;
        String suffix = "/select";
        if (url.startsWith(API + "/") && url.endsWith(suffix)) return url.substring((API + "/").length(), url.length() - suffix.length());
        throw new IllegalArgumentException("请选择订阅源");
    }

    private static String baseUrl(NanoHTTPD.IHTTPSession session) {
        return RequestAddress.base(session);
    }

    private static void select(String id) {
        BridgeSubscriptions.select(id, new BridgeConfig.Listener() {
            @Override public void success(int count) {

            }

            @Override public void error(String message) {
                // The dashboard reads the precise load error from BridgeConfig.status().
            }
        });
    }

    private static NanoHTTPD.Response json(JSONObject body) {
        NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, MIME_JSON, body.toString());
        response.addHeader("Cache-Control", "no-store");
        return response;
    }

    private static NanoHTTPD.Response error(NanoHTTPD.Response.Status status, String message) {
        JSONObject body = new JSONObject();
        try {
            body.put("error", message);
        } catch (Exception ignored) {
        }
        return NanoHTTPD.newFixedLengthResponse(status, MIME_JSON, body.toString());
    }
}
