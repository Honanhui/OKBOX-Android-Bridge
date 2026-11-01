package com.fongmi.android.tv.server.cloud;
import com.github.catvod.net.OkHttp;
import android.util.Base64;
import android.graphics.Bitmap;
import android.graphics.Color;


import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Browser-safe QR login sessions. Provider tokens never leave the Bridge process. */
public final class CloudAuthService {
    private static final String OPLIST = "https://api.oplist.org";
    private static final String FINGERPRINT = "okbox-bridge-android";
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    private CloudAuthService() {
    }

    public static JSONObject cancel(String id) throws Exception {
        if (id != null && id.startsWith(CloudProviderQrService.PREFIX)) return CloudProviderQrService.cancel(id);
        if (id != null) SESSIONS.remove(id);
        return new JSONObject().put("state", "cancelled").put("message", "登录已取消");
    }

    public static JSONObject start(String provider, String name) throws Exception {
        cleanup();
        if (!"aliyun".equals(provider)) return CloudProviderQrService.start(provider, name);
        String payload;
        Session session;
        try {
            JSONObject source = generateAliQr();
            payload = source.getString("payload");
            session = new Session(provider, name, true, "", source.optString("ck"), source.optString("t"),
                    System.currentTimeMillis() + source.optLong("expiresIn", 300L) * 1000L,
                    new JSONObject().put("cookies", source.optString("cookies")));
            queryAliQr(session);
        } catch (Exception directError) {
            JSONObject source = request("/alicloud2/generate_qr");
            payload = source.optString("qr_code_url");
            String providerSession = source.optString("session_id");
            long expiresIn = source.optLong("expires_in", 0L);
            if (!source.optBoolean("success") || payload.isEmpty() || providerSession.isEmpty() || expiresIn <= 0L) {
                String message = source.optString("error");
                if (message.isEmpty()) message = readable(directError, "授权服务没有返回有效二维码");
                throw new IllegalStateException(message);
            }
            session = new Session(provider, name, false, providerSession, "", "",
                    System.currentTimeMillis() + expiresIn * 1000L, new JSONObject());
        }
        String id = UUID.randomUUID().toString();
        SESSIONS.put(id, session);
        return new JSONObject()
                .put("sessionId", id)
                .put("state", "waiting")
                .put("expiresAt", session.expiresAt)
                .put("pollAfter", 700)
                .put("qrImage", renderQr(payload, 360))
                .put("message", "请使用阿里云盘 App 扫码并确认登录");
    }

    public static JSONObject poll(String id) throws Exception {
        if (id != null && id.startsWith(CloudProviderQrService.PREFIX)) return CloudProviderQrService.poll(id);
        Session session = SESSIONS.get(id);
        if (session == null) throw new IllegalArgumentException("登录会话不存在或已失效，请重新获取二维码");
        if (System.currentTimeMillis() >= session.expiresAt) {
            SESSIONS.remove(id);
            return new JSONObject().put("state", "expired").put("message", "二维码已过期，请重新获取");
        }
        JSONObject result = session.direct ? queryAliQr(session) : request("/alicloud2/check_login?session_id=" + encode(session.providerSession));
        String status = result.optString("status").toUpperCase();
        if ("CONFIRMED".equals(status)) {
            JSONObject tokens = session.direct ? aliTokens(result.opt("bizExt")) : openListTokens(session, result);
            if (tokens.optString("refresh_token").isEmpty()) throw new IllegalStateException("阿里云盘已确认登录，但没有返回刷新凭据");
            JSONObject saved = CloudAccountStore.saveOAuth(session.provider, session.name, tokens);
            SESSIONS.remove(id);
            return new JSONObject().put("state", "complete").put("message", "登录成功，账号已保存")
                    .put("accounts", saved.optJSONArray("accounts"));
        }
        if ("EXPIRED".equals(status)) {
            SESSIONS.remove(id);
            return new JSONObject().put("state", "expired").put("message", "二维码已过期，请重新获取");
        }
        String message = result.optString("message");
        if (message.isEmpty()) message = status.contains("SCAN") ? "已扫码，请在手机上确认" : "等待扫码";
        return new JSONObject().put("state", status.contains("SCAN") ? "scanned" : "waiting")
                .put("expiresAt", session.expiresAt).put("pollAfter", 700).put("message", message);
    }

