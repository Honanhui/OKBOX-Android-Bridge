package com.fongmi.android.tv.api.loader;

import android.content.Context;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.Download;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.android.tv.server.cloud.CloudTokenService;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderApi;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.crawler.SpiderNull;

import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Crypto;
import com.github.catvod.utils.Path;

import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import dalvik.system.DexClassLoader;

public class JarLoader {

    private final ConcurrentHashMap<String, DexClassLoader> loaders;
    private final ConcurrentHashMap<String, Method> methods;
    private final ConcurrentHashMap<String, Spider> spiders;
    private final ConcurrentHashMap<String, Object> locks;
    // Keep the same per-site Jar bookkeeping as TVBoxOS. A configuration can
    // reference more than one Spider Jar; proxy/parser callbacks must return
    // to the Jar that created the current site rather than whichever Jar was
    // loaded most recently.
    private final ConcurrentHashMap<String, String> siteJarKeys;
    private final ConcurrentHashMap<String, String> aliases;
    private final ProtectedInitJar protectedInitJar;
    private volatile String recent;

    public JarLoader() {
        loaders = new ConcurrentHashMap<>();
        methods = new ConcurrentHashMap<>();
        spiders = new ConcurrentHashMap<>();
        locks = new ConcurrentHashMap<>();
        siteJarKeys = new ConcurrentHashMap<>();
        aliases = new ConcurrentHashMap<>();
        protectedInitJar = new ProtectedInitJar();
    }

    public void clear() {
        // A third-party Spider must not abort a configuration switch from
        // destroy(). Protected/native implementations can throw after their
        // loader enters an invalid state. TVBoxOS isolates each destroy call
        // for the same reason.
        for (Spider spider : spiders.values()) {
            try {
                spider.destroy();
            } catch (Throwable error) {
                error.printStackTrace();
            }
        }
        loaders.clear();
        methods.clear();
        spiders.clear();
        locks.clear();
        siteJarKeys.clear();
        aliases.clear();
        protectedInitJar.clear();
        recent = null;
    }

    public void setRecent(String recent) {
        if (recent == null || recent.isEmpty()) return;
        this.recent = realKey(recent);
        injectProxyPort(loaders.get(this.recent));
    }

    private void load(String key, File file) {
        if (Thread.interrupted()) return;
        if (!Path.exists(file) || !file.setReadOnly()) return;
        String cachePath = Path.jar().getAbsolutePath();
        DexClassLoader loader = new DexClassLoader(file.getAbsolutePath(), cachePath, cachePath, App.get().getClassLoader());
        if (!invokeInit(loader, file.getAbsolutePath())) return;
        // FeiMao's DEX creates a private OkHttp client. Attach a transport
        // trace before any site is instantiated so /api/trace can report the
        // real upstream URL and HTTP outcome when a Spider returns no cards.
        DexNetworkTrace.install(loader);
        invokeProxy(key, loader);
        injectProxyPort(loader);
        loaders.put(key, loader);
    }

    private boolean invokeInit(DexClassLoader loader, String jar) {
        boolean protectedJar = false;
        try {
            Class<?> clz = loader.loadClass("com.github.catvod.spider.Init");
            protectedJar = protectedInitJar.check(jar);
            if (protectedJar) return protectedInitJar.init(clz);
            Method method = clz.getMethod("init", Context.class);
            method.invoke(null, App.get());
        } catch (Throwable e) {
            e.printStackTrace();
        }
        // No Init class is normal for many open Spider JARs. Only reject a
        // protected JAR when the protected-runtime binding failed.
        return !protectedJar;
    }

