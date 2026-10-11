package com.fongmi.android.tv.server.cloud;

import com.fongmi.android.tv.server.Server;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Bridge-owned compatibility boundary for cloud credentials.
 *
 * Imported subscriptions keep their search/detail spiders, while documented
 * token, cookie and Cloud-drive hooks are redirected to loopback-only Bridge
 * endpoints. This is the same compatibility boundary used by alist-tvbox:
 * the source reads credentials from our endpoint, while Bridge remains the
 * owner of the account and the source never starts a second login flow.
 */
public final class CloudGateway {

    private CloudGateway() {
    }

    public static void rewriteSubscription(JSONObject config) throws Exception {
        JSONArray sites = config.optJSONArray("sites");
        if (sites == null) return;
        int filteredLoginSites = removeSourceLoginSites(sites);
        int rewritten = 0;
        Map<String, String> detected = new LinkedHashMap<>();
        for (int i = 0; i < sites.length(); i++) {
            JSONObject site = sites.optJSONObject(i);
            if (site == null) continue;
            detectProviders(site, detected);
            if (site.has("ext")) {
                Object before = site.opt("ext");
                Object after = rewriteValue(before, "");
                if (!String.valueOf(before).equals(String.valueOf(after))) {
                    site.put("ext", after);
                    rewritten++;
                }
            }
            if (injectBilibiliCredentialEndpoint(site)) rewritten++;
        }
        JSONArray providers = new JSONArray();
        for (Map.Entry<String, String> entry : detected.entrySet()) {
            boolean managed = CloudAccountStore.supportsProvider(entry.getKey());
            providers.put(new JSONObject()
                    .put("id", entry.getKey())
                    .put("name", entry.getValue())
                    .put("mode", managed ? "bridge" : "source")
                    .put("configured", managed && CloudAccountStore.firstSecretForProvider(entry.getKey()).length() > 0));
        }
        config.put("okboxCloud", new JSONObject()
                .put("mode", "bridge")
                .put("revision", 5)
                .put("filteredLoginSites", filteredLoginSites)
                .put("rewrittenSites", rewritten)
                .put("providers", providers)
                .put("accountStatus", "/api/cloud")
                .put("accounts", "/api/cloud/accounts"));
    }

    /**
     * Removes only the source-owned account/configuration entries which are
     * superseded by Bridge account management. Search sites and personal
     * drive browsing sites remain available and receive Bridge credentials
     * through the rewritten endpoints below.
     */
    private static int removeSourceLoginSites(JSONArray sites) {
        int removed = 0;
        for (int i = sites.length() - 1; i >= 0; i--) {
            JSONObject site = sites.optJSONObject(i);
            if (site == null || !isSourceLoginSite(site)) continue;
            sites.remove(i);
            removed++;
        }
        return removed;
    }

    private static boolean isSourceLoginSite(JSONObject site) {
        String key = site.optString("key").trim();
        String api = site.optString("api").trim();
        // 饭太硬：“点我切源”是普通站点入口，只有源自带的网盘配置页需要移除。
        if ("MDrive".equals(key) && "csp_MyDriveGuard".equals(api)) return true;
        // 肥猫：源自带配置中心。
        if ("config".equalsIgnoreCase(key) && "csp_Config".equals(api)) return true;
        // 王二小：扫码提示与两个源内配置中心。
        if ("Doubanaaa".equals(key)) return true;
        return "csp_WexconfigGuard".equals(api) || "csp_WexokconfigGuard".equals(api);
    }

    /** Compatible with TVFan Cloud-drive.txt. Only the local Spider may call it. */
    public static JSONObject tvFanConfig() throws Exception {
        JSONObject result = new JSONObject();
        put(result, "token", "aliyun", "refreshToken", "refresh_token");
        put(result, "quarkCookie", "quark", "cookie");
        put(result, "ucCookie", "uc", "cookie");
        put(result, "bdCk", "baidu", "cookie");
        return result;
    }

