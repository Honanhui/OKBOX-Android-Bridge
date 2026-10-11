package com.fongmi.android.tv;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.SpannableString;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.Result;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import com.fongmi.android.tv.server.BridgeConfig;
import com.fongmi.android.tv.server.BridgeServerService;
import com.fongmi.android.tv.server.BridgeSubscriptions;
import com.fongmi.android.tv.server.BridgeWindowGuard;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.server.cloud.CloudAccountStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Minimal control surface for the LAN bridge. The TVBox runtime owns config
 * loading and all Spider execution; Harmony clients only call its HTTP API. */
public class BridgeActivity extends AppCompatActivity {

    private static final String OPLIST_GLOBAL = "https://api.oplist.org";
    private static final String OPLIST_FINGERPRINT = "okbox-bridge-android";
    private static final String QUARK_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String QR_CANVAS_SCRIPT = "(() => { const primary = Array.from(document.querySelectorAll('.qrcode-display canvas, .qrcode-container canvas')); const all = primary.length ? primary : Array.from(document.querySelectorAll('canvas')); return all.filter(c => c.width >= 80 && c.height >= 80 && Math.abs(c.width - c.height) < 8).slice(0, 8).map(c => c.toDataURL('image/png')); })()";

    private TextView status;
    private BridgeWindowGuard windowGuard;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BridgeConfig.StatusListener bridgeStatus = this::onBridgeStatus;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        windowGuard = new BridgeWindowGuard(this);
        Server.get().start();
        BridgeServerService.start(this);
        BridgeConfig.addStatusListener(bridgeStatus);
        setContentView(content());
        restoreActiveSubscription();
    }

    private LinearLayout content() {
        int padding = dip(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("TVBOX 服务器");
        title.setTextSize(24);
        root.addView(title, match());

        TextView hint = new TextView(this);
        hint.setText("Android Spider 执行端 · 订阅与网盘请在网页管理中心操作");
        root.addView(hint, match());

        TextView guide = new TextView(this);
        guide.setText("\n操作提示\n"
                + "• 订阅管理、网盘登录：请在同一 Wi-Fi 的浏览器打开\n"
                + Server.get().getAddress() + "\n"
                + "• OKBOX 固定订阅入口：\n"
                + Server.get().getAddress() + "/sub/okbox.json\n"
                + "• 本页仅显示 Bridge 状态，也可临时加载一个配置地址。\n");
        guide.setTextIsSelectable(true);
        guide.setPadding(0, dip(8), 0, dip(8));
        root.addView(guide, match());

        TextView stateTitle = new TextView(this);
        stateTitle.setText("运行状态");
        stateTitle.setTextSize(18);
        stateTitle.setPadding(0, dip(12), 0, 0);
        root.addView(stateTitle, match());

        status = new TextView(this);
        status.setGravity(Gravity.START);
        status.setPadding(0, dip(12), 0, 0);
        root.addView(status, match());
        return root;
    }

    private void restoreActiveSubscription() {
        try {
            if (!BridgeSubscriptions.loadActive(null)) {
                showAddress("服务器已启动。\n当前没有订阅源，请在网页管理中心添加订阅。");
            }
        } catch (Exception error) {
            showAddress("服务器已启动，但无法恢复当前订阅源：" + readable(error));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (windowGuard != null) windowGuard.start();
    }

    @Override
    protected void onPause() {
        if (windowGuard != null) windowGuard.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (windowGuard != null) windowGuard.stop();
        BridgeConfig.removeStatusListener(bridgeStatus);
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (windowGuard != null) windowGuard.suppressNow();
        if (!hasFocus) return;
        // Third-party Spider dialogs run in this Activity's process. If one
        // previously hid its host root, restore Bridge once that dialog closes.
        View decor = getWindow().getDecorView();
        decor.setAlpha(1f);
        decor.setVisibility(View.VISIBLE);
        View content = decor.findViewById(android.R.id.content);
        if (content != null) {
            content.setAlpha(1f);
            content.setVisibility(View.VISIBLE);
        }
    }

    private void onBridgeStatus(String state, String message, String activeUrl, int sourceCount) {
        runOnUiThread(() -> {
            if ("loading".equals(state)) {
                showAddress(message + "…");
            } else if ("ready".equals(state)) {
                showAddress("当前订阅源已就绪。\n已加载 " + sourceCount + " 个影视源。\nAndroid Spider 将在本机执行。");
            } else if ("error".equals(state)) {
                showAddress("订阅源加载失败：" + message + "\n请在网页管理中心查看该源的抓取状态。");
            }
        });
    }

    private void showAddress(String text) {
        runOnUiThread(() -> {
            if (status != null) status.setText(text);
        });
    }

    /** Bridge owns account state; this UI never opens a third-party Spider dialog. */
    private void showCloudAccounts() {
        try {
            JSONObject catalog = CloudAccountStore.catalog();
            JSONArray accounts = catalog.getJSONArray("accounts");
            int padding = dip(16);
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            list.setPadding(padding, 0, padding, 0);

            TextView hint = new TextView(this);
            hint.setText("账号、Cookie 和令牌只保存在本机 Bridge 的加密保险库中。网盘适配器会使用这些登录信息解析分享和获取播放地址。");
            hint.setTextSize(15);
            hint.setPadding(0, 0, 0, padding);
            list.addView(hint, match());

            final AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("网盘账号服务")
                    .setNegativeButton("关闭", null)
                    .create();
            if (accounts.length() == 0) {
                TextView empty = new TextView(this);
                empty.setText("暂无网盘账号");
                empty.setPadding(0, padding, 0, padding);
                list.addView(empty, match());
            }
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject account = accounts.getJSONObject(i);
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextView title = new TextView(this);
                title.setText(account.optString("name") + " · " + account.optString("provider"));
                title.setTextSize(16);
                title.setPadding(0, padding, padding, padding);
                row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                Button delete = new Button(this);
                delete.setText("删除");
                delete.setOnClickListener(v -> {
                    try {
                        CloudAccountStore.remove(account.optString("id"));
                        dialog.dismiss();
                        showCloudAccounts();
                    } catch (Exception error) {
                        showAddress("无法删除网盘账号：" + readable(error));
                    }
                });
                row.addView(delete, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                list.addView(row, match());
            }
            final JSONArray providers = catalog.getJSONArray("providers");
            Button add = new Button(this);
            add.setText("添加网盘账号");
            add.setOnClickListener(v -> {
                dialog.dismiss();
                selectCloudProvider(providers);
            });
            list.addView(add, match());
            ScrollView scroll = new ScrollView(this);
            scroll.addView(list, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            dialog.setView(scroll);
            dialog.show();
        } catch (Exception error) {
            showAddress("无法读取网盘账号服务：" + readable(error));
        }
    }

    private void selectCloudProvider(JSONArray providers) {
        try {
            String[] labels = new String[providers.length()];
            for (int i = 0; i < providers.length(); i++) labels[i] = providers.getJSONObject(i).optString("name");
            new AlertDialog.Builder(this).setTitle("选择网盘类型")
                    .setItems(labels, (dialog, which) -> {
                        try {
                            JSONObject provider = providers.getJSONObject(which);
                            // QR authorization is supplied by the active TVBox Spider and
                            // relayed through /api/cloud to OKbox.  Do not start a second,
                            // Bridge-local authorization flow from this management screen.
                            editCloudAccount(provider);
                        }
                        catch (Exception error) { showAddress("无法打开账号表单：" + readable(error)); }
                    }).setNegativeButton("关闭", null).show();
        } catch (Exception error) {
            showAddress("无法读取网盘类型：" + readable(error));
        }
    }

    private void editCloudAccount(JSONObject provider) throws Exception {
        int padding = dip(16);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(padding, 0, padding, 0);
        TextView hint = new TextView(this);
        hint.setText("登录信息将加密保存在本机 Bridge，局域网接口不会返回账号、Cookie 或令牌。\n认证方式：" + provider.optString("mode"));
        hint.setTextSize(15);
        hint.setPadding(0, 0, 0, padding);
        list.addView(hint, match());
        EditText name = new EditText(this);
        name.setHint("账号名称（可选）");
        list.addView(name, match());
        JSONArray fields = provider.getJSONArray("fields");
        EditText[] inputs = new EditText[fields.length()];
        for (int i = 0; i < fields.length(); i++) {
            JSONObject field = fields.getJSONObject(i);
            EditText input = new EditText(this);
            input.setHint(field.optString("label"));
            if (field.optBoolean("secret")) input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            list.addView(input, match());
            inputs[i] = input;
        }
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("添加 " + provider.optString("name"))
                .setPositiveButton("加密保存", null)
                .setNegativeButton("关闭", null)
                .create();
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setView(scroll);
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                JSONObject values = new JSONObject();
                for (int i = 0; i < fields.length(); i++) values.put(fields.getJSONObject(i).optString("id"), inputs[i].getText().toString().trim());
                CloudAccountStore.save("", provider.optString("id"), name.getText().toString(), values);
                dialog.dismiss();
                showCloudAccounts();
            } catch (Exception error) {
                showAddress("无法保存网盘账号：" + readable(error));
            }
        }));
        dialog.show();
    }

    /** The documented OpenList authorization flow has no manual token field. */
    private void showOpenListAccount(JSONObject provider) {
        int padding = dip(18);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(padding, padding, padding, padding);

        TextView heading = new TextView(this);
        heading.setText("登录授权");
        heading.setTextSize(20);
        heading.setPadding(0, 0, 0, dip(12));
        page.addView(heading, match());

        Button scan = new Button(this);
        scan.setAllCaps(false);
        SpannableString scanText = new SpannableString("扫码登录\n官方二维码");
        scanText.setSpan(new StyleSpan(Typeface.BOLD), 0, 4, 0);
        scanText.setSpan(new RelativeSizeSpan(0.62f), 5, scanText.length(), 0);
        scan.setText(scanText);
        scan.setTextSize(21);
        scan.setMinHeight(dip(96));
        scan.setGravity(Gravity.CENTER);
        page.addView(scan, match());

        TextView state = new TextView(this);
        state.setText("● 连接状态：未验证");
        state.setTextSize(16);
        state.setTextColor(Color.GRAY);
        state.setPadding(0, dip(16), 0, 0);
        page.addView(state, match());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("添加 " + provider.optString("name"))
                .setView(page)
                .setPositiveButton("完成", null)
                .setNegativeButton("返回", null)
                .create();
        final boolean[] authorized = {false};
        Runnable failed = () -> {
            if (!dialog.isShowing()) return;
            scan.setEnabled(true);
            if (!authorized[0]) {
                state.setText("● 连接状态：未验证");
                state.setTextColor(Color.GRAY);
            }
        };
        Runnable succeeded = () -> {
            if (!dialog.isShowing()) return;
            authorized[0] = true;
            scan.setEnabled(true);
            state.setText("● 连接状态：连接成功，待点击完成保存");
            state.setTextColor(Color.rgb(38, 180, 103));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
        };
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (!authorized[0]) return;
                dialog.dismiss();
                showCloudAccounts();
            });
            scan.setOnClickListener(v -> {
                scan.setEnabled(false);
                state.setText("● 连接状态：正在获取官方二维码…");
                state.setTextColor(Color.DKGRAY);
                showOpenListLogin(provider, succeeded, failed);
            });
        });
        dialog.show();
    }

    /**
     * Cookie-based providers render their own QR code inside a foreground
     * WebView. This avoids calling Spider-owned login dialogs from a LAN
     * request, which is the path that previously crashed the Bridge process.
     */
    private void showWebCookieLogin(JSONObject provider) {
        String providerId = provider.optString("id");
        if ("quark".equals(providerId)) {
            showQuarkQrLogin(provider);
            return;
        }
        if ("baidu".equals(providerId)) {
            showBaiduQrLogin(provider);
            return;
        }
        String loginUrl = webLoginUrl(providerId);
        if (loginUrl == null) {
            showAddress("该网盘暂不支持网页扫码，请使用 Cookie 导入。");
            return;
        }
        final boolean[] closed = {false};
        WebView web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setUseWideViewPort(true);
        web.getSettings().setLoadWithOverviewMode(true);
        // The providers return an app-download landing page to Android WebView
        // user agents. Request their desktop login page so the QR is rendered.
        web.getSettings().setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        FrameLayout page = new FrameLayout(this);
        page.addView(web, new FrameLayout.LayoutParams(dip(420), dip(560)));
        LinearLayout qrPage = new LinearLayout(this);
        qrPage.setOrientation(LinearLayout.VERTICAL);
        qrPage.setGravity(Gravity.CENTER_HORIZONTAL);
        qrPage.setPadding(dip(12), dip(8), dip(12), dip(8));
        qrPage.setBackgroundColor(Color.WHITE);
        TextView qrHint = new TextView(this);
        qrHint.setText("正在生成登录二维码…");
        qrHint.setTextSize(16);
        qrHint.setTextColor(Color.DKGRAY);
        qrHint.setGravity(Gravity.CENTER);
        qrPage.addView(qrHint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ImageView qrImage = new ImageView(this);
        qrImage.setAdjustViewBounds(false);
        qrImage.setScaleType(ImageView.ScaleType.CENTER);
        qrImage.setBackgroundColor(Color.WHITE);
        qrPage.addView(qrImage, new LinearLayout.LayoutParams(dip(360), dip(360)));
        page.addView(qrPage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(provider.optString("name") + "扫码登录")
                .setView(page)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存登录", null)
                .create();
        Runnable[] readQr = new Runnable[1];
        readQr[0] = new Runnable() {
            private int attempts;

            @Override public void run() {
                if (closed[0] || !dialog.isShowing()) return;
                web.evaluateJavascript(QR_CANVAS_SCRIPT, value -> {
                    Bitmap bitmap = extractQrCanvas(value);
                    if (closed[0] || !dialog.isShowing()) return;
                    if (bitmap != null) {
                        qrImage.setImageBitmap(bitmap);
                        qrHint.setText("请使用" + provider.optString("name") + " App 扫码并确认");
                        return;
                    }
                    if (++attempts >= 30) {
                        qrHint.setText("二维码暂未生成，请点“重新生成”后重试。");
                        return;
                    }
                    handler.postDelayed(this, 500);
                });
            }
        };
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                handler.removeCallbacks(readQr[0]);
                handler.postDelayed(readQr[0], 450);
            }
        });
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String activeUrl = web.getUrl();
            String cookie = cookies.getCookie(loginUrl);
            if ((cookie == null || cookie.trim().isEmpty()) && activeUrl != null) cookie = cookies.getCookie(activeUrl);
            if (cookie == null || !cookie.contains("=")) {
                showAddress("尚未读取到登录 Cookie。请先完成扫码并等待页面登录成功。");
                return;
            }
            try {
                CloudAccountStore.save("", providerId, provider.optString("name"), new JSONObject().put("cookie", cookie));
                dialog.dismiss();
                showCloudAccounts();
            } catch (Exception error) {
                showAddress("无法保存扫码登录：" + readable(error));
            }
        }));
        dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "重新生成", (ignored, which) -> {
            qrImage.setImageDrawable(null);
            qrHint.setText("正在重新生成登录二维码…");
            web.reload();
        });
        dialog.setOnDismissListener(ignored -> {
            closed[0] = true;
            handler.removeCallbacks(readQr[0]);
            web.destroy();
        });
        dialog.show();
        web.loadUrl(loginUrl);
    }

    /**
     * OpenList's mainland endpoint creates the provider-specific authorization
     * URL. The callback returns a token in its URL fragment, which remains
     * inside this WebView and is saved only in Bridge's encrypted vault.
     */
    private void showOpenListLogin(JSONObject provider, Runnable onSuccess, Runnable onFailure) {
        if ("aliyun".equals(provider.optString("id"))) {
            showAliCloudQrLogin(provider, onSuccess, onFailure);
            return;
        }
        OpenListProvider settings = OpenListProvider.from(provider.optString("id"));
        if (settings == null) {
            showAddress("该网盘暂不支持 OpenList 中国大陆扫码登录。");
            onFailure.run();
            return;
        }
        TextView loading = new TextView(this);
        loading.setText("正在生成 " + provider.optString("name") + " 官方登录二维码…");
        loading.setPadding(dip(20), dip(28), dip(20), dip(28));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(provider.optString("name") + "扫码登录")
                .setView(loading).setNegativeButton("取消", null).create();
        dialog.show();
        new Thread(() -> {
            try {
                String url = requestOpenListUrl(settings);
                runOnUiThread(() -> {
                    if (!dialog.isShowing()) {
                        onFailure.run();
                        return;
                    }
                    showOpenListPage(provider, url, dialog, onSuccess, onFailure);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (dialog.isShowing()) dialog.dismiss();
                    showAddress("无法获取登录链接：" + readable(error));
                    onFailure.run();
                });
            }
        }, "OpenListLoginRequest").start();
    }

    /**
     * The OpenList project exposes a native Ali QR session API. Unlike the
     * generic /requests endpoint (which returns an image URL), it supplies a
     * scan payload, an expiry and a session that can be polled for tokens.
     */
    private void showAliCloudQrLogin(JSONObject provider, Runnable onSuccess, Runnable onFailure) {
        final boolean[] closed = {false};
        final boolean[] completed = {false};
        final int[] generation = {0};
        int maximum = Math.min(dip(420), getResources().getDisplayMetrics().widthPixels - dip(40));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dip(16), dip(12), dip(16), dip(12));
        page.setBackgroundColor(Color.WHITE);

        TextView appName = new TextView(this);
        appName.setText("阿里云盘 App");
        appName.setTextSize(21);
        appName.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        appName.setTextColor(Color.rgb(35, 43, 55));
        appName.setGravity(Gravity.CENTER);
        page.addView(appName, match());
        TextView instruction = new TextView(this);
        instruction.setText("请使用阿里云盘 App 扫码并确认登录");
        instruction.setTextSize(15);
        instruction.setTextColor(Color.DKGRAY);
        instruction.setGravity(Gravity.CENTER);
        instruction.setPadding(0, dip(4), 0, dip(6));
        page.addView(instruction, match());
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER);
        image.setBackgroundColor(Color.WHITE);
        page.addView(image, new LinearLayout.LayoutParams(maximum, maximum));
        TextView expiry = new TextView(this);
        expiry.setTextSize(15);
        expiry.setTextColor(Color.GRAY);
        expiry.setGravity(Gravity.CENTER);
        expiry.setPadding(0, dip(8), 0, 0);
        page.addView(expiry, match());
        TextView state = new TextView(this);
        state.setTextSize(15);
        state.setTextColor(Color.DKGRAY);
        state.setGravity(Gravity.CENTER);
        state.setPadding(0, dip(6), 0, 0);
        page.addView(state, match());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("扫码登录 阿里云盘")
                .setView(page)
                .setNegativeButton("取消", null)
                .setNeutralButton("刷新二维码", null)
                .create();
        Runnable refresh = new Runnable() {
            @Override public void run() {
                final int current = ++generation[0];
                image.setImageDrawable(null);
                expiry.setText("正在生成二维码…");
                state.setText("请稍候");
                new Thread(() -> {
                    try {
                        AliQrSession session = createAliQrSession();
                        Bitmap bitmap = renderQr(session.payload, maximum);
                        handler.post(() -> {
                            if (closed[0] || !dialog.isShowing() || current != generation[0]) return;
                            image.setImageBitmap(bitmap);
                            state.setText("请使用阿里云盘 App 扫码并确认登录");
                            updateAliExpiry(expiry, session, closed, generation, current);
                            pollAliLogin(provider, dialog, session, closed, completed, generation, current, state, onSuccess, onFailure);
                        });
                    } catch (Exception error) {
                        handler.post(() -> {
                            if (closed[0] || !dialog.isShowing() || current != generation[0]) return;
                            expiry.setText("二维码未生成");
                            state.setText("获取二维码失败：" + readable(error));
                        });
                    }
                }, "AliCloudQrLogin").start();
            }
        };
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> refresh.run());
            refresh.run();
        });
        dialog.setOnDismissListener(ignored -> {
            closed[0] = true;
            generation[0]++;
            if (!completed[0]) onFailure.run();
        });
        dialog.show();
    }

    private void updateAliExpiry(TextView expiry, AliQrSession session, boolean[] closed, int[] generation, int current) {
        long seconds = Math.max(0, (session.expiresAt - SystemClock.elapsedRealtime() + 999L) / 1000L);
        expiry.setText(seconds > 0 ? "扫码剩余时间：" + seconds + " 秒" : "二维码已过期，请刷新");
        if (seconds > 0) handler.postDelayed(() -> {
            if (!closed[0] && current == generation[0]) updateAliExpiry(expiry, session, closed, generation, current);
        }, 1000L);
    }

    private void pollAliLogin(JSONObject provider, AlertDialog dialog, AliQrSession session, boolean[] closed, boolean[] completed,
                              int[] generation, int current, TextView state, Runnable onSuccess, Runnable onFailure) {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (closed[0] || !dialog.isShowing() || current != generation[0]) return;
                new Thread(() -> {
                    try {
                        JSONObject result = openListJson("/alicloud2/check_login?session_id=" + encode(session.sessionId));
                        String loginState = result.optString("status");
                        String message = result.optString("message");
                        if ("CONFIRMED".equals(loginState)) {
                            handler.post(() -> {
                                if (!closed[0] && dialog.isShowing() && current == generation[0]) state.setText("已确认登录，正在获取凭证…");
                            });
                            JSONObject info = openListJson("/alicloud2/get_user_info?session_id=" + encode(session.sessionId));
                            JSONObject tokens = new JSONObject();
                            tokens.put("access_token", info.optString("access_token", result.optString("access_token")));
                            tokens.put("refresh_token", info.optString("refresh_token"));
                            CloudAccountStore.saveOAuth(provider.optString("id"), provider.optString("name"), tokens);
                            handler.post(() -> {
                                if (closed[0] || !dialog.isShowing() || current != generation[0]) return;
                                completed[0] = true;
                                dialog.dismiss();
                                onSuccess.run();
                            });
                            return;
                        }
                        if ("EXPIRED".equals(loginState)) {
                            handler.post(() -> {
                                if (!closed[0] && dialog.isShowing() && current == generation[0]) state.setText("二维码已过期，请点“刷新二维码”。");
                            });
                            return;
                        }
                        handler.post(() -> {
                            if (!closed[0] && dialog.isShowing() && current == generation[0]) state.setText(message.isEmpty() ? "等待扫码" : message);
                        });
                    } catch (Exception error) {
                        handler.post(() -> {
                            if (!closed[0] && dialog.isShowing() && current == generation[0]) state.setText("检查登录状态失败：" + readable(error));
                        });
                        return;
                    }
                    handler.postDelayed(this, 1800L);
                }, "AliCloudQrPoll").start();
            }
        }, 1200L);
    }

    private AliQrSession createAliQrSession() throws Exception {
        JSONObject result = openListJson("/alicloud2/generate_qr");
        String payload = result.optString("qr_code_url");
        String sessionId = result.optString("session_id");
        long expires = result.optLong("expires_in", 0L);
        if (!result.optBoolean("success") || payload.isEmpty() || sessionId.isEmpty() || expires <= 0L) {
            throw new IllegalStateException(result.optString("error", "授权服务没有返回有效二维码"));
        }
        return new AliQrSession(sessionId, payload, SystemClock.elapsedRealtime() + expires * 1000L);
    }

    private JSONObject openListJson(String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(OPLIST_GLOBAL + path).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(25_000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("X-Client-Fingerprint", OPLIST_FINGERPRINT);
        int code = connection.getResponseCode();
        String body = read(connection, code);
        JSONObject result = new JSONObject(body.isEmpty() ? "{}" : body);
        if (code < 200 || code >= 300) throw new IllegalStateException(result.optString("error", "授权服务返回 HTTP " + code));
        return result;
    }

    private static final class AliQrSession {
        final String sessionId;
        final String payload;
        final long expiresAt;

        AliQrSession(String sessionId, String payload, long expiresAt) {
            this.sessionId = sessionId;
            this.payload = payload;
            this.expiresAt = expiresAt;
        }
    }

    private void showOpenListPage(JSONObject provider, String loginUrl, AlertDialog loading, Runnable onSuccess, Runnable onFailure) {
        loading.dismiss();
        WebView web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setUseWideViewPort(true);
        web.getSettings().setLoadWithOverviewMode(true);
        web.getSettings().setUserAgentString(QUARK_USER_AGENT);
        FrameLayout page = new FrameLayout(this);
        page.addView(web, new FrameLayout.LayoutParams(dip(440), dip(600)));
        TextView status = new TextView(this);
        status.setText("请使用" + provider.optString("name") + " App 扫码并确认登录。\n二维码失效后可点“刷新二维码”重新获取。授权完成后正在获取凭证。");
        status.setTextColor(Color.DKGRAY);
        status.setPadding(dip(12), dip(8), dip(12), dip(8));
        page.addView(status, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        final boolean[] done = {false};
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("扫码登录 " + provider.optString("name"))
                .setView(page).setNegativeButton("取消", null).create();
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                JSONObject tokens = tokensFromOpenListCallback(url);
                if (tokens == null || done[0]) return;
                done[0] = true;
                try {
                    CloudAccountStore.saveOAuth(provider.optString("id"), provider.optString("name"), tokens);
                    dialog.dismiss();
                    onSuccess.run();
                } catch (Exception error) {
                    done[0] = false;
                    status.setText("授权已完成，但保存失败：" + readable(error));
                }
            }

            @Override public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (!done[0]) status.setText("授权页面加载失败：" + description + "。可重新打开扫码登录。");
            }
        });
        dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "刷新二维码", (ignored, which) -> {
            done[0] = true;
            dialog.dismiss();
            showOpenListLogin(provider, onSuccess, onFailure);
        });
        dialog.setOnDismissListener(ignored -> {
            web.destroy();
            if (!done[0]) onFailure.run();
        });
        dialog.show();
        web.loadUrl(loginUrl);
    }

    private String requestOpenListUrl(OpenListProvider provider) throws Exception {
        return requestOpenListUrl(OPLIST_GLOBAL, provider);
    }

    private String requestOpenListUrl(String endpoint, OpenListProvider provider) throws Exception {
        String query = "driver=" + encode(provider.driver) + "&server_use=true&server_set=true&driver_txt=" + encode(provider.driverType);
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint + "/" + provider.driver + "/requests?" + query).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(20_000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json, text/plain, */*");
        int code = connection.getResponseCode();
        String response = read(connection, code);
        if (code < 200 || code >= 300) throw new IllegalStateException("授权服务返回 HTTP " + code);
        String url = "";
        try {
            JSONObject json = new JSONObject(response);
            url = json.optString("text", json.optString("url", json.optString("data")));
            if (url.isEmpty() && json.optJSONObject("data") != null) {
                JSONObject data = json.optJSONObject("data");
                url = data.optString("text", data.optString("url"));
            }
        } catch (Exception ignored) {
            int start = response.indexOf("http");
            if (start >= 0) url = response.substring(start).trim().replaceAll("[\\\"'}]+$", "");
        }
        if (!url.startsWith("https://")) throw new IllegalStateException("授权服务没有返回安全的登录链接");
        return url;
    }

    private JSONObject tokensFromOpenListCallback(String value) {
        try {
            Uri uri = Uri.parse(value);
            String fragment = uri.getFragment();
            if (fragment == null || fragment.isEmpty()) return null;
            byte[] raw = Base64.decode(fragment, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            JSONObject tokens = new JSONObject(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
            return tokens.has("refresh_token") || tokens.has("refreshToken") || tokens.has("access_token") || tokens.has("accessToken") ? tokens : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static final class OpenListProvider {
        final String driver;
        final String driverType;

        OpenListProvider(String driver, String driverType) {
            this.driver = driver;
            this.driverType = driverType;
        }

        static OpenListProvider from(String provider) {
            return switch (provider) {
                case "aliyun" -> new OpenListProvider("alicloud", "alicloud_qr");
                case "baidu" -> new OpenListProvider("baiduyun", "baiduyun_go");
                default -> null;
            };
        }
    }

    /**
     * Quark's browser canvas is a login QR but Android WebView can alter the
     * page state. Create and poll the same native login session directly, so
     * the QR, confirmation and Cookie all belong to one Bridge-owned flow.
     */
    private void showQuarkQrLogin(JSONObject provider) {
        final boolean[] closed = {false};
        final int[] generation = {0};
        int maximum = Math.min(dip(420), getResources().getDisplayMetrics().widthPixels - dip(40));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dip(12), dip(8), dip(12), dip(8));
        page.setBackgroundColor(Color.WHITE);
        TextView hint = new TextView(this);
        hint.setText("正在向夸克获取登录二维码…");
        hint.setTextSize(16);
        hint.setTextColor(Color.DKGRAY);
        hint.setGravity(Gravity.CENTER);
        page.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER);
        image.setBackgroundColor(Color.WHITE);
        page.addView(image, new LinearLayout.LayoutParams(maximum, maximum));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("夸克网盘扫码登录")
                .setView(page)
                .setNegativeButton("取消", null)
                .create();
        Runnable refresh = new Runnable() {
            @Override public void run() {
                final int current = ++generation[0];
                image.setImageDrawable(null);
                hint.setText("正在向夸克获取登录二维码…");
                new Thread(() -> {
                    try {
                        QuarkQrSession session = createQuarkQrSession();
                        Bitmap bitmap = renderQr(session.url, maximum);
                        handler.post(() -> {
                            if (closed[0] || !dialog.isShowing() || current != generation[0]) return;
                            image.setImageBitmap(bitmap);
                            hint.setText("请使用夸克网盘 App 扫码并确认；登录成功后会自动保存");
                            pollQuarkLogin(provider, dialog, session, closed, generation, current, hint);
                        });
                    } catch (Exception error) {
                        handler.post(() -> {
                            if (!closed[0] && dialog.isShowing() && current == generation[0]) hint.setText("获取二维码失败：" + readable(error));
                        });
                    }
                }, "QuarkQrLogin").start();
            }
        };
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> refresh.run());
            refresh.run();
        });
        dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "重新生成", (ignored, which) -> { });
        dialog.setOnDismissListener(ignored -> {
            closed[0] = true;
            generation[0]++;
        });
        dialog.show();
    }

    private void pollQuarkLogin(JSONObject provider, AlertDialog dialog, QuarkQrSession session, boolean[] closed, int[] generation, int current, TextView hint) {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (closed[0] || !dialog.isShowing() || current != generation[0]) return;
                new Thread(() -> {
                    try {
                        JSONObject result = quarkJson("https://uop.quark.cn/cas/ajax/getServiceTicketByQrcodeToken?client_id=532&v=1.2&token=" + encode(session.token) + "&request_id=" + UUID.randomUUID());
                        String ticket = result.optJSONObject("data") == null ? "" : result.optJSONObject("data").optJSONObject("members") == null ? "" : result.optJSONObject("data").optJSONObject("members").optString("service_ticket");
                        if (!ticket.isEmpty()) {
                            String cookie = quarkCookie(ticket);
                            CloudAccountStore.save("", provider.optString("id"), provider.optString("name"), new JSONObject().put("cookie", cookie));
                            handler.post(() -> {
                                if (closed[0] || !dialog.isShowing() || current != generation[0]) return;
                                dialog.dismiss();
                                showAddress("夸克网盘登录已保存。");
                                showCloudAccounts();
                            });
                            return;
                        }
                        int status = result.optInt("status");
                        if (status == 50004002 || status == 50004003 || status == 50004004) {
                            handler.post(() -> {
                                if (!closed[0] && dialog.isShowing() && current == generation[0]) hint.setText("二维码已失效或登录已取消，请点“重新生成”。");
                            });
                            return;
                        }
                    } catch (Exception error) {
                        handler.post(() -> {
                            if (!closed[0] && dialog.isShowing() && current == generation[0]) hint.setText("检查登录状态失败，请点“重新生成”。");
                        });
                        return;
                    }
                    handler.postDelayed(this, 1800);
                }, "QuarkQrPoll").start();
            }
        }, 1200);
    }

    private QuarkQrSession createQuarkQrSession() throws Exception {
        JSONObject result = quarkJson("https://uop.quark.cn/cas/ajax/getTokenForQrcodeLogin?client_id=532&v=1.2&request_id=" + UUID.randomUUID());
        JSONObject data = result.optJSONObject("data");
        JSONObject members = data == null ? null : data.optJSONObject("members");
        String token = members == null ? "" : members.optString("token");
        if (result.optInt("status") != 2000000 || token.isEmpty()) throw new IllegalStateException("夸克没有返回有效登录二维码");
        String url = "https://su.quark.cn/4_eMHBJ?token=" + encode(token) + "&client_id=532&ssb=weblogin&uc_param_str=&uc_biz_str=" + encode("S:custom|OPT:SAREA@0|OPT:IMMERSIVE@1|OPT:BACK_BTN_STYLE@0");
        return new QuarkQrSession(token, url);
    }

    private JSONObject quarkJson(String url) throws Exception {
        HttpURLConnection connection = quarkConnection(url);
        int code = connection.getResponseCode();
        String body = read(connection, code);
        if (code < 200 || code >= 300) throw new IllegalStateException("夸克接口返回 HTTP " + code);
        return new JSONObject(body);
    }

    private String quarkCookie(String ticket) throws Exception {
        HttpURLConnection connection = quarkConnection("https://pan.quark.cn/account/info?st=" + encode(ticket) + "&lw=scan");
        int code = connection.getResponseCode();
        read(connection, code);
        if (code < 200 || code >= 400) throw new IllegalStateException("夸克登录确认失败（HTTP " + code + "）");
        ArrayList<String> values = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
            if (entry.getKey() == null || !"set-cookie".equalsIgnoreCase(entry.getKey())) continue;
            for (String value : entry.getValue()) {
                int separator = value.indexOf(';');
                values.add(separator < 0 ? value : value.substring(0, separator));
            }
        }
        if (values.isEmpty()) throw new IllegalStateException("夸克没有返回登录 Cookie");
        return String.join("; ", values);
    }

    private HttpURLConnection quarkConnection(String value) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(value).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(15_000);
        connection.setRequestProperty("User-Agent", QUARK_USER_AGENT);
        connection.setRequestProperty("Accept", "application/json, text/plain, */*");
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        connection.setRequestProperty("Origin", "https://pan.quark.cn");
        connection.setRequestProperty("Referer", "https://pan.quark.cn/");
        return connection;
    }

    private String read(HttpURLConnection connection, int code) throws Exception {
        InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) text.append(line);
            return text.toString();
        }
    }

    private String encode(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }

    private static final class QuarkQrSession {
        final String token;
        final String url;

        QuarkQrSession(String token, String url) {
            this.token = token;
            this.url = url;
        }
    }

    private void showBaiduQrLogin(JSONObject provider) {
        final boolean[] closed = {false};
        final int[] generation = {0};
        int maximum = Math.min(dip(420), getResources().getDisplayMetrics().widthPixels - dip(40));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dip(12), dip(8), dip(12), dip(8));
        page.setBackgroundColor(Color.WHITE);
        TextView hint = new TextView(this);
        hint.setText("正在向百度网盘获取登录二维码…");
        hint.setTextSize(16);
        hint.setTextColor(Color.DKGRAY);
        hint.setGravity(Gravity.CENTER);
        page.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER);
        image.setBackgroundColor(Color.WHITE);
        page.addView(image, new LinearLayout.LayoutParams(maximum, maximum));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("百度网盘扫码登录")
                .setView(page)
                .setNegativeButton("取消", null)
                .create();
        Runnable refresh = new Runnable() {
            @Override public void run() {
                final int current = ++generation[0];
                image.setImageDrawable(null);
                hint.setText("正在向百度网盘获取登录二维码…");
                new Thread(() -> {
                    try {
                        BaiduQrSession session = createBaiduQrSession();
                        Bitmap source = fetchBitmap(session.imageUrl, session.cookies);
                        if (source == null) throw new IllegalStateException("百度网盘没有返回二维码图片");
                        String payload = qrPayload(source);
                        Bitmap bitmap = payload.isEmpty() ? source : renderQr(payload, maximum);
                        if (bitmap != source) source.recycle();
                        handler.post(() -> {
                            if (closed[0] || !dialog.isShowing() || current != generation[0]) return;
                            image.setImageBitmap(bitmap);
                            hint.setText("请使用百度网盘 App 扫码并确认；登录成功后会自动保存");
                            pollBaiduLogin(provider, dialog, session, closed, generation, current, hint);
                        });
                    } catch (Exception error) {
                        handler.post(() -> {
                            if (!closed[0] && dialog.isShowing() && current == generation[0]) hint.setText("获取二维码失败：" + readable(error));
                        });
                    }
                }, "BaiduQrLogin").start();
            }
        };
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> refresh.run());
            refresh.run();
        });
        dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "重新生成", (ignored, which) -> { });
        dialog.setOnDismissListener(ignored -> {
            closed[0] = true;
            generation[0]++;
        });
        dialog.show();
    }

    private void pollBaiduLogin(JSONObject provider, AlertDialog dialog, BaiduQrSession session, boolean[] closed, int[] generation, int current, TextView hint) {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (closed[0] || !dialog.isShowing() || current != generation[0]) return;
                new Thread(() -> {
                    try {
                        long now = System.currentTimeMillis();
                        JSONObject result = baiduJsonp("https://passport.baidu.com/channel/unicast?channel_id=" + encode(session.sign) + "&tpl=netdisk&_sdkFrom=1&gid=" + encode(session.gid) + "&callback=bd__cbs__" + now + "&apiver=v3&tt=" + now + "&_=" + now, session.cookies);
                        String channel = result.optString("channel_v");
                        JSONObject state = channel.isEmpty() ? null : new JSONObject(channel);
                        if (state != null && state.optInt("status", 1) == 0 && !state.optString("v").isEmpty()) {
                            String cookie = baiduCookie(state.optString("v"), session.cookies);
                            CloudAccountStore.save("", provider.optString("id"), provider.optString("name"), new JSONObject().put("cookie", cookie));
                            handler.post(() -> {
                                if (closed[0] || !dialog.isShowing() || current != generation[0]) return;
                                dialog.dismiss();
                                showAddress("百度网盘登录已保存。");
                                showCloudAccounts();
                            });
                            return;
                        }
                    } catch (Exception error) {
                        handler.post(() -> {
                            if (!closed[0] && dialog.isShowing() && current == generation[0]) hint.setText("检查登录状态失败，请点“重新生成”。");
                        });
                        return;
                    }
                    handler.postDelayed(this, 1800);
                }, "BaiduQrPoll").start();
            }
        }, 1200);
    }

    private BaiduQrSession createBaiduQrSession() throws Exception {
        String gid = UUID.randomUUID().toString().toUpperCase();
        long now = System.currentTimeMillis();
        HttpURLConnection connection = baiduConnection("https://passport.baidu.com/v2/api/getqrcode?lp=pc&qrloginfrom=pc&gid=" + gid + "&callback=bd__cbs__" + now + "&apiver=v3&tt=" + now + "&tpl=netdisk&_=" + now, "");
        int code = connection.getResponseCode();
        JSONObject result = jsonp(read(connection, code));
        if (code < 200 || code >= 300 || result.optInt("errno") != 0 || result.optString("sign").isEmpty()) throw new IllegalStateException("百度网盘没有返回有效登录二维码");
        String image = result.optString("imgurl");
        if (image.isEmpty()) image = "https://passport.baidu.com/v2/api/qrcode?sign=" + encode(result.optString("sign")) + "&lp=pc&qrloginfrom=pc";
        else if (image.startsWith("//")) image = "https:" + image;
        else if (!image.startsWith("http")) image = "https://" + image;
        return new BaiduQrSession(gid, result.optString("sign"), image, responseCookies(connection));
    }

    private JSONObject baiduJsonp(String url, String cookies) throws Exception {
        HttpURLConnection connection = baiduConnection(url, cookies);
        int code = connection.getResponseCode();
        String body = read(connection, code);
        if (code < 200 || code >= 300) throw new IllegalStateException("百度网盘接口返回 HTTP " + code);
        return jsonp(body);
    }

    private String baiduCookie(String bduss, String originalCookies) throws Exception {
        long now = System.currentTimeMillis();
        HttpURLConnection connection = baiduConnection("https://passport.baidu.com/v3/login/main/qrbdusslogin?alg=v3&apiver=v3&bduss=" + encode(bduss) + "&loginVersion=v4&qrcode=1&tpl=netdisk&tt=" + now + "&time=" + (now / 1000) + "&u=https%3A%2F%2Fpan.baidu.com%2F&callback=bd__cbs__login", originalCookies);
        int code = connection.getResponseCode();
        read(connection, code);
        if (code < 200 || code >= 400) throw new IllegalStateException("百度网盘登录确认失败（HTTP " + code + "）");
        String merged = originalCookies;
        String issued = responseCookies(connection);
        if (!issued.isEmpty()) merged = merged.isEmpty() ? issued : merged + "; " + issued;
        if (!merged.contains("BDUSS=")) throw new IllegalStateException("百度网盘没有返回登录 Cookie");
        return merged;
    }

    private HttpURLConnection baiduConnection(String value, String cookies) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(value).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(20_000);
        connection.setRequestProperty("User-Agent", QUARK_USER_AGENT);
        connection.setRequestProperty("Accept", "application/json, text/plain, */*");
        connection.setRequestProperty("Referer", "https://pan.baidu.com/");
        if (!cookies.isEmpty()) connection.setRequestProperty("Cookie", cookies);
        return connection;
    }

    private Bitmap fetchBitmap(String value, String cookies) throws Exception {
        HttpURLConnection connection = baiduConnection(value, cookies);
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) return null;
        try (InputStream stream = connection.getInputStream()) {
            return BitmapFactory.decodeStream(stream);
        }
    }

    private String responseCookies(HttpURLConnection connection) {
        ArrayList<String> values = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
            if (entry.getKey() == null || !"set-cookie".equalsIgnoreCase(entry.getKey())) continue;
            for (String value : entry.getValue()) {
                int separator = value.indexOf(';');
                values.add(separator < 0 ? value : value.substring(0, separator));
            }
        }
        return String.join("; ", values);
    }

    private JSONObject jsonp(String value) throws Exception {
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalStateException("网盘没有返回可识别的登录状态");
        return new JSONObject(value.substring(start, end + 1));
    }

    private static final class BaiduQrSession {
        final String gid;
        final String sign;
        final String imageUrl;
        final String cookies;

        BaiduQrSession(String gid, String sign, String imageUrl, String cookies) {
            this.gid = gid;
            this.sign = sign;
            this.imageUrl = imageUrl;
            this.cookies = cookies;
        }
    }

    /** Re-encodes the QR payload on an integer module grid so scanners get crisp edges. */
    private Bitmap extractQrCanvas(String json) {
        try {
            JSONArray canvases = new JSONArray(json);
            for (int i = 0; i < canvases.length(); i++) {
                String data = canvases.optString(i);
                int separator = data.indexOf(',');
                if (separator < 0 || !data.startsWith("data:image/")) continue;
                byte[] bytes = Base64.decode(data.substring(separator + 1), Base64.DEFAULT);
                Bitmap source = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (source == null) continue;
                String payload = qrPayload(source);
                source.recycle();
                if (!payload.isEmpty()) return renderQr(payload, dip(360));
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private String qrPayload(Bitmap bitmap) {
        try {
            int width = bitmap.getWidth(), height = bitmap.getHeight();
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            Result result = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(width, height, pixels))));
            return result.getText();
        } catch (Exception ignored) {
            return "";
        }
    }

    private Bitmap renderQr(String payload, int maximumSide) throws Exception {
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 4);
        BitMatrix matrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 1, 1, hints);
        int modules = matrix.getWidth();
        int modulePixels = Math.max(1, maximumSide / modules);
        int side = modules * modulePixels;
        int[] pixels = new int[side * side];
        for (int y = 0; y < modules; y++) {
            for (int x = 0; x < modules; x++) {
                int color = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                for (int dy = 0; dy < modulePixels; dy++) {
                    int row = (y * modulePixels + dy) * side + x * modulePixels;
                    for (int dx = 0; dx < modulePixels; dx++) pixels[row + dx] = color;
                }
            }
        }
        Bitmap output = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        output.setPixels(pixels, 0, side, 0, 0, side, side);
        return output;
    }

    private String webLoginUrl(String providerId) {
        return switch (providerId) {
            case "quark" -> "https://pan.quark.cn/?page_info=1";
            case "uc" -> "https://drive.uc.cn/";
            case "pan115" -> "https://y.115.com/login?goto=%2F";
            case "baidu" -> "https://pan.baidu.com/";
            default -> null;
        };
    }

    private String readable(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? "网盘插件没有返回可用的登录界面" : message;
    }


    private LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dip(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
