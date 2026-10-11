package com.fongmi.android.tv.server.cloud;

import com.github.catvod.net.OkHttp;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Refreshes short-lived provider tokens while the long-lived credential remains in the Bridge vault. */
public final class CloudTokenService {
    private static final long REFRESH_MARGIN = 5 * 60 * 1000L;

    private CloudTokenService() {
    }

    public static synchronized JSONObject ensureFresh(String provider) throws Exception {
        if (!"aliyun".equals(provider)) return CloudAccountStore.firstSecretForProvider(provider);
        JSONObject secret = CloudAccountStore.firstSecretForProvider(provider);
        String refresh = first(secret, "refreshToken", "refresh_token", "token");
        if (refresh.isEmpty()) return secret;
        long expiry = secret.optLong("access_expires_at");
        if (!first(secret, "access_token", "accessToken").isEmpty() && expiry > System.currentTimeMillis() + REFRESH_MARGIN) return secret;

        JSONObject body = new JSONObject().put("refresh_token", refresh).put("grant_type", "refresh_token");
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json; charset=utf-8");
        headers.put("User-Agent", "AliApp(AYSD/6.6.0) okhttp/4.9.3");
        RequestBody request = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), body.toString());
        try (Response response = OkHttp.newCall("https://auth.aliyundrive.com/v2/account/token", headers, request).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) throw new IllegalStateException("阿里云盘 Token 刷新失败：HTTP " + response.code());
            JSONObject result = new JSONObject(text);
            String access = result.optString("access_token");
            if (access.isEmpty()) throw new IllegalStateException("阿里云盘没有返回 access_token");
            JSONObject updated = new JSONObject()
                    .put("access_token", access)
                    .put("access_expires_at", System.currentTimeMillis() + Math.max(300, result.optLong("expires_in", 7200)) * 1000L);
            String rotated = result.optString("refresh_token");
            if (!rotated.isEmpty()) updated.put("refreshToken", rotated).put("refresh_token", rotated);
            CloudAccountStore.mergeProviderSecret(provider, updated);
            secret = CloudAccountStore.firstSecretForProvider(provider);
        }
        return secret;
    }

    private static String first(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = object.optString(key).trim();
            if (!value.isEmpty()) return value;
        }
        return "";
    }
}
