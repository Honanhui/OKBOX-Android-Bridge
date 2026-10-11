package com.fongmi.android.tv.server.process;

import com.fongmi.android.tv.server.cloud.CloudAccountStore;
import com.fongmi.android.tv.server.cloud.CloudAccountValidator;
import com.fongmi.android.tv.server.cloud.CloudAuthService;
import com.fongmi.android.tv.server.cloud.CloudShare;
import com.fongmi.android.tv.server.impl.Process;

import org.json.JSONObject;

import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/** Bridge-native unified account center. Secrets are accepted but never returned over LAN. */
public final class CloudAccounts implements Process {
    private static final String PATH = "/api/cloud/accounts";
    private static final String INSPECT = "/api/cloud/inspect";

    @Override public boolean isRequest(NanoHTTPD.IHTTPSession session, String url) { return PATH.equals(url) || INSPECT.equals(url); }

    @Override public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String url, Map<String, String> files) {
        try {
            if (INSPECT.equals(url)) return inspect(session, files);
            if (session.getMethod() == NanoHTTPD.Method.GET) return json(CloudAccountStore.catalog());
            if (session.getMethod() != NanoHTTPD.Method.POST) return error(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, "Only GET and POST are supported");
            JSONObject request = new JSONObject(files.getOrDefault("postData", "{}"));
            String action = request.optString("action");
            if ("save".equals(action)) return json(CloudAccountStore.save(request.optString("id"), request.optString("provider"), request.optString("name"),
                    request.optJSONObject("values") == null ? new JSONObject() : request.optJSONObject("values"), request));
            if ("delete".equals(action)) return json(CloudAccountStore.remove(request.optString("id")));
            if ("enable".equals(action)) return json(CloudAccountStore.setDisabled(request.optString("id"), false));
            if ("disable".equals(action)) return json(CloudAccountStore.setDisabled(request.optString("id"), true));
            if ("login".equals(action)) return json(CloudAccountValidator.login(request.optString("id")));
            if ("validate".equals(action)) return json(CloudAccountValidator.validate(request.optString("id")));
            if ("auth-start".equals(action)) return json(CloudAuthService.start(request.optString("provider"), request.optString("name")));
            if ("auth-poll".equals(action)) return json(CloudAuthService.poll(request.optString("sessionId")));
            if ("authorization".equals(action)) return json(authorization(request.optString("provider")));
            return error(NanoHTTPD.Response.Status.BAD_REQUEST, "Unknown account action");
        } catch (IllegalArgumentException e) {
            return error(NanoHTTPD.Response.Status.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            String message = e.getMessage();
            if (message == null || message.isBlank()) message = "无法处理网盘账号请求";
            return error(NanoHTTPD.Response.Status.INTERNAL_ERROR, message);
        }
    }

    private static JSONObject authorization(String provider) throws Exception {
        if (!CloudAccountStore.supportsProvider(provider)) throw new IllegalArgumentException("不支持的网盘类型");
        JSONObject result = new JSONObject().put("provider", provider).put("external", true);
        if ("aliyun".equals(provider)) {
            return result.put("url", "https://api.oplist.org/")
                    .put("message", "请在 OpenList Token 页面完成阿里授权，再将 refresh token 保存到 Bridge");
        }
        if ("quark".equals(provider)) return result.put("url", "https://pan.quark.cn/").put("message", "登录后复制夸克 Cookie");
        if ("uc".equals(provider)) return result.put("url", "https://drive.uc.cn/").put("message", "登录后复制 UC Cookie");
        if ("pan115".equals(provider)) return result.put("url", "https://115.com/").put("message", "登录后复制 115 Cookie");
        if ("baidu".equals(provider)) return result.put("url", "https://pan.baidu.com/").put("message", "登录后使用备用区保存百度网盘 Cookie");
        return result.put("url", "").put("message", "此网盘使用账号、密码、Cookie 或 Token 登录");
    }

    private NanoHTTPD.Response inspect(NanoHTTPD.IHTTPSession session, Map<String, String> files) throws Exception {
        if (session.getMethod() != NanoHTTPD.Method.POST) return error(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, "Only POST is supported");
        return json(CloudShare.inspect(new JSONObject(files.getOrDefault("postData", "{}")).optString("url")));
    }

    private static NanoHTTPD.Response json(JSONObject body) {
        NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json; charset=utf-8", body.toString());
        response.addHeader("Cache-Control", "no-store");
        return response;
    }

    private static NanoHTTPD.Response error(NanoHTTPD.Response.Status status, String message) {
        JSONObject body = new JSONObject();
        try { body.put("error", message); } catch (Exception ignored) { }
        return NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8", body.toString());
    }
}