    private static JSONObject generateAliQr() throws Exception {
        String csrf = "MuSysYVxW5AMGblcOTSKb3";
        String umid = UUID.randomUUID().toString().replace("-", "");
        String cookies = "";
        try {
            TextResponse page = requestTextResult("GET", "https://passport.alipan.com/mini_login.htm?lang=zh_cn&appName=aliyun_drive&appEntrance=web_default&styleType=auto&notLoadSsoView=false&notKeepLogin=false&isMobile=false&rnd=" + System.currentTimeMillis(), null, "");
            cookies = page.cookies;
            csrf = match(page.body, "_csrf_token[\\\"']?\\s*[:=]\\s*[\\\"']([^\\\"']+)", csrf);
            umid = match(page.body, "umidToken[\\\"']?\\s*[:=]\\s*[\\\"']([^\\\"']+)", umid);
        } catch (Throwable ignored) {
            // The fixed CSRF value is the provider's documented fallback.
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("appName", "aliyun_drive");
        values.put("fromSite", "52");
        values.put("appEntrance", "web_default");
        values.put("_csrf_token", csrf);
        values.put("umidToken", umid);
        values.put("hsiz", "115d9f5f2cf2f87850a93a793aaaecb4");
        values.put("bizParams", "taobaoBizLoginFrom=web_default&renderRefer=https://auth.alipan.com/");
        values.put("mainPage", "false");
        values.put("isMobile", "false");
        values.put("lang", "zh_CN");
        values.put("returnUrl", "");
        values.put("umidTag", "SERVER");
        TextResponse generated = requestTextResult("GET", "https://passport.alipan.com/newlogin/qrcode/generate.do?" + form(values), null, cookies);
        cookies = mergeCookies(cookies, generated.cookies);
        JSONObject root = generated.body.isEmpty() ? new JSONObject() : new JSONObject(generated.body);
        JSONObject content = root.optJSONObject("content");
        JSONObject data = content == null ? null : content.optJSONObject("data");
        String payload = data == null ? "" : data.optString("codeContent");
        if (root.optBoolean("hasError") || content == null || !content.optBoolean("success") || payload.isEmpty()) {
            throw new IllegalStateException("阿里云盘没有返回有效二维码");
        }
        return new JSONObject().put("payload", payload).put("ck", data.optString("ck"))
                .put("t", data.optString("t")).put("expiresIn", 300L).put("cookies", cookies);
    }

    private static JSONObject queryAliQr(Session session) throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("appName", "aliyun_drive");
        values.put("fromSite", "52");
        values.put("ck", session.ck);
        values.put("t", session.t);
        TextResponse response = requestTextResult("POST", "https://passport.alipan.com/newlogin/qrcode/query.do", form(values),
                session.meta.optString("cookies"));
        session.meta.put("cookies", mergeCookies(session.meta.optString("cookies"), response.cookies));
        JSONObject root = response.body.isEmpty() ? new JSONObject() : new JSONObject(response.body);
        JSONObject content = root.optJSONObject("content");
        JSONObject data = content == null ? null : content.optJSONObject("data");
        if (root.optBoolean("hasError") || content == null || !content.optBoolean("success") || data == null) {
            throw new IllegalStateException("查询阿里云盘扫码状态失败");
        }
        String state = data.optString("qrCodeStatus", "NEW").toUpperCase();
        String status = switch (state) {
            case "SCANED", "SCANNED" -> "SCANED";
            case "CONFIRMED" -> "CONFIRMED";
            case "EXPIRED" -> "EXPIRED";
            default -> "WAITING";
        };
        return new JSONObject().put("status", status).put("bizExt", data.opt("bizExt"))
                .put("message", "SCANED".equals(status) ? "已扫码，请在手机上确认" : "等待扫码");
    }