    /** Compatible with common tokenm.json consumers. Only the local Spider may call it. */
    public static JSONObject tokenConfig() throws Exception {
        JSONObject result = tvFanConfig();
        put(result, "open_token", "aliyun", "openToken", "open_token");
        put(result, "quark_cookie", "quark", "cookie");
        put(result, "uc_cookie", "uc", "cookie");
        put(result, "pan115_cookie", "pan115", "cookie");
        put(result, "pan115_delete_code", "pan115", "delete_code", "deleteCode");
        put(result, "baidu_cookie", "baidu", "cookie");
        put(result, "pan123_username", "pan123", "username");
        put(result, "pan123_password", "pan123", "password");
        put(result, "pan123_token", "pan123", "token", "access_token");
        put(result, "pan123_authorization", "pan123", "authorization");
        put(result, "pan123_loginuuid", "pan123", "loginuuid");
        put(result, "pan189_username", "tianyi", "username");
        put(result, "pan189_password", "tianyi", "password");
        put(result, "yd_auth", "mobile139", "token");
        put(result, "bili_cookie", "bilibili", "cookie");
        put(result, "thunder_username", "thunder", "username");
        put(result, "thunder_password", "thunder", "password");
        put(result, "thunder_captchatoken", "thunder", "captcha_token", "captchaToken", "token");
        put(result, "pikpak_username", "pikpak", "username");
        put(result, "pikpak_password", "pikpak", "password");
        if (result.has("pan123_username") || result.has("pan123_token")) result.put("pan123_flags", "4kz");
        if (result.has("pan189_username")) result.put("pan189_flags", "4kz");
        if (result.has("yd_auth")) {
            String token = result.optString("yd_auth");
            if (!token.toLowerCase(Locale.ROOT).startsWith("basic ")) result.put("yd_auth", "Basic " + token);
            result.put("yd_thread_limit", 4).put("yd_flags", "auto|4kz").put("yd_danmu", true);
        }
        return result;
    }

    public static String credential(String provider) throws Exception {
        JSONObject secret = CloudTokenService.ensureFresh(provider);
        return switch (provider) {
            case "aliyun" -> first(secret, "refreshToken", "refresh_token", "token");
            case "quark", "uc", "pan115", "baidu", "guangya", "bilibili" -> first(secret, "cookie", "token");
            case "mobile139" -> first(secret, "token", "accessToken", "access_token");
            default -> first(secret, "token", "accessToken", "access_token", "cookie");
        };
    }

    public static JSONObject accountState(String provider) throws Exception {
        boolean supported = CloudAccountStore.supportsProvider(provider);
        boolean saved = supported && CloudAccountStore.firstSecretForProvider(provider).length() > 0;
        return new JSONObject()
                .put("token", "")
                .put("provider", provider)
                .put("providerName", CloudAccountStore.providerName(provider))
                .put("state", saved ? "confirmed" : "missing")
                .put("managed", true)
                .put("message", saved ? "Bridge 已选择保存的" + CloudAccountStore.providerName(provider) + "账号"
                        : "请先在 Bridge 网盘账号中添加" + CloudAccountStore.providerName(provider));
    }

    public static JSONObject managedLoginState() throws Exception {
        return new JSONObject()
                .put("token", "")
                .put("state", "managed")
                .put("managed", true)
                .put("message", "该订阅源的网盘登录已由 Bridge 接管，请在 Bridge 网盘账号中统一添加账号");
    }

    public static JSONObject status() throws Exception {
        return new JSONObject()
                .put("mode", "bridge")
                .put("revision", 5)
                .put("credentialTransport", "loopback-only")
                .put("accountCount", CloudAccountStore.summaries().length())
                .put("providers", CloudAccountStore.catalog().getJSONArray("providers"))
                .put("accounts", "/api/cloud/accounts");
    }

    public static JSONObject credentials() throws Exception {
        CloudTokenService.ensureFresh("aliyun");
        return CloudAccountStore.credentials();
    }

    public static boolean isLoopback(String remote) {
        return "127.0.0.1".equals(remote) || "::1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote);
    }

    private static Object rewriteValue(Object value, String key) throws Exception {
        if (value instanceof JSONObject object) {
            JSONObject copy = new JSONObject(object.toString());
            Iterator<String> keys = copy.keys();
            while (keys.hasNext()) {
                String childKey = keys.next();
                copy.put(childKey, rewriteValue(copy.opt(childKey), childKey));
            }
            return copy;
        }
        if (value instanceof JSONArray array) {
            JSONArray copy = new JSONArray();
            for (int i = 0; i < array.length(); i++) copy.put(rewriteValue(array.opt(i), key));
            return copy;
        }
        if (!(value instanceof String text)) return value;

        String lowerKey = key.toLowerCase(Locale.ROOT);
        if (lowerKey.equals("cloud-drive") || lowerKey.equals("clouddrive")) return configEndpoint();
        if (lowerKey.equals("alitoken")) return credentialEndpoint("aliyun");
        if (lowerKey.equals("quarkcookie")) return credentialEndpoint("quark");
        if ((lowerKey.equals("token") || lowerKey.equals("alitoken")) && text.equals("file://TV/ali_token.txt")) {
            return credentialEndpoint("aliyun");
        }
        if ((lowerKey.equals("cookie") || lowerKey.equals("quarkcookie")) && text.equals("file://TV/quark_cookie.txt")) {
            return credentialEndpoint("quark");
        }
        return rewriteText(text);
    }

