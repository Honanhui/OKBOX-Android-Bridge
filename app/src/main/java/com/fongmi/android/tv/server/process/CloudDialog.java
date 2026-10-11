package com.fongmi.android.tv.server.process;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.InputType;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.inspector.WindowInspector;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import com.fongmi.android.tv.App;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Reads only this process's cloud login dialogs; never captures the device screen. */
final class CloudDialog {
    private final Map<String, View> choices = new LinkedHashMap<>();
    private final Map<String, EditText> inputs = new LinkedHashMap<>();
    private final Map<String, View> formActions = new LinkedHashMap<>();
    private final Map<View, String> ids = new IdentityHashMap<>();
    private final Map<String, String> labels = new LinkedHashMap<>();
    private final Map<String, View> choiceRoots = new LinkedHashMap<>();
    private final List<View> ownedRoots = new ArrayList<>();
    private String chosenProvider = "";
    private View observedRoot;
    private boolean seen;
    private boolean sawQr;
    private boolean confirmed;
    private long qrSince;
    private List<View> checkBaseline;
    private long checkStarted;
    private String checkMessage = "";
    private String qrHint = "";
    private boolean cookieSubmitted;

    static <T> T onMain(Callable<T> task) throws Exception {
        FutureTask<T> future = new FutureTask<>(task);
        App.post(future);
        try {
            return future.get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            future.cancel(false);
            throw e;
        }
    }

    JSONObject snapshot() throws Exception {
        return onMain(this::read);
    }

