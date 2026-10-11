package com.github.catvod.net;

import androidx.annotation.NonNull;

import com.github.catvod.bean.Doh;
import com.github.catvod.utils.Util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.dnsoverhttps.DnsOverHttps;

public class OkDns implements Dns {

    private final ConcurrentHashMap<String, String> map;
    private volatile Supplier<Doh> supplier;
    private volatile DnsOverHttps doh;
    private volatile List<DnsOverHttps> fallbacks = Collections.emptyList();

    public OkDns() {
        this.map = new ConcurrentHashMap<>();
    }

    public synchronized void setDoh(Doh item) {
        HttpUrl url = HttpUrl.parse(item.getUrl());
        this.doh = url == null ? null : new DnsOverHttps.Builder().client(new OkHttpClient()).url(url).bootstrapDnsHosts(item.getHosts()).build();
        this.supplier = null;
    }

    public synchronized void setDoh(Supplier<Doh> supplier) {
        this.supplier = supplier;
    }

    /**
     * Keeps the Bridge usable when its isolated Android runtime has a stale or
     * filtered system resolver. The normal system resolver remains first; the
     * bundled TVBox DoH providers are only tried after it returns NXDOMAIN.
     */
    public synchronized void setFallbackDoh(List<Doh> items) {
        List<DnsOverHttps> clients = new ArrayList<>();
        if (items != null) {
            for (Doh item : items) {
                HttpUrl url = HttpUrl.parse(item.getUrl());
                if (url == null) continue;
                clients.add(new DnsOverHttps.Builder()
                        .client(new OkHttpClient())
                        .url(url)
                        .bootstrapDnsHosts(item.getHosts())
                        .build());
            }
        }
        this.fallbacks = clients.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(clients);
    }

    public void clear() {
        map.clear();
    }

    public void addAll(List<String> hosts) {
        map.putAll(hosts.stream().filter(Objects::nonNull).map(host -> host.split("=", 2)).filter(splits -> splits.length == 2).collect(Collectors.toMap(s -> s[0].trim(), s -> s[1].trim(), (oldHost, newHost) -> newHost)));
    }

    private String get(String hostname) {
        String target = map.get(hostname);
        if (target != null) return target;
        for (Map.Entry<String, String> entry : map.entrySet()) if (Util.containOrMatch(hostname, entry.getKey())) return entry.getValue();
        return hostname;
    }

    @NonNull
    @Override
    public List<InetAddress> lookup(@NonNull String hostname) throws UnknownHostException {
        Supplier<Doh> supplier = this.supplier;
        if (supplier != null) initDoh(supplier);
        String target = get(hostname);
        if (doh != null) return doh.lookup(target);
        try {
            return Dns.SYSTEM.lookup(target);
        } catch (UnknownHostException systemError) {
            for (DnsOverHttps fallback : fallbacks) {
                try {
                    return fallback.lookup(target);
                } catch (UnknownHostException ignored) {
                    // Try the next public resolver. The original system error
                    // is retained when all of them reject the hostname.
                }
            }
            throw systemError;
        }
    }

    private synchronized void initDoh(Supplier<Doh> supplier) {
        if (supplier != this.supplier) return;
        setDoh(supplier.get());
    }
}
