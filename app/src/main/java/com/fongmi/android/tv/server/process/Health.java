package com.fongmi.android.tv.server.process;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.server.BridgeConfig;
import com.fongmi.android.tv.server.BridgeWindowGuard;
import com.fongmi.android.tv.server.impl.Process;

import org.json.JSONObject;

import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;

/** A fast, Spider-free liveness endpoint used by paired OKbox clients. */
public final class Health implements Process {

    private static final String PATH = "/api/health";
    private static final String MIME_JSON = "application/json; charset=utf-8";
    private final long startedAt = System.currentTimeMillis();

    @Override public boolean isRequest(IHTTPSession session, String url) {
        return PATH.equals(url);
    }

    @Override public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        if (session.getMethod() != NanoHTTPD.Method.GET) {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_JSON,
                    "{\"error\":\"Only GET is supported\"}");
        }
        try {
            JSONObject result = BridgeConfig.status();
            result.put("ok", true);
            result.put("service", "TVBox Spider Bridge");
            result.put("version", BuildConfig.VERSION_NAME);
            result.put("nativeDialogsSuppressed", BridgeWindowGuard.suppressedWindows());
            result.put("startedAt", startedAt);
            result.put("uptimeMs", System.currentTimeMillis() - startedAt);
            Response response = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, MIME_JSON, result.toString());
            response.addHeader("Cache-Control", "no-store");
            return response;
        } catch (Exception error) {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_JSON,
                    "{\"error\":\"Unable to report Bridge health\"}");
        }
    }
}
