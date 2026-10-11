package com.fongmi.android.tv.server.cloud;

import android.util.Base64;

import com.fongmi.android.tv.bean.Result;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Resolves cloud-share payloads with Bridge-owned accounts.
 *
 * Imported Spiders still perform search and detail extraction.  When their
 * episode id exposes a standard cloud share payload, this class takes over
 * before playerContent so the source's private QR/login state is not used.
 */
public final class CloudPlaybackResolver {

    private static final String BAIDU = "https://pan.baidu.com/";
    private static final String NETDISK_UA = "netdisk";
    // Values used by OpenList's current Baidu-share Android request flow.
    private static final String BAIDU_DEVICE_ID = "BB91C9B818963851F99A99261A70E37E|VUFQKX5JL";
    private static final String BAIDU_APP_VERSION = "11.10.4";
    private static final String BAIDU_APP_CERT_MD5 = "ae5821440fab5e1a61a025f014bd8972";
    private static final String BAIDU_HMAC_KEY = "B8ec24caf34ef7227c66767d29ffd3fb";
    private static final MediaType FORM = MediaType.parse("application/x-www-form-urlencoded");

    private CloudPlaybackResolver() {
    }

    /** Returns null when the episode id is not a recognized Bridge share. */
    public static Result resolve(String flag, String id) {
        JSONObject share = object(id);
        if (!isBaiduShare(flag, share)) return null;
        try {
            return resolveBaidu(share);
        } catch (Throwable error) {
            String message = error.getMessage();
            if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
            return Result.error("Bridge 百度网盘取链失败：" + message);
        }
    }

    private static Result resolveBaidu(JSONObject share) throws Exception {
        JSONObject secret = CloudAccountStore.firstSecretForProvider("baidu");
        String cookie = first(secret, "cookie", "token");
        if (cookie.isEmpty()) return Result.error("该线路实际使用百度网盘，请先在 Bridge 网盘账号中登录百度网盘");

        String surl = required(share, "surl");
        String shareId = required(share, "share_id", "shareid");
        String uk = required(share, "uk");
        String fsId = required(share, "fs_id", "fsid");
        String seKey = decodeSeKey(required(share, "seKey", "sekey", "randsk"));

        Throwable mobileError;
        try {
            return resolveBaiduMobile(cookie, shareId, uk, fsId, seKey);
        } catch (Throwable error) {
            mobileError = error;
        }

        // Older accounts and shares can still use the web endpoint. Keep it as
        // a compatibility fallback, while reporting the mobile-flow error when
        // both paths fail because it describes the current Baidu requirement.
        try {
            return resolveBaiduWeb(cookie, surl, shareId, uk, fsId, seKey);
        } catch (Throwable ignored) {
            throw mobileError instanceof Exception ? (Exception) mobileError : new IllegalStateException(mobileError);
        }
    }

