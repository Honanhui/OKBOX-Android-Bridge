package com.fongmi.android.tv.server.process;

import android.app.Activity;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.server.cloud.CloudAccountStore;
import com.fongmi.android.tv.server.cloud.CloudGateway;
import com.fongmi.android.tv.server.impl.Process;

import org.json.JSONObject;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;

/**
 * Compatibility endpoint for older OKbox builds. Cloud credentials are now
 * owned by Bridge and are no longer injected into an upstream Spider dialog.
 */
public final class Cloud implements Process {

    private static final String PATH = "/api/cloud";
    private static final String MIME_JSON = "application/json; charset=utf-8";
    private static final long SESSION_TTL = 10 * 60 * 1000L;
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    @Override public boolean isRequest(NanoHTTPD.IHTTPSession session, String url) {
        return PATH.equals(url);
    }

    @Override public Response doResponse(NanoHTTPD.IHTTPSession session, String url, Map<String, String> files) {
        if (session.getMethod() != NanoHTTPD.Method.POST) return error(Status.METHOD_NOT_ALLOWED, "Only POST is supported");
        clearExpired();
        try {
            JSONObject request = request(files);
            String action = required(request, "action");
            if ("player".equals(action)) return error(Status.NOT_FOUND, "Not a cloud login request");
            return switch (action) {
                case "login" -> start(request);
                case "ensure" -> ensure(request);
                case "cancel" -> json(new JSONObject().put("state", "closed").put("message", "登录已关闭"));
                case "player" -> error(Status.NOT_FOUND, "Not a cloud login request");
                default -> error(Status.GONE, "旧的源内扫码流程已停用，请在 Bridge 网盘账号中登录");
            };
        } catch (IllegalArgumentException e) {
            return error(Status.BAD_REQUEST, e.getMessage());
        } catch (Throwable e) {
            e.printStackTrace();
            return error(Status.INTERNAL_ERROR, message(e));
        }
    }

    private static Response start(JSONObject request) throws Exception {
        return json(CloudGateway.managedLoginState());
    }

    /**
     * Select a Bridge-owned account without opening or mutating an upstream
     * Spider login dialog.
     */
    private static Response ensure(JSONObject request) throws Exception {
        String provider = required(request, "provider");
        return json(CloudGateway.accountState(provider));
    }

    private static Response choose(JSONObject request) throws Exception {
        Session session = requiredSession(request);
        session.dialog.choose(required(request, "optionId"));
        session.touch();
        return state(session);
    }

    private static Response check(JSONObject request) throws Exception {
        Session session = requiredSession(request);
        session.dialog.beginCheck();
        session.touch();
        return state(session);
    }

    private static Response submit(JSONObject request) throws Exception {
        Session session = requiredSession(request);
        JSONObject values = request.optJSONObject("values");
        if (values == null) values = new JSONObject();
        session.dialog.submit(required(request, "submitId"), values);
        session.touch();
        return state(session);
    }

    private static Response cancel(JSONObject request) throws Exception {
        String token = request.optString("token");
        Session session = SESSIONS.remove(token);
        if (session != null) session.close();
        return json(new JSONObject().put("token", token).put("state", "closed").put("message", "登录已关闭"));
    }

