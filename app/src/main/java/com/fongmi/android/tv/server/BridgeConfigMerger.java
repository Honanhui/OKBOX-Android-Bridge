package com.fongmi.android.tv.server;

import com.fongmi.android.tv.api.Decoder;
import com.fongmi.android.tv.server.cloud.CloudGateway;
import com.github.catvod.crawler.SpiderDebug;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Normalizes one imported warehouse into a Bridge-owned TVBox configuration. */
final class BridgeConfigMerger {

    private static final int MAX_DEPOT_DEPTH = 3;
    private static final Set<String> GENERIC_ARRAYS = Set.of(
            "headers", "rules", "doh", "proxy", "flags", "hosts", "ads");

    record Source(String id, String name, String url) {
    }

    private final JSONObject merged = new JSONObject();
    private final JSONArray sites = new JSONArray();
    private final JSONArray parses = new JSONArray();
    private final JSONArray lives = new JSONArray();
    private final Set<String> siteKeys = new HashSet<>();
    private final Set<String> parseNames = new HashSet<>();
    private final Set<String> liveNames = new HashSet<>();
    private final JSONObject errors = new JSONObject();
    private int loadedSources;

    private BridgeConfigMerger() {
    }

    static JSONObject single(Source source) throws Exception {
        // A normal subscription is already one warehouse. Keep its TVBox
        // structure byte-for-byte equivalent at the JSON value level: in
        // particular, protected configurations rely on the root `spider`
        // being the shared/main Jar. Moving that value into every site's
        // `jar` field and deleting `spider` changes JarLoader identity and
        // initialization order, which broke WangErXiao after the Bridge-owned
        // catalogue was introduced.
        JSONObject original = fetchSource(source);
        if (original.optJSONArray("urls") == null) {
            JSONObject result = new JSONObject(original.toString());
            CloudGateway.rewriteSubscription(result);
            return result;
        }

        // A subscription may itself be a depot. It cannot be returned as-is
        // because its children would bypass the Bridge-owned credential
        // gateway, so retain the existing recursive compatibility path only
        // for that case.
        BridgeConfigMerger builder = new BridgeConfigMerger();
        builder.mergeConfig(source, original, 0);
        builder.loadedSources++;
        if (builder.loadedSources == 0) throw new IllegalStateException(firstError(builder.errors));
        builder.merged.put("sites", builder.sites);
        if (builder.parses.length() > 0) builder.merged.put("parses", builder.parses);
        if (builder.lives.length() > 0) builder.merged.put("lives", builder.lives);
        builder.merged.remove("spider");
        CloudGateway.rewriteSubscription(builder.merged);
        return builder.merged;
    }

    private static JSONObject fetchSource(Source source) throws Exception {
        List<String> failures = new ArrayList<>();
        for (String candidate : candidates(source.url())) {
            try {
                return new JSONObject(Decoder.getJson(candidate, "BridgeSubscription"));
            } catch (Throwable error) {
                SpiderDebug.log("[Bridge] subscription fetch failed source=" + source.name()
                        + " candidate=" + candidates(source.url()).indexOf(candidate)
                        + " error=" + error.getClass().getSimpleName());
                failures.add(readable(error));
            }
        }
        throw new IllegalStateException(failures.isEmpty() ? "配置请求失败" : failures.get(failures.size() - 1));
    }

    private void addSource(Source source) {
        List<String> failures = new ArrayList<>();
        for (String candidate : candidates(source.url())) {
            try {
                JSONObject config = new JSONObject(Decoder.getJson(candidate, "BridgeSubscription"));
                mergeConfig(source, config, 0);
                loadedSources++;
                return;
            } catch (Throwable error) {
                failures.add(readable(error));
            }
        }
        putError(source.id(), failures.isEmpty() ? "配置请求失败" : failures.get(failures.size() - 1));
    }

    private void mergeConfig(Source source, JSONObject config, int depth) throws Exception {
        JSONArray depots = config.optJSONArray("urls");
        if (depots != null) {
            if (depth >= MAX_DEPOT_DEPTH) throw new IllegalStateException("多仓嵌套层级过深");
            int before = sites.length();
            List<String> failures = new ArrayList<>();
            for (int index = 0; index < depots.length(); index++) {
                JSONObject depot = depots.optJSONObject(index);
                if (depot == null) continue;
                String url = depot.optString("url").trim();
                if (url.isEmpty()) continue;
                String childName = depot.optString("name").trim();
                Source child = new Source(source.id() + "-" + index,
                        childName.isEmpty() ? source.name() : childName, url);
                try {
                    JSONObject childConfig = new JSONObject(Decoder.getJson(url, "BridgeDepot"));
                    mergeConfig(child, childConfig, depth + 1);
                } catch (Throwable error) {
                    failures.add((childName.isEmpty() ? url : childName) + "：" + readable(error));
                }
            }
            if (sites.length() == before && !failures.isEmpty()) throw new IllegalStateException(failures.get(0));
            return;
        }

        copyRootFields(config);
        mergeSites(source, config.optJSONArray("sites"), config.optString("spider"));
        mergeNamedArray(source, config.optJSONArray("parses"), parses, parseNames, "name");
        mergeNamedArray(source, config.optJSONArray("lives"), lives, liveNames, "name");
        for (String key : GENERIC_ARRAYS) mergeGenericArray(key, config.optJSONArray(key));
    }

