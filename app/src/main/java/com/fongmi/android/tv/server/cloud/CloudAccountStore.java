package com.fongmi.android.tv.server.cloud;
import com.github.catvod.net.OkHttp;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import com.fongmi.android.tv.App;
import android.util.Base64;



import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Bridge-owned account center. Provider adapters receive credentials only from
 * this class; the LAN API only ever returns the redacted account summaries.
 */
public final class CloudAccountStore {
    private static final String PREFS = "bridge_cloud_accounts";
    private static final String DATA = "accounts_v1";
    private static final String API_SECRET = "credential_api_secret";
    private static final String SINGLE_ACCOUNT_MIGRATED = "single_account_migrated_v1";
    private static final String KEY_ALIAS = "okbox.bridge.cloud.accounts";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final Map<String, Provider> PROVIDERS = providers();

    private CloudAccountStore() {
    }

    public static JSONObject catalog() throws Exception {
        JSONArray providers = new JSONArray();
        for (Provider provider : PROVIDERS.values()) providers.put(provider.toJson());
        return new JSONObject().put("providers", providers).put("accounts", summaries());
    }

    public static JSONArray summaries() throws Exception {
        JSONArray output = new JSONArray();
        JSONArray accounts = read();
        Set<String> seenProviders = new HashSet<>();
        for (int i = 0; i < accounts.length(); i++) {
            String providerId = accounts.getJSONObject(i).optString("provider");
            if (!seenProviders.add(providerId)) continue;
            JSONObject account = preferredProvider(accounts, providerId);
            if (account == null) continue;
            output.put(new JSONObject()
                    .put("id", account.optString("id"))
                    .put("provider", account.optString("provider"))
                    .put("providerName", providerName(account.optString("provider")))
                    .put("name", account.optString("name"))
                    .put("mode", account.optString("mode"))
                    .put("disabled", account.optBoolean("disabled"))
                    .put("useProxy", account.optBoolean("useProxy"))
                    .put("folder", account.optString("folder"))
                    .put("concurrency", account.optInt("concurrency", 1))
                    .put("credentials", credentialFlags(account.optJSONObject("secret")))
                    .put("updatedAt", account.optLong("updatedAt"))
                    .put("validatedAt", account.optLong("validatedAt"))
                    .put("validationMessage", account.optString("validationMessage"))
                    .put("validationUrl", account.optString("validationUrl"))
                    .put("state", account.optBoolean("disabled") ? "disabled" : account.optString("validationState", "pending")));
        }
        return output;
    }

    public static String providerName(String providerId) {
        Provider provider = PROVIDERS.get(providerId);
        return provider == null ? providerId : provider.name;
    }

    public static boolean supportsProvider(String providerId) {
        return PROVIDERS.containsKey(providerId);
    }

    public static synchronized JSONObject save(String id, String providerId, String name, JSONObject values) throws Exception {
        return save(id, providerId, name, values, new JSONObject());
    }

