# bakaSSH

Android 上的 SSH 终端。终端仿真用 libvterm；日常显示使用字形位图、整行缓存与脏行追踪，
捏合缩放期间按每帧实际字号直接绘制文字并更新终端网格。

代码由 AI 编写。

## 为什么要有它

这个项目起于手机上滚动中文密集的 tmux / TUI：网格放大后，画面更新跟不上手势，
也容易遇到滚动区域、固定面板和本地选区之间的交互问题。

bakaSSH 保留 libvterm 的终端仿真和 JSch 的 SSH 传输，重点打磨 Android 上的终端显示与触摸交互。

## 当前亮点

- **面向中文密集的大网格 TUI。** 日常渲染复用字形与整行位图，追踪脏行；同为 154×134 网格时，
  本次实测滚动平均 30.35 FPS，官方 Termux 0.118.3 为 17.11 FPS。
- **逐帧真实文字缩放。** 捏合期间按实际字号重新绘制文字、同步终端行列数；松手后恢复行缓存。
- **兼顾 TUI 与本地操作。** 处理滚动区域和固定面板的脏行更新；长按使用连续文本选区与浮动复制菜单，
  选择期间保留画面快照，减少与 tmux 鼠标模式的冲突。

## 真机滚动实测

2026-10-16，OPPO PLB110 / Android 16，真实 SSH + tmux TUI，关闭输入法。
以下三组均核对了实际终端 / PTY 为 **154 列 × 134 行**，使用相同的手机端往返滑动脚本与 Perfetto FrameTimeline 统计。

| 应用 | 整段平均 FPS | 手指移动期间 FPS |
|---|---:|---:|
| **bakaSSH 0.1.2** | **30.35** | **32.11** |
| 官方 Termux 0.118.3 | 17.11 | 17.50 |
| 本地改动版 Termux 0.118.0+tmuxfix3instr | 2.10 | 2.22 |

本次 bakaSSH 的整段平均帧率约为官方 Termux 的 **1.77 倍**。真实 TUI 内容随时间变化，
这组结果用于展示当前表现；它不是固定字节流、完全相同画面的渲染器微基准，也不能单独归因于某个补丁。

在更密的 **335×307** 网格下，bakaSSH 三轮平均 13.76 FPS；额外约 44 秒、18 次滑动未观察到 ANR 或终端退出。
JuiceSSH 3.2.2 在字号 2、209×193 网格的连续滚动中约 41 秒后发生输入超时 ANR，终端退回首页；
ConnectBot 1.11.0 因本次 tmux 场景无法正常滚动，未形成有效帧率结果。两者不列入上表的同网格比较。

**性能仍有提升空间：**bakaSSH 匹配网格的手势阶段仍有约 96.31% 的呈现帧被 FrameTimeline 标为 jank，
包含应用超时与 Buffer Stuffing，并未达到稳定 60 FPS。
逐轮结果、计数方法和限制见 [滚动性能实测](docs/滚动性能实测-20261016.md)。

## 接下来的方向

- **渲染性能：**定位大网格文字栅格化与行缓存重绘成本，降低卡顿；补充固定内容回放和更长时间的稳定性测试。
- **日常使用：**重做 UI，完善密钥与账户管理、多会话切换和 SCP 文件传输。
- **TUI 交互：**正确处理屏幕点击，协调远端鼠标事件、滚动与本地文本选择。
- **文字能力：**评估替换逐字位图方案，支持 Unicode 塑形、组合字符和连字。

以上均为后续工作，详细范围见 [后续计划](docs/后续计划.md)。

## 功能

- SSH 连接（JSch），公钥与密码双认证，支持 ed25519 主机密钥
- 密钥与账户管理：多密钥生成（RSA/ECDSA）、私钥导入（OpenSSH/PEM）、公钥导出与主机独立认证绑定
- 终端仿真基于 libvterm（JNI）
- 位图字体渲染 + 行位图缓存 + 脏行追踪，按终端滚动区域复用缓存
- 中文按 2 格对齐
- 触摸滚动转滚轮事件
- 捏合时逐帧调整实际字号和行列数，松手后恢复行缓存
- 长按选择连续文本，浮动菜单复制 / 全选 / 粘贴；选择期间保留画面快照，不向 tmux 发送鼠标事件
- 扩展按键两行，可自定义，键名与 Termux 的 `extra-keys` 相同
- Pixel 风格界面：跟随系统深浅色与 Android 12+ 壁纸取色；大标题、圆角分组、独立密钥管理与全屏编辑页，支持未保存更改保护
- 系统文件选择器导入私钥，也可粘贴；自动识别加密状态，后台校验并保留原有加密保护
- 终端固定深色；工具栏支持显示 / 隐藏扩展按键，键盘按钮支持切换输入法
- `exit`、断线退回主界面；切到后台后返回保留原会话

## 快速开始

需要 JDK 21、Android SDK（platform 34 / build-tools）、NDK。

```bash
export JAVA_HOME=/path/to/jdk21
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

本地回归与本次修复说明见 [tests/host/README.md](tests/host/README.md) 和
[修复记录](docs/修复记录-20261016.md)。
逐帧文字缩放见 [0.1.2 修复记录](docs/缩放修复-0.1.2.md)。
密钥与账户管理见 [密钥与账户管理说明](docs/密钥与账户管理.md)。
当前界面与导入流程改动见 [Pixel 界面与密钥导入](docs/Pixel界面与密钥导入-20261018.md)。
下一阶段需求见 [后续计划](docs/后续计划.md)。

## 项目结构

```
app/src/main/java/dev/northwind/bakassh/
├── HostListActivity.java   主机列表、密钥、设置与编辑流程
├── EditorDialog.java       全屏编辑页与未保存更改保护
├── PrivateKeyImport.java   私钥文件读取与解析校验
├── MainActivity.java       终端界面：SSH、输入、滚动、缩放、选择复制
├── TerminalView.java       渲染器（位图字体 + 行缓存 + 脏行）
├── TerminalSelection.java  连续文本选择与复制快照
├── NativeTerm.java         libvterm 的 JNI 声明
├── SshTransport.java       JSch 传输
├── ExtraKeys.java          扩展按键配置与键位映射
├── HostStore.java / Host.java
└── KeyManager.java         本机密钥对
app/src/main/java/org/connectbot/   从 ConnectBot 引入的数据层
app/src/main/cpp/libvterm/          终端仿真（neovim 分支）
app/src/main/cpp/libvterm_jni.c     JNI 桥
app/src/main/assets/fonts/          渲染用字体
```

## 依赖与来源

| 依赖 | 用途 | 许可证 |
|---|---|---|
| [libvterm](https://github.com/neovim/libvterm)（neovim 分支） | 终端仿真 | MIT |
| [ConnectBot](https://github.com/connectbot/connectbot) v1.9.13 | 主机/公钥/known_hosts 数据层 | Apache-2.0 |
| [JSch](https://github.com/mwiede/jsch) | SSH 传输 | BSD 风格 |
| [eddsa](https://github.com/str4d/ed25519-java) | 原有 EdDSA 依赖 | MIT |
| Bouncy Castle bcprov | JSch 在 Android 上的 Ed25519 签名与 PKCS8 密钥派生 | MIT |
| Cascadia Mono（Windows Terminal 默认字体） | 拉丁字形 | SIL OFL 1.1 |
| Noto Sans Mono CJK SC | 中文与等宽网格 | SIL OFL 1.1 |
| AndroidX / Material Components | 界面 | Apache-2.0 |

## License

MIT License © 2026 Zhang Feng
