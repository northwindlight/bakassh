sshterm 中的第三方代码归属
==========================

## ConnectBot（主机/密钥管理数据层与界面）

本工程 `app/src/main/java/org/connectbot/` 与 `app/src/main/java/org/keyczar/` 下的部分源码
拷贝自 ConnectBot v1.9.13（最后一个纯 Java 版本）：

    https://github.com/connectbot/connectbot/tree/v1.9.13

    Copyright 2007-2023 Kenny Root and others
    Licensed under the Apache License, Version 2.0
    http://www.apache.org/licenses/LICENSE-2.0

选用 v1.9.13 的原因：v1.10.0 起 ConnectBot 全面转向 Kotlin + Jetpack Compose + Hilt，
而本工程是纯 Java 且受 rpi5 上 aapt2（AOSP 14，只支持 compileSdk 34）限制，
无法引入 Compose 所需的 compileSdk 35。v1.9.13 是 Java + XML 布局 + SQLite 的最后形态。

已就地修改的部分：
- `org/connectbot/bean/HostBean.java`：去掉对 `org.connectbot.transport.TransportFactory`
  的依赖（我们不用它的传输注册表），改为本地 `defaultPortFor(protocol)`。
- `org/connectbot/util/PreferenceConstants.java` 等如与上游不一致，以本仓库为准。

未被拷贝的部分：ConnectBot 的终端渲染层（termlib / TerminalView）与 SSH 传输层
（`org.connectbot.transport.*`、`org.connectbot.service.TerminalManager`）——
本工程用自己的 libvterm JNI + 位图字体渲染器（`TerminalView.java`）与 JSch 传输
（`SshTransport.java`）替代。

## 字体

- `assets/fonts/CascadiaMono.ttf` —— Windows Terminal 的默认字体
  （Cascadia Code 项目，SIL Open Font License 1.1）。
- `assets/fonts/NotoSansMonoCJKsc-Regular.otf` —— Noto Sans Mono CJK SC
  （SIL Open Font License 1.1）。

## libvterm

`app/src/main/cpp/libvterm/` —— MIT License，取自 neovim 的 libvterm 分支。