    public static JSONObject save(String id, String providerId, String name, JSONObject values, JSONObject options) throws Exception {
        Provider provider = PROVIDERS.get(providerId);
        if (provider == null) throw new IllegalArgumentException("不支持的网盘类型");
        JSONArray accounts = read();
        JSONObject sameProvider = preferredProvider(accounts, provider.id);
        String accountId = id.isBlank() ? (sameProvider == null ? UUID.randomUUID().toString() : sameProvider.optString("id")) : id;
        JSONObject previous = find(accounts, accountId);
        JSONObject secret = previous == null || previous.optJSONObject("secret") == null
                ? new JSONObject() : new JSONObject(previous.getJSONObject("secret").toString());
        for (String field : provider.required) {
            String incoming = values.optString(field).trim();
            if (!incoming.isEmpty()) secret.put(field, incoming);
        }
        if ("tianyi".equals(provider.id) && previous != null) {
            JSONObject old = previous.optJSONObject("secret");
            if (old != null && (!old.optString("username").equals(secret.optString("username"))
                    || !old.optString("password").equals(secret.optString("password")))) secret.remove("cookie");
        }
        for (String field : new String[]{"refresh_token", "access_token", "open_token", "open_refresh_token", "open_access_token", "device_id", "captcha_token", "oauth_client_id", "token", "authorization", "loginuuid"}) {
            String incoming = values.optString(field).trim();
            if (!incoming.isEmpty()) secret.put(field, incoming);
        }
        // Preserve provider protocol metadata together with the encrypted secret.
        // Mobile's QR token alone is insufficient for the native Cat session.
        for (String field : new String[]{"account", "auth_token", "userDomainId", "loginData", "token_type", "sub", "user_id", "expires_in", "expires_at"}) {
            if (values.has(field) && !values.isNull(field)) secret.put(field, values.get(field));
        }
        for (String field : provider.required) {
            if ("aliyun".equals(provider.id) && "openToken".equals(field)) continue;
            if ("pan123".equals(provider.id) && !secret.optString("token").trim().isEmpty()) continue;
            if ("thunder".equals(provider.id) && (!secret.optString("refresh_token").trim().isEmpty()
                    || !secret.optString("access_token").trim().isEmpty())) continue;
            if (secret.optString(field).trim().isEmpty()) throw new IllegalArgumentException("请填写" + provider.label(field));
        }
        JSONObject value = new JSONObject();
        value.put("id", accountId);
        value.put("provider", provider.id);
        value.put("name", name.isBlank() ? provider.name : name.trim());
        value.put("mode", provider.mode);
        value.put("disabled", options.optBoolean("disabled"));
        value.put("useProxy", options.optBoolean("useProxy"));
        value.put("folder", options.optString("folder", provider.defaultFolder));
        value.put("concurrency", Math.max(1, options.optInt("concurrency", provider.concurrency)));
        value.put("updatedAt", System.currentTimeMillis());
        value.put("validationState", "pending");
        value.put("validationMessage", "尚未检查");
        value.put("validationUrl", "");
        value.put("validatedAt", 0L);
        value.put("secret", secret);
        boolean replaced = false;
        JSONArray normalized = new JSONArray();
        for (int i = 0; i < accounts.length(); i++) {
            JSONObject existing = accounts.getJSONObject(i);
            if (value.optString("id").equals(existing.optString("id"))) {
                normalized.put(value);
                replaced = true;
            } else if (!provider.id.equals(existing.optString("provider"))) {
                normalized.put(existing);
            }
        }
        if (!replaced) normalized.put(value);
        write(normalized);
        return new JSONObject().put("saved", true).put("accounts", summaries());
    }

    /** Stores OAuth tokens returned by an OpenList-compatible authorization callback. */
    public static JSONObject saveOAuth(String providerId, String name, JSONObject tokens) throws Exception {
        Provider provider = PROVIDERS.get(providerId);
        if (provider == null) throw new IllegalArgumentException("不支持的网盘类型");
        if (tokens.optString("refresh_token").isEmpty() && tokens.optString("refreshToken").isEmpty()
                && tokens.optString("access_token").isEmpty() && tokens.optString("accessToken").isEmpty()) {
            throw new IllegalArgumentException("授权服务没有返回网盘令牌");
        }
        JSONObject normalized = new JSONObject(tokens.toString());
        if (!normalized.has("refreshToken") && normalized.has("refresh_token")) normalized.put("refreshToken", normalized.optString("refresh_token"));
        return save("", provider.id, name, normalized, new JSONObject());
    }

    public static JSONObject remove(String id) throws Exception {
        JSONArray accounts = read();
        JSONArray kept = new JSONArray();
        boolean deleted = false;
        for (int i = 0; i < accounts.length(); i++) {
            JSONObject account = accounts.getJSONObject(i);
            if (id.equals(account.optString("id"))) deleted = true;
            else kept.put(account);
        }
        if (!deleted) throw new IllegalArgumentException("网盘账号不存在");
        write(kept);
        return new JSONObject().put("deleted", true).put("accounts", summaries());
    }

    public static JSONObject setDisabled(String id, boolean disabled) throws Exception {
        JSONArray accounts = read();
        JSONObject selected = find(accounts, id);
        if (selected == null) throw new IllegalArgumentException("网盘账号不存在");
        selected.put("disabled", disabled).put("updatedAt", System.currentTimeMillis());
        write(accounts);
        return new JSONObject().put("saved", true).put("accounts", summaries());
    }

    /** Used only by provider adapters; callers must never return this JSON over LAN. */
    static JSONObject secret(String id) throws Exception {
        JSONArray accounts = read();
        for (int i = 0; i < accounts.length(); i++) {
            JSONObject account = accounts.getJSONObject(i);
            if (id.equals(account.optString("id"))) return account.optJSONObject("secret") == null ? new JSONObject() : account.getJSONObject("secret");
        }
        throw new IllegalArgumentException("网盘账号不存在");
    }

