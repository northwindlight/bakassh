# bakassh

Android 上的 SSH 终端。用 libvterm 做终端仿真，渲染是自己写的：字形预渲染成位图，一行一行缓存，只重画变了的行。目标是**渲染开销不随格数涨**。

代码主要由 AI 写，我负责方向、真机实测和排错。

## 功能

- SSH 连接（JSch），密钥登录，支持 ed25519 主机密钥
- 终端仿真用 libvterm，不自己写 VT 解析
- 位图字体渲染：字形预渲染成位图，位置只按列号算
- 行位图缓存 + 脏行追踪：滚动只搬位图，不重新光栅化
- 中文按 2 格对齐，不变形（字体本身的 CJK 步进就是拉丁的 2 倍）
- 触摸滚动转成滚轮事件，远端 tmux 开 `mouse on` 就能滚
- 捏合缩放字号，松手才重排并同步远端 pty
- 长按选择（带可拖动把手）、复制、全选、粘贴
- 扩展按键两行，可自己改（键名跟 Termux 的 `extra-keys` 一样）
- 主机列表、导出公钥、终端边距与列数可调
- `exit`、断线、崩溃都会退回主界面

## 快速开始

需要 JDK 21、Android SDK（platform 34 / build-tools）、NDK。

```bash
export JAVA_HOME=/path/to/jdk21
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

第一次用：主界面点「＋ 新建主机」，填主机、用户名、端口；「连上后自动执行」可以填 `tmux attach -t zg` 这类命令。想免密就把「公钥」里的内容加到服务器 `~/.ssh/authorized_keys`。

在 aarch64 / 16KB 页内核上构建时，官方 aapt2（x86_64 二进制）在 box64 下会 `mmap EINVAL`，换发行版自带的原生 aapt2：

```properties
android.aapt2FromMavenOverride=/usr/bin/aapt2
```

代价是 `compileSdk` 最高只能到 34。另外 `targetSdk` 别低于 30，否则系统和 Play Protect 会当作旧版应用拦下来。

## 项目结构

```
app/src/main/java/dev/northwind/bakassh/
├── HostListActivity.java   主机列表、设置、扩展按键定制
├── MainActivity.java       终端界面：SSH、输入、滚动、缩放、选择复制
├── TerminalView.java       渲染器（位图字体 + 行缓存 + 脏行）
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

## 性能

真机实测（天玑9400 / 91Hz，内容以中文为主）：

| 指标 | 数值 |
|---|---|
| 单格光栅化 | 1.2 µs |
| 整屏重绘（157×84 格） | 约 13 ms |
| 滚动 | 0.42 ms/帧 |

同样格数下 Termux 整屏重绘约 33 ms。

## 依赖与来源

| 依赖 | 用途 | 许可证 |
|---|---|---|
| [libvterm](https://github.com/neovim/libvterm)（neovim 分支） | 终端仿真 | MIT |
| [ConnectBot](https://github.com/connectbot/connectbot) v1.9.13 | 主机/公钥/known_hosts 数据层、选择逻辑 | Apache-2.0 |
| [JSch](https://github.com/mwiede/jsch) | SSH 传输 | BSD 风格 |
| [eddsa](https://github.com/str4d/ed25519-java) | ed25519 主机密钥校验 | MIT |
| Cascadia Mono（Windows Terminal 默认字体） | 拉丁字形 | SIL OFL 1.1 |
| Noto Sans Mono CJK SC | 中文与等宽网格 | SIL OFL 1.1 |
| AndroidX / Material Components | 界面 | Apache-2.0 |

ConnectBot 用的是 v1.9.13，它是最后一个纯 Java + XML 的版本；v1.10 起改成 Kotlin + Compose + Hilt，要 compileSdk 35。改动说明见 [NOTICE-third-party.md](NOTICE-third-party.md)。

## License

MIT License © 2026 Zhang Feng
