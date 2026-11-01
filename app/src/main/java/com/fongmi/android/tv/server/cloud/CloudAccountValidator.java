package com.fongmi.android.tv.server.cloud;
import com.github.catvod.net.OkHttp;
import android.util.Base64;



import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Performs provider-aware checks without returning credentials to the LAN client. */
public final class CloudAccountValidator {
    private CloudAccountValidator() {
    }

    public static JSONObject validate(String id) throws Exception {
        JSONObject account = CloudAccountStore.account(id);
        if (account.optBoolean("disabled")) return CloudAccountStore.setValidation(id, "disabled", "账号已停用");
        String provider = account.optString("provider");
        JSONObject secret = account.optJSONObject("secret");
        if (secret == null) secret = new JSONObject();
        try {
            if ("tianyi".equals(provider)) return CloudTianyi.validate(id);
            String message = switch (provider) {
                case "aliyun" -> validateAliyun();
                case "uc" -> validateUc(secret);
                case "uctv" -> CloudUcTv.validate(secret);
                case "quark" -> validateQuark(secret);
                case "baidu" -> validateBaidu(secret);
                case "mobile139" -> validateMobileAuthorization(account, secret);
                case "pan123" -> validate123(secret);
                case "pan115" -> validate115(secret);
                case "pan123community" -> validate123Community(secret);
                case "thunder" -> validateThunder(secret);
                case "bilibili" -> validateBilibili(secret);
                default -> throw new IllegalStateException("尚未实现该网盘的官方登录校验，无法确认凭证有效性");
            };
            return CloudAccountStore.setValidation(id, "valid", message);
        } catch (VerificationRequiredException error) {
            return CloudAccountStore.setVerification(id, error.getMessage(), error.url);
        } catch (Exception error) {
            String message = error.getMessage();
            if (message == null || message.isBlank()) message = "网盘服务未返回有效结果";
            return CloudAccountStore.setValidation(id, "invalid", message);
        }
    }

    /** Explicit sign-in path. Account-card checks must continue to use {@link #validate(String)}. */
    public static JSONObject login(String id) throws Exception {
        JSONObject account = CloudAccountStore.account(id);
        if ("tianyi".equals(account.optString("provider"))) {
            try { return CloudTianyi.login(id); }
            catch (Exception error) { return CloudAccountStore.setValidation(id, "invalid", error.getMessage() == null ? "天翼登录失败" : error.getMessage()); }
        }
        if (!"thunder".equals(account.optString("provider"))) return validate(id);
        JSONObject secret = account.optJSONObject("secret");
        if (secret == null) secret = new JSONObject();
        try {
            return CloudAccountStore.setValidation(id, "valid", loginThunder(secret));
        } catch (VerificationRequiredException error) {
            return CloudAccountStore.setVerification(id, error.getMessage(), error.url);
        } catch (Exception error) {
            String message = error.getMessage();
            if (message == null || message.isBlank()) message = "迅雷登录未返回有效结果";
            return CloudAccountStore.setValidation(id, "invalid", message);
        }
    }

    private static JSONObject official(Request request, String provider) throws Exception {
        try (Response response = OkHttp.client(12_000).newCall(request).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            JSONObject result;
            try { result = new JSONObject(text); } catch (Exception error) {
                throw new IllegalStateException(provider + "登录校验未返回有效 JSON（HTTP " + response.code() + "）");
            }
            if (!response.isSuccessful()) throw new IllegalStateException(provider + "登录校验失败（HTTP " + response.code() + "）：" + first(result, "message", "msg", "error_description"));
            return result;
        }
    }

