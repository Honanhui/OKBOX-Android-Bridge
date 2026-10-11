package com.fongmi.android.tv.server;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.net.http.SslError;
import android.text.TextUtils;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.loader.BaseLoader;
import com.fongmi.android.tv.bean.Parse;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.utils.Sniffer;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.google.common.net.HttpHeaders;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Response;

/**
 * Bridge-sized adaptation of TVBox's Android parsing flow. It intentionally
 * retains Android WebView for web parsers but leaves player UI classes out of
 * this APK. The resolved media URL is returned to the LAN media proxy.
 */
public final class AndroidParser {

    public static final class Resolution {
        private final String url;
        private final Map<String, String> headers;

        Resolution(String url, Map<String, String> headers) {
            this.url = url;
            this.headers = headers;
        }

        public String url() {
            return url;
        }

        public Map<String, String> headers() {
            return headers;
        }
    }

    private AndroidParser() {
    }

    public static Resolution resolve(Result result) throws Exception {
        Parse parser = select(result);
        String target = result.getUrl().v();
        if (TextUtils.isEmpty(target)) throw new IllegalStateException("播放源没有返回待解析地址");
        return switch (parser.getType()) {
            case 0 -> web(result, parser, parser.getUrl() + target);
            case 1 -> json(parser, target);
            case 2 -> extensionChecked(parser, target);
            case 3 -> mixChecked(parser, target, result.getFlag());
            case 4 -> superParse(result, target);
            default -> throw new IllegalStateException("不支持的 Android 解析器类型：" + parser.getType());
        };
    }

    private static Parse select(Result result) {
        Parse parser = result.isUseParse() ? VodConfig.get().getParse() : null;
        if (result.getPlayUrl().startsWith("json:")) parser = Parse.get(1, result.getPlayUrl().substring(5));
        if (result.getPlayUrl().startsWith("parse:")) parser = VodConfig.get().getParse(result.getPlayUrl().substring(6));
        if (parser == null || parser.isEmpty()) parser = Parse.get(0, result.getPlayUrl());
        parser.setHeader(result.getHeader());
        Site site = VodConfig.get().getSite(result.getKey());
        parser.setClick(!site.getClick().isEmpty() ? site.getClick() : result.getClick());
        return parser;
    }

    private static Resolution json(Parse parser, String target) throws Exception {
        try (Response response = OkHttp.newCall(parser.getUrl() + target, parser.getHeader()).execute()) {
            if (response.body() == null) throw new IllegalStateException("解析器没有返回内容");
            JSONObject root = new JSONObject(response.body().string());
            JSONObject data = root.optJSONObject("data");
            String url = root.optString("url");
            if (url.isEmpty() && data != null) url = data.optString("url");
            Map<String, String> headers = parserHeaders(root, parser.getHeader());
            if (!valid(url)) throw new IllegalStateException("解析器没有返回有效播放地址");
            return new Resolution(url, headers);
        }
    }

    private static Resolution extension(Parse parser, String target) throws Throwable {
        LinkedHashMap<String, String> jsonParsers = new LinkedHashMap<>();
        for (Parse item : VodConfig.get().getParses()) if (item.getType() == 1) jsonParsers.put(item.getName(), item.extUrl());
        Result output = Result.fromObject(BaseLoader.get().jsonExt(parser.getUrl(), jsonParsers, target));
        return result(output, parser.getHeader(), "扩展解析器没有返回播放地址");
    }

    private static Resolution extensionChecked(Parse parser, String target) throws Exception {
        try {
            return extension(parser, target);
        } catch (Throwable error) {
            throw checked(error);
        }
    }

    private static Resolution mix(Parse parser, String target, String flag) throws Throwable {
        LinkedHashMap<String, HashMap<String, String>> parsers = new LinkedHashMap<>();
        for (Parse item : VodConfig.get().getParses()) parsers.put(item.getName(), item.mixMap());
        Result output = Result.fromObject(BaseLoader.get().jsonExtMix(flag, parser.getUrl(), parser.getName(), parsers, target));
        return result(output, parser.getHeader(), "混合解析器没有返回播放地址");
    }

    private static Resolution mixChecked(Parse parser, String target, String flag) throws Exception {
        try {
            return mix(parser, target, flag);
        } catch (Throwable error) {
            throw checked(error);
        }
    }

    private static Resolution superParse(Result source, String target) throws Exception {
        List<Parse> jsonParsers = VodConfig.get().getParses(1, source.getFlag());
        for (Parse item : jsonParsers) {
            try {
                Resolution value = json(item, target);
                if (valid(value.url())) return value;
            } catch (Exception ignored) {
            }
        }
        List<Parse> webParsers = VodConfig.get().getParses(0, source.getFlag());
        Exception last = null;
        for (Parse item : webParsers) {
            try {
                return web(source, item, item.getUrl() + target);
            } catch (Exception error) {
                last = error;
            }
        }
        throw new IllegalStateException(last == null ? "没有可用的 Android 解析器" : last.getMessage());
    }