    private void copyRootFields(JSONObject config) throws Exception {
        Iterator<String> keys = config.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if ("sites".equals(key) || "parses".equals(key) || "lives".equals(key)
                    || "urls".equals(key) || GENERIC_ARRAYS.contains(key)) continue;
            if (!merged.has(key) && !config.isNull(key)) merged.put(key, config.get(key));
        }
    }

    private void mergeSites(Source source, JSONArray input, String spider) throws Exception {
        if (input == null) return;
        for (int index = 0; index < input.length(); index++) {
            JSONObject raw = input.optJSONObject(index);
            if (raw == null) continue;
            JSONObject site = new JSONObject(raw.toString());
            String key = site.optString("key").trim();
            if (key.isEmpty()) continue;
            if (!siteKeys.add(key)) {
                String base = sourcePrefix(source) + "__" + key;
                String unique = base;
                int suffix = 2;
                while (!siteKeys.add(unique)) unique = base + "_" + suffix++;
                site.put("key", unique);
            }
            if (site.optInt("type", 0) == 3 && site.optString("jar").trim().isEmpty()
                    && spider != null && !spider.trim().isEmpty()) {
                site.put("jar", spider.trim());
            }
            sites.put(site);
        }
    }

    private void mergeNamedArray(Source source, JSONArray input, JSONArray output,
                                 Set<String> names, String nameKey) throws Exception {
        if (input == null) return;
        for (int index = 0; index < input.length(); index++) {
            Object value = input.opt(index);
            if (!(value instanceof JSONObject raw)) {
                if (!contains(output, value)) output.put(value);
                continue;
            }
            JSONObject item = new JSONObject(raw.toString());
            String name = item.optString(nameKey).trim();
            if (!name.isEmpty() && !names.add(name)) {
                String renamed = source.name() + " · " + name;
                int suffix = 2;
                while (!names.add(renamed)) renamed = source.name() + " · " + name + " " + suffix++;
                item.put(nameKey, renamed);
            }
            if (!contains(output, item)) output.put(item);
        }
    }

    private void mergeGenericArray(String key, JSONArray input) throws Exception {
        if (input == null) return;
        JSONArray output = merged.optJSONArray(key);
        if (output == null) {
            output = new JSONArray();
            merged.put(key, output);
        }
        for (int index = 0; index < input.length(); index++) {
            Object value = input.opt(index);
            if (!contains(output, value)) output.put(value);
        }
    }

    private void putError(String id, String message) {
        try {
            errors.put(id, message);
        } catch (Exception ignored) {
        }
    }

    private static List<String> candidates(String value) {
        List<String> items = new ArrayList<>();
        items.add(value);
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("fty.xxooo.cf")) {
            items.add("https://raw.githubusercontent.com/samisold/FongmiTVMod/main/conf/fan.json");
            items.add("https://raw.githubusercontent.com/DodgeZhang/tvbox/main/fantaiying/fty.json");
        } else if (lower.contains("xn--z7x900a.net") || lower.contains("xn--z7x900a.com") || lower.contains("肥猫.net")) {
            items.add("https://raw.githubusercontent.com/samisold/FongmiTVMod/main/conf/feimao.json");
            items.add("https://raw.githubusercontent.com/zzq1234567890/tvbox1/main/feimao.json");
        }
        return items;
    }

    private static String sourcePrefix(Source source) {
        String value = source.id().replaceAll("[^A-Za-z0-9_-]", "");
        return value.length() > 12 ? value.substring(0, 12) : value;
    }

    private static boolean contains(JSONArray array, Object target) {
        String value = String.valueOf(target);
        for (int index = 0; index < array.length(); index++) {
            if (value.equals(String.valueOf(array.opt(index)))) return true;
        }
        return false;
    }

    private static String readable(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message.trim();
    }

    private static String firstError(JSONObject errors) {
        if (errors == null || errors.length() == 0) return "配置请求失败";
        String key = errors.keys().next();
        return errors.optString(key, "配置请求失败");
    }
}
