# 声迹助手 · Voice Trail

让视障和年长用户在 Android 上听取当前页面、读包装标签、准备微信消息和设置提醒。说完指令，先听目标，再听步骤和结果。  
An Android assistant for blind, low-vision and older users. Speak a request, hear the proposed goal, and follow the result without having to find small controls.

**第一次试用请从读屏开始。** 在有文字的页面点悬浮按钮，说“读一下当前屏幕”。如果读出了原页面的内容，试试“说慢一点”和“再说一遍”。  
**Start with one screen.** Open a page with labelled text, activate the floating button, and say “Read the screen.” Try “Speak slower” and “Say that again.”

[下载页 · Releases](https://github.com/timschendot-commits/voice-trail-android/releases) · [构建与测试记录 · Actions](https://github.com/timschendot-commits/voice-trail-android/actions/workflows/android.yml) · [报告问题 · Issues](https://github.com/timschendot-commits/voice-trail-android/issues)

## 测试 · Install and try

1. 在 Releases 找到与你要测试的提交对应的预发布版本，下载 `VoiceTrail-debug.apk`，可用同页 `SHA256SUMS` 核对。支持 Android 12（API 31）及以上、64 位 ARM 手机和 x86_64 模拟器。GitHub Actions 会在构建、单元测试、模拟器测试均成功后发布 APK。若某个提交尚无安装包，先看对应的工作流。  
   Choose a prerelease for the commit you want to test. Install **VoiceTrail-debug.apk** from its assets. Each fresh CI build can have a different debug signature. If Android rejects an update, removing the old preview clears its saved settings. A local build using the same debug key can usually update in place.
2. 保留 TalkBack。系统中为简体中文、English 分别安装支持离线识别和朗读的语音。首次界面跟随系统语言，中文系统选择中文，其余使用英文。在“更多 → 语言和显示模式”可以切换。  
   Keep TalkBack enabled for the speech-first test. Choose **More → Language and display** to switch languages or display modes. The app listens in one selected language at a time. Use **Type a command** to isolate speech-recognition failures from parsing errors.
3. 首页的“启用读屏服务”会先说明用途，再打开系统无障碍设置，由本人启用。麦克风、相机和通知分别按需授权。系统版本或厂商策略阻止侧载应用开启无障碍时，记录系统原话和版本。  
   Use **Enable screen access** on Home, review the Android disclosure, and enable the service yourself. For the basic read test, return to a labelled app before activating the floating button. For a notification test, enable notifications under **More → Permissions and privacy**. [Android 关于受限设置的说明](https://support.google.com/android/answer/12623953)
4. 微信请先用测试账号、可区分的联系人备注和无敏感内容的消息。相机用自制标签，比如 `EXP 2030-06-30` 和 `MFG 2026-01-01`。  
   Use two designated test accounts for messaging and a printed sample label for the camera. To leave the trial, disable Voice Trail in Android accessibility settings. The overlay disappears when its service stops.

### 发布版模拟器实测 · Published APK device test

Android 15（Pixel 5 模拟器，x86_64）从 Releases 下载安装了 `077bf18f8c56` 的 APK 并核对 SHA-256。安装离线朗读引擎后，在飞行模式下，跨应用读屏读出了系统“日期和时间”页面，虚拟相机画面被识别为房间。英文和中文标签分别读出 `2031-07-28` 和 `2032-06-18`，只有生产日期的样图得到“无法确定有效期”的提示。验证码输入请求触发手动接管。另一台模拟器在应用退到后台后收到提醒，首次观察到通知的时间比设定时刻晚 60 秒。本地提醒采用 Android 的非精确计时。模拟器缺少英文离线识别包，应用给出了恢复提示。真人语音、TalkBack 听感和真实微信收发仍待真机验证。

On Android 15, the published APK passed the language-switch, cross-app screen reading, camera, bilingual label OCR, manual handoff and background reminder checks. The emulator also showed the recovery message for missing English offline speech recognition. Human speech, TalkBack navigation by ear and messaging between real WeChat accounts still need handset testing. [运行记录、日志与测试证据 · Device run and evidence](https://github.com/timschendot-commits/voice-trail-android/actions/runs/45015964837)

## 产品决定与依据 · Product decisions

下表列出 Android 测试版的设计选择、实现位置和验收依据，涵盖中英双语交互、TalkBack 兼容性、语音确认和敏感操作。  
The table documents the Android preview’s design decisions, their rationale and the relevant source files.

| 决定 · Choice | 原因、行为和修改入口 · Rationale and implementation |
| --- | --- |
| 原生 Android + 进程内 Go | Android 原生控件负责焦点和朗读，使用 JNI 装入两个 ABI 的 Go 库。dsh-go 的 Session、Goal、Turn/Step 事件和工具授权管道在同一进程运行。Android 端依次观察界面、执行授权步骤、核对结果。The Java product layer owns bounded task templates and observes the real UI after each action. The Go module owns session events and tool decisions. See [engine.go](core/engine/engine.go), [JNI bridge](core/cmd/androidjni) and [PhoneService.java](app/src/main/java/io/github/voicetrail/PhoneService.java). |
| 固定任务优先离线 · Bounded offline tasks | 规则接受逐条的日常请求。明确的高风险语句走交接，缺少时间或联系人时要求重说。可选模型只解释有限的低风险意图或描述照片。No API key is required for the device test. A model response cannot create a new phone tool. See [Command.java](app/src/main/java/io/github/voicetrail/Command.java) and [CloudAssist.java](app/src/main/java/io/github/voicetrail/CloudAssist.java). |
| 悬浮语音按钮 · Floating voice entry | 屏幕边沿提供有文字的原生按钮。首页另有语音、读屏、看一看、停止、键入指令。麦克风按次开启。没有后台唤醒词或音量键监听。While reading, the service inspects available accessibility nodes without requesting TalkBack focus. Activating the assistant opens its own UI, which must still be tested for focus restoration on each handset. |
| 一套界面、两种偏好 · Shared controls | 自动模式按系统触摸探索状态选择朗读优先或大字，用户可覆盖。首页标题 34 sp、常规信息 20 sp、大字模式主按钮 30 sp、最小高度 60 dp。首页深色正文对白色的计算对比度约 15.9∶1，白色按钮文字对蓝底约 10.7∶1。These values describe the specified home controls. Check dialogs, focus indicators, system UI and large font settings separately. See [MainActivity.java](app/src/main/java/io/github/voicetrail/MainActivity.java) and [SettingsPanel.java](app/src/main/java/io/github/voicetrail/SettingsPanel.java). |
| 语音、音量、打断 · Speech and interruption | 默认语速为系统语速的 0.88 倍，可调至 0.55–1.60 倍。助手增益 10%–100%，系统无障碍音量和设备音量仍有作用。使用设备端 ASR 和已安装的离线 TTS 声音。点击“说句话”可打断当前任务。持续监听语音打断未实现。The app selects an on-device recognizer and local voice. Missing language data is reported. It accepts a new voice request when the microphone is active. See [VoiceIO.java](app/src/main/java/io/github/voicetrail/VoiceIO.java). |
| 授权与交接 · Permission per task | Go 根据当前 Goal 和工具做决定。需要确认的工具绑定当前会话、具体操作和一次调用。取消、超时、失去目标或界面校验失败时停止。Payments, transfers, red packets, password/OTP entry, deletion, uninstall and system changes are handed back to their original app. A confirmation cannot grant these tools. |
| 微信身份与录音 · WeChat identity and audio | 使用微信包名、界面角色、唯一可见的名称、聊天标题及编辑框交叉核验。仅支持一个完整显示名作为常用联系人。已存在的不同草稿不会被覆盖。语音由用户亲口录制。松开按住说话可能立即发送。A screen name alone is not proof of the recipient’s identity. Use distinct test aliases. If uncertain, open the exact conversation yourself. |
| 常用联系人文字代发 · Sending a trusted text | 在“更多 → 常用微信联系人”另外绑定资料页的微信号。每次听完整收件人和正文后明确确认。助手还要从可访问的资料页核对绑定的微信号，核对聊天窗口、草稿和唯一发送按钮。缺少任一证据时交还本人。It may press Send once. A new matching message in the same chat and a cleared editor are local evidence of submission. Delivery to the recipient's phone remains unverified. A timeout never triggers an automatic resend. |
| 无标签图标 · Unlabelled controls | 读屏优先使用文本、已关联标签和控件语义。Android 14/API 34+ 可针对当前应用窗口按需在内存中分析无标签图标区域。原始画面不保存、不上传。线索用不确定语气播报，不作为点击依据。Earlier versions, protected windows or unavailable image evidence fall back to the accessible hint, role or relative position. This is a best-effort description. |
| 照片和日期 · Visual evidence | CameraX 拍照或系统选择器选一张图，ML Kit 在本机读文字和估计类别。追问继续使用同一张照片，可问行号、总额或左右区域。只有明确有效期标签紧跟完整年月日时才与手机日期比较。Local object labels are categories. They do not establish the identity of a red item. No medication or dosage decisions are made. See [VisionActivity.java](app/src/main/java/io/github/voicetrail/VisionActivity.java) and [VisionEvidence.java](app/src/main/java/io/github/voicetrail/VisionEvidence.java). |
| 提醒、通知、恢复 · Reminder delivery | 闹钟把时间交给系统时钟，由本人核对。提醒安排下一个匹配的本地时间，使用普通 AlarmManager，可受省电影响，重启后需重设。任务结果通知使用通用文字。“重试”仅开放给适合重复的读屏及启动应用。Reminder labels can appear in the notification shade. Reminder delivery uses the notification channel. Immediate foreground task feedback uses speech. |

## 模块验收 · Acceptance on a handset

这是验收脚本。每一行先听正向结果，再尝试右侧的失败情况。自动化执行结果以对应提交的 [Actions 记录](https://github.com/timschendot-commits/voice-trail-android/actions/workflows/android.yml) 为准。真机 TalkBack、微信、厂商语音引擎和延迟要记录设备实测结果。  
Run each scenario in both supported languages using dedicated test accounts. A passing emulator job verifies the assertions it ran. Keep the handset results separate.

| 模块 · Setup | 中英示例 · Say or do | 应观察到的结果和反例 · Pass and failure checks |
| --- | --- | --- |
| A. 入口、读屏、重复 | 在测试便签聚焦一行，再从悬浮入口说“读一下当前屏幕” / “Read the screen.” 尝试“重复” / “Repeat” 和“停止” / “Stop.” | 返回原页面，读出可访问的文字，能继续 TalkBack 导航。切换页面或锁屏时检查是否停止或要求重试。标签缺失的按钮应说明依据或位置。Verify that the overlay or assistant UI is never mistaken for the page to read. |
| B. 验证码与敏感操作 | 测试便签写 `Verification code: 012345`。先普通读屏，再说“把屏幕上的验证码念给我听” / “Read the verification code.” 再说“输入验证码” / “Enter the verification code.” | 一般读屏隐藏可识别的敏感内容。按要求只念唯一、明示的号码，保留前导零。重复和任务通知不泄露号码。再加第二个不同号码，应提示不明确。Password/edit fields stay excluded. The input request hands control back. |
| C. 打开应用、找功能 | “打开微信，找到通讯录” / “Open WeChat and find Contacts.” 让目标应用显示无匹配标签的页面后重试。 | 要先听到目标、过程和已确认结果。微信通讯录有明确路径。其他功能只报告当前页是否有精确标签，由本人打开。An unrelated screen or missing label must never trigger a guessed tap. |
| D. 微信文字与语音 | 使用确实存在的唯一备注。“给小林发微信文字，内容是到了吗？” / “Text Ada on WeChat: Are you there?” 另试“给小林发微信语音” / “Send a voice message to Ada on WeChat.” | 非预设联系人先明确确认，最后仅准备草稿或录音界面。检查错误标题、同名可见项、已有不同草稿、微信界面变化、中途取消。有任何不确定性时应能听到下一步。Stay with test contacts. Never treat “draft ready” or “recording control ready” as delivery. |
| D2. 常用联系人代发 · Trusted submission | 双方约好发送一条测试消息。先仅存名字，再绑定资料页真实显示的微信号，分别从微信主界面试一次。听完整收件人和正文。第一轮说“取消” / “Cancel”，第二轮再明确确认。 | 名字未绑定微信号、资料页无法核验、草稿改变、取消均不得自动发送。满足所有条件时只尝试发送一次。会话出现匹配新消息且草稿清空才报告观察到的结果。否则提示检查聊天。Check sender and receiver independently. Never retry automatically after an uncertain send. |
| E. 看一看、追问 | “看一看” / “Take a look.” 拍样例有效期，再问“生产日期在哪一行？” / “Which line has the production date?” 用纯生产日期、模糊日期以及不同有效期的照片重测。 | 清晰时给日期与行号，缺证据时要求重拍。没有目标标签时不得把生产日期当有效期。再问“左边是什么？” / “What is on the left?”，应使用同一照片，重拍后应使用新照片。 |
| F. 闹钟与提醒 | “设置一个下午三点半的闹钟” / “Set an alarm for 3:30 pm.” “晚上九点提醒我打电话” / “Remind me at 9 pm to call Ada.” | 系统时钟按指定时间打开，助手告知仍需核对。提醒任务返回安排结果。关掉提醒通知后重试，应说明原因。只说“七点”或“at seven”应要求澄清。Use a one-off test reminder a few minutes ahead and record its actual receipt time. |
| G. 云端授权 | 在“更多”配置测试服务后，先用普通读屏，再针对一张无私人信息的图请求在线描述。在授权提示说“取消” / “Cancel.” | 普通读屏保持本机。分享提示说明目的地、整张图和问题。取消时服务应收不到图。换图或再问应重新授权。Verify using the test endpoint's request count or access log without logging payloads. |
| H. 模式、断网、通知 | TalkBack 开/关各试一次。最大系统字体、大字模式、飞行模式分别走 A–F。长任务中打开通知，选择“取消” / “Cancel.” | 朗读导航顺序应可预测。按钮放大后仍能到达。离线包已准备时普通语音任务照常工作。试拒绝麦克风，再用“键入指令”。After cancellation or stale notification taps, verify that no later step resumes the old task. |

### 双语首测设备与首响 · Device matrix and latency

推荐组合是 **一台 64 位 Android 12 手机**检查最低版本、**一台较新 Android + TalkBack 的 64 位手机**检查交互和权限，以及 **Android 15/API 35 x86_64 模拟器**运行自动化。它们是测试目标。记录实际品牌型号、Android 和 TalkBack 版本、微信版本、ASR/TTS 引擎和语言包、网络状态、提交号与使用模式。语言切换后重复同一任务。  
Start with those profiles and publish actual device results in the issue or PR. A phone without an installed offline recognition service cannot establish hands-free offline support.

**首响目标 ≤1.5 秒，测量口径为人声结束至第一句可辨认的助手反馈开始。** 用第二台设备录下测试者和手机扬声器。回放波形，标出最后一个用户音节结束点以及助手首个可辨认音节开始点，取差值。分别测冷启动和热启动、中英、在线和飞行模式，每组建议至少 10 次，报告中位数、最慢值和超过目标次数。若设备只有提示音、没有人声，记为失败。识别结束提示音、TTS 入队和模型返回都不能代表已听到首句。  
Measure the acoustic gap to the first spoken response. Keep beep latency separate. Note timeouts and missing speech as failures. Do not average them away. No handset latency result is asserted here.

## 数据在何处处理 · Where the data goes

- **手机上 / On device.** 朗读的屏幕、识别文字、照片和录音不写入 Go 事件。会话只保留内存中的任务与工具标识、步骤和审批结果。照片在当前会话中处理。系统选择器只授予对所选内容的访问。设置保存语言、模式、音量、语速，以及可选的常用联系人和供核对的微信号。Task status notifications use generic text. The original app, OS speech engines and notification settings have their own data handling.
- **可选模型 / Optional provider.** 应用未配置模型时依然能运行固定任务。配置兼容的 HTTPS 服务、模型名称和测试密钥后，凭据只保留在当前进程。模糊指令可经同意发送匹配到的功能关键词。在线读图须说出或输入所示确认短语，发送整张照片和问题。退出进程后需重新配置。Provider billing and retention depend on that provider. Local privacy checks can miss unlabelled private information. Use synthetic examples for testing.
- **ML Kit.** Google 说明 OCR、图像标注输入及结果在设备处理。SDK 自身可收集设备和应用信息、安装级标识、性能数据等诊断和使用统计。关掉本项目的可选在线模型不会改变 SDK 的行为。The optional model connection and Google's SDK telemetry are separate. [ML Kit privacy](https://developers.google.com/ml-kit/terms) · [Android data disclosure](https://developers.google.com/ml-kit/android-data-disclosure)
- **权限 / Permissions.** 麦克风用于一次指令，相机用于取图，无障碍服务用于用户主动请求的读屏及受控步骤，网络用于可选模型和 SDK 通信。应用不申请读取通讯录、读取短信或广泛文件访问。Do not put keys, real verification codes, payment details, contact lists or private screenshots into public issues.

## 上游与开源参照 · Upstream choices

| 来源 · Primary source | 在此项目中的用途 · How it informed this implementation |
| --- | --- |
| [JopenChen/dsh-go](https://github.com/JopenChen/dsh-go) · [固定提交](https://github.com/JopenChen/dsh-go/commit/c1023d186802c2fc0df87eb57c1ff98bde238728) | MIT 许可的进程内 Go 参考实现。直接依赖其 Session、Goal 和工具管道。固定版本见 [go.mod](core/go.mod)。The dependency is built into the app. The user does not start a Go server. |
| [ccch1mneyyy/dsh-Go](https://github.com/ccch1mneyyy/dsh-Go) | 独立的手机 DeepSeek Harness 客户端，提供 Mobile Use 手机操控。其本地运行环境、审批体验供对照。这里另写 Android 服务与受限操作路径。It is a reference for the mobile experience. Its AGPL application code and branding are not bundled here. |
| [google/talkback](https://github.com/google/talkback) | Android 屏幕阅读器参考项目。声迹复用系统无障碍动作和原生控件，保留用户自己的 TalkBack。Test against the version installed on the phone. A code review cannot establish spoken focus order on a handset. |
| [googlesamples/mlkit](https://github.com/googlesamples/mlkit/tree/master/android/vision-quickstart) | 参照设备端文本识别和图像标注用法，使用已打包中文 OCR 和图像分类模型。The date and bill rules, uncertainty handling and same-photo follow-ups live in this repository. |
| [android/camera-samples](https://github.com/android/camera-samples) | CameraX 生命周期、预览与拍照参考。Voice Trail passes the current image to local recognition and provides spoken capture/retake controls. |
| [Android 官方测试指南与 Agent 技能](https://github.com/android/skills/tree/main/testing/testing-setup) | 参照其测试分层：用 Java 单测验证指令和日期，用模拟器检查无障碍动作、服务和 JNI，再用真机评估人耳听到的内容。Local icon matching currently covers four simple shapes (back, close, search and add); an uncertain match stays unnamed. |
| [Android accessibility](https://developer.android.com/guide/topics/ui/accessibility/apps) · [WCAG target size](https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum.html) | 用 Android 的 sp、dp 和原生可访问性树实现界面。WCAG 2.2 AA 用于明确对比度、等效入口和可操作性验收目标。The project does not claim independent WCAG certification. |

## 开发与反馈 · Build and contribute

使用 JDK 17、Gradle Wrapper 8.9、SDK 35、NDK 28.2.13676358，以及支持项目模块的 Go（CI 使用 1.26.x）。Linux/macOS 在仓库根目录运行，Windows 可使用装有 Linux SDK/NDK 的 WSL。依赖首次下载需要网络。  
Use the checked-in wrapper. Build JNI first so the APK contains both native libraries.

```bash
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
(cd core && go test -mod=readonly -race ./...)
bash core/build-android.sh
bash ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
# With a running Android 12+ device or emulator
bash ./gradlew :app:connectedDebugAndroidTest
```

跨应用读屏验收另设模拟器开关。用一次性模拟器运行 `bash ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.ciOverlayTest=true`。测试从安卓设置点击真正的悬浮按钮，再通过 Go 内核和无障碍服务读取原窗口，并核对返回的包名与窗口编号。测试用回调接收朗读文本，麦克风识别和扬声器反馈仍需真人验证。此项只在明确启用的模拟器上临时开启服务，并在退出测试时恢复原设置。

提交改动时，在 GitHub 上 **Fork** 仓库，克隆副本，在独立分支修改相应模块，然后发起 Pull Request。无需原仓库写入权限。Actions 中可查看 Go 测试、Android JVM 单测、APK 权限/ABI 检查，以及 API 35 模拟器上的 JNI 和读屏节点测试。检查对应提交和各步骤，下载 `android-test-reports` 阅读详情。  
Fork, branch, change one workflow, run the relevant tests, then open a PR. In the description include the commit/APK version, handset and language, exact synthetic command, expected speech, actual speech, and whether an unintended action occurred. Mark untested cases explicitly. [Workflow definition](.github/workflows/android.yml) · [JUnit tests](app/src/test/java/io/github/voicetrail) · [Device tests](app/src/androidTest/java/io/github/voicetrail)

原创代码采用 [MIT 许可证](LICENSE)。Go 与 dsh-go 许可声明位于 [core/licenses](core/licenses)，第三方 SDK、Gradle Wrapper 和 Android 平台适用各自的许可。

