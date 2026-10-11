package com.fongmi.android.tv.api;

import android.util.Base64;

import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Crypto;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Util;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.Response;

public class Decoder {

    private static final Pattern JS_URI = Pattern.compile("\"(\\.|\\.\\.)/(.?|.+?)\\.js\\?(.?|.+?)\"");
    private static final long CONFIG_TIMEOUT = 15_000L;

    public static String getJson(String url, String tag) throws Exception {
        try (Response res = OkHttp.newCall(OkHttp.client(CONFIG_TIMEOUT), url, tag).execute()) {
            if (!res.isSuccessful()) throw new Exception("配置请求失败：HTTP " + res.code());
            HttpUrl httpUrl = res.request().url();
            int size = HttpUrl.parse(url).querySize();
            if (httpUrl.querySize() == size) url = httpUrl.toString();
            if (res.body() == null) throw new Exception("配置响应为空");
            return verify(url, res.body().string());
        }
    }

    private static String verify(String url, String data) throws Exception {
        if (data.isEmpty()) throw new Exception();
        if (Json.isObj(data)) return normalizeJson(fix(url, data));
        if (data.contains("**")) data = base64(data);
        if (data.startsWith("2423")) data = cbc(data.replaceAll("\\s+", ""));
        return normalizeJson(fix(url, data));
    }

    private static String fix(String url, String data) {
        Matcher matcher = JS_URI.matcher(data);
        while (matcher.find()) data = replace(url, data, matcher.group());
        if (data.contains("../")) data = data.replace("../", UrlUtil.resolve(url, "../"));
        if (data.contains("./")) data = data.replace("./", UrlUtil.resolve(url, "./"));
        if (data.contains("__JS1__")) data = data.replace("__JS1__", "./");
        if (data.contains("__JS2__")) data = data.replace("__JS2__", "../");
        return data;
    }

    private static String replace(String url, String data, String ext) {
        String t = ext.replace("\"./", "\"" + UrlUtil.resolve(url, "./"));
        t = t.replace("\"../", "\"" + UrlUtil.resolve(url, "../"));
        t = t.replace("./", "__JS1__").replace("../", "__JS2__");
        return data.replace(ext, t);
    }

    private static String cbc(String data) throws Exception {
        String decode = new String(Util.hex2byte(data), StandardCharsets.UTF_8).toLowerCase();
        String key = padEnd(decode.substring(decode.indexOf("$#") + 2, decode.indexOf("#$")));
        String iv = padEnd(decode.substring(decode.length() - 13));
        data = data.substring(data.indexOf("2324") + 4, data.length() - 26);
        byte[] decrypted = Crypto.decryptAesCbc(Util.hex2byte(data), key.getBytes(StandardCharsets.UTF_8), iv.getBytes(StandardCharsets.UTF_8));
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    private static String base64(String data) {
        String extract = extract(data);
        if (extract.isEmpty()) return data;
        return new String(Base64.decode(extract, Base64.DEFAULT));
    }

    private static String extract(String data) {
        Matcher matcher = Pattern.compile("[A-Za-z0-9]{8}\\*\\*").matcher(data);
        return matcher.find() ? data.substring(data.indexOf(matcher.group()) + 10) : "";
    }

    private static String padEnd(String key) {
        return key + "0000000000000000".substring(key.length());
    }

    /** Removes JSON comments without touching URL or comment text inside strings. */
    private static String normalizeJson(String data) throws Exception {
        StringBuilder result = new StringBuilder(data.length());
        boolean inString = false;
        boolean escaped = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int index = 0; index < data.length(); index++) {
            char current = data.charAt(index);
            char next = index + 1 < data.length() ? data.charAt(index + 1) : '\0';
            if (lineComment) {
                if (current == '\n' || current == '\r') {
                    lineComment = false;
                    result.append(current);
                }
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                    index++;
                } else if (current == '\n' || current == '\r') {
                    result.append(current);
                }
                continue;
            }
            if (inString) {
                result.append(current);
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '\"') inString = false;
                continue;
            }
            if (current == '\"') {
                inString = true;
                result.append(current);
            } else if (current == '/' && next == '/') {
                lineComment = true;
                index++;
            } else if (current == '/' && next == '*') {
                blockComment = true;
                index++;
            } else {
                // Some TVBox subscriptions contain a field such as ext": {
                // (missing only the opening quote). Repair that exact key
                // shape outside strings; never rewrite quoted field values,
                // URLs, script text, or otherwise malformed JSON.
                if (missingKeyQuote(data, index, result)) {
                    result.append('"');
                    inString = true;
                }
                result.append(current);
            }
        }
        if (blockComment) throw new Exception("配置包含未闭合的块注释");
        return result.toString();
    }

    private static boolean missingKeyQuote(String data, int index, StringBuilder result) {
        char first = data.charAt(index);
        if (!(first == '_' || first >= 'A' && first <= 'Z' || first >= 'a' && first <= 'z')) return false;
        int previous = result.length() - 1;
        while (previous >= 0 && Character.isWhitespace(result.charAt(previous))) previous--;
        if (previous < 0 || result.charAt(previous) != '{' && result.charAt(previous) != ',') return false;
        int end = index + 1;
        while (end < data.length()) {
            char value = data.charAt(end);
            if (!(value == '_' || value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z'
                    || value >= '0' && value <= '9')) break;
            end++;
        }
        if (end >= data.length() || data.charAt(end) != '"') return false;
        end++;
        while (end < data.length() && Character.isWhitespace(data.charAt(end))) end++;
        return end < data.length() && data.charAt(end) == ':';
    }
}
