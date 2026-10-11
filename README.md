# bakassh

一个**只追求性能**的 Android SSH 终端。名字取自「バカ ssh」——笨蛋级别的实现，别的先不管。

## 为什么要写它

手机上用 Termux + tmux，滚动时卡到不可用（实测整屏中文重绘 **33~34ms**、tmux copy-mode 只有 3 FPS、松手停不住）。
根因不是 GPU，是终端渲染太贵：重绘被 4KB pty 读分片绑架、零脏区追踪、非 ASCII 字符逐格 `measureText`。

换 ConnectBot 也一样卡：它的 **默认网格只有 Termux 的约 1/5，却更卡** ——
termlib 的字形宽度缓存只覆盖 Latin-1（中文逐格测量），Compose 每帧重录整棵子树。

## 怎么做的

| 层 | 选型 |
|---|---|
| SSH | JSch（mwiede 维护分支），纯 Java |
| 终端仿真 | **libvterm**（C99 + JNI），不自己写仿真器 |
| 渲染 | **位图字体**：字形预渲染成位图块，位置**只由列号算出**，永不累加字体步进 |
| 缓存 | 行位图缓存 + 脏行追踪；滚动只搬位图、不重新光栅化 |
| 选择复制 | ConnectBot 的 `SelectionArea`（两段式状态机 + 可拖动把手） |
| 扩展按键 | 学 Termux 的 `extra-keys`，两行可自定义 |
| 界面 | Material 3（Pixel 风格） |

**实测（PLB110 / 天玑9400 / 91Hz）**：热缓存下单格 **1.2µs**；157×84 整屏重绘 **~13ms**（同规模 Termux ~33ms）；
滚动 **0.42ms/帧 @91fps**。设计目标是「成本与格数无关」，中端机也成立。

## 构建

需要 Android SDK（build-tools、platform 34）与 NDK。

```bash
export JAVA_HOME=/path/to/jdk21
./gradlew assembleDebug          # 或 gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**踩过的坑**（如果你也在 16KB 页内核 / aarch64 上构建）：

* 官方 aapt2 是 x86_64 二进制，在 16KB 页内核 + box64 下 `mmap` 会 `EINVAL`
  → 用发行版自带的原生 aapt2，并在 `gradle.properties` 里加
  `android.aapt2FromMavenOverride=/usr/bin/aapt2`；代价是 **compileSdk 只能到 34**。
* `targetSdk` 必须 ≥ 30 左右，否则系统和 Play Protect 会把 app 当「专为旧版 Android 打造」拦截。

## 来源与许可

本项目采用 **MIT**（见 `LICENSE`），但**包含**以下第三方代码/资源，各自遵循其原许可证：

| 来源 | 用在哪 | 许可证 |
|---|---|---|
| **[ConnectBot](https://github.com/connectbot/connectbot)** v1.9.13 | `app/src/main/java/org/connectbot/**`：主机/公钥/known_hosts 数据层、`SelectionArea` 选择逻辑 | Apache-2.0 |
| **[libvterm](https://github.com/neovim/libvterm)**（neovim 分支） | `app/src/main/cpp/libvterm/**`：终端仿真 | MIT |
| **[JSch](https://github.com/mwiede/jsch)** | SSH 传输 | BSD 风格 |
| **[eddsa](https://github.com/str4d/ed25519-java)** | ed25519 主机密钥校验 | MIT |
| **Cascadia Mono**（Windows Terminal 的默认字体） | `assets/fonts/CascadiaMono.ttf` | SIL OFL 1.1 |
| **Noto Sans Mono CJK SC** | `assets/fonts/NotoSansMonoCJKsc-Regular.otf` | SIL OFL 1.1 |
| AndroidX / Material Components | 界面 | Apache-2.0 |

细节与就地修改说明见 [`NOTICE-third-party.md`](NOTICE-third-party.md)。

> 说明：ConnectBot 取的是 **v1.9.13**（最后一个纯 Java + XML 版本）。
> v1.10 起转向 Kotlin + Jetpack Compose + Hilt，需要 compileSdk 35，与本项目的构建约束冲突。
