package com.fongmi.android.tv.server.process;

import java.net.URI;

/** Provider names come from the plugin menu, including providers added by later configs. */
final class CloudProvider {
    static boolean isChoice(String label) {
        if (label.isEmpty() || label.length() > 80 || label.matches("(?is).*(?:清除|清空|退出|注销|删除|取消|确认授权|同意|https?://).*")) return false;
        return label.matches(".*(?:我的|网盘|云盘).*(?:未|已)(?:登录|登入|登陆|授权).*")
                || label.matches(".*(?:网盘|云盘)(?:登录|登入|登陆)?$")
                // Several TVBox sources (including feimao.net/tv) expose
                // their official QR flow through cards named “设置夸克 Cookie”
                // or “设置百度 Token”. These are login actions, not secrets;
                // only clear/remove cards above are rejected.
                || label.matches(".*(?:设置|添加|更新|获取).*(?:Cookie|cookie|Token|token).*");
    }

    static String name(String label) {
        return label.replaceFirst("[-－—：:（(\\s]*(?:未|已)(?:登录|登入|登陆|授权).*$", "")
                .replaceFirst("^(?:我的|设置|添加|更新|获取)", "")
                .replaceAll("(?:云盘|网盘|登录|登入|登陆|Cookie|cookie|Token|token)", "").trim();
    }

    static boolean matches(String label, String provider) {
        String name = name(label);
        return !provider.isEmpty() && !name.isEmpty() && (name.equals(provider) || provider.startsWith(name));
    }

    static boolean loggedIn(String label) {
        return label.matches(".*已(?:登录|登入|登陆|授权).*")
                && !label.matches(".*(?:未(?:登录|登入|登陆|授权)|失效|过期).*");
    }

    /** Keep a QR payload private; return only the app family needed to scan it. */
    static String qrLoginHint(String payload) {
        try {
            String host = URI.create(payload).getHost();
            if (host == null) return "请使用二维码页面指定的 App 扫码并确认登录";
            host = host.toLowerCase();
            if (host.equals("wappass.baidu.com") || host.endsWith(".baidu.com")) {
                return "此二维码属于百度登录，请用已登录的百度 App 扫码；UC App 不能确认此二维码";
            }
            if (host.equals("drive.uc.cn") || host.endsWith(".uc.cn")) {
                return "此二维码属于 UC 登录，请用已登录的 UC 浏览器 App 的“扫一扫”扫码并确认";
            }
        } catch (Exception ignored) {
        }
        return "请使用二维码页面指定的 App 扫码并确认登录";
    }

    static boolean isWeChatPushQr(String payload) {
        String value = payload == null ? "" : payload.toLowerCase();
        return value.startsWith("weixin://") || value.contains("weixin.qq.com") || value.contains("wechat");
    }
}
