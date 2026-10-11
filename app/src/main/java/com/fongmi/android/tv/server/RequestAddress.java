package com.fongmi.android.tv.server;

import java.util.Locale;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/** Builds public response URLs from the direct request or a trusted reverse proxy. */
public final class RequestAddress {

    private RequestAddress() {
    }

    public static String base(NanoHTTPD.IHTTPSession session) {
        Map<String, String> headers = session.getHeaders();
        String forwarded = headers.getOrDefault("forwarded", "");
        String host = first(headers.get("x-forwarded-host"));
        String scheme = first(headers.get("x-forwarded-proto"));
        if (host.isEmpty()) host = forwardedValue(forwarded, "host");
        if (scheme.isEmpty()) scheme = forwardedValue(forwarded, "proto");
        if (host.isEmpty()) host = first(headers.get("host"));
        if (!safeHost(host)) return Server.get().getAddress(false);
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !"http".equals(scheme)) scheme = "http";
        return scheme + "://" + host;
    }

    private static String first(String value) {
        if (value == null) return "";
        int comma = value.indexOf(',');
        return (comma < 0 ? value : value.substring(0, comma)).trim();
    }

    private static String forwardedValue(String header, String name) {
        String first = first(header);
        for (String part : first.split(";")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length != 2 || !name.equalsIgnoreCase(pair[0].trim())) continue;
            return pair[1].trim().replaceAll("^\"|\"$", "");
        }
        return "";
    }

    private static boolean safeHost(String value) {
        return value != null && !value.isEmpty() && value.length() <= 255
                && value.matches("[A-Za-z0-9.\\-:\\[\\]]+");
    }
}
