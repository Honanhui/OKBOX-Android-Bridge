package com.fongmi.android.tv.api.loader;

import com.github.catvod.net.RequestTrace;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import dalvik.system.DexClassLoader;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Response;

/**
 * Adds transport-only tracing to the HTTP helper packaged by the FeiMao
 * Spider JAR. That helper constructs its own OkHttp client, so its requests
 * bypass the host's normal {@link com.github.catvod.net.OkHttp} trace.
 *
 * The hook is optional: other Spider JARs do not contain these classes and
 * continue untouched. It records URL, status and elapsed time only.
 */
final class DexNetworkTrace {

    private static final String CLIENT = "com.github.catvod.spider.merge.n.c";
    private static final String HOLDER = "com.github.catvod.spider.merge.n.b";

    private DexNetworkTrace() {
    }

    static void install(DexClassLoader loader) {
        try {
            Class<?> clientClass = loader.loadClass(CLIENT);
            Class<?> holderClass = loader.loadClass(HOLDER);
            Method factory = clientClass.getMethod("a");
            Object original = factory.invoke(null);
            if (!(original instanceof OkHttpClient)) return;

            Field holderField = holderClass.getDeclaredField("a");
            holderField.setAccessible(true);
            Object holder = holderField.get(null);
            if (holder == null) return;

            Field clientField = clientClass.getDeclaredField("a");
            clientField.setAccessible(true);
            Object current = clientField.get(holder);
            if (!(current instanceof OkHttpClient)) return;

            OkHttpClient traced = ((OkHttpClient) current).newBuilder()
                    .addNetworkInterceptor(new TransportTrace())
                    .build();
            clientField.set(holder, traced);
        } catch (Throwable ignored) {
            // This is a diagnostic enhancement for one known helper. A JAR
            // without it must remain fully compatible with the Bridge.
        }
    }

    private static final class TransportTrace implements Interceptor {

        @Override
        public Response intercept(Chain chain) throws IOException {
            long start = System.currentTimeMillis();
            try {
                Response response = chain.proceed(chain.request());
                RequestTrace.record(chain.request(), response.code(), System.currentTimeMillis() - start, "");
                return response;
            } catch (IOException error) {
                RequestTrace.record(chain.request(), -1, System.currentTimeMillis() - start,
                        error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()));
                throw error;
            }
        }
    }
}
