# OKBOX Android Bridge

在 Android 设备上运行媒体服务，供 OKBOX 连接、解析和播放视频。配置及扫码登录在后台网页操作。

## 安装部署

1. 上传本项目到 GitHub，打开 **Actions → Build APK → Run workflow**。
2. 构建完成后，在 **Artifacts** 下载安装包，解压并安装到 Android 设备。通常选择 `arm64-v8a`。
3. 打开应用，浏览器访问 `http://设备IP:9978`，在后台添加视频源。
4. 在 OKBOX 添加该媒体服务器地址，保持两台设备网络互通。

默认生成可安装的调试包。GitHub 每次构建的调试签名可能不同，更新时可能需要卸载旧包；正式发布应配置固定签名。

## 本地构建

安装 JDK 21、Android SDK（API 37、Build Tools 37.0.0）和 Python 3.10，设置 `ANDROID_HOME`，执行：

```bash
chmod +x gradlew
BRIDGE_PYTHON="$(command -v python3.10)" ./gradlew :app:assembleMobileDebug
```

安装包位于 `app/build/outputs/apk/mobile/debug/`。版本在 `bridge.properties` 设置。项目独立构建，无需鸿蒙工程；Python 运行库由 Chaquopy 打入 APK。

基于 [FongMi/TV](https://github.com/FongMi/TV) 与 CatVod，保留上游源码及 GPL-3.0 许可。视频源的可用性取决于对应服务。
