# 当前 Harness 接入

适配基线为 Harness `0.1.7-rc.2`（`fc52f72338ca024fd8dc1e0650ddc5e092e82d25`）、Mobile `f98fd9615db70b7fd55d0fddada1c65647d4cc98` 和 Mobile Gateway `d805c567d106cc9bd19e5c429fe7c689f3be9645`。Android 和 iOS 共享 Session V4 历史及独立 assistant stream 协议。

## Gateway 依赖

Gateway 源码位于相邻的 `dsh-plugin-mobile-gateway` 仓库。本项目在 `config/current-harness-gateway.patch` 保存该基线所需的补丁：浏览器依赖使用 `dsh-client-ui-renderer`，定时任务能力只在 Host 挂载 `schedule` 服务时声明。Android 和 iOS 在未声明该能力时提示插件未启用，不发送目录请求。

在上述 Gateway 提交上应用补丁并验证：

```sh
git -C ../dsh-plugin-mobile-gateway apply --check ../dsh-mobile/config/current-harness-gateway.patch
git -C ../dsh-plugin-mobile-gateway apply ../dsh-mobile/config/current-harness-gateway.patch
cd ../dsh-plugin-mobile-gateway
pnpm install --frozen-lockfile
npm test
```

已应用补丁的检出不需要再次执行 `git apply`。更新 Gateway 基线时，重新核对补丁和实际 Harness 接口。

## 账号和端口

原版 Mobile Gateway 的设备配对凭据不是 `dsh-passwords` 账号凭据。它的 Host 调用没有传入账号 principal，事件广播也没有账号过滤，因此不能直接安装到已启用账号隔离的 Web profile 并声称支持多账号。账号模式的接入需要同时处理认证、每个连接的 principal、资源访问检查、事件过滤和凭据撤销。

本分支的 Android 和 iOS 已增加原生“账号登录”入口，配套服务端改动位于 `sdwhwzp/dsh-passwords` 的 `dev` 分支。先部署该账号网关并启用 `MCP_MOBILE_AUTH_ENABLED=true`，保证网关自身提供 HTTPS 且证书受手机信任，再在 App 输入 HTTPS 服务器地址、用户名和密码。登录后仍使用原生聊天界面，可在主机列表切换账号。不同账号使用独立资料 ID、凭据、偏好和缓存；密码不保存，refresh cookie 由系统安全存储保护，短期 bearer 在重连时续期。删除账号会先联网撤销设备会话，失败时保留资料并提示错误。

账号连接使用登录响应里的 `/api/mobile.v1/<gatewayId>`，不需要新增端口或在 Harness 中启用全局 Mobile Gateway 插件。桥接的每次调用和订阅均经过现有账号网关；`session/control` 的基线与增量也按会话权限过滤。账号模式不开放宿主机原始目录浏览、文件下载、全局默认模型修改或定时任务管理；有权限时使用现有账号网页完成这些操作。完整服务端说明见 [dsh-passwords 原生移动端接入](https://github.com/sdwhwzp/dsh-passwords/blob/dev/docs/native-mobile.md)。

`dsh-passwords` 使用 `3081` 时，Mobile Gateway 的独立 LAN 监听必须另选端口，例如 `3083`。不要停用现有登录网关或关闭设备鉴权来解决端口冲突。手机使用配对码携带的实际地址；Android Emulator 可通过 `adb reverse tcp:3083 tcp:3083` 访问该端口。

## 本机验证

2026-09-28 的隔离测试通过正常 `dsh web` profile 启动当前 Harness，在独立 DSH_HOME 和回环端口上完成了设备配对、Host 信息、工作区、会话列表、预设、Provider、权限选项、空会话创建、Session V4 历史读取、重命名和归档。未调用模型，未修改现有账号或会话数据。默认 Web profile 不提供 `schedule/catalog`，因此移动端按能力声明处理该可选功能。

`scripts/check-harness-mobile.mjs` 可重复执行上述联调。先通过 `dsh web` 启动隔离测试 profile，启用设备鉴权和网关，然后传入其回环 HTTP 地址、临时工作目录及 Gateway 源码目录：

```sh
node scripts/check-harness-mobile.mjs http://127.0.0.1:13880 /tmp/mobile-workspace ../dsh-plugin-mobile-gateway
```

该检查创建配对设备和一条空会话，并在结束前归档该会话；只对临时 Host 执行。认证凭据不会打印。启用定时任务能力的 Host 还会验证任务目录。

账号模式也可对临时 HTTPS 网关执行相同检查：设置临时账号的 `DSH_MOBILE_USERNAME` 和 `DSH_MOBILE_PASSWORD` 环境变量，并在上述命令末尾加 `--account`。测试证书可通过 Node 的 `NODE_EXTRA_CA_CERTS` 信任；不要关闭 TLS 验证。脚本只接受回环地址，并创建、归档空会话。

本机验证已包括 Android APK 构建及单元测试、Kotlin 共享层测试、iOS Simulator 构建和 16 项账号/主机隔离/本地化测试、服务端 27 项认证与 Remote mux 测试，以及当前 Harness 上通过账号网关完成的原生协议联调（包括命令目录和 assistant-stream 订阅）。这些结果不代表真机签名安装或真实模型生成已经验收。

本机 Gradle 缓存和 iOS DerivedData 已移到挂载的外置盘 `/Volumes/External/dsh-mobile-build-cache-20260928`，原来的 `.gradle-user` 与 `build/ios` 路径为本机符号链接。源码仍在 `/Users/wangzhipeng/dsh-mobile`；其他机器使用自己的缓存目录。
