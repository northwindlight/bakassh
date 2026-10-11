# bakassh

Android 上的 SSH 终端。终端仿真用 libvterm，渲染自己实现：字形预渲染成位图块，整行缓存，只重画脏行。

代码由 AI 编写。

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