    private static Resolution result(Result output, Map<String, String> fallback, String message) throws Exception {
        String url = output.getUrl().v();
        if (output.needParse()) throw new IllegalStateException(message);
        if (!valid(url)) throw new IllegalStateException(message);
        Map<String, String> headers = output.getHeader().isEmpty() ? fallback : output.getHeader();
        return new Resolution(url, new HashMap<>(headers));
    }

    @SuppressLint("SetJavaScriptEnabled")
    private static Resolution web(Result source, Parse parser, String start) throws Exception {
        if (!valid(start)) throw new IllegalStateException("网页解析器地址无效");
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean();
        AtomicReference<String> foundUrl = new AtomicReference<>("");
        AtomicReference<Map<String, String>> foundHeaders = new AtomicReference<>(new HashMap<>());
        AtomicReference<WebView> holder = new AtomicReference<>();
        App.post(() -> {
            WebView view = new WebView(App.get());
            holder.set(view);
            WebSettings settings = view.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setDatabaseEnabled(true);
            settings.setUseWideViewPort(true);
            settings.setLoadWithOverviewMode(true);
            settings.setMediaPlaybackRequiresUserGesture(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            setCookies(view, start, parser.getHeader());
            view.setWebViewClient(new WebViewClient() {
                @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView ignored, WebResourceRequest request) {
                    String candidate = request.getUrl().toString();
                    if (isVideo(source.getKey(), candidate) && completed.compareAndSet(false, true)) {
                        foundUrl.set(candidate);
                        foundHeaders.set(new HashMap<>(request.getRequestHeaders()));
                        done.countDown();
                    }
                    return super.shouldInterceptRequest(ignored, request);
                }

                @Override public void onPageFinished(WebView page, String pageUrl) {
                    if (!"about:blank".equals(pageUrl)) runScripts(page, pageUrl, parser.getClick(), 0);
                }

                @Override @SuppressLint("WebViewClientOnReceivedSslError")
                public void onReceivedSslError(WebView ignored, SslErrorHandler handler, SslError error) {
                    handler.proceed();
                }
            });
            view.loadUrl(start, parser.getHeader());
        });
        boolean ready = done.await(Constant.TIMEOUT_PARSE_DEF + TimeUnit.SECONDS.toMillis(3), TimeUnit.MILLISECONDS);
        WebView view = holder.get();
        App.post(() -> {
            if (view != null) {
                view.stopLoading();
                view.loadUrl("about:blank");
                view.destroy();
            }
        });
        if (!ready || !valid(foundUrl.get())) throw new IllegalStateException("Android 网页解析未返回播放地址");
        return new Resolution(foundUrl.get(), foundHeaders.get());
    }

    private static void runScripts(WebView view, String pageUrl, String click, int index) {
        List<String> scripts = new ArrayList<>(Sniffer.getScript(Uri.parse(pageUrl)));
        if (!TextUtils.isEmpty(click) && !scripts.contains(click)) scripts.add(0, click);
        if (index >= scripts.size()) return;
        String script = scripts.get(index);
        if (TextUtils.isEmpty(script)) {
            runScripts(view, pageUrl, click, index + 1);
        } else {
            view.evaluateJavascript(script, ignored -> runScripts(view, pageUrl, click, index + 1));
        }
    }

    private static void setCookies(WebView view, String url, Map<String, String> headers) {
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptThirdPartyCookies(view, true);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (HttpHeaders.USER_AGENT.equalsIgnoreCase(entry.getKey())) view.getSettings().setUserAgentString(entry.getValue());
            if (HttpHeaders.COOKIE.equalsIgnoreCase(entry.getKey())) cookies.setCookie(url, entry.getValue());
        }
    }

    private static Map<String, String> parserHeaders(JSONObject root, Map<String, String> fallback) {
        Map<String, String> result = new HashMap<>();
        for (String key : new String[]{HttpHeaders.USER_AGENT, HttpHeaders.REFERER, HttpHeaders.COOKIE, "ua"}) {
            String value = root.optString(key);
            if (!value.isEmpty()) result.put("ua".equals(key) ? HttpHeaders.USER_AGENT : key, value);
        }
        return result.isEmpty() ? new HashMap<>(fallback) : result;
    }

    private static boolean isVideo(String key, String url) {
        try {
            Spider spider = VodConfig.get().getSite(key).spider();
            return spider.manualVideoCheck() ? spider.isVideoFormat(url) : Sniffer.isVideoFormat(url);
        } catch (Exception ignored) {
            return Sniffer.isVideoFormat(url);
        }
    }

    private static boolean valid(String url) {
        return url != null && url.trim().toLowerCase(Locale.ROOT).startsWith("http") && url.trim().length() > 12;
    }

    private static Exception checked(Throwable error) {
        return error instanceof Exception value ? value : new IllegalStateException(error.getMessage(), error);
    }
}
