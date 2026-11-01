# TVBOX-LITE 与 Android Bridge 对照及账号补齐

日期：2026-11-01。参考为工作区 tvbox-lite（0.4），修改目标为 FongMiSpiderBridge/TV-fongmi；未修改当前 OKBOX 连接的 TVBOX-LITE。

## 功能对照

| 项目 | TVBOX-LITE | Android Bridge |
| --- | --- | --- |
| 订阅管理、多仓切换 | 已有 | 已有 |
| 首页、分类、搜索、详情、播放 API | Cat Node 执行 | Android Spider 执行 |
| HTTP Range、HLS 媒体代理和媒体信息 | 已有 | 已有 |
| 账号加密、单服务主账号、停用和删除 | 已有 | 保留 Android Keystore；同步账号业务更新 |
| UC TV 独立账号及扫码 | 已有 | 本次新增 |
| 天翼官方登录、短信发送和验证 | 已有 | 本次新增后端及管理页 |
| 123社区手动 Cookie 及真实检查 | 已有 | 本次新增 |
| UC、夸克、百度、115、移动真实检查 | 已有 | 本次移植；不再把“字段已填写”当登录有效 |
| 取消扫码 | 已有 | 本次新增后端及管理页调用 |
| 阿里授权及扫码状态处理 | 已有 | 本次同步；二维码保留 Android Bitmap |
| Android 123 扫码修复 | 参考仍使用旧流程 | 保留现有 wxCode 换 Token 流程 |
| 天翼更换账号密码 | 清除旧 Cookie | 本次同步，避免复用另一账号会话 |
| Cat Node 订阅执行、Node 脚本更新及 Cat 凭据同步 | 已有 | 尚未移植，不能将其等同于 Android Spider 凭据注入 |
| Docker 日志管理和源验证页面 | 已有 | Android 仍使用原 /api/trace；未做相同管理界面 |

## 接口和移植边界

账号目录新增 uctv、pan123community。/api/cloud/accounts 新增 auth-cancel、tianyi-interactive-login、tianyi-sms-send、tianyi-sms-submit。已有管理页新增 UC TV 扫码入口、天翼短信弹窗及天翼/迅雷显式登录按钮。

账号服务移植自本地参考代码。Android SharedPreferences、Android Keystore/AES-GCM 和 Bitmap 二维码保持 Android 实现，不复制 Docker 的文件密钥存储。桌面 Swing HTML 解析替换为 Jsoup；UC TV 使用 Android JSONObject.keys 及兼容的十六进制编码。没有修改播放器和 HDS 页面。

新增账号登录不代表所有第三方 Spider 已支持这些凭据。UC TV 与 UC Cookie 账号独立保存；未验证的 Spider 字段不进行猜测注入。Cat Node 是 Node.js 服务，不能在当前 Android DEX/QuickJS 宿主中直接视为兼容。

## 验证

- Android mobile debug APK 构建。
- 管理页 JavaScript 语法检查。
- 直接从修改后代码提取逻辑执行7项离线断言：社区账号名不能取文章作者，正确读取已登录表单/侧栏；UC TV 仅 OAuth 轮询“未确认授权”视为等待，目录接口及401均失败。
- 本次未执行用户真实扫码、短信发送、账号登录及媒体播放；没有读取或迁移当前 TVBOX-LITE 的个人凭据。

后续实机验收需安装新版 Android Bridge，在它自己的账号中心授权后验证真实线路。当前不能宣称两个服务器所有功能已完全一致。
