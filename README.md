# bakassh

Android 上的 SSH 终端。终端仿真用 libvterm，渲染自己实现：字形预渲染成位图块，整行缓存，只重画脏行。

代码由 AI 编写。

## 为什么要有它

**Termux + tmux 滚动时不可用。** Termux 的重绘绑在 pty 读上——读一次 4KB 就重画一次；也没有脏区概念，
每次都是整屏重画；非 ASCII 字符还是逐格测量。内容以中文为主时（tmux、日志类界面）这几点叠在一起，
翻页就成了幻灯片，手指抬起来画面还在动。

**ConnectBot 同样卡，而且代价更高。** 它的终端层是 libvterm + Compose：字形宽度缓存只覆盖 Latin-1，
中文退化成逐格测量；绘制又是每帧重录整棵 Compose 子树。结果是它默认网格只有 Termux 的约 1/5，
照样卡到不能用。

两者的瓶颈都在渲染层，不在网络也不在 GPU。所以这个项目保留成熟的终端仿真（libvterm）和传输（JSch），
只把渲染换掉。

## 功能

- SSH 连接（JSch），密钥登录，支持 ed25519 主机密钥
- 终端仿真基于 libvterm（JNI）
- 位图字体渲染 + 行位图缓存 + 脏行追踪，滚动只搬位图
- 中文按 2 格对齐
- 触摸滚动转滚轮事件
- 捏合缩放字号
- 长按选择、复制 / 全选 / 粘贴
- 扩展按键两行，可自定义，键名与 Termux 的 `extra-keys` 相同
- 主机列表、导出公钥、终端边距与列数可调
- `exit`、断线、崩溃退回主界面

## 快速开始

需要 JDK 21、Android SDK（platform 34 / build-tools）、NDK。

```bash
export JAVA_HOME=/path/to/jdk21
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

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

## License

MIT License © 2026 Zhang Feng