    private static Result resolveBaiduMobile(String cookie, String shareId, String uk, String fsId, String seKey) throws Exception {
        String bduss = cookieValue(cookie, "BDUSS");
        if (bduss.isEmpty()) throw new IllegalStateException("百度登录 Cookie 缺少 BDUSS，请重新扫码登录");

        JSONObject sync = getJson("https://tieba.baidu.com/mo/q/sync", cookie);
        JSONObject syncData = sync.optJSONObject("data");
        String uid = syncData == null ? "" : String.valueOf(syncData.opt("user_id"));
        // The Tieba sync endpoint omits errno on a successful response. Go's
        // zero-value decoding (used by OpenList) treats that as success.
        if (sync.optInt("errno", 0) != 0 || uid.isEmpty() || "null".equals(uid)) {
            throw baiduError("无法取得百度账号 UID", sync);
        }

        long timestamp = System.currentTimeMillis() / 1000L;
        HttpUrl skUrl = HttpUrl.parse(BAIDU + "api/report/user").newBuilder()
                .addQueryParameter("action", "ANDROID_ACTIVE_BACKGROUND_UPLOAD_AND_DOWNLOAD")
                .addQueryParameter("clienttype", "1")
                .addQueryParameter("needrookie", "1")
                .addQueryParameter("timestamp", String.valueOf(timestamp))
                .addQueryParameter("bind_uid", uid)
                .addQueryParameter("channel", "android")
                .build();
        JSONObject skResult = getJson(skUrl.toString(), cookie);
        String encryptedSk = skResult.optString("uinfo");
        if (skResult.optInt("errno", 0) != 0 || encryptedSk.isEmpty()) {
            throw baiduError("无法取得百度移动端签名信息", skResult);
        }

        long timestampMs = timestamp * 1000L;
        String trueSk = rc4(Base64.decode(encryptedSk, Base64.DEFAULT), uid.getBytes(StandardCharsets.UTF_8));
        String randBase = hex(sha1(bduss)) + uid + trueSk + timestampMs
                + BAIDU_DEVICE_ID + BAIDU_APP_VERSION + BAIDU_APP_CERT_MD5;
        String rand = hex(sha1(randBase));

        String extra = new JSONObject().put("sekey", seKey).toString();
        String body = "encrypt=0&uk=" + uk + "&product=share&primaryid=" + shareId
                + "&fid_list=[\"" + fsId + "\"]&extra=" + extra;
        String signBase = body + "_" + BAIDU_DEVICE_ID + "_" + timestamp;
        String signature = hex(hmacSha1(BAIDU_HMAC_KEY, signBase));

        HttpUrl downloadUrl = HttpUrl.parse(BAIDU + "api/sharedownload").newBuilder()
                .addQueryParameter("sign", signature)
                .addQueryParameter("timestamp", String.valueOf(timestamp))
                .addQueryParameter("rand", rand)
                .addQueryParameter("time", String.valueOf(timestampMs))
                .addQueryParameter("devuid", BAIDU_DEVICE_ID)
                .addQueryParameter("channel", "android")
                .addQueryParameter("clienttype", "1")
                .addQueryParameter("version", BAIDU_APP_VERSION)
                .build();
        JSONObject download = postRawJson(downloadUrl.toString(), cookie, body);
        return baiduResult(download, cookie);
    }

    private static Result resolveBaiduWeb(String cookie, String surl, String shareId, String uk, String fsId, String seKey) throws Exception {

        HttpUrl signUrl = HttpUrl.parse(BAIDU + "share/tplconfig").newBuilder()
                .addQueryParameter("fields", "sign,timestamp")
                .addQueryParameter("channel", "chunlei")
                .addQueryParameter("web", "1")
                .addQueryParameter("app_id", "250528")
                .addQueryParameter("clienttype", "0")
                .addQueryParameter("surl", surl)
                .build();
        JSONObject sign = getJson(signUrl.toString(), cookie);
        JSONObject signData = sign.optJSONObject("data");
        if (sign.optInt("errno", -1) != 0 || signData == null) throw baiduError("无法获取下载签名", sign);
        String signature = signData.optString("sign");
        String timestamp = String.valueOf(signData.opt("timestamp"));
        if (signature.isEmpty() || timestamp.isEmpty() || "null".equals(timestamp)) throw new IllegalStateException("百度网盘没有返回下载签名");

        FormBody body = new FormBody.Builder()
                .add("encrypt", "0")
                .add("extra", new JSONObject().put("sekey", seKey).toString())
                .add("fid_list", "[" + fsId + "]")
                .add("primaryid", shareId)
                .add("product", "share")
                .add("type", "nolimit")
                .add("uk", uk)
                .build();
        HttpUrl downloadUrl = HttpUrl.parse(BAIDU + "api/sharedownload").newBuilder()
                .addQueryParameter("app_id", "250528")
                .addQueryParameter("channel", "chunlei")
                .addQueryParameter("clienttype", "12")
                .addQueryParameter("web", "1")
                .addQueryParameter("sign", signature)
                .addQueryParameter("timestamp", timestamp)
                .build();
        JSONObject download = postJson(downloadUrl.toString(), cookie, body);
        return baiduResult(download, cookie);
    }

    private static Result baiduResult(JSONObject download, String cookie) {
        if (download.optInt("errno", -1) != 0) throw baiduError("获取播放地址失败", download);
        JSONArray list = download.optJSONArray("list");
        JSONObject first = list == null || list.length() == 0 ? null : list.optJSONObject(0);
        String link = first == null ? "" : first.optString("dlink");
        if (link.isEmpty()) throw new IllegalStateException("百度网盘没有返回播放地址");

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", NETDISK_UA);
        headers.put("Cookie", cookie);
        Result result = Result.empty();
        result.setParse(0);
        result.setUrl(link);
        result.setHeader(headers);
        return result;
    }

    private static JSONObject getJson(String url, String cookie) throws Exception {
        Request request = request(url, cookie).get().build();
        return execute(request);
    }

    private static JSONObject postJson(String url, String cookie, FormBody body) throws Exception {
        Request request = request(url, cookie).post(body).build();
        return execute(request);
    }

