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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Direct QR sessions backed by each provider's official login API. */
final class CloudProviderQrService {
    static final String PREFIX = "provider:";
    private static final String BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36";
    private static final String UC_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) uc-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch";
    private static final String UC_WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36";
    private static final String PAN123_LOGIN = "https://user.123pan.cn";
    private static final String PAN123_LOGIN_PAGE = PAN123_LOGIN + "/centerlogin?redirect_url=https%3A%2F%2Fyun.123pan.cn&source_page=website";
    private static final String MOBILE_139_BASE = "https://yun.139.com/orchestration/auth-rebuild";
    private static final String MOBILE_139_VERSION = "7.18.0";
    private static final String MOBILE_139_CLIENT = "10701";
    private static final String MOBILE_139_TOKEN_KEY = "UqEZkrjCKfa02pP6jntzFmkzOz86zHUC";
    private static final String MOBILE_139_LOGIN_KEY = "qPqDw263XgFgL3u8";
    // i.xunlei.com uses this account client for its QR login page.  The pan
    // client can issue a device code too, but the Xunlei app cannot resolve a
    // complete device record for that code and opens an empty device page.
    private static final String THUNDER_LOGIN_CLIENT = "XW5SkOhLDjnOZP7J";
    private static final String THUNDER_API = "https://xluser-ssl.xunlei.com";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    private CloudProviderQrService() {
    }

    static JSONObject start(String provider, String name) throws Exception {
        cleanup();
        Session session = switch (provider) {
            case "quark" -> startQuark(name);
            case "uc" -> startUc(name);
            case "uctv" -> startUcTv(name);
            case "pan115" -> start115(name);
            case "pan123" -> start123(name);
            case "baidu" -> startBaidu(name);
            case "guangya" -> startGuangya(name);
            case "mobile139" -> startMobile139(name);
            case "bilibili" -> startBilibili(name);
            case "thunder" -> startThunder(name);
            default -> throw new IllegalArgumentException("该网盘暂不支持自动扫码，请使用账号登录或下方手动方式");
        };
        String id = PREFIX + UUID.randomUUID();
        SESSIONS.put(id, session);
        return new JSONObject().put("sessionId", id).put("state", "waiting")
                .put("expiresAt", session.expiresAt).put("pollAfter", pollAfter(session))
                .put("qrImage", session.directImage ? session.qrPayload : renderQr(session.qrPayload, 360)).put("message", session.message);
    }

    static JSONObject poll(String id) throws Exception {
        Session session = SESSIONS.get(id);
        if (session == null) throw new IllegalArgumentException("登录会话不存在或已失效，请重新获取二维码");
        if (System.currentTimeMillis() >= session.expiresAt) return expired(id, "二维码已过期，请重新获取");
        return switch (session.provider) {
            case "quark" -> pollQuark(id, session);
            case "uc" -> pollUc(id, session);
            case "uctv" -> pollUcTv(id, session);
            case "pan115" -> poll115(id, session);
            case "pan123" -> poll123(id, session);
            case "baidu" -> pollBaidu(id, session);
            case "guangya" -> pollGuangya(id, session);
            case "mobile139" -> pollMobile139(id, session);
            case "bilibili" -> pollBilibili(id, session);
            case "thunder" -> pollThunder(id, session);
            default -> throw new IllegalArgumentException("不支持的登录会话");
        };
    }

    static JSONObject cancel(String id) throws Exception {
        if (id != null) SESSIONS.remove(id);
        return new JSONObject().put("state", "cancelled").put("message", "登录已取消");
    }

    /** China Mobile Cloud's current web QR protocol (the same flow used by yun.139.com). */
    private static Session startMobile139(String name) throws Exception {
        String sid = randomAlphaNumeric(16);
        // Match the current yun.139.com fallback exactly. FingerprintJS is not
        // available in the Bridge process, and the official page falls back
        // to its own 32-character alpha-numeric randomID in that case.
        String visitor = randomAlphaNumeric(32);
        String aesKey = randomAlphaNumeric(16);
        JSONObject keyPayload = new JSONObject().put("clientCode", MOBILE_139_CLIENT).put("type", 1);
        JSONObject keyResult = json("POST", MOBILE_139_BASE + "/key/v1.0/getRsaPublicKey",
                mobile139Headers(visitor, "", keyPayload), jsonBody(keyPayload));
        JSONObject data = keyResult.optJSONObject("data");
        if (data == null) data = new JSONObject();
        String publicKey = first(data, "publicKey", "rsaPublicKey", "key");
        if (publicKey.isEmpty()) publicKey = first(keyResult, "publicKey", "rsaPublicKey", "key");
        if (publicKey.isEmpty()) throw new IllegalStateException("移动云盘没有返回登录公钥");
        String encryptedKey = rsaPkcs1Encrypt(publicKey, aesKey);
        String qr = "https://yun.139.com/w/#/qrcLogin?sID=" + enc(sid) + "&dID=" + enc(visitor) + "&cType=9";
        JSONObject meta = new JSONObject().put("visitor", visitor).put("aesKey", aesKey).put("encryptedKey", encryptedKey);
        return session("mobile139", name, sid, qr, "请使用中国移动云盘 App 扫码并确认登录", meta, false);
    }

    private static JSONObject pollMobile139(String id, Session session) throws Exception {
        String visitor = session.meta.optString("visitor");
        String aesKey = session.meta.optString("aesKey");
        // Keep this byte-for-byte compatible with the official web client's
        // loginDataPrecode() QR branch. The permission endpoint does not accept
        // the UI model (type/authLogin) directly.
        JSONObject login = new JSONObject().put("dycPwd", session.token).put("loginStyle", "QRCode")
                .put("ifOpenAccount", "0").put("clientEnv", "3").put("setCookie", 0);
        JSONObject payload = new JSONObject().put("encryptMsg", aesEcbEncrypt(login.toString(), aesKey))
                .put("clientId", MOBILE_139_CLIENT).put("autoLogin", true).put("returnToken", true);
        JSONObject result = jsonAllowError("POST", MOBILE_139_BASE + "/permission/v1.0/login",
                mobile139Headers(visitor, session.meta.optString("encryptedKey"), payload), jsonBody(payload));
        JSONObject data = mobile139LoginData(result);
        JSONObject status = data.optJSONObject("result");
        if (status == null) status = new JSONObject();
        String code = first(status, "resultCode", "code");
        if (code.isEmpty()) code = first(result, "resultCode", "code");
        String account = first(data, "account");
        if (account.isEmpty()) account = decodeBase64(first(data, "encryptAccount"));
        String token = first(data, "token", "authToken");
        // Evaluate protocol states before credential fields. Waiting responses
        // contain those fields with JSON null values, and must never complete
        // or close the QR dialog.
        if ("200059548".equals(code)) return waiting(session, true, "已扫码，请在手机上确认");
        if ("200059541".equals(code) || code.isEmpty()) return waiting(session, false, "等待扫码");
        if ("200059542".equals(code)) return expired(id, "二维码已过期，请重新获取");
        if ("200059549".equals(code)) return expired(id, "登录已取消，请重新获取二维码");
        // The transport-level success flag is also true while the QR is merely
        // waiting, so only credential-bearing responses complete the session.
        boolean successful = !account.isEmpty() && !token.isEmpty();
        if (successful) {
            token = normalizeMobile139Token(token, aesKey);
            String authorization = Base64.encodeToString(("pc:" + account + ":" + token).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            JSONObject values = new JSONObject().put("token", authorization).put("authorization", "Basic " + authorization)
                    .put("account", account).put("auth_token", token).put("userDomainId", first(data, "userDomainId", "user_domain_id"))
                    .put("loginData", data);
            JSONObject saved = CloudAccountStore.save("", session.provider, session.name, values);
            SESSIONS.remove(id);
            return new JSONObject().put("state", "complete").put("message", "移动云盘登录成功，授权已自动保存")
                    .put("accounts", saved.optJSONArray("accounts"));
        }
        String message = first(status, "resultDesc", "message");
        if (message.isEmpty()) message = first(result, "message", "msg");
        throw new IllegalStateException(message.isEmpty() ? "移动云盘登录状态异常（" + code + "）" : message);
    }

    private static JSONObject mobile139LoginData(JSONObject result) throws Exception {
        Object value = result.opt("data");
        if (value instanceof JSONObject object) return object;
        if (!(value instanceof String text) || text.isBlank()) return new JSONObject();
        return new JSONObject(aesEcbDecryptHex(text, MOBILE_139_LOGIN_KEY));
    }

    /** Current QR flow embedded by pan.xunlei.com/login through Xunlei SSO. */
    private static Session startThunder(String name) throws Exception {
        String deviceId = md5(UUID.randomUUID().toString());
        JSONObject request = new JSONObject().put("client_id", THUNDER_LOGIN_CLIENT).put("scope", "");
        JSONObject result = json("POST", THUNDER_API + "/v1/auth/device/code",
                thunderHeaders(THUNDER_LOGIN_CLIENT, deviceId), jsonBody(request));
        String deviceCode = first(result, "device_code");
        String complete = first(result, "verification_uri_complete");
        String shortUri = first(result, "short_uri_complete");
        if (deviceCode.isEmpty() || (complete.isEmpty() && shortUri.isEmpty())) {
            throw new IllegalStateException("迅雷没有返回有效登录二维码");
        }
        // short_uri_complete is generated for this exact device-code session
        // and redirects to /center/account/personal/qrcode-login. Rebuilding
        // the URL through /xluser.core.login/v3/qrlogin changes its target to
        // /device/, which is only a device-management page and cannot approve
        // the login (the Xunlei app therefore showed an empty device list).
        String qr = shortUri.isEmpty() ? complete : shortUri;
        int expires = Math.max(30, result.optInt("expires_in", 120));
        int interval = Math.max(1, result.optInt("interval", 2));
        JSONObject meta = new JSONObject().put("clientId", THUNDER_LOGIN_CLIENT).put("interval", interval)
                .put("deviceId", deviceId);
        return new Session("thunder", name, deviceCode, qr, System.currentTimeMillis() + expires * 1000L,
                "请使用迅雷 App 扫码并确认登录", meta, false);
    }

    private static JSONObject pollThunder(String id, Session session) throws Exception {
        JSONObject body = new JSONObject()
                .put("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .put("device_code", session.token).put("client_id", session.meta.optString("clientId", THUNDER_LOGIN_CLIENT));
        String clientId = session.meta.optString("clientId", THUNDER_LOGIN_CLIENT);
        JSONObject result = jsonAllowError("POST", THUNDER_API + "/v1/auth/token",
                thunderHeaders(clientId, session.meta.optString("deviceId")), jsonBody(body));
        String access = first(result, "access_token");
        if (!access.isEmpty()) {
            JSONObject values = new JSONObject().put("access_token", access)
                    .put("device_id", session.meta.optString("deviceId")).put("oauth_client_id", clientId)
                    .put("user_id", first(result, "user_id", "sub")).put("expires_in", result.optLong("expires_in", 7200));
            String refresh = first(result, "refresh_token");
            if (!refresh.isEmpty()) values.put("refresh_token", refresh);
            String tokenType = first(result, "token_type");
            if (!tokenType.isEmpty()) values.put("token_type", tokenType);
            JSONObject saved = CloudAccountStore.save("", session.provider, session.name, values);
            SESSIONS.remove(id);
            return new JSONObject().put("state", "complete").put("message", "迅雷云盘登录成功，Token 已自动保存")
                    .put("accounts", saved.optJSONArray("accounts"));
        }
        String error = first(result, "error").toLowerCase(Locale.ROOT);
        if ("authorization_pending".equals(error)) {
            boolean scanned = thunderWaitingConsent(result.optJSONArray("details"));
            return waiting(session, scanned, scanned ? "已扫码，请在迅雷 App 中确认" : "等待迅雷 App 扫码");
        }
        if ("slow_down".equals(error)) return waiting(session, false, "等待扫码");
        if ("expired_token".equals(error)) return expired(id, "迅雷二维码已过期，请重新获取");
        if ("access_denied".equals(error)) return expired(id, "已取消迅雷登录，请重新获取二维码");
        String message = first(result, "error_description", "message");
        throw new IllegalStateException(message.isEmpty() ? "迅雷扫码登录状态异常" : message);
    }

    private static boolean thunderWaitingConsent(JSONArray details) {
        if (details == null) return false;
        String text = details.toString().toUpperCase(Locale.ROOT);
        return text.contains("WAITING_CONSENT") || text.contains("CONFIRM") || text.contains("CONSENT");
    }

    /** Bilibili's official web QR flow used by the Feimao Bili spider. */
    private static Session startBilibili(String name) throws Exception {
        JSONObject result = json("GET", "https://passport.bilibili.com/x/passport-login/web/qrcode/generate",
                bilibiliHeaders(), null);
        JSONObject data = result.optJSONObject("data");
        if (data == null) data = new JSONObject();
        String key = first(data, "qrcode_key");
        String qr = first(data, "url");
        if (key.isEmpty() || qr.isEmpty()) throw new IllegalStateException("B站没有返回有效登录二维码");
        return new Session("bilibili", name, key, qr, System.currentTimeMillis() + 180_000L,
                "请使用哔哩哔哩 App 扫码并确认登录", new JSONObject(), false);
    }

    private static JSONObject pollBilibili(String id, Session session) throws Exception {
        HttpResult response = request("GET", "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key="
                + enc(session.token), bilibiliHeaders(), null);
        JSONObject result = response.body.isEmpty() ? new JSONObject() : new JSONObject(response.body);
        if (response.code < 200 || response.code >= 300) {
            String message = first(result, "message", "msg");
            throw new IllegalStateException(message.isEmpty() ? "B站登录请求返回 HTTP " + response.code : message);
        }
        JSONObject data = result.optJSONObject("data");
        if (data == null) data = new JSONObject();
        int code = data.optInt("code", -1);
        if (code == 0) {
            String cookie = ensureBilibiliBuvid3(response.cookies);
            if (cookie.isEmpty() || !cookie.contains("SESSDATA=")) {
                throw new IllegalStateException("B站已确认登录，但没有返回有效 Cookie，请刷新二维码重试");
            }
            JSONObject values = new JSONObject().put("cookie", cookie);
            String refreshToken = first(data, "refresh_token");
            if (!refreshToken.isEmpty()) values.put("refresh_token", refreshToken);
            JSONObject saved = CloudAccountStore.save("", session.provider, session.name, values);
            SESSIONS.remove(id);
            return new JSONObject().put("state", "complete").put("message", "B站登录成功，Cookie 已自动保存")
                    .put("accounts", saved.optJSONArray("accounts"));
        }
        if (code == 86090) return waiting(session, true, "已扫码，请在哔哩哔哩 App 中确认");
        if (code == 86101) return waiting(session, false, "等待扫码");
        if (code == 86038) return expired(id, "B站二维码已过期，请重新获取");
        String message = first(data, "message");
        if (message.isEmpty()) message = first(result, "message", "msg");
        throw new IllegalStateException(message.isEmpty() ? "B站登录状态异常（" + code + "）" : message);
    }

    private static Session startGuangya(String name) throws Exception {
        JSONObject payload = new JSONObject().put("client_id", "aMe-8VSlkrbQXpUR").put("scope", "user");
        JSONObject result = json("POST", "https://account.guangyapan.com/v1/auth/device/code", jsonHeaders(), jsonBody(payload));
        String deviceCode = result.optString("device_code");
        String qr = result.optString("verification_uri_complete");
        if (deviceCode.isEmpty() || qr.isEmpty()) {
            throw new IllegalStateException(first(result, "error_description", "message", "error").isEmpty()
                    ? "光鸭云盘没有返回有效登录二维码" : first(result, "error_description", "message", "error"));
        }
        return session("guangya", name, deviceCode, qr, "请使用光鸭云盘 App 扫码并确认登录", new JSONObject(), false);
    }

    private static JSONObject pollGuangya(String id, Session session) throws Exception {
        JSONObject payload = new JSONObject()
                .put("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .put("device_code", session.token)
                .put("client_id", "aMe-8VSlkrbQXpUR");
        JSONObject result = jsonAllowError("POST", "https://account.guangyapan.com/v1/auth/token", jsonHeaders(), jsonBody(payload));
        String accessToken = result.optString("access_token");
        if (!accessToken.isEmpty()) {
            JSONObject values = new JSONObject().put("token", accessToken).put("access_token", accessToken)
                    .put("token_type", result.optString("token_type", "Bearer")).put("sub", result.optString("sub"));
            String refreshToken = result.optString("refresh_token");
            if (!refreshToken.isEmpty()) values.put("refresh_token", refreshToken);
            JSONObject saved = CloudAccountStore.save("", session.provider, session.name, values);
            SESSIONS.remove(id);
            return new JSONObject().put("state", "complete").put("message", "光鸭云盘登录成功，Token 已自动保存")
                    .put("accounts", saved.optJSONArray("accounts"));
        }
        String error = result.optString("error");
        if ("authorization_pending".equals(error)) return waiting(session, false, "等待扫码确认");
        if ("expired_token".equals(error) || "access_denied".equals(error)) {
            return expired(id, "二维码已过期或授权已取消，请重新获取");
        }
        String message = first(result, "error_description", "message", "error");
        throw new IllegalStateException(message.isEmpty() ? "光鸭云盘登录状态异常" : message);
    }

    private static Session startQuark(String name) throws Exception {
        JSONObject result = json("GET", "https://uop.quark.cn/cas/ajax/getTokenForQrcodeLogin?client_id=532&v=1.2&request_id=" + enc(UUID.randomUUID().toString()), quarkHeaders(), null);
        String token = nested(result, "data", "members").optString("token");
        if (result.optInt("status") != 2000000 || token.isEmpty()) throw new IllegalStateException("夸克没有返回有效登录二维码");
        String qr = "https://su.quark.cn/4_eMHBJ?token=" + enc(token) + "&client_id=532&ssb=weblogin&uc_param_str=&uc_biz_str="
                + enc("S:custom|OPT:SAREA@0|OPT:IMMERSIVE@1|OPT:BACK_BTN_STYLE@0");
        return session("quark", name, token, qr, "请使用夸克网盘 App 扫码并确认登录", new JSONObject(), false);
    }

    private static JSONObject pollQuark(String id, Session session) throws Exception {
        JSONObject result = json("GET", "https://uop.quark.cn/cas/ajax/getServiceTicketByQrcodeToken?client_id=532&v=1.2&token="
                + enc(session.token) + "&request_id=" + enc(UUID.randomUUID().toString()), quarkHeaders(), null);
        String ticket = nested(result, "data", "members").optString("service_ticket");
        if (!ticket.isEmpty()) {
            HttpResult login = request("GET", "https://pan.quark.cn/account/info?st=" + enc(ticket) + "&lw=scan", quarkHeaders(), null);
            return saveCookie(id, session, login.cookies, "夸克网盘登录成功，Cookie 已自动保存");
        }
        int status = result.optInt("status");
        if (status == 50004002 || status == 50004003 || status == 50004004) return expired(id, "二维码已失效或登录已取消，请重新获取");
        return waiting(session, status != 50004001, status == 50004001 ? "等待扫码" : "已扫码，请在手机上确认");
    }

    private static Session startUc(String name) throws Exception {
        long now = System.currentTimeMillis();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("client_id", "381");
        values.put("v", "1.2");
        values.put("request_id", String.valueOf(now));
        Map<String, String> authHeaders = ucHeaders();
        authHeaders.put("User-Agent", UC_WEB_UA);
        HttpResult landing = request("GET", "https://drive.uc.cn/", authHeaders, null);
        if (landing.code != 200) throw new IllegalStateException("UC 登录页面会话初始化失败（HTTP " + landing.code + "）");
        authHeaders.put("Cookie", landing.cookies);
        authHeaders.put("Referer", "https://drive.uc.cn/");
        HttpResult initial = request("POST", "https://api.open.uc.cn/cas/ajax/getTokenForQrcodeLogin?__dt=0&__t=" + now, authHeaders, form(values));
        if (initial.code != 200) throw new IllegalStateException("UC 二维码请求失败（HTTP " + initial.code + "）");
        JSONObject result = new JSONObject(initial.body);
        String token = nested(result, "data", "members").optString("token");
        if (result.optInt("status") != 2000000 || token.isEmpty()) throw new IllegalStateException("UC 没有返回有效登录二维码");
        String qr = "https://su.uc.cn/1_n0ZCv?uc_param_str=dsdnfrpfbivesscpgimibtbmnijblauputogpintnwktprchmt&token="
                + enc(token) + "&client_id=381&uc_biz_str=" + enc("S:custom|C:titlebar_fix");
        return session("uc", name, token, qr, "请使用 UC 网盘 App 扫码并确认登录", new JSONObject().put("cookies", mergeCookies(landing.cookies, initial.cookies)), false);
    }

    private static Session startUcTv(String name) throws Exception {
        JSONObject r=CloudUcTv.authorize();String query=first(r,"query_token"),qr=first(r,"qr_data");
        if(r.optInt("status",-1)!=0||query.isEmpty()||qr.isEmpty())throw new IllegalStateException("UC TV 没有返回有效二维码："+first(r,"error_info","message"));
        return session("uctv",name,query,qr.startsWith("data:")?qr:"data:image/png;base64,"+qr,"请使用UC App扫码确认TV授权（Token由授权兑换服务获取）",new JSONObject(),true);
    }
    private static JSONObject pollUcTv(String id, Session session) throws Exception {
        JSONObject r=CloudUcTv.code(session.token);String code=first(r,"code");
        if(r.optInt("status",-1)!=0||code.isEmpty())return waiting(session,false,"等待UC App扫码确认TV授权");
        JSONObject tokens=session.meta.optJSONObject("tvTokens");
        if(tokens==null){tokens=CloudUcTv.exchange(code);tokens.put("token",tokens.optString("access_token")).put("device_id",CloudUcTv.DEVICE);session.meta.put("tvTokens",tokens);}
        CloudUcTv.validate(tokens);
        JSONObject saved=CloudAccountStore.save("","uctv",session.name,tokens);SESSIONS.remove(id);
        return new JSONObject().put("state","complete").put("message","UC TV授权已保存，请点检查验证有效性").put("accounts",saved.optJSONArray("accounts"));
    }

    private static JSONObject pollUc(String id, Session session) throws Exception {
        long now = System.currentTimeMillis();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("client_id", "381");
        values.put("v", "1.2");
        values.put("request_id", String.valueOf(now));
        values.put("token", session.token);
        Map<String, String> authHeaders = ucHeaders();
        authHeaders.put("User-Agent", UC_WEB_UA);
        authHeaders.put("Referer", "https://drive.uc.cn/");
        authHeaders.put("Cookie", session.meta.optString("cookies"));
        HttpResult ticketResponse = request("POST", "https://api.open.uc.cn/cas/ajax/getServiceTicketByQrcodeToken?__dt=0&__t=" + now, authHeaders, form(values));
        if (ticketResponse.code != 200) throw new IllegalStateException("UC 扫码票据请求失败（HTTP " + ticketResponse.code + "）");
        String sessionCookies = mergeCookies(session.meta.optString("cookies"), ticketResponse.cookies);
        session.meta.put("cookies", sessionCookies);
        JSONObject result = new JSONObject(ticketResponse.body);
        String ticket = nested(result, "data", "members").optString("service_ticket");
        if (!ticket.isEmpty()) {
            authHeaders.put("Cookie", sessionCookies);
            HttpResult login = request("GET", "https://drive.uc.cn/account/info?st=" + enc(ticket), authHeaders, null);
            JSONObject accountInfo = login.body.isEmpty() ? new JSONObject() : new JSONObject(login.body);
            if (login.code != 200 || "AUTH_ERROR".equals(accountInfo.optString("code"))) {
                return new JSONObject().put("state", "failed").put("message", "UC 扫码已确认，但账号授权兑换失败（HTTP " + login.code + "，code=" + accountInfo.optString("code", "未知") + "），未保存 Cookie");
            }
            if (login.cookies.isEmpty()) throw new IllegalStateException("UC 没有返回登录 Cookie");
            Map<String, String> headers = ucHeaders();
            headers.put("User-Agent", UC_WEB_UA);
            String loginCookies = mergeCookies(sessionCookies, login.cookies);
            headers.put("Cookie", loginCookies);
            headers.put("Referer", "https://drive.uc.cn/");
            HttpResult warmup = request("GET", "https://pc-api.uc.cn/1/clouddrive/file/sort?pr=UCBrowser&fr=pc&pdir_fid=0&_page=1&_size=50&_fetch_total=1&_fetch_sub_dirs=0&_sort=file_type:asc,updated_at:desc", headers, null);
            JSONObject verified = warmup.body.isEmpty() ? new JSONObject() : new JSONObject(warmup.body);
            if (warmup.code != 200 || verified.optInt("status", -1) != 200) {
                return new JSONObject().put("state", "failed").put("message", "UC 扫码已确认，但官方目录登录校验失败（HTTP " + warmup.code + "），未保存 Cookie，请刷新二维码重试");
            }
            return saveCookie(id, session, mergeCookies(loginCookies, warmup.cookies), "UC 网盘登录成功，Cookie 已自动保存");
        }
        int status = result.optInt("status");
        if (status == 50004002 || status == 50004003 || status == 50004004) return expired(id, "二维码已失效或登录已取消，请重新获取");
        return waiting(session, status != 50004001, status == 50004001 ? "等待扫码" : "已扫码，请在手机上确认");
    }

    private static Session start115(String name) throws Exception {
        JSONObject result = json("GET", "https://qrcodeapi.115.com/api/1.0/web/1.0/token/", headers(), null);
        JSONObject data = result.optJSONObject("data");
        if (data == null) data = new JSONObject();
        String uid = data.optString("uid");
        String qr = data.optString("qrcode");
        if (uid.isEmpty() || qr.isEmpty()) throw new IllegalStateException("115 没有返回有效登录二维码");
        JSONObject meta = new JSONObject().put("time", data.optString("time")).put("sign", data.optString("sign"));
        return session("pan115", name, uid, qr, "请使用 115 App 扫码并确认登录", meta, false);
    }

    private static JSONObject poll115(String id, Session session) throws Exception {
        String url = "https://qrcodeapi.115.com/get/status/?uid=" + enc(session.token)
                + "&time=" + enc(session.meta.optString("time")) + "&sign=" + enc(session.meta.optString("sign"))
                + "&_=" + (System.currentTimeMillis() / 1000L);
        JSONObject result = json("GET", url, headers(), null);
        JSONObject data = result.optJSONObject("data");
        int status = data == null ? -9 : data.optInt("status", -9);
        if (status == 2) {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("app", "qandroid");
            values.put("account", session.token);
            JSONObject login = json("POST", "https://passportapi.115.com/app/1.0/qandroid/1.0/login/qrcode/", headers(), form(values));
            String cookie = cookieObject(nested(login, "data", "cookie"));
            if (cookie.isEmpty()) throw new IllegalStateException(login.optString("message", "115 没有返回登录 Cookie"));
            return saveCookie(id, session, cookie, "115 网盘登录成功，Cookie 已自动保存");
        }
        if (status == -1 || status == -2) return expired(id, status == -2 ? "登录已取消，请重新获取二维码" : "二维码已过期，请重新获取");
        return waiting(session, status == 1, status == 1 ? "已扫码，请在手机上确认" : "等待扫码");
    }

    /** 123 网盘网页端使用的官方 App 扫码登录协议。 */
    /** 123 网盘扫码会话必须用 wxCode 换取 Bearer Token，轮询结果本身不是授权凭据。 */
    private static Session start123(String name) throws Exception {
        String loginUuid = sha256(UUID.randomUUID() + ":" + System.nanoTime());
        String clientUniId = UUID.randomUUID().toString();
        HttpResult seed = request("GET", PAN123_LOGIN_PAGE, pan123Headers(loginUuid, ""), null);
        String cookies = seed.cookies;
        HttpResult response = request("GET", PAN123_LOGIN + "/api/user/qr-code/generate?uniID=" + enc(clientUniId),
                pan123Headers(loginUuid, cookies), null);
        JSONObject root = parsePan123(response, "123 网盘没有返回有效登录二维码");
        if (root.optInt("code", -1) != 0) throw pan123Error(root, "123 网盘没有返回有效登录二维码");
        JSONObject data = root.optJSONObject("data");
        if (data == null) data = new JSONObject();
        String uniId = first(data, "uniID", "uniId");
        String qr = first(data, "url", "qrCodeUrl", "qr_code_url");
        if (uniId.isEmpty() || qr.isEmpty()) throw new IllegalStateException("123 网盘没有返回有效登录二维码");
        String separator = qr.contains("?") ? "&" : "?";
        qr += separator + "env=production&uniID=" + enc(uniId) + "&source=123pan&type=login";
        JSONObject meta = new JSONObject().put("loginuuid", loginUuid).put("cookies", mergeCookies(cookies, response.cookies));
        return new Session("pan123", name, uniId, qr, System.currentTimeMillis() + 5 * 60_000L,
                "请使用 123 云盘 App 扫码并确认登录", meta, false);
    }

    private static JSONObject poll123(String id, Session session) throws Exception {
        String cookies = session.meta.optString("cookies");
        String loginUuid = session.meta.optString("loginuuid");
        String resultUrl = PAN123_LOGIN + "/api/user/qr-code/result?uniID=" + enc(session.token) + "&remember=true&gray=true";
        HttpResult response = request("GET", resultUrl, pan123Headers(loginUuid, cookies), null);
        session.meta.put("cookies", mergeCookies(cookies, response.cookies));
        JSONObject root = parsePan123(response, "123 网盘登录状态异常");
        if (root.optInt("code", -1) != 0) throw pan123Error(root, "123 网盘登录状态异常");
        JSONObject data = root.optJSONObject("data");
        if (data == null) data = new JSONObject();
        int status = data.optInt("loginStatus", data.optInt("status", 0));
        if (status == 3) return expired(id, "二维码已过期，请重新获取");
        if (status == 4) return expired(id, "登录会话已失效，请重新获取二维码");
        if (status != 1 && status != 2) return waiting(session, false, "等待扫码");

        JSONObject wxPayload = new JSONObject().put("uniID", session.token);
        HttpResult wxResponse = request("POST", PAN123_LOGIN + "/api/user/qr-code/wx_code",
                pan123Headers(loginUuid, session.meta.optString("cookies")), jsonBody(wxPayload));
        session.meta.put("cookies", mergeCookies(session.meta.optString("cookies"), wxResponse.cookies));
        JSONObject wxRoot = parsePan123(wxResponse, "123 网盘正在等待手机确认");
        if (wxRoot.optInt("code", -1) != 0) return waiting(session, true, "已扫码，请在手机上确认");
        JSONObject wxData = wxRoot.optJSONObject("data");
        String wxCode = wxData == null ? "" : first(wxData, "wxCode", "wechat_code");
        if (wxCode.isEmpty()) return waiting(session, true, "已扫码，正在完成登录");

        JSONObject loginPayload = new JSONObject().put("from", "web").put("wechat_code", wxCode)
                .put("type", 4).put("remember", true).put("gray", true);
        HttpResult loginResponse = request("POST", PAN123_LOGIN + "/api/user/sign_in",
                pan123Headers(loginUuid, session.meta.optString("cookies")), jsonBody(loginPayload));
        JSONObject login = parsePan123(loginResponse, "123 网盘登录失败");
        int code = login.optInt("code", -1);
        JSONObject loginData = login.optJSONObject("data");
        String token = loginData == null ? "" : first(loginData, "token", "access_token", "accessToken");
        if (token.isEmpty()) token = cookieValue(loginResponse.cookies, "sso-token");
        if ((code != 0 && code != 200) || token.isEmpty()) throw pan123Error(login, "123 网盘没有返回有效授权 Token");
        return save123Token(id, session, token);
    }

    private static JSONObject save123Token(String id, Session session, String token) throws Exception {
        String normalized = token.trim();
        if (normalized.regionMatches(true, 0, "Bearer ", 0, 7)) normalized = normalized.substring(7).trim();
        if (normalized.isEmpty()) throw new IllegalStateException("123 网盘没有返回有效授权 Token");
        JSONObject values = new JSONObject().put("token", normalized).put("authorization", "Bearer " + normalized)
                .put("loginuuid", session.meta.optString("loginuuid"));
        JSONObject saved = CloudAccountStore.save("", session.provider, session.name, values);
        SESSIONS.remove(id);
        return new JSONObject().put("state", "complete").put("message", "123 网盘登录成功，Token 已自动保存")
                .put("accounts", saved.optJSONArray("accounts"));
    }

    private static JSONObject parsePan123(HttpResult response, String fallback) throws Exception {
        if (response.body.isEmpty()) throw new IllegalStateException(fallback + "（HTTP " + response.code + "）");
        JSONObject result = new JSONObject(response.body);
        if (response.code < 200 || response.code >= 300) throw pan123Error(result, fallback + "（HTTP " + response.code + "）");
        return result;
    }

    private static IllegalStateException pan123Error(JSONObject response, String fallback) {
        String message = first(response, "message", "msg", "error");
        return new IllegalStateException(message.isEmpty() ? fallback : message);
    }

    private static Session startBaidu(String name) throws Exception {
        String gid = UUID.randomUUID().toString().toUpperCase();
        long now = System.currentTimeMillis();
        HttpResult seed = request("GET", "https://pan.baidu.com/", baiduHeaders(""), null);
        String url = "https://passport.baidu.com/v2/api/getqrcode?lp=pc&qrloginfrom=pc&gid=" + enc(gid)
                + "&callback=bd__cbs__" + now + "&apiver=v3&tt=" + now + "&tpl=netdisk&_=" + now;
        HttpResult response = request("GET", url, baiduHeaders(seed.cookies), null);
        JSONObject result = jsonp(response.body);
        String sign = result.optString("sign");
        if (response.code < 200 || response.code >= 300 || result.optInt("errno") != 0 || sign.isEmpty()) throw new IllegalStateException("百度网盘没有返回有效登录二维码");
        String image = result.optString("imgurl");
        if (image.isEmpty()) image = "https://passport.baidu.com/v2/api/qrcode?sign=" + enc(sign) + "&lp=pc&qrloginfrom=pc";
        else if (image.startsWith("//")) image = "https:" + image;
        else if (!image.startsWith("http")) image = "https://" + image;
        JSONObject meta = new JSONObject().put("gid", gid).put("cookies", mergeCookies(seed.cookies, response.cookies));
        return session("baidu", name, sign, image, "请使用百度网盘 App 扫码并确认登录", meta, true);
    }

    private static JSONObject pollBaidu(String id, Session session) throws Exception {
        long now = System.currentTimeMillis();
        String original = session.meta.optString("cookies");
        String url = "https://passport.baidu.com/channel/unicast?channel_id=" + enc(session.token)
                + "&tpl=netdisk&_sdkFrom=1&gid=" + enc(session.meta.optString("gid")) + "&callback=bd__cbs__" + now
                + "&apiver=v3&tt=" + now + "&_=" + now;
        HttpResult response = request("GET", url, baiduHeaders(original), null);
        String current = mergeCookies(original, response.cookies);
        session.meta.put("cookies", current);
        JSONObject result = jsonp(response.body);
        String channel = result.optString("channel_v");
        JSONObject state = channel.isEmpty() ? null : new JSONObject(channel);
        if (state != null && state.optInt("status", 1) == 0 && !state.optString("v").isEmpty()) {
            String confirm = "https://passport.baidu.com/v3/login/main/qrbdusslogin?alg=v3&apiver=v3&bduss=" + enc(state.optString("v"))
                    + "&loginVersion=v4&qrcode=1&tpl=netdisk&tt=" + now + "&time=" + (now / 1000)
                    + "&u=https%3A%2F%2Fpan.baidu.com%2F&callback=bd__cbs__login";
            HttpResult login = request("GET", confirm, baiduHeaders(current), null);
            String cookie = mergeCookies(current, login.cookies);
            if (!cookie.contains("BDUSS=")) throw new IllegalStateException("百度网盘没有返回登录 Cookie");
            return saveCookie(id, session, cookie, "百度网盘登录成功，Cookie 已自动保存");
        }
        return waiting(session, state != null, state == null ? "等待扫码" : "已扫码，请在手机上确认");
    }

    private static JSONObject saveCookie(String id, Session session, String cookie, String message) throws Exception {
        if (cookie == null || cookie.isBlank()) throw new IllegalStateException(CloudAccountStore.providerName(session.provider) + "没有返回登录 Cookie");
        JSONObject saved = CloudAccountStore.save("", session.provider, session.name, new JSONObject().put("cookie", cookie));
        if ("pan115".equals(session.provider)) {
            org.json.JSONArray accounts = saved.optJSONArray("accounts");
            if (accounts != null) for (int i = 0; i < accounts.length(); i++) {
                JSONObject account = accounts.getJSONObject(i);
                if (!"pan115".equals(account.optString("provider"))) continue;
                JSONObject checked = CloudAccountValidator.validate(account.getString("id"));
                saved = checked;
                message = "valid".equals(checked.optString("state")) ? checked.optString("message") : "115扫码凭据已保存；账号信息检查未通过，请点击检查重试";
                break;
            }
        }
        SESSIONS.remove(id);
        return new JSONObject().put("state", "complete").put("message", message).put("accounts", saved.optJSONArray("accounts"));
    }

    private static JSONObject waiting(Session session, boolean scanned, String message) throws Exception {
        return new JSONObject().put("state", scanned ? "scanned" : "waiting").put("expiresAt", session.expiresAt)
                .put("pollAfter", pollAfter(session)).put("message", message);
    }

    private static int pollAfter(Session session) {
        if ("mobile139".equals(session.provider)) return 2000;
        if ("thunder".equals(session.provider)) return Math.max(1000, session.meta.optInt("interval", 2) * 1000);
        return 1000;
    }

    private static JSONObject expired(String id, String message) throws Exception {
        SESSIONS.remove(id);
        return new JSONObject().put("state", "expired").put("message", message);
    }

    private static Session session(String provider, String name, String token, String qr, String message, JSONObject meta, boolean directImage) {
        return new Session(provider, name, token, qr, System.currentTimeMillis() + 5 * 60_000L, message, meta, directImage);
    }

    private static JSONObject json(String method, String url, Map<String, String> headers, byte[] body) throws Exception {
        HttpResult response = request(method, url, headers, body);
        JSONObject result = response.body.isEmpty() ? new JSONObject() : new JSONObject(response.body);
        if (response.code < 200 || response.code >= 300) {
            String message = first(result, "message", "error", "msg");
            throw new IllegalStateException(message.isEmpty() ? "请求返回 HTTP " + response.code : message);
        }
        return result;
    }

    private static JSONObject jsonAllowError(String method, String url, Map<String, String> headers, byte[] body) throws Exception {
        HttpResult response = request(method, url, headers, body);
        JSONObject result = response.body.isEmpty() ? new JSONObject() : new JSONObject(response.body);
        if ((response.code < 200 || response.code >= 300) && result.length() == 0) {
            throw new IllegalStateException("请求返回 HTTP " + response.code);
        }
        return result;
    }

    private static HttpResult request(String method, String url, Map<String, String> headers, byte[] body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(10_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestMethod(method);
        for (Map.Entry<String, String> entry : headers.entrySet()) connection.setRequestProperty(entry.getKey(), entry.getValue());
        if (body != null) {
            connection.setDoOutput(true);
            if (!headers.containsKey("Content-Type")) connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(body); }
        }
        int code = connection.getResponseCode();
        InputStream input = code >= 200 && code < 400 ? connection.getInputStream() : connection.getErrorStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (input != null) try (InputStream stream = input) {
            byte[] chunk = new byte[4096];
            int count;
            while ((count = stream.read(chunk)) >= 0) buffer.write(chunk, 0, count);
        }
        String cookies = responseCookies(connection);
        connection.disconnect();
        return new HttpResult(code, new String(buffer.toByteArray(), StandardCharsets.UTF_8), cookies);
    }

    private static Map<String, String> headers() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");
        headers.put("User-Agent", BROWSER_UA);
        return headers;
    }

    private static Map<String, String> quarkHeaders() {
        Map<String, String> headers = headers();
        headers.put("Origin", "https://pan.quark.cn");
        headers.put("Referer", "https://pan.quark.cn/");
        return headers;
    }

    private static Map<String, String> jsonHeaders() {
        Map<String, String> headers = headers();
        headers.put("Content-Type", "application/json; charset=utf-8");
        return headers;
    }

    private static Map<String, String> ucHeaders() {
        Map<String, String> headers = headers();
        headers.put("User-Agent", UC_UA);
        return headers;
    }

    private static Map<String, String> baiduHeaders(String cookies) {
        Map<String, String> headers = headers();
        headers.put("Referer", "https://pan.baidu.com/");
        if (cookies != null && !cookies.isEmpty()) headers.put("Cookie", cookies);
        return headers;
    }

    private static Map<String, String> pan123Headers(String loginUuid, String cookies) {
        Map<String, String> headers = headers();
        headers.put("Content-Type", "application/json;charset=UTF-8");
        headers.put("App-Version", "132");
        headers.put("Platform", "web");
        headers.put("Origin", "https://user.123pan.cn");
        headers.put("Referer", PAN123_LOGIN_PAGE);
        if (loginUuid != null && !loginUuid.isEmpty()) headers.put("Loginuuid", loginUuid);
        if (cookies != null && !cookies.isEmpty()) headers.put("Cookie", cookies);
        return headers;
    }

    private static Map<String, String> thunderHeaders(String clientId, String deviceId) {
        Map<String, String> headers = jsonHeaders();
        headers.put("x-client-id", clientId);
        headers.put("x-sdk-version", "8.1.4");
        headers.put("x-protocol-version", "301");
        headers.put("x-client-version", "0.0.1");
        headers.put("x-device-name", "OKBOX Bridge");
        headers.put("x-device-model", "Android TV");
        headers.put("x-os-version", "Android");
        headers.put("x-platform-version", "10");
        if (deviceId != null && !deviceId.isEmpty()) headers.put("x-device-id", deviceId);
        headers.put("x-request-id", UUID.randomUUID().toString());
        headers.put("Origin", "https://i.xunlei.com");
        headers.put("Referer", "https://i.xunlei.com/");
        return headers;
    }

    private static Map<String, String> bilibiliHeaders() {
        Map<String, String> headers = headers();
        headers.put("Origin", "https://www.bilibili.com");
        headers.put("Referer", "https://www.bilibili.com/");
        return headers;
    }

    private static Map<String, String> mobile139Headers(String visitor, String encryptedKey, JSONObject body) throws Exception {
        Map<String, String> headers = headers();
        String clientInfo = "||9|" + MOBILE_139_VERSION + "|chrome|124|" + visitor + "||windows 10||zh-CN|||Y2hyb21l||";
        headers.put("Content-Type", "application/json;charset=UTF-8");
        headers.put("Origin", "https://yun.139.com");
        headers.put("Referer", "https://yun.139.com/w/");
        headers.put("caller", "web");
        headers.put("X-Deviceinfo", clientInfo);
        headers.put("x-yun-client-info", clientInfo);
        headers.put("CMS-DEVICE", "default");
        headers.put("x-yun-channel-source", "10000034");
        headers.put("x-huawei-channelSrc", "10000034");
        headers.put("x-yun-svc-type", "1");
        headers.put("x-SvcType", "1");
        headers.put("x-m4c-caller", "PC");
        headers.put("x-m4c-src", "10002");
        headers.put("x-inner-ntwk", "2");
        headers.put("mcloud-route", "001");
        headers.put("mcloud-version", MOBILE_139_VERSION);
        headers.put("mcloud-channel", "1000101");
        headers.put("mcloud-client", MOBILE_139_CLIENT);
        headers.put("INNER-HCY-ROUTER-HTTPS", "1");
        headers.put("x-yun-module-type", "100");
        headers.put("x-yun-app-channel", "10000034");
        headers.put("x-yun-api-version", "v1");
        if (encryptedKey != null && !encryptedKey.isEmpty()) headers.put("mcloud-skey", encryptedKey);
        if (body != null) {
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            String nonce = randomAlphaNumeric(16);
            headers.put("mcloud-sign", timestamp + "," + nonce + "," + mobile139Sign(body.toString(), timestamp, nonce));
        }
        return headers;
    }

    private static String mobile139Sign(String json, String timestamp, String nonce) throws Exception {
        String encoded = URLEncoder.encode(json, "UTF-8").replace("+", "%20")
                .replace("%21", "!").replace("%27", "'").replace("%28", "(")
                .replace("%29", ")").replace("%7E", "~");
        char[] chars = encoded.toCharArray();
        Arrays.sort(chars);
        String sorted = new String(chars);
        String bodyHash = md5(Base64.encodeToString(sorted.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        return md5(bodyHash + md5(timestamp + ":" + nonce)).toUpperCase(Locale.US);
    }

    private static String aesEcbEncrypt(String source, String key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"));
        return Base64.encodeToString(cipher.doFinal(source.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    private static String aesEcbDecryptHex(String source, String key) throws Exception {
        String value = source.trim();
        if ((value.length() & 1) != 0) throw new IllegalArgumentException("移动云盘返回了无效的登录数据");
        byte[] encrypted = new byte[value.length() / 2];
        for (int i = 0; i < encrypted.length; i++) {
            int high = Character.digit(value.charAt(i * 2), 16);
            int low = Character.digit(value.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) throw new IllegalArgumentException("移动云盘返回了无效的登录数据");
            encrypted[i] = (byte) ((high << 4) | low);
        }
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    /**
     * Mobile Cloud returns token as Base64(16-byte IV + AES-CBC ciphertext).
     * This fixed response key is used by the current yun.139.com web client and is
     * deliberately different from the per-session AES key used to encrypt login requests.
     */
    static String normalizeMobile139Token(String source, String sessionKey) throws Exception {
        // Existing/manual credentials are already plaintext pipe-separated tokens.
        if (source.contains("|")) return source;
        try {
            String decoded = decryptMobile139Token(source);
            if (decoded.contains("|")) return decoded;
        } catch (Exception ignored) { }
        if (sessionKey != null && !sessionKey.isEmpty()) {
            try {
                Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(sessionKey.getBytes(StandardCharsets.UTF_8), "AES"));
                String decoded = new String(cipher.doFinal(Base64.decode(source, Base64.DEFAULT)), StandardCharsets.UTF_8);
                if (decoded.contains("|")) return decoded;
            } catch (Exception ignored) { }
        }
        throw new IllegalStateException("移动扫码返回的 Token 未能转换为官方授权格式，请重新扫码；未保存无效 Token");
    }
    private static String decryptMobile139Token(String source) throws Exception {
        byte[] packed = Base64.decode(source, Base64.DEFAULT);
        if (packed.length <= 16 || (packed.length - 16) % 16 != 0) {
            throw new IllegalArgumentException("移动云盘登录令牌格式无效");
        }
        byte[] iv = Arrays.copyOfRange(packed, 0, 16);
        byte[] ciphertext = Arrays.copyOfRange(packed, 16, packed.length);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(MOBILE_139_TOKEN_KEY.getBytes(StandardCharsets.UTF_8), "AES"),
                new IvParameterSpec(iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    private static String ensureBilibiliBuvid3(String cookie) {
        if (cookie == null || cookie.isBlank() || cookie.contains("buvid3=")) return cookie == null ? "" : cookie;
        return cookie + "; buvid3=" + UUID.randomUUID() + (10_000 + RANDOM.nextInt(90_000)) + "infoc";
    }

    private static String rsaPkcs1Encrypt(String publicKey, String value) throws Exception {
        String clean = publicKey.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        KeyFactory factory = KeyFactory.getInstance("RSA");
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, factory.generatePublic(new X509EncodedKeySpec(Base64.decode(clean, Base64.DEFAULT))));
        return Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    private static String decodeBase64(String value) {
        if (value == null || value.isEmpty()) return "";
        try { return new String(Base64.decode(value, Base64.DEFAULT), StandardCharsets.UTF_8); }
        catch (Exception ignored) { return ""; }
    }

    private static String md5(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte item : digest) result.append(String.format(Locale.US, "%02x", item & 0xff));
        return result.toString();
    }

    private static String randomAlphaNumeric(int length) {
        final String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder result = new StringBuilder(length);
        for (int i = 0; i < length; i++) result.append(chars.charAt(RANDOM.nextInt(chars.length())));
        return result.toString();
    }

    private static byte[] form(Map<String, String> values) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (text.length() > 0) text.append('&');
            text.append(enc(entry.getKey())).append('=').append(enc(entry.getValue()));
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] jsonBody(JSONObject value) {
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String responseCookies(HttpURLConnection connection) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
            if (entry.getKey() == null || !"set-cookie".equalsIgnoreCase(entry.getKey())) continue;
            for (String value : entry.getValue()) {
                String pair = value.substring(0, value.indexOf(';') < 0 ? value.length() : value.indexOf(';')).trim();
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
        for (String part : source.split(";")) {
            int equal = part.indexOf('=');
            if (equal > 0) values.put(part.substring(0, equal).trim(), part.substring(equal + 1).trim());
        }
    }

    private static String cookieValue(String source, String key) {
        if (source == null || source.isEmpty()) return "";
        for (String part : source.split(";")) {
            int equal = part.indexOf('=');
            if (equal > 0 && key.equals(part.substring(0, equal).trim())) return part.substring(equal + 1).trim();
        }
        return "";
    }

    private static String cookieObject(JSONObject object) {
        Map<String, String> values = new LinkedHashMap<>();
        object.keys().forEachRemaining(key -> values.put(key, object.optString(key)));
        return joinCookies(values);
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

    private static JSONObject nested(JSONObject source, String... keys) {
        JSONObject current = source;
        for (String key : keys) {
            current = current == null ? null : current.optJSONObject(key);
            if (current == null) return new JSONObject();
        }
        return current;
    }

    private static JSONObject jsonp(String source) throws Exception {
        int start = source.indexOf('{');
        int end = source.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalStateException("网盘没有返回可识别的登录状态");
        return new JSONObject(source.substring(start, end + 1));
    }

    private static String first(JSONObject source, String... keys) {
        for (String key : keys) {
            if (!source.has(key) || source.isNull(key)) continue;
            String value = source.optString(key, "").trim();
            if ("null".equalsIgnoreCase(value)) continue;
            if (!value.isEmpty()) return value;
        }
        return "";
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
        for (int y = 0; y < modules; y++) for (int x = 0; x < modules; x++) {
            int color = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            for (int dy = 0; dy < modulePixels; dy++) {
                int row = (y * modulePixels + dy) * side + x * modulePixels;
                for (int dx = 0; dx < modulePixels; dx++) pixels[row + dx] = color;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, side, 0, 0, side, side);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        bitmap.recycle();
        return "data:image/png;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
    }

    private static String enc(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); } catch (Exception ignored) { return value; }
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte item : digest) result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return result.toString();
    }

    private static void cleanup() {
        long now = System.currentTimeMillis();
        SESSIONS.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
    }

    private record Session(String provider, String name, String token, String qrPayload, long expiresAt, String message, JSONObject meta, boolean directImage) {
    }

    private record HttpResult(int code, String body, String cookies) {
    }
}
