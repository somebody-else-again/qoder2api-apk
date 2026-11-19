package com.qoder.gateway;

import android.app.Activity;
import android.os.Bundle;
import android.system.Os;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * qoder2api 的 Android 壳。
 *
 * 不内置任何 PAT / 账号数据：App 启动内置的 arm64 二进制作为本地网关，
 * 用户在界面粘贴自己的 PAT，App 通过本机管理 API
 *   POST /api/auth/login       （用内置控制台密码换 session）
 *   POST /api/accounts/add     （提交 {"pat","region"}，由 Go 侧完成私有协议签名与落盘）
 * 把 PAT 交给服务。PAT 最终只落在 App 私有目录，不进 APK，可安全分发安装包。
 *
 * dsh-go 等客户端通过 http://127.0.0.1:8963/v1 访问（API Key 默认 qccg）。
 */
public class MainActivity extends Activity {

    // App 自用的控制台密码：通过环境变量注入服务，仅用于本机管理 API 登录。
    // 用户无需知道它；它不保护数据（数据都在 App 私有目录），只是走通服务自带的鉴权。
    // Apk 版本横幅：日志第一行可见，用于确认用户确实装了本版本。
    private static final String APK_VERSION = "v3-local-apply-20261119";
    private static final String CONSOLE_PASSWORD = "qoder2api-apk-console";
    private static final String HOST = "http://127.0.0.1:";
    private static final int WEB_PORT = 3588;