    private void invokeProxy(String key, DexClassLoader loader) {
        try {
            Class<?> clz = loader.loadClass("com.github.catvod.spider.Proxy");
            Method method = clz.getMethod("proxy", Map.class);
            methods.put(key, method);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public void parseJar(String key, String jar) {
        if (key == null || key.isEmpty() || jar == null || jar.isEmpty()) return;
        if (loaders.containsKey(key)) return;
        if (jar.startsWith("assets")) jar = UrlUtil.convert(jar);
        Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            if (loaders.containsKey(key)) return;
            String[] texts = jar.split(";md5;");
            String md5 = texts.length > 1 ? texts[1].trim() : "";
            if (md5.startsWith("http")) md5 = OkHttp.string(md5).trim();
            String source = texts[0];
            aliases.put(Crypto.md5(source), key);
            if (!md5.isEmpty() && Crypto.equals(Path.jar(source), md5)) {
                load(key, Path.jar(source));
            } else if (source.startsWith("http")) {
                load(key, Download.create(source, Path.jar(source)).get());
            } else if (source.startsWith("file")) {
                load(key, Path.local(source));
            }
        }
    }

    public DexClassLoader dex(String jar) {
        try {
            String jaKey = Crypto.md5(jar);
            parseJar(jaKey, jar);
            return loaders.get(jaKey);
        } catch (Throwable e) {
            e.printStackTrace();
            return null;
        }
    }

    public Spider getSpider(String key, String api, String ext, String jar) {
        key = key == null ? "" : key;
        api = api == null ? "" : api;
        ext = ext == null ? "" : ext;
        jar = jar == null ? "" : jar;
        if (api.isEmpty() || jar.isEmpty()) return new SpiderNull();
        final String siteKey = key;
        final String spiderApi = api;
        final String spiderExt = ext;
        final String spiderJar = jar;
        final String jaKey = Crypto.md5(spiderJar);
        final String spKey = jaKey + siteKey;
        // TVBoxOS updates the active Jar on every Spider acquisition. This is
        // essential for JARs whose proxy/parser helpers use the active loader.
        setRecent(jaKey);
        siteJarKeys.put(siteKey, jaKey);
        Spider spider = spiders.computeIfAbsent(spKey, k -> {
            Spider created = createSpider(siteKey, spiderApi, spiderExt, spiderJar);
            // A transient JAR download failure must not poison this site for
            // the rest of the process lifetime. Only cache working instances.
            return created instanceof SpiderNull ? null : created;
        });
        if (spider == null) return new SpiderNull();
        injectBaiduCredential(siteKey, spiderApi, loaders.get(jaKey));
        return spider;
    }

    /**
     * Duopan keeps Baidu authorization in its own encrypted Spider storage
     * (mi.baidu), so placing a Cookie in subscription ext has no effect.
     * Run the Spider's own setter every time the site is acquired: the Spider
     * cache remains valid while source switching and account updates still
     * receive the currently selected Bridge credential.
     */
    private void injectBaiduCredential(String siteKey, String api, DexClassLoader loader) {
        if (!"csp_Duopan".equals(api) || loader == null) return;
        try {
            String cookie = CloudTokenService.ensureFresh("baidu").optString("cookie").trim();
            if (cookie.isEmpty()) return;
            Class<?> credential = loader.loadClass("com.github.catvod.spider.merge.p.f");
            Method write = credential.getDeclaredMethod("i", String.class);
            write.setAccessible(true);
            Object writeResult = write.invoke(null, cookie);

            Class<?> baidu = loader.loadClass("com.github.catvod.spider.merge.b.C");
            Method singleton = baidu.getDeclaredMethod("n");
            singleton.setAccessible(true);
            Object instance = singleton.invoke(null);
            Method configured = baidu.getDeclaredMethod("A");
            configured.setAccessible(true);
            boolean verified = Boolean.TRUE.equals(configured.invoke(instance));
            if (!Boolean.TRUE.equals(writeResult) || !verified) {
                SpiderDebug.log("[Bridge] Baidu credential injection was not accepted for site=" + siteKey);
                return;
            }
            SpiderDebug.log("[Bridge] Baidu credential injected and verified for site=" + siteKey);
        } catch (Throwable error) {
            // A non-Duopan JAR can use the same API key. Keep it usable and
            // surface a redacted diagnostic instead of leaking credentials.
            SpiderDebug.log("[Bridge] Baidu credential injection failed for site=" + siteKey
                    + ": " + error.getClass().getSimpleName());
        }
    }

    /** Creates an uncached Spider for a controlled source-health probe. */
    public Spider createSpider(String key, String api, String ext, String jar) {
        try {
            String jaKey = Crypto.md5(jar);
            parseJar(jaKey, jar);
            DexClassLoader loader = loaders.get(jaKey);
            if (loader == null) return new SpiderNull();
            Spider spider = (Spider) loader.loadClass("com.github.catvod.spider." + className(api)).newInstance();
            spider.siteKey = key;
            spider.initApi(new SpiderApi());
            spider.init(App.get(), ext);
            injectBaiduCredential(key, api, loader);
            return spider;
        } catch (Throwable e) {
            e.printStackTrace();
            return new SpiderNull();
        }
    }

    /** Promotes a health-checked Spider for later category/detail/player calls. */
    public void replaceSpider(String key, String jar, Spider spider) {
        if (spider == null || spider instanceof SpiderNull) return;
        String jaKey = Crypto.md5(jar);
        siteJarKeys.put(key, jaKey);
        spiders.put(jaKey + key, spider);
        setRecent(jaKey);
    }

    private void injectProxyPort(DexClassLoader loader) {
        try {
            int port = com.github.catvod.Proxy.getPort();
            if (port <= 0) return;
            Class<?> proxy = loader.loadClass("com.github.catvod.Proxy");
            proxy.getMethod("set", int.class).invoke(null, port);
        } catch (Throwable ignored) {
            // Older JARs inherit the host Proxy class and do not package one.
        }
    }

    private DexClassLoader requireRecentLoader() {
        DexClassLoader loader = loaders.get(recent);
        if (loader == null) throw new IllegalStateException("No jar loaded for recent key: " + recent);
        return loader;
    }

    public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) throws Throwable {
        Class<?> clz = requireRecentLoader().loadClass("com.github.catvod.parser.Json" + key);
        Method method = clz.getMethod("parse", LinkedHashMap.class, String.class);
        return (JSONObject) method.invoke(null, jxs, url);
    }

    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) throws Throwable {
        Class<?> clz = requireRecentLoader().loadClass("com.github.catvod.parser.Mix" + key);
        Method method = clz.getMethod("parse", LinkedHashMap.class, String.class, String.class, String.class);
        return (JSONObject) method.invoke(null, jxs, name, flag, url);
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        String siteKey = params == null ? null : params.get("siteKey");
        if (siteKey != null && !siteKey.isEmpty()) {
            Object[] siteResult = proxyInvoke(methods.get(siteJarKeys.get(siteKey)), params);
            if (siteResult != null) return siteResult;
        }
        Method method = recent != null ? methods.get(recent) : null;
        Object[] result = proxyInvoke(method, params);
        if (result != null) return result;
        return tryOthers(params);
    }

    private Object[] tryOthers(Map<String, String> p) {
        return methods.entrySet().stream().filter(e -> !e.getKey().equals(recent)).map(e -> proxyInvoke(e.getValue(), p)).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private Object[] proxyInvoke(Method method, Map<String, String> params) {
        try {
            return method == null ? null : (Object[]) method.invoke(null, params);
        } catch (Throwable e) {
            e.printStackTrace();
            return null;
        }
    }

    private String realKey(String key) {
        String alias = aliases.get(key);
        return alias == null || alias.isEmpty() ? key : alias;
    }

    private String className(String api) {
        return api.contains("csp_") ? api.split("csp_", 2)[1] : api;
    }
}

