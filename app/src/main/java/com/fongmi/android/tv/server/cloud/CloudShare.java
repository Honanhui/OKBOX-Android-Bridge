package com.fongmi.android.tv.server.cloud;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

import okhttp3.HttpUrl;

/** Identifies a cloud share without executing a source-provided Spider. */
public final class CloudShare {

    private CloudShare() {
    }

    public static JSONObject inspect(String value) throws Exception {
        String url = value == null ? "" : value.trim();
        if (url.isEmpty()) throw new IllegalArgumentException("请输入网盘分享链接");
        if (url.length() > 4096) throw new IllegalArgumentException("网盘分享链接过长");
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null || (!"http".equals(parsed.scheme()) && !"https".equals(parsed.scheme()))) {
            throw new IllegalArgumentException("网盘分享链接格式无效");
        }
        String provider = provider(parsed.host());
        JSONObject result = new JSONObject().put("url", parsed.toString()).put("provider", provider);
        if (provider.isEmpty()) return result.put("recognized", false).put("message", "暂未识别该网盘链接");
        JSONArray matches = new JSONArray();
        JSONArray accounts = CloudAccountStore.summaries();
        for (int i = 0; i < accounts.length(); i++) {
            JSONObject account = accounts.getJSONObject(i);
            if (provider.equals(account.optString("provider"))) matches.put(account);
        }
        return result.put("recognized", true)
                .put("providerName", CloudAccountStore.providerName(provider))
                .put("accounts", matches)
                .put("message", matches.length() > 0 ? "已找到可用的 Bridge 网盘账号" : "请先在 Bridge 添加该网盘账号");
    }

    private static String provider(String host) {
        String value = host.toLowerCase(Locale.ROOT);
        if (value.endsWith("aliyundrive.com") || value.endsWith("alipan.com")) return "aliyun";
        if (value.endsWith("quark.cn")) return "quark";
        if (value.endsWith("drive.uc.cn") || value.endsWith("uc.cn")) return "uc";
        if (value.endsWith("115.com")) return "pan115";
        if (value.endsWith("123pan.com")) return "pan123";
        if (value.endsWith("189.cn")) return "tianyi";
        if (value.endsWith("139.com")) return "mobile139";
        if (value.endsWith("xunlei.com")) return "thunder";
        if (value.endsWith("mypikpak.com") || value.endsWith("pikpak.me")) return "pikpak";
        if (value.endsWith("baidu.com")) return "baidu";
        if (value.contains("guangya")) return "guangya";
        return "";
    }
}