    private TextView logView;
    private EditText patInput;
    private RadioGroup regionGroup;
    private volatile boolean serviceReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        new Thread(this::deployAndStart, "deploy").start();
    }

    private void buildUi() {
        int pad = 24;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121212);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("qoder2api 本机网关");
        title.setTextSize(18);
        title.setTextColor(0xFF81C784);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("1) 等下方出现“服务就绪”；\n"
                + "2) 粘贴你的 Qoder PAT，选区域，点“应用 PAT”；\n"
                + "3) dsh-go 填 Base URL=http://127.0.0.1:8963/v1  Key=qccg  Model=auto");
        hint.setTextSize(12);
        hint.setTextColor(0xFFBDBDBD);
        hint.setPadding(0, 8, 0, 12);
        root.addView(hint);

        patInput = new EditText(this);
        patInput.setHint("pt-... 个人访问令牌");
        patInput.setSingleLine(true);
        patInput.setTextColor(0xFFE8F5E9);
        patInput.setHintTextColor(0xFF616161);
        root.addView(patInput);

        regionGroup = new RadioGroup(this);
        regionGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton cn = new RadioButton(this);
        cn.setText("国内版 cn");
        cn.setId(1);
        cn.setChecked(true);
        RadioButton gl = new RadioButton(this);
        gl.setText("国际版 global");
        gl.setId(2);
        regionGroup.addView(cn);
        regionGroup.addView(gl);
        root.addView(regionGroup);

        Button apply = new Button(this);
        apply.setText("应用 PAT");
        apply.setOnClickListener(v -> {
            String pat = patInput.getText().toString().trim();
            if (TextUtils.isEmpty(pat)) {
                log("请先粘贴 PAT。");
                return;
            }
            String region = regionGroup.getCheckedRadioButtonId() == 2 ? "global" : "cn";
            apply.setEnabled(false);
            new Thread(() -> {
                applyPat(pat, region);
                runOnUiThread(() -> apply.setEnabled(true));
            }, "apply").start();
        });
        root.addView(apply);

        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setTextColor(0xFFE8F5E9);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF1E1E1E);
        sv.addView(logView, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.setMargins(0, 12, 0, 0);
        sv.setLayoutParams(lp);
        root.addView(sv);

        setContentView(root);
    }

    private void log(String s) {
        runOnUiThread(() -> logView.append(s + "\n"));
    }

    private void deployAndStart() {
        File dataRoot = new File(getFilesDir(), "qoder2api");
        try {
            log("== qoder2api for Android ==");
            log("APK 版本: " + APK_VERSION);
            log("架构: " + System.getProperty("os.arch"));

            // 关键：二进制必须从 nativeLibraryDir 执行。
            // Android 10+ 对 getFilesDir() 施加 SELinux noexec，直接 exec 会 error=13；
            // jniLibs 里的 .so 会被系统解压到 nativeLibraryDir，该目录允许 exec。
            File bin = new File(getApplicationInfo().nativeLibraryDir, "libqoder2api.so");
            if (!bin.exists()) {
                log("未找到内置二进制: " + bin.getAbsolutePath());
                return;
            }
            try {
                Os.chmod(bin.getAbsolutePath(), 0700);
            } catch (Exception ignored) {
                // nativeLibraryDir 通常已可执行；失败也不阻断
            }

            copyAssetIfAbsent("data/settings.json", new File(dataRoot, "settings.json"), false);
            new File(dataRoot, "accounts").mkdirs();
            new File(dataRoot, "secrets").mkdirs();
            new File(dataRoot, "logs").mkdirs();

            // 关键：确认当前在跑的网关确是本版本。若残留旧进程（旧 so 没有
            // /api/local/version 端点），必须先杀掉再启动，否则会出现
            // "换了 APK 但跑的还是旧二进制 / 旧登录逻辑" 的假象。
            String runningVer = httpGetBody(HOST + WEB_PORT + "/api/local/version");
            boolean sameVersion = runningVer != null && runningVer.contains(APK_VERSION);
            if (!sameVersion) {
                killStale(bin);
                log("正在启动本地网关（" + APK_VERSION + "）...");
                ProcessBuilder pb = new ProcessBuilder(
                        bin.getAbsolutePath(),
                        "--data-dir=" + dataRoot.getAbsolutePath(),
                        "--instance=cn");
                pb.directory(dataRoot);
                pb.environment().put("QODER2API_HOME", dataRoot.getAbsolutePath());
                pb.environment().put("QODER2API_CONSOLE_PASSWORD", CONSOLE_PASSWORD);
                pb.redirectErrorStream(true);
                final Process p = pb.start();
                Thread reader = new Thread(() -> {
                    byte[] buf = new byte[4096];
                    try (InputStream in = p.getInputStream()) {
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            log(new String(buf, 0, n, StandardCharsets.UTF_8));
                        }
                    } catch (IOException e) {
                        log("日志读取失败: " + e.getMessage());
                    }
                }, "logcat");
                reader.setDaemon(true);
                reader.start();
            } else {
                log("服务已在运行。");
            }

            for (int i = 0; i < 60; i++) {
                if (httpGet(HOST + WEB_PORT + "/health") != null) {
                    serviceReady = true;
                    break;
                }
                Thread.sleep(500);
            }
            if (serviceReady) {
                log("服务就绪。粘贴 PAT 并点“应用 PAT”即可。");
            } else {
                log("等待服务就绪超时，请查看上方日志或重启 App。");
            }
        } catch (Exception e) {
            log("启动失败: " + e);
        }
    }

    private void applyPat(String pat, String region) {
        if (!serviceReady) {
            log("服务尚未就绪，请稍候再试。");
            return;
        }
        try {
            log("提交 PAT（region=" + region + "）...");
            // 新版网关：一步式本机端点，密码 + PAT + Region 一次提交，
            // 服务端直接校验并落盘，完全绕开 session/cookie 登录链路。
            String body = "{\"password\":" + JSONObject.quote(CONSOLE_PASSWORD)
                    + ",\"pat\":" + JSONObject.quote(pat)
                    + ",\"region\":" + JSONObject.quote(region) + "}";
            String resp = postJson(HOST + WEB_PORT + "/api/local/apply", body, null);
            log("本机端点响应: " + (resp == null ? "<无响应>" : resp));
            if (resp == null || !resp.contains("\"id\"")) {
                // 回退：旧网关没有本机端点，走 session 登录 + /api/accounts/add
                log("本机端点不可用，尝试兼容旧版登录链路...");
                String token = login();
                if (token == null) {
                    log("登录失败：请重启 App 重试。");
                    return;
                }
                String body2 = "{\"pat\":" + JSONObject.quote(pat)
                        + ",\"region\":" + JSONObject.quote(region) + "}";
                resp = postJson(HOST + WEB_PORT + "/api/accounts/add", body2, token);
                log("兼容链路响应: " + (resp == null ? "<无响应>" : resp));
                if (resp == null || !resp.contains("\"id\"")) {
                    log("应用失败：请检查 PAT 是否正确、服务是否仍在运行。");
                    return;
                }
            }
            log("✓ 添加成功！dsh-go 现在可用：");
            log("  Base URL: http://127.0.0.1:8963/v1");
            log("  API Key : qccg");
            log("  Model   : auto");
            log("PAT 已存入 App 私有目录，下次打开自动加载，无需重输。");
            patInput.setText("");
        } catch (Exception e) {
            log("应用失败: " + e);
        }
    }

    private String login() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(HOST + WEB_PORT + "/api/auth/login").openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(5000);
            c.setReadTimeout(10000);
            c.setRequestProperty("Content-Type", "application/json");
            byte[] body = ("{\"password\":" + JSONObject.quote(CONSOLE_PASSWORD) + "}")
                    .getBytes(StandardCharsets.UTF_8);
            java.io.OutputStream os = c.getOutputStream();
            os.write(body);
            os.flush();
            os.close();
            int code = c.getResponseCode();
            if (code != 200) {
                log("登录 HTTP " + code + "，请检查服务状态。");
                return null;
            }
            // 首选：服务端已在 JSON body 直接返回 session_token（新版网关），
            // 绕开 Android/OkHttp 对 Set-Cookie 多值头的解析坑。
            String resp = readAll(c.getInputStream());
            try {
                JSONObject jo = new JSONObject(resp);
                String tok = jo.optString("session_token", null);
                if (tok != null && !tok.isEmpty()) return tok;
            } catch (Exception ignored) {
            }
            // 回退：老网关只在 Set-Cookie 里给 token，遍历全部同名头。
            for (java.util.Map.Entry<String, java.util.List<String>> h
                    : c.getHeaderFields().entrySet()) {
                if (h.getKey() == null || !"Set-Cookie".equalsIgnoreCase(h.getKey())) continue;
                for (String cookie : h.getValue()) {
                    for (String part : cookie.split(";")) {
                        String kv = part.trim();
                        if (kv.startsWith("qoder2api_session_")) {
                            int eq = kv.indexOf('=');
                            if (eq > 0) return kv.substring(eq + 1);
                        }
                    }
                }
            }
            log("登录响应未包含 session token: " + resp);
            return null;
        } catch (Exception e) {
            log("登录异常: " + e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private String postJson(String url, String body, String token) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(5000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Content-Type", "application/json");
            if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
            java.io.OutputStream os = c.getOutputStream();
            os.write(body.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();
            int code = c.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            return readAll(in);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private String httpGet(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(2000);
            c.setReadTimeout(2000);
            return c.getResponseCode() == 200 ? "" : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private void copyAssetIfAbsent(String assetPath, File dest, boolean executable) throws IOException {
        if (dest.exists() && dest.length() > 0) return;
        File parent = dest.getParentFile();
        if (parent != null) parent.mkdirs();
        try (InputStream in = getAssets().open(assetPath);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        if (executable) {
            try {
                Os.chmod(dest.getAbsolutePath(), 0700);
            } catch (Exception e) {
                // chmod 失败时进程启动一般也会失败并写进日志，这里不阻断部署流程
            }
        }
    }

    // GET 并返回响应体（用于探测 /api/local/version）
    private String httpGetBody(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(2000);
            c.setReadTimeout(2000);
            if (c.getResponseCode() != 200) return null;
            return readAll(c.getInputStream());
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // 杀掉可能残留的旧版网关进程。native 子进程独立于 Activity 生命周期，
    // 重装 APK 或重启 App 并不会自动结束它；若检测到版本不匹配必须先杀干净。
    private void killStale(File bin) {
        String name = bin.getName();
        try {
            Process p = new ProcessBuilder("pidof", name).start();
            String out = readAll(p.getInputStream()).trim();
            p.waitFor();
            if (out.isEmpty()) return;
            log("检测到残留网关进程 " + out + "，正在停止...");
            for (String pid : out.split("\\s+")) {
                if (pid.isEmpty()) continue;
                android.os.Process.killProcess(Integer.parseInt(pid));
            }
            // 给旧进程一点退出时间，避免端口未释放
            Thread.sleep(600);
            log("残留进程已清理。");
        } catch (Exception e) {
            log("清理残留进程: " + e);
        }
    }
}