    private static String validateQuark(JSONObject secret) throws Exception {
        String cookie = first(secret, "cookie", "Cookie");
        if (cookie.isEmpty()) throw new IllegalStateException("夸克 Cookie 为空，请重新登录");
        JSONObject result = official(new Request.Builder().url("https://drive-pc.quark.cn/1/clouddrive/member?pr=ucpro&fr=pc&uc_param_str=&fetch_subscribe=true&fetch_identity=true")
            .header("Cookie", cookie).header("Referer", "https://pan.quark.cn/").header("User-Agent", "Mozilla/5.0").build(), "夸克");
        if (result.optInt("status", -1) != 200 || result.optInt("code", 0) != 0 || result.optJSONObject("data") == null)
            throw new IllegalStateException("夸克 Cookie 校验失败：" + first(result, "message", "msg") + "（status=" + result.optInt("status", -1) + "，code=" + result.optInt("code", -1) + "）");
        return "登录有效";
    }

    private static String validateBaidu(JSONObject secret) throws Exception {
        String cookie = first(secret, "cookie", "Cookie");
        if (cookie.isEmpty()) throw new IllegalStateException("百度 Cookie 为空，请重新登录");
        JSONObject result = official(new Request.Builder().url("https://pan.baidu.com/rest/2.0/membership/user/info?method=query&clienttype=0&app_id=250528&web=1")
            .header("Cookie", cookie).header("Referer", "https://pan.baidu.com/disk/main").header("User-Agent", "netdisk").build(), "百度");
        JSONObject user = result.optJSONObject("user_info");
        int code = result.has("errno") ? result.optInt("errno", -1) : result.optInt("error_code", 0);
        if (code != 0 || user == null || user.optLong("uk", 0) <= 0)
            throw new IllegalStateException("百度 Cookie 校验失败：" + first(result, "errmsg", "message", "msg") + "（code=" + code + (user == null ? "，缺少用户信息" : "") + "）");
        return "登录有效";
    }