    private JSONObject read() throws Exception {
        choices.clear();
        inputs.clear();
        formActions.clear();
        labels.clear();
        choiceRoots.clear();
        Activity activity = App.activity();
        List<View> roots = roots();
        String qr = "";
        boolean pushQr = false;
        boolean expired = false;
        JSONArray options = new JSONArray();
        JSONArray formFields = new JSONArray();
        JSONArray submitActions = new JSONArray();
        View checkedMenu = null;
        // Inspect every login window. The account menu underneath a QR dialog
        // can report success while the plugin leaves that QR dialog attached.
        for (int i = roots.size() - 1; i >= 0; i--) {
            View root = roots.get(i);
            if (!root.isShown() || isBridgeRoot(root, activity)) continue;
            List<View> views = new ArrayList<>();
            flatten(root, views);
            boolean success = false;
            boolean relevant = false;
            for (View view : views) {
                if (view instanceof EditText input) {
                    String id = ids.computeIfAbsent(input, key -> UUID.randomUUID().toString());
                    String label = inputLabel(input);
                    boolean secret = (input.getInputType() & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0
                            || label.matches(".*(?:密码|密碼|password).*");
                    inputs.put(id, input);
                    formFields.put(new JSONObject().put("id", id).put("label", label).put("secret", secret));
                    relevant = true;
                    continue;
                }
                if (view instanceof ImageView image && qr.isEmpty()) {
                    QrImage value = qrImage(image.getDrawable());
                    if (value != null) {
                        // A WeChat QR created by “推送网盘授权” binds a push
                        // service. It cannot authenticate UC, Quark, Baidu or
                        // other cloud drives for playback, so keep it out of
                        // OKbox's cloud-login flow.
                        pushQr = value.push;
                        if (!pushQr) {
                            qr = value.image;
                            qrHint = value.hint;
                        } else {
                            checkMessage = "该源返回的是微信“推送网盘授权”二维码，不是网盘登录二维码；它不能用于播放网盘资源。";
                        }
                    }
                    relevant |= !qr.isEmpty() || pushQr;
                }
                if (!(view instanceof TextView text) || view instanceof EditText) continue;
                String label = text.getText().toString().trim();
                success |= !chosenProvider.isEmpty() && ownedRoots.contains(root)
                        && label.matches(".*(?:登录成功|登入成功|登陆成功|授权成功).*");
                boolean selected = CloudProvider.matches(label, chosenProvider);
                success |= selected && CloudProvider.loggedIn(label);
                expired |= label.matches(".*(?:二维码.*(?:失效|过期)|扫码超时).*");
                View target = clickTarget(view, root);
                if (isFormAction(label, target) && !formActions.containsValue(target)) {
                    String id = ids.computeIfAbsent(target, key -> UUID.randomUUID().toString());
                    formActions.put(id, target);
                    labels.put(id, label);
                    submitActions.put(new JSONObject().put("id", id).put("label", label));
                    relevant = true;
                }
                if (!CloudProvider.isChoice(label)) continue;
                relevant = true;
                if (selected && checkBaseline != null && !checkBaseline.contains(root)) {
                    checkedMenu = root;
                    checkMessage = CloudProvider.loggedIn(label) ? "" : "网盘插件仍显示未登录。手机确认尚未同步，请稍后再次检查；仍失败可重新获取二维码。";
                }
                if (target == null || choices.containsValue(target)) continue;
                String id = ids.computeIfAbsent(target, key -> UUID.randomUUID().toString());
                choices.put(id, target);
                labels.put(id, label);
                choiceRoots.put(id, root);
                options.put(new JSONObject().put("id", id).put("label", label));
            }
            if (!relevant && !success) continue;
            confirmed |= success;
            observedRoot = root;
            if (!ownedRoots.contains(root)) ownedRoots.add(root);
            seen = true;
        }
        if (checkedMenu != null) {
            hide(checkedMenu);
            checkBaseline = null;
        } else if (checkBaseline != null && System.currentTimeMillis() - checkStarted > 10000) {
            checkBaseline = null;
            checkMessage = "暂时无法确认登录状态，请保持 Android 桥接 App 在前台后再次检查";
        }
        if (!qr.isEmpty()) {
            if (!sawQr) qrSince = System.currentTimeMillis();
            sawQr = true;
        }
        String state = confirmed ? "confirmed" : checkBaseline != null ? "checking" : expired ? "expired" : pushQr ? "unsupported" : !qr.isEmpty() ? "qr"
                : formFields.length() > 0 ? "form"
                : sawQr && !checkMessage.isEmpty() ? "closed" : options.length() > 0 ? "choose" : seen ? "closed" : "waiting";
        if (confirmed) {
            qr = "";
            for (View root : ownedRoots) hide(root);
        }
        return new JSONObject().put("state", state).put("qrImage", qr).put("qrHint", qrHint).put("options", options)
                .put("formFields", formFields).put("formActions", submitActions).put("provider", chosenProvider)
                .put("checkMessage", checkMessage).put("sawQr", sawQr).put("qrSince", qrSince);
    }

    void providerHint(String flag) { chosenProvider = CloudProvider.name(flag); }

    void beginCheck() throws Exception {
        onMain(() -> {
            if (chosenProvider.isEmpty()) throw new IllegalArgumentException("请先选择要登录的网盘");
            checkBaseline = new ArrayList<>(roots());
            checkStarted = System.currentTimeMillis();
            checkMessage = "";
            return null;
        });
    }

    void choose(String id) throws Exception {
        onMain(() -> {
            View selected = choices.get(id);
            View root = choiceRoots.get(id);
            if (selected == null || root == null || !root.isAttachedToWindow() || !selected.isEnabled()) {
                throw new IllegalArgumentException("网盘选项已更新，请重新选择");
            }
            read();
            if (!root.isAttachedToWindow() || !choices.containsValue(selected)) throw new IllegalArgumentException("登录窗口已更新");
            chosenProvider = CloudProvider.name(labels.getOrDefault(id, ""));
            confirmed = CloudProvider.loggedIn(labels.getOrDefault(id, ""));
            sawQr = false;
            checkMessage = "";
            qrHint = "";
            choices.clear();
            if (confirmed) return null;
            if (selected.getParent() instanceof AdapterView<?> adapter) {
                int position = adapter.getPositionForView(selected);
                if (position == AdapterView.INVALID_POSITION) throw new IllegalArgumentException("网盘选项不可用");
                adapter.performItemClick(selected, position, adapter.getItemIdAtPosition(position));
            } else if (!selected.performClick()) {
                throw new IllegalArgumentException("无法打开网盘登录");
            }
            hideWhenReady();
            return null;
        });
    }

    void submit(String actionId, JSONObject values) throws Exception {
        onMain(() -> {
            for (Map.Entry<String, EditText> entry : inputs.entrySet()) {
                EditText input = entry.getValue();
                if (!input.isAttachedToWindow()) throw new IllegalArgumentException("登录表单已关闭，请重新打开");
                input.setText(values.optString(entry.getKey()));
            }
            View action = formActions.get(actionId);
            if (action == null || !action.isAttachedToWindow() || !action.isEnabled() || !action.performClick()) {
                throw new IllegalArgumentException("登录按钮已更新，请重新打开登录页面");
            }
            hideWhenReady();
            return null;
        });
    }

    /**
     * Third-party Spiders do not expose a common cookie API.  The only safe
     * generic route is their own LoginShow UI: select the requested provider,
     * then fill a field explicitly labelled Cookie and press its submit button.
     */
    String injectCookie(String providerId, String cookie) throws Exception {
        return onMain(() -> injectCookieOnMain(providerId, cookie));
    }

    private String injectCookieOnMain(String providerId, String cookie) throws Exception {
        if (cookie.trim().isEmpty()) return "missing";
        JSONObject state = read();
        if (confirmed) return "confirmed";
        if (chosenProvider.isEmpty()) {
            String optionId = "";
            for (Map.Entry<String, String> entry : labels.entrySet()) {
                if (matchesProvider(entry.getValue(), providerId)) {
                    optionId = entry.getKey();
                    break;
                }
            }
            if (optionId.isEmpty()) {
                String phase = state.optString("state");
                return "waiting".equals(phase) || "closed".equals(phase) ? "waiting" : "unsupported";
            }
            View selected = choices.get(optionId);
            View root = choiceRoots.get(optionId);
            if (selected == null || root == null || !root.isAttachedToWindow() || !selected.isEnabled()) return "waiting";
            String label = labels.getOrDefault(optionId, "");
            chosenProvider = CloudProvider.name(label);
            confirmed = CloudProvider.loggedIn(label);
            sawQr = false;
            checkMessage = "";
            qrHint = "";
            if (confirmed) return "confirmed";
            choices.clear();
            if (selected.getParent() instanceof AdapterView<?> adapter) {
                int position = adapter.getPositionForView(selected);
                if (position == AdapterView.INVALID_POSITION) return "unsupported";
                adapter.performItemClick(selected, position, adapter.getItemIdAtPosition(position));
            } else if (!selected.performClick()) {
                return "unsupported";
            }
            hideWhenReady();
            return "waiting";
        }
        if (!matchesProvider(chosenProvider, providerId)) return "unsupported";
        if (cookieSubmitted) return "submitted";
        state = read();
        EditText cookieInput = null;
        for (EditText input : inputs.values()) {
            if (isCookieField(inputLabel(input), providerId)) {
                cookieInput = input;
                break;
            }
        }
        if (cookieInput == null) return state.optBoolean("sawQr") ? "qr" : "unsupported";
        View submit = null;
        for (Map.Entry<String, View> entry : formActions.entrySet()) {
            String label = labels.getOrDefault(entry.getKey(), "");
            if (isCookieSubmit(label)) {
                submit = entry.getValue();
                break;
            }
        }
        if (submit == null || !submit.isAttachedToWindow() || !submit.isEnabled()) return "unsupported";
        cookieInput.setText(cookie);
        if (!submit.performClick()) return "unsupported";
        cookieSubmitted = true;
        hideWhenReady();
        return "submitted";
    }

    /**
     * The Spider can only create its provider QR dialog in an Android window.
     * Keep the window alive for the Spider, but make the dialog itself invisible:
     * its QR bitmap and state are then safely sent to OKbox by snapshot().
     */
    void hideWhenReady() {
        App.post(new Runnable() {
            private int attempts;

            @Override public void run() {
                try {
                    JSONObject state = read();
                    String phase = state.optString("state");
                    // Keep the Spider-owned window alive because its callback and QR bitmap
                    // belong to the Spider process.  Once we have observed that window, hide
                    // it immediately: OKbox receives the same state and QR through /api/cloud.
                    if (observedRoot != null && observedRoot.isAttachedToWindow()) observedRoot.setAlpha(0f);
                    if ("choose".equals(phase) || "qr".equals(phase) || "form".equals(phase)) {
                        return;
                    }
                } catch (Exception ignored) {
                }
                // A slow Spider may create its dialog after the original four-second window.
                // Continue observing for fifteen seconds without showing that dialog locally.
                if (++attempts < 300) App.post(this, 50);
            }
        });
    }

    void close() {
        App.post(() -> {
            for (int i = ownedRoots.size() - 1; i >= 0; i--) {
                View root = ownedRoots.get(i);
                if (!root.isAttachedToWindow()) continue;
                // A Spider Dialog owns a separate Android window (including its dim
                // layer).  Hiding that root leaves the dim layer attached on some
                // Android versions, which is why Bridge ended on a black screen.
                // Prefer the dialog's own Close/Cancel listener so it dismisses its
                // window cleanly.  The transparent fallback is only for Spiders that
                // expose no close control at all.
                if (!dismissWithOwnControl(root)) hide(root);
            }
            choices.clear();
            inputs.clear();
            formActions.clear();
            ids.clear();
            labels.clear();
            choiceRoots.clear();
            ownedRoots.clear();
            observedRoot = null;
            qrHint = "";
            cookieSubmitted = false;
            restoreBridgeSurface();
            App.post(CloudDialog::restoreBridgeSurface, 120);
        });
    }

    private static boolean matchesProvider(String label, String providerId) {
        String value = (label + " " + CloudProvider.name(label)).toLowerCase(Locale.ROOT);
        return switch (providerId) {
            case "quark" -> value.contains("夸克") || value.contains("quark");
            case "uc" -> value.contains("uc") || value.contains("优视");
            case "baidu" -> value.contains("百度");
            case "pan115" -> value.contains("115");
            default -> false;
        };
    }

    private static boolean isCookieField(String label, String providerId) {
        String value = label.toLowerCase(Locale.ROOT);
        return value.contains("cookie") || ("quark".equals(providerId) && value.contains("夸克") && value.contains("令牌"));
    }

    private static boolean isCookieSubmit(String label) {
        return label.matches(".*(?:保存|确认|登录|登入|登陆|提交).*" );
    }

    /**
     * A Spider window does not expose its Dialog instance. Dispatching BACK
     * can bubble into BridgeActivity, so a transparent fallback is safer than
     * a synthetic Back event. Do not change visibility: that can leave a
     * Window-level dim layer over the Bridge activity.
     */
    private static void hide(View root) {
        if (!root.isAttachedToWindow()) return;
        root.setAlpha(0f);
    }

    private static boolean dismissWithOwnControl(View root) {
        List<View> views = new ArrayList<>();
        flatten(root, views);
        for (int i = views.size() - 1; i >= 0; i--) {
            View view = views.get(i);
            if (!(view instanceof TextView text) || view instanceof EditText) continue;
            String label = text.getText().toString().trim();
            if (!label.matches("^(?:关闭|取消|返回|知道了|确定)$")) continue;
            View target = clickTarget(view, root);
            if (target != null && target.isAttachedToWindow() && target.isEnabled() && target.performClick()) return true;
        }
        return false;
    }

    private static boolean isBridgeRoot(View root, Activity activity) {
        if (activity == null || activity.getWindow() == null) return false;
        View decor = activity.getWindow().getDecorView();
        return root == decor || root.getRootView() == decor;
    }

    private static void restoreBridgeSurface() {
        Activity activity = App.activity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || activity.getWindow() == null) return;
        View decor = activity.getWindow().getDecorView();
        decor.setAlpha(1f);
        decor.setVisibility(View.VISIBLE);
        View content = decor.findViewById(android.R.id.content);
        if (content != null) {
            content.setAlpha(1f);
            content.setVisibility(View.VISIBLE);
        }
        decor.invalidate();
    }

