package com.fongmi.android.tv.server.process;

import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/** Reads the current configuration centre's Spider-owned cloud login menu. */
public final class CloudStatus {

    private static final long READ_TIMEOUT = 3_000L;

    private CloudStatus() {
    }

    public static JSONObject query() throws Exception {
        Site site = configurationCentre();
        if (site.isEmpty()) throw new IllegalArgumentException("请先加载 TVBox 配置");
        if (site.getType() != 3) throw new IllegalArgumentException("当前配置中心不是 Spider 源，无法读取网盘登录状态");

        CloudDialog dialog = new CloudDialog();
        try {
            // SiteApi.action normally runs on TVBox's worker executor. Do not
            // force LoginShow onto the UI thread: several public Spiders create
            // their QR dialog asynchronously and rely on that original contract.
            SiteApi.action(site.getKey(), "LoginShow");
            // The Spider requires an Android window, but only the snapshot is
            // needed here. Keep the Bridge surface invisible during the read.
            dialog.hideWhenReady();

            JSONObject state = waitForOptions(dialog);
            state.put("sourceKey", site.getKey());
            state.put("sourceName", site.getName());
            return state;
        } finally {
            dialog.close();
        }
    }

    private static JSONObject waitForOptions(CloudDialog dialog) throws Exception {
        long deadline = System.currentTimeMillis() + READ_TIMEOUT;
        JSONObject state = dialog.snapshot();
        JSONArray options = state.optJSONArray("options");
        while ((options == null || options.length() == 0) && System.currentTimeMillis() < deadline) {
            Thread.sleep(120L);
            state = dialog.snapshot();
            options = state.optJSONArray("options");
        }
        return state;
    }

    private static Site configurationCentre() {
        List<Site> sites = VodConfig.get().getSites();
        for (Site site : sites) {
            String name = site.getName();
            if (site.getType() == 3 && (name.contains("配置") || name.contains("中心"))) return site;
        }
        Site home = VodConfig.get().getHome();
        if (!home.isEmpty() && home.getType() == 3) return home;
        for (Site site : sites) if (site.getType() == 3) return site;
        return new Site();
    }
}
