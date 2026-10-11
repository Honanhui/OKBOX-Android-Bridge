package com.github.catvod.net.interceptor;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import com.github.catvod.net.RequestTrace;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

public class RequestInterceptor implements Interceptor {

    private final ConcurrentHashMap<String, String> authMap;

    public RequestInterceptor() {
        authMap = new ConcurrentHashMap<>();
    }

    public void clear() {
        authMap.clear();
    }

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request request = chain.request();
        Request.Builder builder = request.newBuilder();
        HttpUrl url = request.url();
        checkAuth(url, builder);
        Request prepared = builder.build();
        long startedAt = System.currentTimeMillis();
        try {
            Response response = chain.proceed(prepared);
            RequestTrace.record(prepared, response.code(), System.currentTimeMillis() - startedAt, "");
            return response;
        } catch (IOException error) {
            RequestTrace.record(prepared, 0, System.currentTimeMillis() - startedAt, error.getMessage());
            throw error;
        }
    }

    private void checkAuth(HttpUrl url, Request.Builder builder) {
        String host = url.host();
        String auth = url.queryParameter("auth");
        if (auth != null) authMap.put(host, auth);
        else if (authMap.containsKey(host)) builder.url(url.newBuilder().addQueryParameter("auth", authMap.get(host)).build());
    }
}
