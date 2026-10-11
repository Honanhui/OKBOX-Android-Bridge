package com.fongmi.android.tv.server.process;

import com.fongmi.android.tv.server.impl.Process;
import com.github.catvod.net.OkHttp;

import java.io.FilterInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.HttpUrl;
import okhttp3.Headers;
import okhttp3.Request;

/**
 * Streams a resolved video through the Android TVBox process. Tokens are
 * created only by the player endpoint, so a LAN client cannot turn this into
 * an arbitrary HTTP proxy.
 */
public class Media implements Process {

    private static final String PATH = "/api/media";
    // The Harmony media engine picks its demuxer from a URL before it sends
    // the first request. Keep the token protection, but provide a truthful
    // HLS suffix for callers that have already inspected the stream.
    private static final String HLS_PATH = "/api/media.m3u8";
    private static final String MP4_PATH = "/api/media.mp4";
    private static final String MKV_PATH = "/api/media.mkv";
    private static final long TTL = 30 * 60 * 1000L;
    private static final int PROBE_BYTES = 64 * 1024;
    // Baidu/Dudu links can take more than Harmony's ten-second HTTP timeout
    // when a short byte range is sent upstream, while their sequential stream
    // starts immediately. Cover the player bootstrap reads locally.
    private static final long FAST_RANGE_WINDOW = 2L * 1024 * 1024;
    private static final ConcurrentHashMap<String, Entry> ENTRIES = new ConcurrentHashMap<>();

    public static String put(String url, Map<String, String> header) {
        discardExpired();
        // Legacy spiders can report an uninitialised embedded proxy port.
        url = url.replaceFirst("^http://(?:127\\.0\\.0\\.1|localhost):-1/proxy(?=[/?]|$)",
                com.github.catvod.Proxy.getUrl(true));
        // Cloud-drive plugins occasionally put a mini instruction such as
        // "if_status_eq_404{...}" directly in a query value. Browsers may
        // tolerate that form, but OkHttp correctly rejects the raw braces
        // before it can open the media request. Keep existing escapes intact
        // and encode only characters that cannot appear literally in a URI.
        url = normalizeUrl(url);
        String token = UUID.randomUUID().toString().replace("-", "");
        ENTRIES.put(token, new Entry(url, new HashMap<>(header), System.currentTimeMillis() + TTL));
        return token;
    }

