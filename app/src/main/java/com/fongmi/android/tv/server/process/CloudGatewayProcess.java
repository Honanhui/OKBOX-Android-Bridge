package com.fongmi.android.tv.server.process;

import com.fongmi.android.tv.server.cloud.CloudGateway;
import com.fongmi.android.tv.server.cloud.CloudShare;
import com.fongmi.android.tv.server.impl.Process;

import org.json.JSONObject;

import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/** Bridge cloud config endpoints. Credential-bearing routes are loopback only. */
public final class CloudGatewayProcess implements Process {
    private static final String CONFIG = "/cloud/config";
    private static final String TOKENM = "/cloud/tokenm";
    private static final String CREDENTIAL = "/cloud/credential/";
    private static final String STATUS = "/api/cloud/gateway";
    private static final String RESOLVE = "/api/cloud/resolve";
    private static final String JSON = "application/json; charset=utf-8";

    @Override public boolean isRequest(NanoHTTPD.IHTTPSession session, String url) {
        return CONFIG.equals(url) || TOKENM.equals(url) || STATUS.equals(url) || RESOLVE.equals(url) || url.startsWith(CREDENTIAL);
    }

    @Override public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String url, Map<String, String> files) {
        try {
            if (STATUS.equals(url)) {
                if (session.getMethod() != NanoHTTPD.Method.GET) return error(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, "Only GET is supported");
                return json(CloudGateway.status());
            }
            if (RESOLVE.equals(url)) {
                if (session.getMethod() != NanoHTTPD.Method.POST) return error(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, "Only POST is supported");
                JSONObject result = CloudShare.inspect(new JSONObject(files.getOrDefault("postData", "{}")).optString("url"));
                result.put("state", result.optJSONArray("accounts") != null && result.optJSONArray("accounts").length() > 0
                        ? "account-ready" : "account-required");
                result.put("resolver", "pending-provider-adapter");
                return json(result);
            }
            if (!CloudGateway.isLoopback(session.getRemoteIpAddress())) {
                return error(NanoHTTPD.Response.Status.FORBIDDEN, "Cloud credentials are available only to the local Android Spider");
            }
            if (session.getMethod() != NanoHTTPD.Method.GET) return error(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, "Only GET is supported");
            if (CONFIG.equals(url)) return json(CloudGateway.tvFanConfig());
            if (TOKENM.equals(url)) return json(CloudGateway.tokenConfig());
            if (url.startsWith(CREDENTIAL)) {
                String provider = url.substring(CREDENTIAL.length()).trim();
                if (provider.isEmpty()) return error(NanoHTTPD.Response.Status.BAD_REQUEST, "Missing provider");
                String value = CloudGateway.credential(provider);
                if (value.isEmpty()) return error(NanoHTTPD.Response.Status.NOT_FOUND, "Cloud account is not configured");
                return text(value);
            }
            return error(NanoHTTPD.Response.Status.NOT_FOUND, "Unknown cloud endpoint");
        } catch (IllegalArgumentException e) {
            return error(NanoHTTPD.Response.Status.BAD_REQUEST, e.getMessage());
        } catch (Throwable e) {
            return error(NanoHTTPD.Response.Status.INTERNAL_ERROR, message(e));
        }
    }

    private static NanoHTTPD.Response json(JSONObject body) {
        NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, JSON, body.toString());
        response.addHeader("Cache-Control", "no-store");
        return response;
    }

    private static NanoHTTPD.Response text(String body) {
        NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain; charset=utf-8", body);
        response.addHeader("Cache-Control", "no-store");
        return response;
    }

    private static NanoHTTPD.Response error(NanoHTTPD.Response.Status status, String message) {
        JSONObject body = new JSONObject();
        try { body.put("error", message); } catch (Exception ignored) { }
        NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(status, JSON, body.toString());
        response.addHeader("Cache-Control", "no-store");
        return response;
    }

    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.trim().isEmpty() ? error.getClass().getSimpleName() : value.trim();
    }
}
