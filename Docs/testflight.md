# TestFlight 发布

本 fork 的 iOS App 使用 `com.wangzhipeng.dshmobile`，实时活动扩展使用 `com.wangzhipeng.dshmobile.AgentLiveActivityWidget`，签名团队为 Hongkun Wang（`K8N68KDS73`）。两个 target 的版本均为 `1.6.1`、构建号 `16`；再次上传同一版本时同步增加两个 target 的 `CURRENT_PROJECT_VERSION`。主 App 的 Info.plist 从构建设置读取版本。

在 Xcode 的 Apple Accounts 中登录具有该团队签名权限的账号。构建需要 Java 17、Android SDK、Kotlin/Native 和 Xcode 的 iOS/Metal 工具链。Gradle 使用 `GRADLE_USER_HOME` 指定缓存；外置盘路径只属于本机配置，不写入项目。

```sh
xcodebuild -project DeepSeekHarnessMobile.xcodeproj \
  -scheme DeepSeekHarnessMobile -configuration Release \
  -destination 'generic/platform=iOS' -allowProvisioningUpdates \
  -archivePath /absolute/output/DshMobile.xcarchive archive

xcodebuild -exportArchive \
  -archivePath /absolute/output/DshMobile.xcarchive \
  -exportOptionsPlist config/ExportOptions-AppStore.plist \
  -exportPath /absolute/output/AppStore -allowProvisioningUpdates
```

导出配置使用 `app-store-connect` 和 `destination=export`，只生成本地发布文件。在 App Store Connect 新建 iOS App 时选择上述主 App Bundle ID，再通过 Xcode Organizer 或 Transporter 上传正式 IPA。上传后由 Apple 处理构建，并在 TestFlight 中设置测试人员；归档或导出成功不代表 Apple 已接收或处理构建。

手机内选择“账号登录”，服务器填写 `https://gr.gr-iot.cn:3081`，使用现有账号密码。