    private static String normalizeUrl(String url) {
        return url.replace(" ", "%20")
                .replace("{", "%7B")
                .replace("}", "%7D")
                .replace("[", "%5B")
                .replace("]", "%5D")
                .replace("|", "%7C")
                .replace("\\", "%5C")
                .replace("\"", "%22");
    }

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String url) {
        return url.equals(PATH) || url.equals(HLS_PATH) || url.equals(MP4_PATH) || url.equals(MKV_PATH);
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String url, Map<String, String> files) {
        if (session.getMethod() != NanoHTTPD.Method.GET && session.getMethod() != NanoHTTPD.Method.HEAD) {
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, NanoHTTPD.MIME_PLAINTEXT, "Only GET is supported");
        }
        String token = session.getParms().get("token");
        Entry entry = token == null ? null : ENTRIES.get(token);
        if (entry == null || entry.expired()) {
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Playback token expired");
        }
        entry.touch();
        try {
            Map<String, String> header = new HashMap<>(entry.header());
            String range = session.getHeaders().get("range");
            RangeSpec requested = RangeSpec.parse(range);
            boolean sequentialBootstrap = session.getMethod() == NanoHTTPD.Method.GET && requested != null
                    && requested.start < FAST_RANGE_WINDOW && requested.length() <= FAST_RANGE_WINDOW;
            int mediaPrefix = entry.mediaPrefix();
            if (range != null && !range.isEmpty()) {
                // A full sequential response avoids the fixed ~10 s delay that
                // some Dudu/Baidu gateways impose on short range requests.
                header.put("Range", sequentialBootstrap ? "bytes=0-" : shiftRange(range, mediaPrefix));
            }
            Response upstream = openMedia(entry.url(), header);
            ResponseBody body = upstream.body();
            if (body == null) {
                upstream.close();
                return NanoHTTPD.newFixedLengthResponse(new MediaStatus(502), NanoHTTPD.MIME_PLAINTEXT, "Empty media response");
            }
            // Resolve against the final URL after redirects, not the token URL.
            // Relative segment URLs otherwise point back to /api on this server,
            // and absolute URLs lose the spider's Referer/Cookie/User-Agent.
            if (session.getMethod() == NanoHTTPD.Method.GET && upstream.isSuccessful() && isPlaylist(upstream)) {
                try (Response playlistResponse = upstream) {
                    String source = body.string();
                    if (!source.trim().startsWith("#EXTM3U")) throw new IOException("Invalid HLS playlist");
                    String playlist = HlsPlaylist.rewrite(source, playlistResponse.request().url().toString(),
                            child -> PATH + "?token=" + entry.children().compute(child, (key, previous) -> {
                                Entry cached = previous == null ? null : ENTRIES.get(previous);
                                if (cached == null || cached.expired()) return put(key, entry.header());
                                cached.touch();
                                return previous;
                            }));
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK,
                            "application/vnd.apple.mpegurl", playlist);
                }
            }
            int skipPrefix = 0;
            // Do not wait for a 64 KiB look-ahead on a normal file. Some cloud
            // providers deliver a tiny range immediately but throttle a larger
            // read long enough for Harmony's AVPlayer request to time out.
            // The expensive probe is only needed for image/generic responses,
            // where an HLS segment may actually be hidden behind an image.
            int detectedPrefix = mediaPrefix == 0 && mayBeDisguisedSegment(upstream) ? disguisedTsPrefix(upstream) : 0;
            if (detectedPrefix > 0) {
                entry.setMediaPrefix(detectedPrefix);
                mediaPrefix = detectedPrefix;
                // A native player generally uses byte ranges for HLS segments.
                // Retry with the requested logical range mapped past the picture
                // prefix, so the response has exactly the bytes it asked for.
                if (range != null && !range.isEmpty()) {
                    upstream.close();
                    header.put("Range", shiftRange(range, mediaPrefix));
                    upstream = openMedia(entry.url(), header);
                    body = upstream.body();
                    if (body == null) {
                        upstream.close();
                        return NanoHTTPD.newFixedLengthResponse(new MediaStatus(502), NanoHTTPD.MIME_PLAINTEXT, "Empty media response");
                    }
                } else {
                    skipPrefix = mediaPrefix;
                }
            }
            // Several cloud CDNs send Content-Range even when the client did
            // not ask for one. Forwarding that as 200 creates an invalid HTTP
            // response; AVPlayer probes this with HEAD before it starts a file.
            boolean head = session.getMethod() == NanoHTTPD.Method.HEAD;
            // Some cloud gateways return a Content-Range header even for a
            // plain HEAD request. Propagating their 206 response makes
            // AVPlayer reject an otherwise valid file before it performs its
            // first GET. A HEAD request describes the complete resource here;
            // only an actual GET range is allowed to remain partial.
            boolean partial = !head && !upstream.header("Content-Range", "").isEmpty();
            int requestedSkip = sequentialBootstrap ? (int) requested.start : 0;
            NanoHTTPD.Response response = createResponse(upstream, body, head,
                    partial || sequentialBootstrap, skipPrefix + requestedSkip, mediaPrefix > 0 ? "video/mp2t" : null,
                    sequentialBootstrap ? requested.length() : -1);
            copyHeader(upstream, response, "Accept-Ranges");
            if (sequentialBootstrap) copyRequestedContentRange(upstream, response, requested);
            else if (!head) copyContentRange(upstream, response, mediaPrefix);
            copyHeader(upstream, response, "Cache-Control");
            return response;
        } catch (Throwable e) {
            return NanoHTTPD.newFixedLengthResponse(new MediaStatus(502), NanoHTTPD.MIME_PLAINTEXT, "Unable to load media: " + e.getMessage());
        }
    }

    private static boolean isPlaylist(Response upstream) throws IOException {
        String mime = upstream.header("Content-Type", "").toLowerCase(Locale.ROOT);
        return mime.contains("mpegurl") || upstream.request().url().encodedPath().toLowerCase(Locale.ROOT).endsWith(".m3u8")
                || upstream.peekBody(32).string().trim().startsWith("#EXTM3U");
    }

    /**
     * Follow cloud redirects ourselves so a malformed-but-common Location
     * value can be normalised before OkHttp parses it. OkHttp's automatic
     * redirect handler parses Location first and therefore never reaches the
     * normalisation in {@link #put(String, Map)}.
     */
    private static Response openMedia(String url, Map<String, String> header) throws IOException {
        String target = normalizeUrl(url);
        for (int count = 0; count < 6; count++) {
            Response response = OkHttp.noRedirect().newCall(new Request.Builder()
                    .url(target).headers(Headers.of(header)).build()).execute();
            if (!response.isRedirect()) return response;
            String location = response.header("Location");
            HttpUrl next = location == null ? null : response.request().url().resolve(normalizeUrl(location));
            if (next == null) return response;
            response.close();
            target = next.toString();
        }
        throw new IOException("Too many media redirects");
    }

    private static NanoHTTPD.Response createResponse(Response upstream, ResponseBody body, boolean head, boolean partial,
                                                       int skipPrefix, String forcedMime, long lengthOverride) throws IOException {
        String mime = forcedMime == null ? mime(upstream) : forcedMime;
        InputStream source = head ? new ByteArrayInputStream(new byte[0]) : body.byteStream();
        if (!head && skipPrefix > 0) skip(source, skipPrefix);
        InputStream stream = new ResponseInputStream(source, upstream);
        if (!head && lengthOverride >= 0) stream = new LimitedInputStream(stream, lengthOverride);
        // OkHttp reports -1 for a number of ranged cloud responses. Their
        // Content-Range header is still authoritative. Passing 0 for HEAD or
        // chunking a known range makes Harmony conclude the MKV has no body.
        long length = lengthOverride >= 0 ? lengthOverride : contentLength(upstream, body, skipPrefix);
        // A number of cloud CDNs return a 200 status with a valid
        // Content-Range body. Harmony's player treats that combination as an
        // invalid seek response, while a compliant 206 plays normally.
        // Do not expose an upstream's unsolicited 206 on HEAD. Native
        // AVPlayer treats it as a broken initial resource probe.
        NanoHTTPD.Response.IStatus status = partial ? status(206) : status(head ? 200 : upstream.code());
        return length >= 0 ? NanoHTTPD.newFixedLengthResponse(status, mime, stream, length) : NanoHTTPD.newChunkedResponse(status, mime, stream);
    }

    private static long contentLength(Response upstream, ResponseBody body, int skipPrefix) {
        long length = body.contentLength();
        if (length >= 0 && (!upstream.header("Content-Range", "").isEmpty() || length > 0)) {
            return Math.max(0, length - skipPrefix);
        }
        java.util.regex.Matcher range = java.util.regex.Pattern
                .compile("(?i)^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$")
                .matcher(upstream.header("Content-Range", "").trim());
        if (range.matches()) {
            long start = Long.parseLong(range.group(1));
            long end = Long.parseLong(range.group(2));
            return Math.max(0, end - start + 1 - skipPrefix);
        }
        return length < 0 ? -1 : Math.max(0, length - skipPrefix);
    }

    private static void skip(InputStream stream, int count) throws IOException {
        int remaining = count;
        while (remaining > 0) {
            long skipped = stream.skip(remaining);
            if (skipped > 0) {
                remaining -= (int) skipped;
                continue;
            }
            if (stream.read() < 0) throw new IOException("Truncated disguised media segment");
            remaining--;
        }
    }

    private static String mime(Response upstream) {
        String value = upstream.header("Content-Type", "application/octet-stream");
        String normalized = value.toLowerCase(Locale.ROOT);
        // Some cloud endpoints use the non-standard "application/oct-stream".
        // Correct it before handing the response to the native video decoder.
        if (normalized.startsWith("application/oct-stream")) value = "application/octet-stream" + value.substring("application/oct-stream".length());
        if (value.toLowerCase(Locale.ROOT).startsWith("application/octet-stream")) {
            String detected = containerMime(upstream);
            if (detected != null) return detected;
        }
        return value;
    }

    private static String containerMime(Response upstream) {
        try {
            byte[] bytes = upstream.peekBody(16).bytes();
            if (bytes.length >= 8 && bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p') return "video/mp4";
            if (bytes.length >= 4 && (bytes[0] & 0xFF) == 0x1A && (bytes[1] & 0xFF) == 0x45 && (bytes[2] & 0xFF) == 0xDF && (bytes[3] & 0xFF) == 0xA3) return "video/x-matroska";
        } catch (IOException ignored) {
        }
        return null;
    }

    /**
     * Some sources store MPEG-TS payloads after a valid 1x1 PNG/JPEG header
     * so image hosts will accept them. Native HLS players identify the image
     * first and never reach the video data. A sequence of four 188-byte TS
     * sync markers is sufficiently specific and lets the proxy remove only
     * the camouflage bytes.
     */
    private static int disguisedTsPrefix(Response upstream) {
        try {
            byte[] bytes = upstream.peekBody(PROBE_BYTES).bytes();
            if (!isImage(bytes)) return 0;
            for (int offset = 1; offset + 564 < bytes.length; offset++) {
                if ((bytes[offset] & 0xFF) == 0x47 && (bytes[offset + 188] & 0xFF) == 0x47
                        && (bytes[offset + 376] & 0xFF) == 0x47 && (bytes[offset + 564] & 0xFF) == 0x47) {
                    return offset;
                }
            }
        } catch (IOException ignored) {
        }
        return 0;
    }

    private static boolean mayBeDisguisedSegment(Response upstream) {
        String type = upstream.header("Content-Type", "").toLowerCase(Locale.ROOT);
        return type.startsWith("image/") || type.startsWith("application/octet-stream")
                || type.startsWith("application/oct-stream");
    }

    private static boolean isImage(byte[] bytes) {
        return bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                || bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF;
    }

    private static String shiftRange(String range, int prefix) {
        if (prefix <= 0 || range == null) return range;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)^bytes=(\\d+)-(\\d*)$").matcher(range.trim());
        if (!matcher.matches()) return range;
        long start = Long.parseLong(matcher.group(1)) + prefix;
        String end = matcher.group(2);
        return "bytes=" + start + (end.isEmpty() ? "-" : "-" + (Long.parseLong(end) + prefix));
    }

    private static void copyContentRange(Response upstream, NanoHTTPD.Response response, int prefix) {
        String value = upstream.header("Content-Range");
        if (value == null || value.isEmpty()) return;
        if (prefix <= 0) {
            response.addHeader("Content-Range", value);
            return;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$").matcher(value.trim());
        if (!matcher.matches()) {
            response.addHeader("Content-Range", value);
            return;
        }
        long start = Math.max(0, Long.parseLong(matcher.group(1)) - prefix);
        long end = Math.max(start, Long.parseLong(matcher.group(2)) - prefix);
        String total = "*".equals(matcher.group(3)) ? "*" : String.valueOf(Math.max(0, Long.parseLong(matcher.group(3)) - prefix));
        response.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + total);
    }

    private static void copyRequestedContentRange(Response upstream, NanoHTTPD.Response response, RangeSpec requested) {
        long total = totalLength(upstream);
        if (total <= requested.start) return;
        long end = Math.min(requested.end, total - 1);
        response.addHeader("Content-Range", "bytes " + requested.start + "-" + end + "/" + total);
    }

    private static long totalLength(Response upstream) {
        java.util.regex.Matcher range = java.util.regex.Pattern
                .compile("(?i)^bytes\\s+(\\d+)-(\\d+)/(\\d+)$")
                .matcher(upstream.header("Content-Range", "").trim());
        if (range.matches()) return Long.parseLong(range.group(3));
        ResponseBody body = upstream.body();
        return body == null ? -1 : body.contentLength();
    }

    private static NanoHTTPD.Response.IStatus status(int code) {
        NanoHTTPD.Response.Status known = NanoHTTPD.Response.Status.lookup(code);
        return known != null ? known : new MediaStatus(code);
    }

    private static void copyHeader(Response upstream, NanoHTTPD.Response response, String name) {
        String value = upstream.header(name);
        if (value != null && !value.isEmpty()) response.addHeader(name, value);
    }

    private static void discardExpired() {
        long now = System.currentTimeMillis();
        ENTRIES.entrySet().removeIf(item -> item.getValue().expiresAt() <= now);
    }

    private static final class Entry {
        private final String url;
        private final Map<String, String> header;
        private final Map<String, String> children = new ConcurrentHashMap<>();
        private volatile long expiresAt;
        private volatile int mediaPrefix;

        Entry(String url, Map<String, String> header, long expiresAt) {
            this.url = url;
            this.header = header;
            this.expiresAt = expiresAt;
        }
        String url() { return url; }
        Map<String, String> header() { return header; }
        Map<String, String> children() { return children; }
        long expiresAt() { return expiresAt; }
        int mediaPrefix() { return mediaPrefix; }
        void setMediaPrefix(int value) { mediaPrefix = value; }
        void touch() { expiresAt = System.currentTimeMillis() + TTL; }
        private boolean expired() {
            return expiresAt <= System.currentTimeMillis();
        }
    }

    private record MediaStatus(int code) implements NanoHTTPD.Response.IStatus {
        @Override public String getDescription() { return code + " Media"; }
        @Override public int getRequestStatus() { return code; }
    }

    private static class ResponseInputStream extends FilterInputStream {

        private final Response response;

        ResponseInputStream(InputStream stream, Response response) {
            super(stream);
            this.response = response;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                response.close();
            }
        }
    }

    private static class LimitedInputStream extends FilterInputStream {

        private long remaining;

        LimitedInputStream(InputStream stream, long limit) {
            super(stream);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int value = super.read();
            if (value >= 0) remaining--;
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) return -1;
            int read = super.read(buffer, offset, (int) Math.min(length, remaining));
            if (read > 0) remaining -= read;
            return read;
        }
    }

    private static final class RangeSpec {

        private final long start;
        private final long end;

        private RangeSpec(long start, long end) {
            this.start = start;
            this.end = end;
        }

        private long length() {
            return end - start + 1;
        }

        private static RangeSpec parse(String value) {
            if (value == null) return null;
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)^bytes=(\\d+)-(\\d+)$").matcher(value.trim());
            if (!matcher.matches()) return null;
            long start = Long.parseLong(matcher.group(1));
            long end = Long.parseLong(matcher.group(2));
            return end >= start ? new RangeSpec(start, end) : null;
        }
    }
}