    private static View clickTarget(View view, View root) {
        View current = view;
        while (current != root) {
            if (current.isEnabled() && (current.isClickable() || current.getParent() instanceof AdapterView<?>)) return current;
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) break;
            current = (View) parent;
        }
        return null;
    }

    private static String inputLabel(EditText input) {
        CharSequence hint = input.getHint();
        if (hint != null && hint.length() > 0) return hint.toString().trim();
        CharSequence description = input.getContentDescription();
        if (description != null && description.length() > 0) return description.toString().trim();
        return "登录信息";
    }

    private static boolean isFormAction(String label, View target) {
        return target != null && target.isEnabled() && label.matches(".*(?:登录|登入|登陆|保存|确认|提交|下一步|获取验证码).*");
    }

    private static void flatten(View view, List<View> output) {
        if (!view.isShown() || output.size() >= 256) return;
        output.add(view);
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) flatten(group.getChildAt(i), output);
    }

    @SuppressWarnings("unchecked")
    private static List<View> roots() {
        if (Build.VERSION.SDK_INT >= 29) return WindowInspector.getGlobalWindowViews();
        try {
            Class<?> type = Class.forName("android.view.WindowManagerGlobal");
            Object manager = type.getMethod("getInstance").invoke(null);
            Field field = type.getDeclaredField("mViews");
            field.setAccessible(true);
            return new ArrayList<>((List<View>) field.get(manager));
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private static QrImage qrImage(Drawable drawable) {
        if (drawable == null || drawable.getIntrinsicWidth() < 80 || drawable.getIntrinsicHeight() < 80) return null;
        Bitmap bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        android.graphics.Rect original = new android.graphics.Rect(drawable.getBounds());
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.WHITE);
            drawable.setBounds(16, 16, 496, 496);
            drawable.draw(canvas);
            int[] pixels = new int[512 * 512];
            bitmap.getPixels(pixels, 0, 512, 0, 0, 512, 512);
            // Only export images which actually contain a QR code, not arbitrary dialog art.
            String payload = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(512, 512, pixels)))).getText();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes);
            return new QrImage("data:image/png;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP),
                    CloudProvider.qrLoginHint(payload), CloudProvider.isWeChatPushQr(payload));
        } catch (Exception ignored) {
            return null;
        } finally {
            drawable.setBounds(original);
            bitmap.recycle();
        }
    }

    private record QrImage(String image, String hint, boolean push) { }
}