    /** Internal-only account snapshot for the provider validator. */
    static JSONObject account(String id) throws Exception {
        JSONArray accounts = read();
        JSONObject account = find(accounts, id);
        if (account == null) throw new IllegalArgumentException("网盘账号不存在");
        return new JSONObject(account.toString());
    }

    static JSONObject setValidation(String id, String state, String message) throws Exception {
        JSONArray accounts = read();
        JSONObject account = find(accounts, id);
        if (account == null) throw new IllegalArgumentException("网盘账号不存在");
        account.put("validationState", state);
        account.put("validationMessage", message == null ? "" : message);
        account.put("validationUrl", "");
        account.put("validatedAt", System.currentTimeMillis());
        write(accounts);
        return new JSONObject()
                .put("id", id)
                .put("state", state)
                .put("message", message == null ? "" : message)
                .put("accounts", summaries());
    }

    static JSONObject setVerification(String id, String message, String url) throws Exception {
        JSONArray accounts = read();
        JSONObject account = find(accounts, id);
        if (account == null) throw new IllegalArgumentException("网盘账号不存在");
        account.put("validationState", "verify");
        account.put("validationMessage", message == null ? "" : message);
        account.put("validationUrl", url == null ? "" : url);
        account.put("validatedAt", System.currentTimeMillis());
        write(accounts);
        return new JSONObject()
                .put("id", id)
                .put("state", "verify")
                .put("message", message == null ? "" : message)
                .put("validationUrl", url == null ? "" : url)
                .put("accounts", summaries());
    }

    /**
     * Returns a private credential only to code running inside the Bridge app.
     * This method is deliberately not used by any LAN endpoint: callers must
     * apply the value directly to a provider UI and must never serialize it.
     */
    public static JSONObject firstSecretForProvider(String providerId) throws Exception {
        JSONArray accounts = read();
        JSONObject account = preferredProvider(accounts, providerId);
        return account == null || account.optBoolean("disabled") ? new JSONObject() : copySecret(account);
    }

