# 主机侧回归

这些测试编译真实 JNI 桥与仓库内的 libvterm，在 Linux JVM 上运行；不模拟终端解析器。
需要 C 编译器、JDK 21 或更新版本。

```bash
bash tests/host/run.sh
```

覆盖固定 TUI 行与滚动边界、帧间脏行迁移、搬运事件溢出、分段及突发终端应答、
XTVERSION 即时回包，以及连续文本选择、反向拖选、中文双宽字符和不可变复制快照。

SSH 回归需要 Python 3 + Paramiko、ssh-keygen 和项目使用的 JSch JAR：

```bash
JSCH_JAR=/path/to/jsch-0.2.26.jar bash tests/host/transport.sh
```

脚本生成临时密钥并在随机 loopback 端口启动合成 SSH 服务端，实际执行公钥认证、
PTY 请求、版本查询应答、键入、窗口大小变更与 EOF；不登录系统账户、不执行真实 shell。
退出时清理进程和密钥。

这些回归不运行 Android View、输入法或触摸系统。APK 构建和 lint 另外运行：

```bash
gradle assembleDebug lintDebug
```
