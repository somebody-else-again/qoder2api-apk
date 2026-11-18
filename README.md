# qoder2api Android APK（壳工程）

把 `qoder2api` 的 arm64 二进制打进一个 Android App，安装后点开即在本机 `127.0.0.1:8963` 启动网关。
**PAT 不再内置**，而是在 App 界面里粘贴输入，因此安装包可以安全传播，换账号也无需重新打包。
手机上的 dsh-go 直接连它即可，**完全不需要电脑常开**。

## 原理

- Android 基于 Linux，静态 Go 二进制（CGO_ENABLED=0、AArch64、PIE）可在 App 私有目录直接 exec。
- App 启动时只解压 `qoder2api` 二进制与干净的 `settings.json` 到 `getFilesDir()/qoder2api`，
  `chmod 0700` 后拉起本地网关，日志回显到界面。
- 用户输入 PAT 后，App 通过本机管理 API 交给 Go 服务：
  - `POST /api/auth/login`（用 App 内置控制台密码换 session）
  - `POST /api/accounts/add`（提交 `{"pat","region"}`）
  - 私有 cosy 协议签名、验证、落盘、激活全部由 Go 二进制完成，Java 侧不碰私有协议。
- PAT 最终只写入 App 私有目录的 `secrets/*.token`，不进 APK。
- 链路：`dsh-go → http://127.0.0.1:8963/v1 → qoder2api → Qoder 网关`，对 dsh-go 就是标准 OpenAI 接口。

## 目录结构

```
apk-build/
├── app/src/main/
│   ├── java/com/qoder/gateway/MainActivity.java   # 壳：解压 + 启动 + PAT 输入 + 日志
│   └── assets/
│       ├── qoder2api                              # arm64 二进制（8.7MB，不压缩）
│       └── data/settings.json                     # 干净配置（无密码/无盐/无账号）
├── .github/workflows/build-apk.yml                # 云端构建
└── build.gradle / settings.gradle / app/build.gradle / gradle-wrapper.properties
```

## 构建 APK（本机无需 Android SDK）

本机没有 JDK / Android SDK，使用 GitHub Actions 免费云端构建。由于工程内已无 PAT / 账号数据，
用公开仓库也无妨（如需私有也可以）：

1. 在 GitHub 新建一个空仓库（例如 `qoder2api-apk`）。
2. 把本目录 `apk-build` 的内容作为仓库根目录 push 上去：

   ```powershell
   cd C:\code\qoder2api\apk-build
   git init
   git add .
   git commit -m "qoder2api android shell"
   git remote add origin https://github.com/<你的用户名>/qoder2api-apk.git
   git push -u origin main
   ```

   > 本机 git 走 schannel 会失败，push 若报错可加代理：
   > `git -c http.proxy=http://127.0.0.1:7897 -c https.proxy=http://127.0.0.1:7897 push -u origin main`，
   > 或直接用网页上传文件。

3. 打开仓库 **Actions** 页，等 `Build qoder2api APK` 跑完（也可手动 `Run workflow`）。
4. 在 Artifacts 里下载 `qoder2api-apk`，解压得到 `app-debug.apk`。
5. 传到手机安装（需允许“安装未知来源应用”）。

## 手机端使用

1. 打开 App，等待日志出现 **“服务就绪”**（首次解压约几秒）。
2. 粘贴你的 Qoder PAT（`pt-` 开头），选择区域（国内版选 `cn`，国际版选 `global`），点 **“应用 PAT”**。
3. 看到 `✓ 添加成功` 后，在 dsh-go 里配置：
   - Base URL：`http://127.0.0.1:8963/v1`
   - API Key：`qccg`（Bridge 默认 key）
   - 模型：`auto`
4. PAT 会存入 App 私有目录，**下次打开自动加载，无需重输**；换号只需重新粘贴新 PAT。
5. 保持 App 后台运行。系统杀后台会停服务，请在 **电池优化 / 后台运行管理** 中把本 App 设为不受限制。

## 安全与注意事项

- ✅ APK 内**不含任何 PAT / 账号数据**，可安全分发；PAT 只在用户手机上输入并保存在 App 私有目录。
- 二进制只有 arm64 版，适用于绝大多数现代安卓机；x86 模拟器不兼容。
- App 用 `usesCleartextTraffic=true` 允许访问本机 HTTP（仅 127.0.0.1，服务桥绑定 0.0.0.0 但建议勿在公共 WiFi 暴露）。
- 卸载 App 会连同 PAT 与账号状态一起删除；首次启动会自动生成每机独立的设备指纹盐。
- PAT 失效时无需重装，直接在 App 里粘贴新 PAT 覆盖即可（新账号会被设为激活）。
