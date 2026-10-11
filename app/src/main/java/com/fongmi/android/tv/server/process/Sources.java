package com.fongmi.android.tv.server.process;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.server.impl.Process;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.Objects;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;

/** Returns source metadata from the configuration loaded by this APK. */
public class Sources implements Process {

    private static final String PATH = "/api/sources";
    private static final String MIME_JSON = "application/json; charset=utf-8";

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.equals(PATH);
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        if (session.getMethod() != NanoHTTPD.Method.GET) return error(Status.METHOD_NOT_ALLOWED, "Only GET is supported");
        try {
            if (VodConfig.get().getSites().isEmpty()) return error(Status.SERVICE_UNAVAILABLE, "No TVBox configuration is loaded");
            JSONArray sites = new JSONArray();
            for (Site site : VodConfig.get().getSites()) sites.put(toJson(site));
            JSONObject response = new JSONObject();
            response.put("sites", sites);
            return NanoHTTPD.newFixedLengthResponse(Status.OK, MIME_JSON, response.toString());
        } catch (Throwable e) {
            e.printStackTrace();
            return error(Status.INTERNAL_ERROR, Objects.toString(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    private static JSONObject toJson(Site site) throws Exception {
        JSONObject item = new JSONObject();
        item.put("key", site.getKey());
        item.put("name", site.getName());
        item.put("type", site.getType());
        item.put("searchable", site.getSearchable());
        item.put("quickSearch", site.getQuickSearch());
        item.put("filterable", site.getCategories().isEmpty() ? 0 : 1);
        return item;
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
