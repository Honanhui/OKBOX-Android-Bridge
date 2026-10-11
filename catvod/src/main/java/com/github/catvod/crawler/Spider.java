package com.github.catvod.crawler;

import android.content.Context;

import com.github.catvod.net.OkHttp;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Dns;
import okhttp3.OkHttpClient;

public abstract class Spider {

    // Keep this mutable for binary compatibility with TVBoxOS Spider JARs
    // that assign their own shared empty JSON object during initialization.
    public static JSONObject empty = new JSONObject();
    /**
     * Compatibility field exposed by the q215613905/TVBoxOS Spider API.
     * Several external DEX spiders access it directly instead of retaining
     * the Context passed to init().  It must live on the parent class for
     * Dalvik to resolve the inherited field at runtime.
     */
    protected static Context mContext;
    public String siteKey;

    public static Dns safeDns() {
        return OkHttp.dns();
    }

    public static OkHttpClient client() {
        return OkHttp.client();
    }

    public void init(Context context) throws Exception {
        mContext = context;
    }

    public void init(Context context, String extend) throws Exception {
        init(context);
    }

    /**
     * Present in the TVBoxOS Spider ABI. JAR spiders compiled against that
     * base class may override it, even when they do not need host callbacks.
     */
    public void initApi(SpiderApi api) {
    }

    public String homeContent(boolean filter) throws Exception {
        return "";
    }

    public String homeVideoContent() throws Exception {
        return "";
    }

    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        return "";
    }

    public String detailContent(List<String> ids) throws Exception {
        return "";
    }

    public String searchContent(String key, boolean quick) throws Exception {
        return "";
    }

    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return "";
    }

    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        return "";
    }

    public String liveContent(String url) throws Exception {
        return "";
    }

    public boolean manualVideoCheck() throws Exception {
        return false;
    }

    public boolean isVideoFormat(String url) throws Exception {
        return false;
    }

    public void cancelByTag() {
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        return proxyLocal(params);
    }

    public Object[] proxyLocal(Map<String, String> params) throws Exception {
        return null;
    }

    public String action(String action) throws Exception {
        return null;
    }

    public void destroy() {
    }
}