    private static String validateMobileAuthorization(JSONObject account, JSONObject secret) throws Exception {
        String decoded;
        try { decoded = new String(java.util.Base64.getDecoder().decode(first(secret, "token")), "UTF-8"); }
        catch (Exception error) { throw new IllegalStateException("移动 Token 格式无效，请重新扫码登录"); }
        String[] parts = decoded.split(":", 3);
        if (parts.length != 3 || parts[1].isBlank() || parts[2].isBlank()) throw new IllegalStateException("移动 Token 缺少账号或授权信息，请重新扫码登录");
        parts[2] = CloudProviderQrService.normalizeMobile139Token(parts[2], "");
        String xml = "<root><token>" + xmlEscape(parts[2]) + "</token><account>" + xmlEscape(parts[1]) + "</account><clienttype>656</clienttype></root>";
        Request request = new Request.Builder().url("https://aas.caiyun.feixin.10086.cn/tellin/authTokenRefresh.do")
            .post(RequestBody.create(MediaType.parse("application/xml; charset=utf-8"), xml)).build();
        try (Response response = OkHttp.client(12_000).newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IllegalStateException("移动官方 Token 授权检查失败（HTTP " + response.code() + "）");
            String body = response.body() == null ? "" : response.body().string();
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            org.w3c.dom.Document document = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
            String code = xmlValue(document, "return"), token = xmlValue(document, "token");
            if (!"0".equals(code) || token.isBlank()) throw new IllegalStateException("移动官方 Token 授权检查失败：" + xmlValue(document, "desc") + "（code=" + code + "）");
            String refreshed = java.util.Base64.getEncoder().encodeToString((parts[0] + ":" + parts[1] + ":" + token).getBytes(StandardCharsets.UTF_8));
            JSONObject values = new JSONObject().put("token", refreshed).put("authorization", "Basic " + refreshed).put("account", parts[1]).put("auth_token", token);
            JSONObject loginData = secret.optJSONObject("loginData");
            if (loginData != null) values.put("loginData", new JSONObject(loginData.toString()).put("token", token));
            CloudAccountStore.save(account.optString("id"), "mobile139", account.optString("name"), values, account);
            return "登录有效，已通过官方 Token 授权检查";
        }
    }
    private static String xmlEscape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    private static String xmlValue(org.w3c.dom.Document document, String tag) {
        org.w3c.dom.NodeList nodes = document.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }
    private static String validateMobile(JSONObject secret) throws Exception {
        String token = first(secret, "token");
        if (token.isEmpty()) throw new IllegalStateException("移动 Token 为空，请重新登录");
        String account = first(secret, "account");
        if (account.isEmpty()) {
            try { String[] parts = new String(java.util.Base64.getDecoder().decode(token), "UTF-8").split(":"); if (parts.length > 1) account = parts[1]; }
            catch (Exception error) { throw new IllegalStateException("移动 Token 格式无效，请重新扫码登录"); }
        }
        if (account.isEmpty()) throw new IllegalStateException("移动授权缺少账号信息，请重新登录");
        String domain = first(secret, "userDomainId", "user_domain_id");
        JSONObject bodyObject = new JSONObject();
        if (!domain.isEmpty()) bodyObject.put("userDomainId", domain);
        else bodyObject.put("commonAccountInfo", new JSONObject().put("account", account).put("accountType", 1));
        String body = bodyObject.toString();
        String ts = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String nonce = UUID.randomUUID().toString().replace("-", "").substring(0,16);
        String encoded = java.net.URLEncoder.encode(body, "UTF-8").replace("+", "%20").replace("%21", "!").replace("%27", "'").replace("%28", "(").replace("%29", ")").replace("%7E", "~");
        char[] chars = encoded.toCharArray(); java.util.Arrays.sort(chars);
        String b64 = java.util.Base64.getEncoder().encodeToString(new String(chars).getBytes(StandardCharsets.UTF_8));
        String sign = digest("MD5", digest("MD5", b64) + digest("MD5", ts + ":" + nonce)).toUpperCase(java.util.Locale.ROOT);
        JSONObject result = official(new Request.Builder().url("https://user-njs.yun.139.com/user/disk/quota/detail")
            .header("Authorization", "Basic " + token).header("caller", "web").header("mcloud-channel", "1000101").header("mcloud-client", "10701").header("mcloud-version", "7.14.0").header("mcloud-sign", ts + "," + nonce + "," + sign)
            .header("CMS-DEVICE", "default").header("Origin", "https://yun.139.com").header("Referer", "https://yun.139.com/w/").header("x-DeviceInfo", "||9|7.14.0|chrome|120.0.0.0|||windows 10||zh-CN|||").header("x-huawei-channelSrc", "10000034").header("x-inner-ntwk", "2").header("x-m4c-caller", "PC").header("x-m4c-src", "10002").header("x-SvcType", "1").header("Inner-Hcy-Router-Https", "1")
            .post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"), body)).build(), "移动");
        JSONObject data = result.optJSONObject("data");
        JSONObject status = result.optJSONObject("result");
        String code = status == null ? first(result, "code", "resultCode") : first(status, "resultCode", "code");
        if ((!code.isEmpty() && !"0".equals(code) && !"000000".equals(code)) || data == null || !data.has("diskSize"))
            throw new IllegalStateException("移动 Token 校验失败：" + (status == null ? first(result, "message", "msg", "resultDesc") : first(status, "resultDesc", "message")) + "（code=" + code + "）");
        return "登录有效";
    }
    private static String validateUc(JSONObject secret) throws Exception {
        String cookie = first(secret, "cookie", "Cookie");
        if (cookie.isEmpty()) throw new IllegalStateException("UC Cookie 为空，请扫码登录");
        Request request = new Request.Builder().url("https://pc-api.uc.cn/1/clouddrive/file/sort?pr=UCBrowser&fr=pc&pdir_fid=0&_page=1&_size=1&_fetch_total=1&_fetch_sub_dirs=0&_sort=file_type:asc,updated_at:desc")
                .header("Cookie", cookie).header("Referer", "https://drive.uc.cn/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36").build();
        try (Response response = OkHttp.client(12_000).newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            JSONObject result = body.isEmpty() ? new JSONObject() : new JSONObject(body);
            if (response.code() == 401 || result.optInt("status") == 401) throw new IllegalStateException("UC Cookie 已失效或授权被拒绝，请重新扫码登录（HTTP 401）");
            if (!response.isSuccessful() || result.optInt("status", -1) != 200) throw new IllegalStateException("UC 官方目录验证失败（HTTP " + response.code() + "），不能确认登录有效");
            return "登录有效，已通过 UC 官方目录检查";
        }
    }
    private static String validateAliyun() throws Exception {
        JSONObject secret = CloudTokenService.ensureFresh("aliyun");
        if (first(secret, "access_token", "accessToken").isEmpty()) throw new IllegalStateException("阿里云盘授权已失效，请重新扫码登录");
        return "登录有效，Token 已刷新";
    }

    private static String validate115(JSONObject secret) throws Exception {
        String cookie = secret.optString("cookie").trim();
        if (cookie.isEmpty() || cookie.contains("\r") || cookie.contains("\n")) throw new IllegalStateException("请填写有效的115 Cookie");
        Request request = new Request.Builder().url("https://my.115.com/?ct=ajax&ac=nav")
            .header("Cookie", cookie).header("Referer", "https://115.com/")
            .header("User-Agent", "Mozilla/5.0").build();
        return parse115Identity(official(request, "115"));
    }

    static String parse115Identity(JSONObject result) {
        JSONObject data = result.optJSONObject("data");
        if (!result.optBoolean("state") || data == null || first(data, "user_id", "uid").isEmpty())
            throw new IllegalStateException("115 Cookie未通过登录校验，请重新登录后更新");
        String name = first(data, "user_name", "uname", "nick_name", "nickname");
        return name.isEmpty() ? "115登录有效；接口未返回账号名称" : "115登录有效，账号：" + name;
    }

    private static String validate123Community(JSONObject secret) throws Exception {
        String cookie = secret.optString("cookie").trim();
        if (cookie.isEmpty() || cookie.contains("\r") || cookie.contains("\n")) throw new IllegalStateException("请填写有效的123社区Cookie");
        Request request = new Request.Builder().url("https://pan1.me/my.htm").header("Cookie", cookie).header("User-Agent", "Mozilla/5.0").build();
        try (Response response = OkHttp.client(12000).newBuilder().followRedirects(false).build().newCall(request).execute()) {
            String html = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful() || !html.contains("user-logout.htm")) throw new IllegalStateException("社区Cookie未通过登录校验，请在pan1.me正常登录后更新");
            String username = communityUsername(html);
            if (username.isEmpty()) {
                Request profileRequest = request.newBuilder().url("https://pan1.me/?my-profile.htm").build();
                try (Response profile = OkHttp.client(12000).newBuilder().followRedirects(false).build().newCall(profileRequest).execute()) {
                    String page = profile.body() == null ? "" : profile.body().string();
                    if (profile.isSuccessful() && page.contains("user-logout.htm")) username = communityUsername(page);
                } catch (Exception ignored) { /* Valid login remains valid if the optional name lookup fails. */ }
            }
            return username.isEmpty() ? "社区登录有效；页面未返回账号名称" : "社区登录有效，账号：" + username;
        }
    }