    private static String rewriteText(String value) {
        String result = value;
        result = result.replace("./tvfan/Cloud-drive.txt", configEndpoint());
        result = result.replace("tvfan/Cloud-drive.txt", configEndpoint());
        result = result.replace("./lib/tokenm.json", tokenEndpoint());
        result = result.replace("lib/tokenm.json", tokenEndpoint());
        result = replaceAny(result, credentialEndpoint("aliyun"),
                "http://127.0.0.1:9978/file/tvfan/token.txt",
                "http://127.0.0.1:9978/file/tvfan/tokengo.txt",
                "http://127.0.0.1:9978/file/tvbox/token.txt",
                "http://127.0.0.1:9978/file/cainisi/token.txt",
                "http://127.0.0.1:9978/file/fatcat/token.txt",
                "file://TV/ali_token.txt");
        result = replaceAny(result, credentialEndpoint("quark"),
                "http://127.0.0.1:9978/file/tvfan/cookie.txt",
                "http://127.0.0.1:9978/file/tvbox/quark_cookie.txt",
                "file://TV/quark_cookie.txt");
        return result;
    }

    /**
     * Detects standard account families used by an imported source without
     * executing its Spider. Product/source labels such as 嘟嘟 or 多多 are
     * deliberately not treated as account providers: they are aggregation
     * lines and must first expose an underlying Baidu/UC/etc. share.
     */
    private static void detectProviders(JSONObject site, Map<String, String> output) {
        String text = site.toString().toLowerCase(Locale.ROOT);
        detect(text, output, "aliyun", "阿里云盘", "aliyun", "aliyundrive", "alipan", "阿里", "ali_token", "alitoken");
        detect(text, output, "quark", "夸克网盘", "quark", "夸克", "quark_cookie", "quarkcookie");
        detect(text, output, "uc", "UC 网盘", "ucpan", "drive.uc.cn", "uc_cookie", "uccookie", "优汐");
        detect(text, output, "pan115", "115 网盘", "115网盘", "115 网盘", "pan115", "115.com");
        detect(text, output, "pan123", "123 网盘", "123网盘", "123 网盘", "pan123", "123pan");
        detect(text, output, "tianyi", "天翼云盘", "天翼", "cloud189", "pan189", "189.cn");
        detect(text, output, "mobile139", "移动云盘", "移动云盘", "pan139", "yd_auth", "139.com");
        detect(text, output, "mobile139", "移动云盘", "wexwoydpan", "wexydpan", "我的┃异动", "我的|异动");
        detect(text, output, "bilibili", "B站", "csp_bili", "bilibili", "哔哩", "b站");
        detect(text, output, "thunder", "迅雷云盘", "迅雷", "xunlei", "thunder_");
        detect(text, output, "pikpak", "PikPak", "pikpak");
        detect(text, output, "baidu", "百度网盘", "百度", "baidupan", "baidu_cookie", "bdck");
        detect(text, output, "guangya", "光鸭云盘", "光鸭", "guangya");
    }

    private static void detect(String text, Map<String, String> output, String id, String name, String... markers) {
        for (String marker : markers) {
            if (!text.contains(marker.toLowerCase(Locale.ROOT))) continue;
            output.putIfAbsent(id, name);
            return;
        }
    }

    /**
     * Bili spiders accept an HTTP cookie source in ext.cookie. Point them at
     * the loopback-only credential endpoint so the LAN manifest never contains
     * the user's Bilibili Cookie.
     */
    private static boolean injectBilibiliCredentialEndpoint(JSONObject site) throws Exception {
        String api = site.optString("api");
        if (!("csp_Bili".equals(api) || "csp_BiliGuard".equals(api))) return false;
        Object ext = site.opt("ext");
        if (!(ext instanceof JSONObject object)) return false;
        String endpoint = credentialEndpoint("bilibili");
        if (endpoint.equals(object.optString("cookie"))) return false;
        JSONObject copy = new JSONObject(object.toString());
        copy.put("cookie", endpoint);
        site.put("ext", copy);
        return true;
    }

    private static String replaceAny(String value, String replacement, String... candidates) {
        String result = value;
        for (String candidate : candidates) result = result.replace(candidate, replacement);
        return result;
    }

    private static void put(JSONObject target, String output, String provider, String... fields) throws Exception {
        String value = first(CloudTokenService.ensureFresh(provider), fields);
        if (!value.isEmpty()) target.put(output, value);
    }

    private static String first(JSONObject object, String... names) {
        for (String name : names) {
            String value = object.optString(name).trim();
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private static String configEndpoint() { return localBase() + "/cloud/config"; }
    private static String tokenEndpoint() { return localBase() + "/cloud/tokenm"; }
    private static String credentialEndpoint(String provider) { return localBase() + "/cloud/credential/" + provider; }
    private static String localBase() { return Server.get().getAddress(true); }
}