    private static JSONObject postRawJson(String url, String cookie, String body) throws Exception {
        RequestBody requestBody = RequestBody.create(FORM, body);
        Request request = request(url, cookie).post(requestBody).build();
        return execute(request);
    }

    private static Request.Builder request(String url, String cookie) {
        return new Request.Builder().url(url).header("User-Agent", NETDISK_UA).header("Cookie", cookie)
                .header("Accept", "application/json, text/plain, */*");
    }

    private static JSONObject execute(Request request) throws Exception {
        try (Response response = OkHttp.client(12_000).newCall(request).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) throw new IllegalStateException("HTTP " + response.code());
            if (text.isEmpty()) throw new IllegalStateException("网盘服务返回空内容");
            return new JSONObject(text);
        }
    }

    private static IllegalStateException baiduError(String prefix, JSONObject response) {
        String detail = first(response, "show_msg", "errmsg", "error_msg", "message");
        int errno = response.optInt("errno", -1);
        return new IllegalStateException(prefix + (detail.isEmpty() ? "（errno " + errno + "）" : "：" + detail));
    }

    private static String cookieValue(String cookie, String name) {
        if (cookie == null) return "";
        for (String part : cookie.split(";")) {
            int equals = part.indexOf('=');
            if (equals > 0 && name.equalsIgnoreCase(part.substring(0, equals).trim())) {
                return part.substring(equals + 1).trim();
            }
        }
        return "";
    }

    private static String decodeSeKey(String value) {
        String output = value;
        if (output.contains("%")) {
            try {
                // Preserve literal '+' while decoding percent escapes from a
                // Spider episode payload.
                output = java.net.URLDecoder.decode(output.replace("+", "%2B"), StandardCharsets.UTF_8.name());
            } catch (Exception ignored) {
            }
        }
        return output.replace('-', '+').replace('~', '=').replace('_', '/');
    }

    private static byte[] sha1(String value) throws Exception {
        return MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmacSha1(String key, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] value) {
        StringBuilder output = new StringBuilder(value.length * 2);
        for (byte item : value) output.append(String.format("%02x", item & 0xff));
        return output.toString();
    }

    private static String rc4(byte[] input, byte[] key) {
        if (key.length == 0) throw new IllegalArgumentException("百度账号 UID 为空");
        int[] state = new int[256];
        for (int i = 0; i < state.length; i++) state[i] = i;
        for (int i = 0, j = 0; i < state.length; i++) {
            j = (j + state[i] + (key[i % key.length] & 0xff)) & 0xff;
            int swap = state[i];
            state[i] = state[j];
            state[j] = swap;
        }
        byte[] output = new byte[input.length];
        for (int n = 0, i = 0, j = 0; n < input.length; n++) {
            i = (i + 1) & 0xff;
            j = (j + state[i]) & 0xff;
            int swap = state[i];
            state[i] = state[j];
            state[j] = swap;
            int keyByte = state[(state[i] + state[j]) & 0xff];
            output[n] = (byte) (input[n] ^ keyByte);
        }
        return new String(output, StandardCharsets.UTF_8);
    }

    private static boolean isBaiduShare(String flag, JSONObject value) {
        if (value == null) return false;
        boolean shape = has(value, "share_id", "shareid") && has(value, "uk")
                && has(value, "fs_id", "fsid") && has(value, "seKey", "sekey", "randsk") && has(value, "surl");
        if (!shape) return false;
        String label = (flag + " " + value.optString("flag")).toLowerCase();
        return label.contains("嘟嘟") || label.contains("百度") || label.contains("baidu");
    }

    private static JSONObject object(String value) {
        if (value == null) return null;
        String text = value.trim();
        if (!text.startsWith("{") || !text.endsWith("}")) return null;
        try { return new JSONObject(text); } catch (Exception ignored) { return null; }
    }

    private static boolean has(JSONObject object, String... names) {
        return !first(object, names).isEmpty();
    }

    private static String required(JSONObject object, String... names) {
        String value = first(object, names);
        if (value.isEmpty()) throw new IllegalArgumentException("分享信息缺少 " + names[0]);
        return value;
    }

    private static String first(JSONObject object, String... names) {
        if (object == null) return "";
        for (String name : names) {
            Object raw = object.opt(name);
            String value = raw == null || JSONObject.NULL.equals(raw) ? "" : String.valueOf(raw).trim();
            if (!value.isEmpty()) return value;
        }
        return "";
    }
}
