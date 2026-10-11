package com.fongmi.android.tv.server.process;

import java.net.URI;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps playlists, segments and encryption keys on the authenticated proxy. */
final class HlsPlaylist {
    private static final Pattern URI_ATTRIBUTE = Pattern.compile("(?<=[,:])URI=\"([^\"]+)\"");

    static String rewrite(String playlist, String baseUrl, Function<String, String> proxy) {
        URI base = URI.create(baseUrl);
        StringBuilder output = new StringBuilder();
        for (String line : playlist.split("\\r?\\n", -1)) {
            String value = line.trim();
            if (!value.isEmpty() && !value.startsWith("#")) {
                line = resolve(base, value, proxy);
            } else if (value.startsWith("#EXT")) {
                Matcher matcher = URI_ATTRIBUTE.matcher(line);
                StringBuffer rewritten = new StringBuffer();
                while (matcher.find()) {
                    matcher.appendReplacement(rewritten, Matcher.quoteReplacement(
                            "URI=\"" + resolve(base, matcher.group(1), proxy) + "\""));
                }
                matcher.appendTail(rewritten);
                line = rewritten.toString();
            }
            if (output.length() > 0) output.append('\n');
            output.append(line);
        }
        return output.toString();
    }

    private static String resolve(URI base, String value, Function<String, String> proxy) {
        URI resolved = base.resolve(value);
        String scheme = resolved.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)
                ? proxy.apply(resolved.toString()) : value;
    }
}