    public static void mergeProviderSecret(String providerId, JSONObject values) throws Exception {
        JSONArray accounts = read();
        JSONObject account = preferredProvider(accounts, providerId);
        if (account == null || account.optBoolean("disabled")) throw new IllegalArgumentException("尚未配置" + providerName(providerId));
        JSONObject secret = account.optJSONObject("secret");
        if (secret == null) secret = new JSONObject();
        java.util.Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!values.isNull(key)) secret.put(key, values.get(key));
        }
        account.put("secret", secret).put("updatedAt", System.currentTimeMillis());
        write(accounts);
    }

    public static String credentialSecret() {
        String value = prefs().getString(API_SECRET, "");
        if (!value.isEmpty()) return value;
        value = UUID.randomUUID().toString().replace("-", "");
        if (!prefs().edit().putString(API_SECRET, value).commit()) throw new IllegalStateException("无法创建网盘接口密钥");
        return value;
    }

    public static boolean validCredentialSecret(String value) {
        String expected = credentialSecret();
        String actual = value == null ? "" : value;
        int diff = expected.length() ^ actual.length();
        int length = Math.max(expected.length(), actual.length());
        for (int i = 0; i < length; i++) diff |= (i < expected.length() ? expected.charAt(i) : 0) ^ (i < actual.length() ? actual.charAt(i) : 0);
        return diff == 0;
    }

    public static JSONObject credentials() throws Exception {
        JSONObject result = new JSONObject();
        putCredential(result, "ali", "aliyun", "refresh_token", "refreshToken", "refresh_token");
        putCredential(result, "quark", "quark", "cookie", "cookie");
        putCredential(result, "uc", "uc", "cookie", "cookie");
        putCredential(result, "115", "pan115", "cookie", "cookie");
        putCredential(result, "baidu", "baidu", "cookie", "cookie");
        putCredential(result, "139", "mobile139", "token", "token");
        putCredential(result, "bili", "bilibili", "cookie", "cookie");
        putCredential(result, "guangya", "guangya", "token", "token", "cookie");
        putPassword(result, "123", "pan123");
        putPassword(result, "189", "tianyi");
        putPassword(result, "pikpak", "pikpak");
        JSONObject ali = result.optJSONObject("ali");
        JSONObject aliSecret = firstSecretForProvider("aliyun");
        if (ali != null) {
            putFirst(ali, "access_token", aliSecret, "access_token", "accessToken");
            putFirst(ali, "open_refresh_token", aliSecret, "open_refresh_token", "open_token", "openToken");
            putFirst(ali, "open_access_token", aliSecret, "open_access_token", "openAccessToken");
        }
        JSONObject pan123Secret = firstSecretForProvider("pan123");
        if (pan123Secret.length() > 0) {
            JSONObject node = result.optJSONObject("123");
            if (node == null) {
                node = new JSONObject();
                result.put("123", node);
            }
            putFirst(node, "token", pan123Secret, "token", "access_token");
            putFirst(node, "authorization", pan123Secret, "authorization");
            putFirst(node, "loginuuid", pan123Secret, "loginuuid");
        }
        JSONObject thunder = firstSecretForProvider("thunder");
        if (thunder.length() > 0) {
            JSONObject node = new JSONObject();
            result.put("xunlei", node);
            putFirst(node, "username", thunder, "username");
            putFirst(node, "password", thunder, "password");
            putFirst(node, "refresh_token", thunder, "refresh_token");
            putFirst(node, "access_token", thunder, "access_token", "token");
            putFirst(node, "device_id", thunder, "device_id");
            putFirst(node, "captcha_token", thunder, "captcha_token", "captchaToken");
            putFirst(node, "token_type", thunder, "token_type");
        }
        return result;
    }

    private static JSONObject credentialFlags(JSONObject secret) throws Exception {
        JSONObject result = new JSONObject();
        if (secret == null) return result;
        for (String key : new String[]{"refreshToken", "openToken", "cookie", "token", "username", "password", "refresh_token", "access_token"}) {
            if (!secret.optString(key).isEmpty()) result.put(key, true);
        }
        return result;
    }

    private static JSONObject find(JSONArray accounts, String id) {
        for (int i = 0; i < accounts.length(); i++) {
            JSONObject account = accounts.optJSONObject(i);
            if (account != null && id.equals(account.optString("id"))) return account;
        }
        return null;
    }

    private static JSONObject preferredProvider(JSONArray accounts, String provider) {
        JSONObject first = null;
        JSONObject enabled = null;
        for (int i = 0; i < accounts.length(); i++) {
            JSONObject account = accounts.optJSONObject(i);
            if (account == null || !provider.equals(account.optString("provider"))) continue;
            if (first == null) first = account;
            if (!account.optBoolean("disabled") && enabled == null) enabled = account;
            if (!account.optBoolean("disabled") && account.optBoolean("master")) return account;
        }
        return enabled == null ? first : enabled;
    }

    private static JSONObject copySecret(JSONObject account) throws Exception {
        JSONObject secret = account.optJSONObject("secret");
        return secret == null ? new JSONObject() : new JSONObject(secret.toString());
    }

    private static void putCredential(JSONObject result, String output, String provider, String field, String... candidates) throws Exception {
        JSONObject secret = firstSecretForProvider(provider);
        String value = first(secret, candidates);
        if (!value.isEmpty()) result.put(output, new JSONObject().put(field, value));
    }

    private static void putPassword(JSONObject result, String output, String provider) throws Exception {
        JSONObject secret = firstSecretForProvider(provider);
        String username = first(secret, "username");
        String password = first(secret, "password");
        if (!username.isEmpty() && !password.isEmpty()) result.put(output, new JSONObject().put("username", username).put("password", password));
    }

    private static void putFirst(JSONObject target, String output, JSONObject secret, String... candidates) throws Exception {
        String value = first(secret, candidates);
        if (!value.isEmpty()) target.put(output, value);
    }

    private static String first(JSONObject secret, String... candidates) {
        if (secret == null) return "";
        for (String key : candidates) {
            String value = secret.optString(key).trim();
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private static synchronized JSONArray read() throws Exception {
        String payload = prefs().getString(DATA, "");
        JSONArray accounts = payload.isEmpty() ? new JSONArray() : new JSONArray(decrypt(payload));
        JSONArray activeAccounts = new JSONArray();
        boolean retiredRemoved = false;
        for (int i = 0; i < accounts.length(); i++) {
            JSONObject account = accounts.getJSONObject(i);
            if (isRetiredProvider(account.optString("provider"))) retiredRemoved = true;
            else activeAccounts.put(account);
        }
        if (retiredRemoved) {
            write(activeAccounts);
            accounts = activeAccounts;
        }
        if (prefs().getBoolean(SINGLE_ACCOUNT_MIGRATED, false)) return accounts;
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < accounts.length(); i++) {
            String provider = accounts.getJSONObject(i).optString("provider");
            counts.put(provider, counts.getOrDefault(provider, 0) + 1);
        }
        JSONArray migrated = new JSONArray();
        boolean removed = false;
        for (int i = 0; i < accounts.length(); i++) {
            JSONObject account = accounts.getJSONObject(i);
            if (counts.getOrDefault(account.optString("provider"), 0) > 1) removed = true;
            else migrated.put(account);
        }
        if (removed) write(migrated);
        if (!prefs().edit().putBoolean(SINGLE_ACCOUNT_MIGRATED, true).commit()) throw new IllegalStateException("无法完成网盘账号迁移");
        return removed ? migrated : accounts;
    }

    private static boolean isRetiredProvider(String providerId) {
        return "quarkTv".equals(providerId)
                || "ucTv".equals(providerId)
                || "open115".equals(providerId)
                || "open123".equals(providerId);
    }

    private static void write(JSONArray accounts) throws Exception {
        if (!prefs().edit().putString(DATA, encrypt(accounts.toString())).commit()) throw new IllegalStateException("无法保存网盘账号");
    }

    private static SharedPreferences prefs() {
        return App.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    private static String encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORM);
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "." + Base64.encodeToString(encrypted, Base64.NO_WRAP);
    }

    private static String decrypt(String value) throws Exception {
        String[] parts = value.split("\\.", 2);
        if (parts.length != 2) throw new IllegalStateException("网盘账号数据损坏");
        Cipher cipher = Cipher.getInstance(TRANSFORM);
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    private static Map<String, Provider> providers() {
        Map<String, Provider> items = new LinkedHashMap<>();
        add(items, new Provider("aliyun", "阿里云盘", "oauth-token", "0", 2, new String[]{"refreshToken", "openToken"}, "刷新令牌", "开放刷新令牌（可选）"));
        add(items, new Provider("quark", "夸克网盘", "cookie", "0", 10, new String[]{"cookie"}, "Cookie"));
        add(items, new Provider("uc", "UC 网盘", "cookie", "0", 8, new String[]{"cookie"}, "Cookie"));
        add(items, new Provider("uctv", "UC TV 网盘", "token", "0", 2, new String[]{"token"}, "TV Token"));
        add(items, new Provider("pan115", "115 网盘", "cookie", "0", 2, new String[]{"cookie"}, "Cookie"));
        add(items, new Provider("pan123community", "123社区", "cookie", "0", 1, new String[]{"cookie"}, "123社区 Cookie（登录 pan1.me 后获取）"));
        add(items, new Provider("pan123", "123 网盘", "password", "0", 1, new String[]{"username", "password"}, "账号", "密码"));
        add(items, new Provider("tianyi", "天翼云盘", "password", "-11", 1, new String[]{"username", "password"}, "账号", "密码"));
        add(items, new Provider("mobile139", "移动云盘", "token", "0", 1, new String[]{"token"}, "令牌"));
        add(items, new Provider("bilibili", "B站", "cookie", "0", 1, new String[]{"cookie"}, "Cookie"));
        add(items, new Provider("thunder", "迅雷云盘", "password", "0", 1, new String[]{"username", "password"}, "账号", "密码"));
        add(items, new Provider("pikpak", "PikPak", "password", "0", 1, new String[]{"username", "password"}, "账号", "密码"));
        add(items, new Provider("baidu", "百度网盘", "cookie", "/", 2, new String[]{"cookie"}, "Cookie"));
        add(items, new Provider("guangya", "光鸭云盘", "oauth-token", "0", 1, new String[]{"token"}, "Token"));
        return items;
    }

    private static void add(Map<String, Provider> items, Provider provider) { items.put(provider.id, provider); }

    private record Provider(String id, String name, String mode, String defaultFolder, int concurrency, String[] required, String... labels) {
        String label(String field) {
            for (int i = 0; i < required.length; i++) if (required[i].equals(field)) return labels[i];
            return field;
        }

        JSONObject toJson() throws Exception {
            JSONArray fields = new JSONArray();
            for (int i = 0; i < required.length; i++) fields.put(new JSONObject().put("id", required[i]).put("label", labels[i])
                    .put("required", i == 0 || !"aliyun".equals(id))
                    .put("secret", required[i].toLowerCase().contains("password") || required[i].equals("cookie") || required[i].toLowerCase().contains("token")));
            return new JSONObject().put("id", id).put("name", name).put("mode", mode)
                    .put("defaultFolder", defaultFolder).put("concurrency", concurrency).put("fields", fields);
        }
    }
}
