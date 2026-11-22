# 声迹助手 · Voice Trail

一个可供朋友修改和测试的中英双语 Android 无障碍助手。支持本机语音读屏、看一看、查找应用、准备微信消息及设置时间提醒。默认在手机上完成常用任务，个人模型服务可以按需配置。

## 获取测试包 · Get the APK

在 [Releases](https://github.com/timschendot-commits/voice-trail-android/releases) 选择带有 `VoiceTrail-debug.apk` 和 `SHA256SUMS` 的预发布版本。Android 12（API 31）及以上、arm64-v8a 或 x86_64。CI 编译并在模拟器通过 JNI 测试后才会发布。测试签名可能随构建变化，若升级提示签名冲突，卸载旧版将清空本地设置。首次安装请保留原有 TalkBack。

## 首测 · First test

1. 在手机系统准备当前语言的离线识别和朗读语音包。打开声迹助手，在“更多 → 语言和显示”切换简体中文或 English。
2. 在首页选择“启用读屏服务”，听取权限说明，在系统无障碍设置中自行开启。返回一款已有文字标签的应用。
3. 通过悬浮按钮说“读一下当前屏幕”或 “Read the screen”。通过标准是听到刚才应用的内容、TalkBack 焦点可继续使用、验证码和密码不进入普通播报。试试“再说一遍”和“停止”。相机和麦克风按使用时申请。
4. 开飞行模式重复以上步骤。若系统缺少离线识别引擎或语言包，助手会说明，页面提供键入指令。相机首次试用请用印有模拟日期的商品包装。

You can fork the repo, build the APK using the workflow, or install a tested preview from Releases. The Android UI, confirmations, errors and speech support English and Simplified Chinese. Set up both system voice packs for a bilingual test. Leave feedback with the Android, TalkBack, WeChat and speech-engine versions. Use fictional contacts and labels in public issues.

## 工程决定

产品使用 JopenChen/dsh-go 的 Go Session、Goal 和工具审批，经 JNI 在同一进程运行。另一个同名 Android 项目采用不同运行方式。手机工具基于公开 Android AccessibilityService 接口开发，没有复制另一项目的品牌、素材或代码。日常指令使用可审阅的双语规则。Go 工具以当前任务授权，固定命令不依赖远程模型。只有用户显式配置服务并逐次同意，才会将匹配到的功能关键词或当前照片和问题发给该服务。常规读屏始终在本机执行。详情和模块验收随实现维护。

上游：[Go 内核](https://github.com/JopenChen/dsh-go) · [Android 同名项目](https://github.com/ccch1mneyyy/dsh-Go) · [Google ML Kit 样例](https://github.com/googlesamples/mlkit/tree/master/android/vision-quickstart)

## 开发

构建环境使用 JDK 17、Gradle Wrapper 8.9、Android SDK 35、NDK 28.2.13676358 和 Go 1.25 或更高。Linux/macOS 安装 Android 工具后，从仓库根运行下列命令。Windows 可用带有 Linux SDK/NDK 的 WSL，或使用 GitHub Actions。

```bash
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
(cd core && go test -mod=mod -race ./...)
bash core/build-android.sh
bash ./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

原创代码采用 MIT 许可证。Go 和 dsh-go 的许可声明位于 `core/licenses`。第三方 SDK、Gradle Wrapper 和 Android 平台使用各自的许可。当前版本为工程测试版。

