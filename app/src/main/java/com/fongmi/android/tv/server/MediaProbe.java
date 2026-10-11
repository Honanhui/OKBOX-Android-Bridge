package com.fongmi.android.tv.server;

import android.net.Uri;

import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.MetadataRetriever;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.extractor.DefaultExtractorsFactory;

import com.fongmi.android.tv.App;
import com.github.catvod.net.OkHttp;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Reads stream tracks with Media3 without creating renderers or playing media. */
public final class MediaProbe {

    private static final long TIMEOUT_MS = 2200;
    private static final long FAST_TIMEOUT_MS = 1200;
    private static final int FAST_TAIL_BYTES = 1024 * 1024;
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;
    private static final ConcurrentHashMap<String, CachedInfo> CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService CLEANUP = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "okbox-media-probe-cleanup");
        thread.setDaemon(true);
        return thread;
    });

    private MediaProbe() {
    }

    public static JSONObject probe(String url, Map<String, String> headers) {
        return probe(url, headers, "");
    }

    public static JSONObject probe(String url, Map<String, String> headers, String episodeHint) {
        String cacheKey = url + '\n' + headers.hashCode();
        CachedInfo cached = CACHE.get(cacheKey);
        if (cached != null && !cached.expired()) return cached.copy();
        CACHE.entrySet().removeIf(entry -> entry.getValue().expired());

        JSONObject result = new JSONObject();
        MetadataRetriever retriever = null;
        ListenableFuture<TrackGroupArray> future = null;
        long startedAt = System.nanoTime();
        try {
            MediaItem item = new MediaItem.Builder().setUri(Uri.parse(url)).build();
            // Keep a timed-out metadata request from occupying the same cloud
            // connection while the real playback request is starting.
            OkHttpClient probeClient = OkHttp.player().newBuilder()
                    .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .connectTimeout(1500, TimeUnit.MILLISECONDS)
                    .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .build();
            HttpDataSource.Factory http = new OkHttpDataSource.Factory(probeClient);
            http.setDefaultRequestProperties(headers);
            DataSource.Factory dataSource = new DefaultDataSource.Factory(App.get(), http);
            DefaultMediaSourceFactory mediaSource = new DefaultMediaSourceFactory(dataSource, new DefaultExtractorsFactory());
            retriever = new MetadataRetriever.Builder(App.get(), item).setMediaSourceFactory(mediaSource).build();
            future = retriever.retrieveTrackGroups();

            // Cloud-drive entries already contain their exact byte size. Most
            // MP4 files keep the audio sample entry near the end, so a single
            // 1 MiB range can identify EAC3/AC3/DTS/TrueHD/Opus much sooner
            // than Media3 can build every seek table. The normal Media3 probe
            // runs in parallel and remains the fallback for all other files.
            JSONObject fast = fastUnsupportedAudio(url, headers, episodeHint);
            if (fast != null) {
                CACHE.put(cacheKey, new CachedInfo(fast));
                return fast;
            }

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            TrackGroupArray groups = future.get(Math.max(1, TIMEOUT_MS - elapsedMs), TimeUnit.MILLISECONDS);

            Format video = null;
            Format audio = null;
            String kind = kindFromUrl(url);
            for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
                androidx.media3.common.TrackGroup group = groups.get(groupIndex);
                for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
                    Format format = group.getFormat(trackIndex);
                    kind = preferKind(kind, kindFromMime(format.containerMimeType));
                    if (video == null && MimeTypes.isVideo(format.sampleMimeType)) video = format;
                    if (audio == null && MimeTypes.isAudio(format.sampleMimeType)) audio = format;
                }
            }

            result.put("kind", kind);
            if (video != null) {
                result.put("videoCodec", codec(video));
                if (video.width > 0) result.put("width", video.width);
                if (video.height > 0) result.put("height", video.height);
                if (video.frameRate > 0) result.put("frameRate", video.frameRate);
                int bitrate = video.averageBitrate > 0 ? video.averageBitrate : video.bitrate;
                if (bitrate > 0) result.put("videoBitrate", bitrate);
            }
            if (audio != null) {
                result.put("audioCodec", codec(audio));
                if (audio.channelCount > 0) result.put("audioChannels", audio.channelCount);
                if (audio.sampleRate > 0) result.put("audioSampleRate", audio.sampleRate);
            }
            result.put("confidence", video != null || audio != null ? "high" : "low");
            if (video != null || audio != null) CACHE.put(cacheKey, new CachedInfo(result));
        } catch (Throwable error) {
            putQuietly(result, "kind", kindFromUrl(url));
            putQuietly(result, "confidence", "low");
        } finally {
            closeAsync(future, retriever);
        }
        return result;
    }

    private static JSONObject fastUnsupportedAudio(String url, Map<String, String> headers, String episodeHint) {
        long size = fileSize(episodeHint);
        if (size <= 0) return null;
        long start = Math.max(0, size - FAST_TAIL_BYTES);
        OkHttpClient client = OkHttp.player().newBuilder()
                .callTimeout(FAST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .connectTimeout(900, TimeUnit.MILLISECONDS)
                .readTimeout(FAST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(FAST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build();
        Request request = new Request.Builder().url(url).headers(Headers.of(headers))
                .header("Range", "bytes=" + start + "-" + (size - 1)).build();
        try (Response response = client.newCall(request).execute()) {
            if (response.code() != 206 || response.header("Content-Range", "").isEmpty()) return null;
            ResponseBody body = response.body();
            if (body == null) return null;
            byte[] bytes = readLimited(body.byteStream(), FAST_TAIL_BYTES);
            String codec = unsupportedAudioCodec(bytes);
            if (codec.isEmpty()) return null;
            JSONObject info = new JSONObject();
            info.put("kind", mp4AudioEntry(bytes) ? "mp4" : kindFromUrl(url));
            info.put("audioCodec", codec);
            info.put("confidence", "high");
            return info;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static long fileSize(String episodeHint) {
        if (episodeHint == null || episodeHint.isBlank()) return -1;
        try {
            JSONObject value = new JSONObject(episodeHint);
            long size = value.optLong("size", -1);
            if (size <= 0) size = value.optLong("file_size", -1);
            return size;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static byte[] readLimited(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 64 * 1024));
        byte[] buffer = new byte[16 * 1024];
        int remaining = limit;
        while (remaining > 0) {
            int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) break;
            output.write(buffer, 0, read);
            remaining -= read;
        }
        return output.toByteArray();
    }

    private static String unsupportedAudioCodec(byte[] bytes) {
        if (contains(bytes, "ec-3") || contains(bytes, "A_EAC3")) return "audio/eac3";
        if (contains(bytes, "ac-3") || contains(bytes, "A_AC3")) return "audio/ac3";
        if (contains(bytes, "dtsc") || contains(bytes, "dtsh") || contains(bytes, "dtsl")
                || contains(bytes, "dtse") || contains(bytes, "A_DTS")) return "audio/vnd.dts";
        if (contains(bytes, "mlpa") || contains(bytes, "A_TRUEHD")) return "audio/true-hd";
        if (contains(bytes, "Opus") || contains(bytes, "A_OPUS")) return "audio/opus";
        return "";
    }

    private static boolean mp4AudioEntry(byte[] bytes) {
        return contains(bytes, "ec-3") || contains(bytes, "ac-3") || contains(bytes, "dtsc")
                || contains(bytes, "dtsh") || contains(bytes, "dtsl") || contains(bytes, "dtse")
                || contains(bytes, "mlpa");
    }

    private static boolean contains(byte[] bytes, String value) {
        byte[] needle = value.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        outer:
        for (int index = 0; index <= bytes.length - needle.length; index++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (bytes[index + offset] != needle[offset]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static void closeAsync(ListenableFuture<TrackGroupArray> future, MetadataRetriever retriever) {
        if (future == null && retriever == null) return;
        CLEANUP.execute(() -> {
            try {
                if (future != null && !future.isDone()) future.cancel(true);
            } catch (Throwable ignored) {
            }
            try {
                if (retriever != null) retriever.close();
            } catch (Throwable ignored) {
            }
        });
    }

    private static String codec(Format format) {
        if (format.codecs != null && !format.codecs.isBlank()) return format.codecs.trim();
        return format.sampleMimeType == null ? "" : format.sampleMimeType.trim();
    }

    private static String kindFromUrl(String url) {
        String path = Uri.parse(url).getPath();
        String value = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (value.endsWith(".m3u8")) return "hls";
        if (value.endsWith(".mp4") || value.endsWith(".m4v") || value.endsWith(".mov")) return "mp4";
        if (value.endsWith(".mkv") || value.endsWith(".webm")) return "mkv";
        return "direct";
    }

    private static String kindFromMime(String mime) {
        if (mime == null) return "direct";
        String value = mime.toLowerCase(Locale.ROOT);
        if (value.contains("mpegurl")) return "hls";
        if (value.contains("matroska") || value.contains("webm")) return "mkv";
        if (value.contains("mp4") || value.contains("quicktime")) return "mp4";
        return "direct";
    }

    private static String preferKind(String current, String candidate) {
        return "direct".equals(current) && !"direct".equals(candidate) ? candidate : current;
    }

    private static void putQuietly(JSONObject result, String name, Object value) {
        try {
            result.put(name, value);
        } catch (Throwable ignored) {
        }
    }

    private static final class CachedInfo {
        private final String json;
        private final long expiresAt;

        CachedInfo(JSONObject info) {
            this.json = info.toString();
            this.expiresAt = System.currentTimeMillis() + CACHE_TTL_MS;
        }

        boolean expired() {
            return expiresAt <= System.currentTimeMillis();
        }

        JSONObject copy() {
            try {
                return new JSONObject(json);
            } catch (Throwable ignored) {
                return new JSONObject();
            }
        }
    }
}
