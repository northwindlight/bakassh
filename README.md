# bakassh

**一个只追求性能的 Android SSH 终端。**

终端仿真用 libvterm，渲染自己写：字形预渲染成位图块，整行缓存，只重画脏行。

代码由 AI 编写。

## 为什么要写

Termux + tmux 在手机上滚动是没法用的：整屏中文重绘 33ms 起步，copy-mode 只有几帧，松手还停不住。
ConnectBot 一样卡，而且它的默认网格还只有 Termux 的 1/5。问题不在 GPU —— 是重绘被 4KB pty 读分片绑架、
没有脏区追踪、非 ASCII 字符逐格测量。这个项目就把这层重写掉。

## 功能

- **SSH** — JSch，密钥登录，支持 ed25519 主机密钥；`exit`、断线、崩溃都退回主界面
- **终端仿真** — libvterm（JNI）
- **渲染** — 位图字体：字形预渲染成位图块，位置只按列号算，不累加字体步进；行位图缓存 + 脏行追踪，滚动只搬位图
- **中文对齐** — 用 CJK 步进恰好是拉丁两倍的等宽字体，中文占 2 格，不做横向缩放
- **滚动** — 触摸转成滚轮事件，远端 tmux 开 `mouse on` 就能滚
- **缩放** — 捏合调字号，手势期间实时预览，松手才重排并同步远端 pty
- **选择复制** — 长按选择、带可拖动把手，复制 / 全选 / 粘贴
- **按键栏** — 两行，可自己改，键名与 Termux 的 `extra-keys` 相同
- **主界面** — 主机列表、导出公钥、终端边距与列数可调

## 快速开始

需要 JDK 21、Android SDK（platform 34 / build-tools）、NDK。

```bash
export JAVA_HOME=/path/to/jdk21
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

第一次用：主界面点「＋ 新建主机」，填主机、用户名、端口；「连上后自动执行」可填 `tmux attach -t zg` 这类命令。
免密登录就把「公钥」里的内容加到服务器 `~/.ssh/authorized_keys`。

在 aarch64 / 16KB 页内核上构建时，官方 aapt2 是 x86_64 二进制，box64 下 `mmap` 会 `EINVAL`，换成发行版自带的原生 aapt2：

```properties
android.aapt2FromMavenOverride=/usr/bin/aapt2
```

代价是 `compileSdk` 最高只能到 34。另外 `targetSdk` 别低于 30，否则系统和 Play Protect 会当成旧版应用拦掉。

## 怎么自己看性能

App 每秒往 logcat 打一行：

```
RENDER fps=… avg_frame_ms=… avg_raster_ms=… frames=… rasterized_rows=… blits=…
```

`avg_raster_ms` 是光栅化耗时，`rasterized_rows` 是这一秒真正重画了多少行。

```bash
adb logcat -s sshterm-view | grep RENDER
```

**这里不给绝对数字。** 同一份代码在不同机型、不同网格、不同内容下差很多，没有测法和条件的数字没有意义。

设计目标只有一个：渲染开销不随格数增长。

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

ConnectBot 取 v1.9.13 —— 最后一个纯 Java + XML 的版本；v1.10 起改成 Kotlin + Compose + Hilt，要 compileSdk 35。
就地修改见 [NOTICE-third-party.md](NOTICE-third-party.md)。

## License

MIT License © 2026 Zhang Feng
