package com.fongmi.android.tv.server.process;

import com.fongmi.android.tv.server.impl.Process;
import com.github.catvod.net.RequestTrace;
import com.github.catvod.crawler.SpiderDebug;

import org.json.JSONObject;

import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;

/** Development-only upstream request trace, used to port Android spiders. */
public final class Trace implements Process {

    private static final String PATH = "/api/trace";
    private static final String MIME_JSON = "application/json; charset=utf-8";

    @Override public boolean isRequest(IHTTPSession session, String url) {
        return PATH.equals(url);
    }

    @Override public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        if (session.getMethod() != NanoHTTPD.Method.GET) {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_JSON,
                    "{\"error\":\"Only GET is supported\"}");
        }
        Map<String, List<String>> parameters = session.getParameters();
        boolean clear = parameters.containsKey("clear") && !parameters.get("clear").isEmpty()
                && "1".equals(parameters.get("clear").get(0));
        JSONObject result = RequestTrace.snapshot(clear);
        try {
            // RequestTrace only observes host OkHttp calls. Third-party DEX
            // spiders often use their own client and report failures through
            // SpiderDebug instead, so return both streams together.
            result.put("spiderLogs", SpiderDebug.snapshot(clear));
        } catch (Throwable ignored) {
        }
        Response response = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, MIME_JSON,
                result.toString());
        response.addHeader("Cache-Control", "no-store");
        return response;
    }
}