    private static JSONObject aliTokens(Object value) throws Exception {
        JSONObject decoded;
        if (value instanceof JSONObject object) decoded = object;
        else {
            String encoded = value == null ? "" : String.valueOf(value).trim();
            if (encoded.isEmpty()) throw new IllegalStateException("阿里云盘登录结果为空");
            decoded = new JSONObject(new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8));
        }
        JSONObject login = decoded.optJSONObject("pds_login_result");
        if (login == null) throw new IllegalStateException("阿里云盘登录结果缺少凭据");
        return new JSONObject().put("access_token", login.optString("accessToken"))
                .put("refresh_token", login.optString("refreshToken"));
    }

    private static JSONObject openListTokens(Session session, JSONObject result) throws Exception {
        JSONObject info = result;
        if (result.optString("refresh_token").isEmpty() && result.optString("refreshToken").isEmpty()
                && result.optString("access_token").isEmpty() && result.optString("accessToken").isEmpty()) {
            info = request("/alicloud2/get_user_info?session_id=" + encode(session.providerSession));
        }
        return new JSONObject().put("access_token", first(info, result, "access_token", "accessToken"))
                .put("refresh_token", first(info, result, "refresh_token", "refreshToken"));
    }

    private static String first(JSONObject preferred, JSONObject fallback, String... keys) {
        for (String key : keys) {
            String value = preferred.optString(key).trim();
            if (!value.isEmpty()) return value;
        }
        for (String key : keys) {
            String value = fallback.optString(key).trim();
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private static JSONObject request(String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(OPLIST + path).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("X-Client-Fingerprint", FINGERPRINT);
        int code = connection.getResponseCode();
        InputStream input = code >= 200 && code < 400 ? connection.getInputStream() : connection.getErrorStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (input != null) {
            byte[] chunk = new byte[4096];
            int count;
            while ((count = input.read(chunk)) >= 0) buffer.write(chunk, 0, count);
            input.close();
        }
        String body = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        connection.disconnect();
        JSONObject result = body.isEmpty() ? new JSONObject() : new JSONObject(body);
        if (code < 200 || code >= 300) throw new IllegalStateException(result.optString("error", "授权服务返回 HTTP " + code));
        return result;
    }

    private static JSONObject requestJson(String method, String url, String body) throws Exception {
        String text = requestText(method, url, body);
        return text.isEmpty() ? new JSONObject() : new JSONObject(text);
    }

    private static String requestText(String method, String url, String body) throws Exception {
        return requestTextResult(method, url, body, "").body;
    }

    private static TextResponse requestTextResult(String method, String url, String body, String cookies) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(12_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestMethod(method);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36");
        connection.setRequestProperty("Accept", "application/json, text/plain, */*");
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        connection.setRequestProperty("Referer", "https://passport.alipan.com/");
        connection.setRequestProperty("Origin", "https://passport.alipan.com");
        connection.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        if (cookies != null && !cookies.isEmpty()) connection.setRequestProperty("Cookie", cookies);
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
        }
        int code = connection.getResponseCode();
        InputStream input = code >= 200 && code < 400 ? connection.getInputStream() : connection.getErrorStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (input != null) {
            try (input) {
                byte[] chunk = new byte[4096];
                int count;
                while ((count = input.read(chunk)) >= 0) buffer.write(chunk, 0, count);
            }
        }
        String response = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        String responseCookies = responseCookies(connection);
        connection.disconnect();
        if (code < 200 || code >= 400) throw new IllegalStateException("阿里云盘登录接口返回 HTTP " + code);
        return new TextResponse(response, responseCookies);
    }

    private static String responseCookies(HttpURLConnection connection) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
            if (entry.getKey() == null || !"set-cookie".equalsIgnoreCase(entry.getKey())) continue;
            for (String value : entry.getValue()) {
                int semicolon = value.indexOf(';');
                String pair = value.substring(0, semicolon < 0 ? value.length() : semicolon).trim();
                int equal = pair.indexOf('=');
                if (equal > 0) values.put(pair.substring(0, equal), pair.substring(equal + 1));
            }
        }
        return joinCookies(values);
    }

    private static String mergeCookies(String current, String incoming) {
        Map<String, String> values = new LinkedHashMap<>();
        readCookies(values, current);
        readCookies(values, incoming);
        return joinCookies(values);
    }

    private static void readCookies(Map<String, String> values, String source) {
        if (source == null) return;
        for (String part : source.split(";")) {
            int equal = part.indexOf('=');
            if (equal > 0) values.put(part.substring(0, equal).trim(), part.substring(equal + 1).trim());
        }
    }

    private static String joinCookies(Map<String, String> values) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            if (result.length() > 0) result.append("; ");
            result.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return result.toString();
    }

    private static String form(Map<String, String> values) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (result.length() > 0) result.append('&');
            result.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return result.toString();
    }

    private static String match(String value, String expression, String fallback) {
        Matcher matcher = Pattern.compile(expression).matcher(value == null ? "" : value);
        return matcher.find() ? matcher.group(1) : fallback;
    }

    private static String readable(Throwable error, String fallback) {
        String message = error == null ? "" : error.getMessage();
        return message == null || message.trim().isEmpty() ? fallback : message.trim();
    }

    private static String renderQr(String payload, int maximumSide) throws Exception {
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 3);
        BitMatrix matrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 1, 1, hints);
        int modules = matrix.getWidth();
        int modulePixels = Math.max(1, maximumSide / modules);
        int side = modules * modulePixels;
        int[] pixels = new int[side * side];
        for (int y = 0; y < modules; y++) {
            for (int x = 0; x < modules; x++) {
                int color = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                for (int dy = 0; dy < modulePixels; dy++) {
                    int row = (y * modulePixels + dy) * side + x * modulePixels;
                    for (int dx = 0; dx < modulePixels; dx++) pixels[row + dx] = color;
                }
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, side, 0, 0, side, side);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        bitmap.recycle();
        return "data:image/png;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
    }

    private static void cleanup() {
        long now = System.currentTimeMillis();
        SESSIONS.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception ignored) {
            return value;
        }
    }

    private record Session(String provider, String name, boolean direct, String providerSession, String ck, String t, long expiresAt, JSONObject meta) {
    }

    private record TextResponse(String body, String cookies) {
    }
}