    /** Only read the signed-in profile form/navigation; never a post author or script value. */
    static String communityUsername(String html) {
        org.jsoup.nodes.Document page = org.jsoup.Jsoup.parse(html);
        String name = "";
        org.jsoup.nodes.Element input = page.selectFirst("input[name=username]");
        if (input != null) name = input.val();
        if (name.isBlank()) { org.jsoup.nodes.Element sidebar = page.selectFirst(".sidebar-user-section-username"); if (sidebar != null) name = sidebar.text(); }
        if (name.isBlank()) for (org.jsoup.nodes.Element link : page.select("a.username[href]")) {
            if (link.attr("href").matches("(?:https://pan1[.]me/|/)?[?]?my(?:-profile)?[.]htm(?:[?#].*)?")) {
                String text = link.text().trim();
                if (!text.matches("我的.*|个人.*|账号.*|设置|用户中心|基本资料|修改密码|修改头像")) name = text;
            }
        }
        String clean = name.replaceAll("[\\p{Cntrl}]", "").trim();
        return clean.substring(0, Math.min(80, clean.length()));
    }

    private static String validate123(JSONObject secret) throws Exception {
        String savedToken = first(secret, "token", "access_token");
        if (!savedToken.isEmpty()) {
            Request.Builder builder = new Request.Builder().url("https://www.123pan.cn/b/api/user/info").get()
                    .header("Authorization", savedToken.regionMatches(true, 0, "Bearer ", 0, 7) ? savedToken : "Bearer " + savedToken)
                    .header("App-Version", "3").header("Platform", "web")
                    .header("Loginuuid", first(secret, "loginuuid"))
                    .header("Accept", "application/json, text/plain, */*")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36");
            try (Response response = OkHttp.client(12_000).newCall(builder.build()).execute()) {
                String text = response.body() == null ? "" : response.body().string();
                JSONObject result = text.isEmpty() ? new JSONObject() : new JSONObject(text);
                int code = result.optInt("code", response.code());
                if (!response.isSuccessful() || (code != 0 && code != 200)) {
                    String message = result.optString("message", result.optString("msg", "123 网盘 Token 已失效"));
                    throw new IllegalStateException(message + (response.isSuccessful() ? "" : "（HTTP " + response.code() + "）"));
                }
                return "登录有效，已通过 Token 检查";
            }
        }
        String username = first(secret, "username");
        String password = first(secret, "password");
        if (username.isEmpty() || password.isEmpty()) throw new IllegalStateException("账号或密码不完整");
        JSONObject body = new JSONObject().put("passport", username).put("password", password).put("type", 1);
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json; charset=utf-8");
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("App-Version", "3");
        headers.put("Platform", "web");
        headers.put("Origin", "https://yun.123pan.cn");
        headers.put("Referer", "https://yun.123pan.cn/");
        headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36");
        RequestBody request = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), body.toString());
        try (Response response = OkHttp.newCall("https://user.123pan.cn/api/user/sign_in", headers, request).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            JSONObject result = text.isEmpty() ? new JSONObject() : new JSONObject(text);
            int code = result.optInt("code", response.code());
            JSONObject data = result.optJSONObject("data");
            String token = data == null ? "" : first(data, "token", "access_token", "accessToken");
            if (!response.isSuccessful() || ((code != 0 && code != 200) || token.isEmpty())) {
                String message = result.optString("message", result.optString("msg", "123 网盘登录失败"));
                throw new IllegalStateException(message + (response.isSuccessful() ? "" : "（HTTP " + response.code() + "）"));
            }
            CloudAccountStore.mergeProviderSecret("pan123", new JSONObject().put("token", token));
            return "账号登录有效";
        }
    }

    private static String validateBilibili(JSONObject secret) throws Exception {
        String cookie = first(secret, "cookie");
        if (cookie.isEmpty()) throw new IllegalStateException("B站 Cookie 为空");
        Request request = new Request.Builder().url("https://api.bilibili.com/x/member/web/account").get()
                .header("Cookie", cookie)
                .header("Referer", "https://www.bilibili.com/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124 Safari/537.36")
                .build();
        try (Response response = OkHttp.client(12_000).newCall(request).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            JSONObject result = text.isEmpty() ? new JSONObject() : new JSONObject(text);
            int code = result.optInt("code", response.code());
            JSONObject data = result.optJSONObject("data");
            if (!response.isSuccessful() || code != 0 || data == null) {
                String message = result.optString("message", "B站 Cookie 已失效");
                throw new IllegalStateException(message + (response.isSuccessful() ? "" : "（HTTP " + response.code() + "）"));
            }
            String name = data.optString("uname", "B站账号");
            return "登录有效：" + name;
        }
    }

    private static String validateThunder(JSONObject secret) throws Exception {
        String deviceId = first(secret, "device_id");
        if (deviceId.length() != 32) deviceId = md5(UUID.randomUUID().toString());

        String access = first(secret, "access_token");
        Exception accessError = null;
        if (!access.isEmpty()) {
            try {
                validateThunderAccess(deviceId, new JSONObject().put("access_token", access), secret);
                return "登录有效，已通过 Token 检查";
            } catch (Exception error) {
                accessError = error;
            }
        }

        String refresh = first(secret, "refresh_token");
        if (!refresh.isEmpty()) {
            try {
                String oauthClient = first(secret, "oauth_client_id");
                if (oauthClient.isEmpty()) oauthClient = "Xp6vsxz_7IYVw2BB";
                JSONObject refreshBody = new JSONObject().put("grant_type", "refresh_token")
                        .put("refresh_token", refresh).put("client_id", oauthClient);
                if ("Xp6vsxz_7IYVw2BB".equals(oauthClient)) refreshBody.put("client_secret", "Xp6vsy4tN9toTVdMSpomVdXpRmES");
                JSONObject token = thunderPost("https://xluser-ssl.xunlei.com/v1/auth/token",
                        refreshBody, thunderHeaders(deviceId, false, oauthClient));
                saveThunderToken(deviceId, secret, token);
                validateThunderAccess(deviceId, token, secret);
                return "登录有效，Token 已刷新并通过账号检查";
            } catch (Exception refreshError) {
                String message = refreshError.getMessage();
                if (message == null || message.isBlank()) message = accessError == null ? "Token 无效" : accessError.getMessage();
                throw new IllegalStateException("迅雷授权已失效，请重新登录获取 Token：" + message);
            }
        }
        if (accessError != null) {
            String message = accessError.getMessage();
            throw new IllegalStateException("迅雷 Token 已失效且没有刷新 Token，请重新授权" + (message == null || message.isBlank() ? "" : "：" + message));
        }
        throw new IllegalStateException("当前迅雷账号没有可复用的 Token，请重新登录；检查操作不会再次执行账号密码登录或打开短信验证");
    }

    private static String loginThunder(JSONObject secret) throws Exception {
        String username = first(secret, "username");
        String password = first(secret, "password");
        if (username.isEmpty() || password.isEmpty()) throw new IllegalStateException("账号或密码不完整");
        String deviceId = first(secret, "device_id");
        if (deviceId.length() != 32) deviceId = md5(UUID.randomUUID().toString());

        JSONObject coreBody = new JSONObject()
                .put("protocolVersion", "301").put("sequenceNo", "1000012").put("platformVersion", "10")
                .put("isCompressed", "0").put("appid", "40").put("clientVersion", "8.31.0.9726")
                .put("peerID", "00000000000000000000000000000000").put("appName", "ANDROID-com.xunlei.downloadprovider")
                .put("sdkVersion", "512000").put("devicesign", thunderDeviceSign(deviceId)).put("netWorkType", "WIFI")
                .put("providerName", "NONE").put("deviceModel", "M2004J7AC").put("deviceName", "Xiaomi_M2004j7ac")
                .put("OSVersion", "12").put("creditkey", first(secret, "creditkey")).put("hl", "zh-CN")
                .put("userName", username).put("passWord", password).put("verifyKey", "").put("verifyCode", "").put("isMd5Pwd", "0");
        JSONObject core = thunderPost("https://xluser-ssl.xunlei.com/xluser.core.login/v3/login", coreBody, thunderHeaders(deviceId, true));
        String sessionId = first(core, "sessionID", "session_id");
        if (sessionId.isEmpty()) throw new IllegalStateException("迅雷登录未返回会话，可能需要设备验证");

        JSONObject meta = new JSONObject();
        if (username.matches("\\w+([-+.']\\w+)*@\\w+([-.]\\w+)*\\.\\w+([-.]\\w+)*")) meta.put("email", username);
        else if (username.length() >= 11 && username.length() <= 18) meta.put("phone_number", username);
        else meta.put("username", username);
        JSONObject captcha = thunderPost("https://xluser-ssl.xunlei.com/v1/shield/captcha/init",
                new JSONObject().put("action", "POST:/v1/auth/signin/token")
                        .put("captcha_token", first(secret, "captcha_token", "captchaToken"))
                        .put("client_id", "Xp6vsxz_7IYVw2BB").put("device_id", deviceId).put("meta", meta)
                        .put("redirect_uri", "xlaccsdk01://xunlei.com/callback?state=harbor"),
                thunderHeaders(deviceId, false));
        String verifyUrl = captcha.optString("url");
        String captchaToken = first(captcha, "captcha_token");
        if (captchaToken.isEmpty()) throw new IllegalStateException("迅雷未返回验证码令牌");
        CloudAccountStore.mergeProviderSecret("thunder", new JSONObject()
                .put("device_id", deviceId).put("captcha_token", captchaToken));
        secret.put("device_id", deviceId).put("captcha_token", captchaToken);
        if (!verifyUrl.isEmpty()) throw new VerificationRequiredException("需要完成一次迅雷设备验证，完成后请再次点登录并保存", verifyUrl);

        Map<String, String> signinHeaders = thunderHeaders(deviceId, false);
        signinHeaders.put("x-captcha-token", captchaToken);
        JSONObject token = thunderPost("https://xluser-ssl.xunlei.com/v1/auth/signin/token",
                new JSONObject().put("client_id", "Xp6vsxz_7IYVw2BB").put("client_secret", "Xp6vsy4tN9toTVdMSpomVdXpRmES")
                        .put("provider", "access_end_point_token").put("signin_token", sessionId), signinHeaders);
        saveThunderToken(deviceId, secret, token);
        validateThunderAccess(deviceId, token, secret);
        return "账号登录有效，Token 已保存并通过账号检查";
    }

    private static void validateThunderAccess(String deviceId, JSONObject token, JSONObject current) throws Exception {
        String access = first(token, "access_token");
        if (access.isEmpty()) access = first(current, "access_token");
        if (access.isEmpty()) throw new IllegalStateException("迅雷没有可用于检查登录状态的 Token");
        String oauthClient = first(current, "oauth_client_id");
        if (oauthClient.isEmpty()) oauthClient = "Xp6vsxz_7IYVw2BB";
        Map<String, String> headers = thunderHeaders(deviceId, false, oauthClient);
        headers.put("Authorization", "Bearer " + access);
        String captcha = first(token, "captcha_token");
        if (captcha.isEmpty()) captcha = first(current, "captcha_token", "captchaToken");
        if (!captcha.isEmpty()) headers.put("x-captcha-token", captcha);
        Request.Builder builder = new Request.Builder().url("https://xluser-ssl.xunlei.com/v1/user/me").get();
        headers.forEach(builder::addHeader);
        try (Response response = OkHttp.client(12_000).newCall(builder.build()).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            JSONObject result = text.isEmpty() ? new JSONObject() : new JSONObject(text);
            String message = first(result, "error_description", "errorDesc", "error", "message");
            long errorCode = result.optLong("error_code", result.optLong("errorCode", 0));
            if (!response.isSuccessful() || errorCode != 0 || !message.isEmpty()) {
                if (message.isEmpty()) message = "迅雷账号状态检查失败";
                String review = first(result, "reviewurl", "url");
                if (!review.isEmpty()) throw new VerificationRequiredException("需要完成迅雷设备验证，完成后请重新检查", review);
                throw new IllegalStateException(message + (response.isSuccessful() ? "" : "（HTTP " + response.code() + "）"));
            }
        }
    }

    private static JSONObject thunderPost(String url, JSONObject body, Map<String, String> headers) throws Exception {
        headers.put("Content-Type", "application/json; charset=utf-8");
        RequestBody requestBody = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), body.toString());
        Request.Builder builder = new Request.Builder().url(url).post(requestBody);
        headers.forEach(builder::addHeader);
        try (Response response = OkHttp.client(12_000).newCall(builder.build()).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            JSONObject result = text.isEmpty() ? new JSONObject() : new JSONObject(text);
            String error = first(result, "error_description", "errorDesc", "error", "message");
            long errorCode = result.optLong("error_code", result.optLong("errorCode", 0));
            if (!response.isSuccessful() || errorCode != 0 || (!error.isEmpty() && !"success".equalsIgnoreCase(error))) {
                if (error.isEmpty()) error = "迅雷登录失败";
                String review = first(result, "reviewurl", "url");
                if (!review.isEmpty()) throw new VerificationRequiredException("需要完成迅雷设备验证，完成后请重新检查", review);
                throw new IllegalStateException(error + (response.isSuccessful() ? "" : "（HTTP " + response.code() + "）"));
            }
            return result;
        }
    }

    private static Map<String, String> thunderHeaders(String deviceId, boolean core) {
        return thunderHeaders(deviceId, core, "Xp6vsxz_7IYVw2BB");
    }

    private static Map<String, String> thunderHeaders(String deviceId, boolean core, String clientId) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", core ? "android-ok-http-client/xl-acc-sdk/version-5.0.12.512000"
                : "ANDROID-com.xunlei.downloadprovider/8.31.0.9726 netWorkType/5G appid/40 deviceName/Xiaomi_M2004j7ac deviceModel/M2004J7AC OSVersion/12 protocolVersion/301 platformVersion/10 sdkVersion/512000 Oauth2Client/0.9 (Linux 4_14_186-perf-gddfs8vbb238b) (JAVA 0)");
        headers.put("Accept", "application/json;charset=UTF-8");
        headers.put("x-device-id", deviceId);
        headers.put("x-client-id", clientId);
        headers.put("x-client-version", "8.31.0.9726");
        return headers;
    }

    private static void saveThunderToken(String deviceId, JSONObject current, JSONObject token) throws Exception {
        String access = first(token, "access_token");
        if (access.isEmpty()) throw new IllegalStateException("迅雷没有返回 access_token");
        JSONObject values = new JSONObject().put("device_id", deviceId).put("access_token", access);
        String refresh = first(token, "refresh_token");
        if (refresh.isEmpty()) refresh = first(current, "refresh_token");
        if (!refresh.isEmpty()) values.put("refresh_token", refresh);
        String oauthClient = first(current, "oauth_client_id");
        if (!oauthClient.isEmpty()) values.put("oauth_client_id", oauthClient);
        String captcha = first(current, "captcha_token", "captchaToken");
        if (!captcha.isEmpty()) values.put("captcha_token", captcha);
        String type = first(token, "token_type");
        if (!type.isEmpty()) values.put("token_type", type);
        CloudAccountStore.mergeProviderSecret("thunder", values);
    }

    private static String thunderDeviceSign(String deviceId) throws Exception {
        String sha = digest("SHA-1", deviceId + "com.xunlei.downloadprovider" + "40" + "34a062aaa22f906fca4fefe9fb3a3021");
        return "div101." + deviceId + md5(sha);
    }

    private static String md5(String value) throws Exception { return digest("MD5", value); }

    private static String digest(String algorithm, String value) throws Exception {
        byte[] data = MessageDigest.getInstance(algorithm).digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder text = new StringBuilder(data.length * 2);
        for (byte item : data) text.append(String.format("%02x", item & 0xff));
        return text.toString();
    }

    private static String validateRequired(JSONObject secret, String provider) {
        if (secret.length() == 0) throw new IllegalStateException(CloudAccountStore.providerName(provider) + "凭据为空");
        return "Token/Cookie 或账号凭据已保存，尚未在线校验登录状态";
    }

    private static String first(JSONObject object, String... keys) {
        if (object == null) return "";
        for (String key : keys) {
            String value = object.optString(key).trim();
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private static final class VerificationRequiredException extends Exception {
        private final String url;

        private VerificationRequiredException(String message, String url) {
            super(message);
            this.url = url;
        }
    }
}
