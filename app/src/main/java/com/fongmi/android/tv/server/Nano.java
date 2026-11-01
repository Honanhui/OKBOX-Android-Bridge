package com.fongmi.android.tv.server;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.server.impl.Process;
import com.fongmi.android.tv.server.process.Spider;
import com.fongmi.android.tv.server.process.Sources;
import com.fongmi.android.tv.server.process.Media;
import com.fongmi.android.tv.server.process.Health;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class Nano extends NanoHTTPD {

    private List<Process> process;

    public Nano(int port) {
        super(port);
        addProcess();
    }

    private void addProcess() {
        process = new ArrayList<>();
        process.add(new com.fongmi.android.tv.server.process.Manager());
        process.add(new Health());
        process.add(new com.fongmi.android.tv.server.process.Trace());
        process.add(new Spider());
        process.add(new Sources());
        process.add(new com.fongmi.android.tv.server.process.Subscriptions());
        process.add(new Media());
        process.add(new com.fongmi.android.tv.server.process.CloudAccounts());
        process.add(new com.fongmi.android.tv.server.process.CloudGatewayProcess());
        process.add(new com.fongmi.android.tv.server.process.Proxy());
    }

    public static Response ok() {
        return ok("OK");
    }

    public static Response ok(String text) {
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, text);
    }

    public static Response error(String text) {
        return error(Response.Status.INTERNAL_ERROR, text);
    }

    public static Response error(Response.Status status, String text) {
        return newFixedLengthResponse(status, MIME_PLAINTEXT, text);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String url = session.getUri().trim();
        Map<String, String> files = new HashMap<>();
        if (session.getMethod() == Method.POST) parse(session, files);
        for (Process process : process) if (process.isRequest(session, url)) return process.doResponse(session, url, files);
        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Unknown endpoint");
    }

    private void parse(IHTTPSession session, Map<String, String> files) {
        try {
            String ct = session.getHeaders().get("content-type");
            if (ct != null && ct.startsWith("multipart/form-data")) {
                session.getHeaders().put("content-type", ct.contains("charset=") ? ct : ct + "; charset=utf-8");
            } else if (ct != null && ct.startsWith("application/json") && !ct.contains("charset=")) {
                // Browsers commonly omit the charset for JSON. NanoHTTPD then
                // falls back to ASCII and turns Chinese into replacement chars.
                session.getHeaders().put("content-type", ct + "; charset=utf-8");
            }
            session.parseBody(files);
        } catch (Exception ignored) {
        }
    }
}