    private static Response state(Session session) throws Exception {
        session.touch();
        if (session.hasAutoCookie()) session.autoStatus = session.dialog.injectCookie(session.provider, session.cookie);
        JSONObject payload = session.dialog.snapshot();
        payload.put("token", session.token);
        if (session.hasAutoCookie()) payload.put("provider", session.provider);
        String phase = payload.optString("state");
        long age = System.currentTimeMillis() - session.createdAt;
        if ("unsupported".equals(session.autoStatus) || "qr".equals(session.autoStatus)) {
            payload.put("state", "unsupported");
            payload.put("message", "当前影视源 Spider 未提供可填写的 " + CloudAccountStore.providerName(session.provider) + " Cookie 表单，无法自动注入；请使用该源自己的扫码登录。" );
        } else if ("closed".equals(phase) && session.hasAutoCookie()
                && ("waiting".equals(session.autoStatus) || "submitted".equals(session.autoStatus))) {
            if (age < 12_000L) {
                payload.put("state", "waiting");
                payload.put("message", "正在等待影视源 Spider 确认" + CloudAccountStore.providerName(session.provider) + "登录…");
            } else {
                payload.put("state", "unsupported");
                payload.put("message", "影视源 Spider 没有返回可确认的 " + CloudAccountStore.providerName(session.provider) + " 登录结果；未继续使用该 Cookie。请使用该源自己的扫码登录。" );
            }
        } else if ("waiting".equals(phase) && age > 12_000L) {
            payload.put("state", "error");
            payload.put("message", "Bridge 尚未创建网盘登录窗口。请先打开 Android Bridge 到前台一次，再回到 OKbox 重试。");
        } else if ("qr".equals(phase)) {
            payload.put("message", "请使用对应网盘手机 App 扫码并确认；二维码只显示在 OKbox。");
        } else if ("choose".equals(phase)) {
            payload.put("message", "请选择要登录的网盘");
        } else if ("form".equals(phase)) {
            payload.put("message", "请输入账号信息后登录");
        } else if ("confirmed".equals(phase)) {
            payload.put("message", session.hasAutoCookie() ? "已将 Bridge 保存的 " + CloudAccountStore.providerName(session.provider) + " Cookie 写入影视源 Spider" : "网盘登录成功");
        } else if ("expired".equals(phase)) {
            payload.put("message", "二维码已失效，请重新获取");
        } else if (payload.optString("checkMessage").length() > 0) {
            payload.put("message", payload.optString("checkMessage"));
        } else {
            payload.put("message", "正在读取网盘登录界面…");
        }
        return json(payload);
    }

    private static Session requiredSession(JSONObject request) {
        Session session = SESSIONS.get(required(request, "token"));
        if (session == null) throw new IllegalArgumentException("网盘登录会话已过期，请重新打开");
        return session;
    }

    private static void ensureForegroundWindow() {
        Activity activity = App.activity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || activity.getWindow() == null) {
            throw new IllegalArgumentException("请先把 Android Bridge 打开到前台一次，然后回到 OKbox 继续扫码");
        }
    }

    private static void clearExpired() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Session> entry : SESSIONS.entrySet()) {
            Session session = entry.getValue();
            if (now - session.updatedAt <= SESSION_TTL || !SESSIONS.remove(entry.getKey(), session)) continue;
            session.close();
        }
    }

    private static JSONObject request(Map<String, String> files) throws Exception {
        String body = files.get("postData");
        if (TextUtils.isEmpty(body)) throw new IllegalArgumentException("Missing JSON request body");
        return new JSONObject(body);
    }

    private static String required(JSONObject request, String name) {
        String value = request.optString(name).trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }

    private static String requiredDetail(String command) {
        String id = command.substring("detail:".length()).trim();
        if (id.isEmpty()) throw new IllegalArgumentException("Missing detail id");
        return id;
    }

    private static Response json(JSONObject payload) {
        Response response = NanoHTTPD.newFixedLengthResponse(Status.OK, MIME_JSON, payload.toString());
        response.addHeader("Cache-Control", "no-store");
        return response;
    }

    private static Response error(Status status, String value) {
        JSONObject payload = new JSONObject();
        try { payload.put("error", value); } catch (Exception ignored) { }
        Response response = NanoHTTPD.newFixedLengthResponse(status, MIME_JSON, payload.toString());
        response.addHeader("Cache-Control", "no-store");
        return response;
    }

    private static String message(Throwable error) {
        String value = error.getMessage();
        return TextUtils.isEmpty(value) ? error.getClass().getSimpleName() : value;
    }

    private static final class Session {
        final String token = UUID.randomUUID().toString();
        final CloudDialog dialog = new CloudDialog();
        final long createdAt = System.currentTimeMillis();
        final String provider;
        String cookie;
        String autoStatus = "";
        volatile long updatedAt = createdAt;

        Session() { this("", ""); }

        Session(String provider, String cookie) {
            this.provider = provider;
            this.cookie = cookie;
        }

        void touch() { updatedAt = System.currentTimeMillis(); }
        boolean hasAutoCookie() { return !provider.isEmpty() && !cookie.isEmpty(); }
        void close() { cookie = ""; dialog.close(); }
    }
}
