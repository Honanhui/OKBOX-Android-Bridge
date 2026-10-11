package com.fongmi.android.tv.server.process;

import android.text.TextUtils;

import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.server.AndroidParser;
import com.fongmi.android.tv.server.RequestAddress;
import com.fongmi.android.tv.server.cloud.CloudPlaybackResolver;
import com.fongmi.android.tv.server.impl.Process;
import com.fongmi.android.tv.server.MediaProbe;
import com.github.catvod.crawler.SpiderDebug;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;

/**
 * Exposes the configured TVBox sites to a trusted LAN client.  The endpoint
 * deliberately delegates to {@link SiteApi}, so JAR, JavaScript and Python
 * spiders retain the same runtime behaviour as the TV application.
 */
public class Spider implements Process {

    private static final String PATH = "/api/spider";
    private static final String MIME_JSON = "application/json; charset=utf-8";

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.equals(PATH);
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        if (session.getMethod() != NanoHTTPD.Method.POST) return error(Status.METHOD_NOT_ALLOWED, "Only POST is supported");
        try {
            if (VodConfig.get().getSites().isEmpty()) return error(Status.SERVICE_UNAVAILABLE, "No TVBox configuration is loaded");
            JSONObject request = request(files);
            String action = required(request, "action");
            String siteKey = required(request, "siteKey");
            Site site = VodConfig.get().getSite(siteKey);
            if (site.isEmpty()) return error(Status.NOT_FOUND, "Unknown siteKey: " + siteKey);
            Result result = dispatch(action, site, request);
            if ("player".equals(action) && (result.needParse() || result.isUseParse())) return parse(result, session, request);
            return ok(result, action, session, request);
        } catch (IllegalArgumentException e) {
            return error(Status.BAD_REQUEST, e.getMessage());
        } catch (Throwable e) {
            e.printStackTrace();
            return error(Status.INTERNAL_ERROR, Objects.toString(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    private static JSONObject request(Map<String, String> files) throws Exception {
        String body = files.get("postData");
        if (TextUtils.isEmpty(body)) throw new IllegalArgumentException("Missing JSON request body");
        return new JSONObject(body);
    }

    private static Result dispatch(String action, Site site, JSONObject request) throws Exception {
        // A source can call SpiderDebug with a large JSON response. Prefix
        // every bridge invocation so /api/trace identifies which source that
        // response belongs to when the first configured source was opened
        // automatically before the user selected another one.
        SpiderDebug.log("[Bridge] action=" + action + " site=" + site.getKey()
                + " api=" + site.getApi() + " type=" + site.getType());
        return switch (action) {
            case "home" -> homeContent(site);
            case "category" -> SiteApi.categoryContent(site.getKey(), required(request, "categoryId"), value(request, "page", "1"), request.optBoolean("filter", false), extend(request));
            case "detail" -> SiteApi.detailContent(site.getKey(), required(request, "id"));
            case "search" -> SiteApi.searchContent(site, required(request, "keyword"), request.optBoolean("quick", false), value(request, "page", "1"));
            case "player" -> playerContent(site, required(request, "flag"), required(request, "id"));
            default -> throw new IllegalArgumentException("Unsupported action: " + action);
        };
    }

    private static Result playerContent(Site site, String flag, String id) throws Exception {
        Result managed = CloudPlaybackResolver.resolve(flag, id);
        if (managed != null) return managed;
        try {
            return SiteApi.playerContent(site.getKey(), flag, id);
        } catch (Exception error) {
            // Some legacy spiders expect a compound episode ID even though
            // their detail response already contains a normal media URL.
            // Never hand a webpage, share link or opaque ID to the player.
            if (!isDirectMediaUrl(id)) throw error;
            SpiderDebug.log("[Bridge] direct media fallback site=" + site.getKey()
                    + " error=" + error.getClass().getSimpleName());
            Result direct = new Result();
            direct.setUrl(id);
            direct.setParse(0);
            direct.setFlag(flag);
            direct.setKey(site.getKey());
            direct.setHeader(site.getHeader());
            return direct;
        }
    }

    private static boolean isDirectMediaUrl(String value) {
        return value != null && value.matches("(?i)^https?://[^\\s?#]+\\.(m3u8|mp4|mkv|webm|mov|m4v)(?:[?#].*)?$");
    }

    /**
     * Keep the bridge's home request equivalent to Android TVBox.  In
     * particular, do not fetch every detail page just to fill posters: that
     * turns one home request into dozens of Spider calls and can hide an
     * otherwise valid card list behind a timeout.
     *
     * SiteApi has a UI crash-recovery flag that deliberately skips one home
     * request after a HomeActivity crash. That flag belongs to the Android UI,
     * not this LAN service. If it produced an empty result, call the same
     * Spider methods directly once so a stale UI flag cannot make OKBOX see an
     * empty source forever.
     */
    private static Result homeContent(Site site) throws Exception {
        if (site.getType() == 3 && site.recent().spider() instanceof com.github.catvod.crawler.SpiderNull) {
            throw new IllegalStateException("影视源 Spider 未能加载：JAR 下载或初始化失败，请检查订阅配置后重试");
        }
        Result result = SiteApi.homeContent(site);
        if (site.getType() != 3 || !result.getList().isEmpty()) return result;

        Result recovered = SiteApi.recoverSiteUrls(site);
        if (!recovered.getList().isEmpty()) return recovered;

        com.github.catvod.crawler.Spider spider = site.recent().spider();
        Result retry = Result.fromJson(spider.homeContent(true));
        java.util.List<Vod> videos = Result.fromJson(spider.homeVideoContent()).getList();
        if (!videos.isEmpty()) retry.setList(videos);
        // SiteApi also applies configured category filtering. Preserve the
        // first response's directory if a Spider omits it on this retry.
        if (retry.getTypes().isEmpty() && !result.getTypes().isEmpty()) retry.setTypes(result.getTypes());
        // Android TVBox enters the first available category when a Spider
        // provides a directory but no recommendation feed. Do this inside the
        // Android runtime, where the category id and Spider state are known,
        // so the LAN client receives the actual first page rather than having
        // to reconstruct the TVBox navigation rule from JSON.
        if (retry.getList().isEmpty() && !retry.getTypes().isEmpty()) {
            String typeId = retry.getTypes().get(0).getTypeId();
            if (!typeId.isEmpty()) {
                Result category = SiteApi.categoryContent(site.getKey(), typeId, "1", false, new HashMap<>());
                if (!category.getList().isEmpty()) retry.setList(category.getList());
            }
        }
        return retry;
    }

    private static HashMap<String, String> extend(JSONObject request) {
        HashMap<String, String> values = new HashMap<>();
        JSONObject object = request.optJSONObject("extend");
        if (object == null) return values;
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            values.put(key, String.valueOf(object.opt(key)));
        }
        return values;
    }

    private static String required(JSONObject request, String name) {
        String value = request.optString(name).trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }

    private static String value(JSONObject request, String name, String fallback) {
        String value = request.optString(name).trim();
        return value.isEmpty() ? fallback : value;
    }

    private static Response ok(Result result, String action, IHTTPSession session, JSONObject request) {
        // A number of HLS providers return no custom request headers, but
        // disguise their transport-stream segments as PNG/JPEG responses.
        // Those links still need the media proxy: it rewrites child playlist
        // URLs and removes the image prefix before an external client opens
        // the segment. Keep the supplied headers when present; an empty map
        // is also a valid proxy entry.
        if (!"player".equals(action) || result.needParse() || result.getRealUrl().isEmpty()) {
            return NanoHTTPD.newFixedLengthResponse(Status.OK, MIME_JSON, result.toString());
        }
        try {
            JSONObject payload = new JSONObject(result.toString());
            payload.put("mediaInfo", MediaProbe.probe(result.getRealUrl(), result.getHeader(), request.optString("id")));
            String token = Media.put(result.getRealUrl(), result.getHeader());
            removeSecretHeaders(payload);
            payload.put("streamUrl", RequestAddress.base(session) + "/api/media?token=" + token);
            return NanoHTTPD.newFixedLengthResponse(Status.OK, MIME_JSON, payload.toString());
        } catch (Throwable e) {
            return NanoHTTPD.newFixedLengthResponse(Status.OK, MIME_JSON, result.toString());
        }
    }

    /**
     * TVBox sites frequently return an intermediate player page instead of a
     * media URL. The Android player normally resolves that page through its
     * configured JSON/Web parsers. Do the same work here, then expose the
     * resolved stream through the LAN media proxy so OKBOX never has to run a
     * JavaScript parser itself.
     */
    private static Response parse(Result result, IHTTPSession session, JSONObject request) throws Exception {
        AndroidParser.Resolution parsed = AndroidParser.resolve(result);
        JSONObject payload = new JSONObject(result.toString());
        payload.put("parse", 0);
        payload.put("jx", 0);
        payload.put("url", parsed.url());
        payload.put("header", new JSONObject(parsed.headers()));
        payload.put("mediaInfo", MediaProbe.probe(parsed.url(), parsed.headers(), request.optString("id")));
        String token = com.fongmi.android.tv.server.process.Media.put(parsed.url(), parsed.headers());
        removeSecretHeaders(payload);
        payload.put("streamUrl", RequestAddress.base(session) + "/api/media?token=" + token);
        return NanoHTTPD.newFixedLengthResponse(Status.OK, MIME_JSON, payload.toString());
    }

    private static void removeSecretHeaders(JSONObject payload) throws Exception {
        JSONObject headers = payload.optJSONObject("header");
        if (headers == null) return;
        java.util.List<String> remove = new java.util.ArrayList<>();
        Iterator<String> names = headers.keys();
        while (names.hasNext()) {
            String name = names.next();
            if ("cookie".equalsIgnoreCase(name) || "authorization".equalsIgnoreCase(name)) remove.add(name);
        }
        for (String name : remove) headers.remove(name);
    }

    private static Response error(Status status, String message) {
        JSONObject result = new JSONObject();
        try {
            result.put("error", Objects.toString(message, "Unknown error"));
        } catch (Exception ignored) {
        }
        return NanoHTTPD.newFixedLengthResponse(status, MIME_JSON, result.toString());
    }
}
