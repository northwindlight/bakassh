# 主机侧回归

这些测试编译真实 JNI 桥与仓库内的 libvterm，在 Linux JVM 上运行；不模拟终端解析器。
需要 C 编译器、JDK 21 或更新版本。

```bash
bash tests/host/run.sh
```

覆盖固定 TUI 行与滚动边界、帧间脏行迁移、搬运事件溢出、分段及突发终端应答、
XTVERSION 即时回包，以及连续文本选择、反向拖选、中文双宽字符和不可变复制快照。
还覆盖逐帧浮点字号/网格、取消与指针切换、尺寸通知合并和 300 帧真实 JNI resize。
新增中文被 ASCII 局部覆盖、尾随格覆盖，以及双宽字符重叠覆盖后连续滚动 200 帧；
旧代码确实会把替换中文的 `i` 错报成两格宽，修补后必须保持单格。

SSH 回归需要 Python 3 + Paramiko、ssh-keygen 和项目使用的 JSch JAR：

```bash
JSCH_JAR=/path/to/jsch-0.2.26.jar bash tests/host/transport.sh
```

脚本生成临时密钥并在随机 loopback 端口启动合成 SSH 服务端，实际执行公钥认证、
PTY 请求、版本查询应答、键入、窗口大小变更与 EOF；不登录系统账户、不执行真实 shell。
退出时清理进程和密钥。

还会实际并发打开两条 SSH 连接，核对独立的 JNI 画面和 PTY 尺寸、无 View 时的终端应答、
关闭一条后另一条继续收发、EOF 保留最后画面，以及连接期间反复取消的生命周期。

这些回归不运行 Android View、输入法或触摸系统。APK 构建和 lint 另外运行：

```bash
gradle assembleDebug lintDebug
```

Android 真机文字缩放与手势回归（主机侧回归不代替这项验收）：

```bash
gradle assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w dev.northwind.bakassh.test/dev.northwind.bakassh.ZoomInstrumentation
```

该 instrumentation 还包含真实字体 / Canvas 像素回归：字形不依赖之前的 Paint 状态、
白色实心光标与 CJK 尾随格、会话切换时清理行缓存、View 销毁不释放服务借出的终端。
**本轮无 ADB 设备，只编译了这些测试，未执行。** 前台服务、系统通知、锁屏和厂商后台限制仍须真机验收。

私钥导入回归（真实 ssh-keygen / OpenSSL 密钥和 JSch，强制使用 Android 所用的 BC 签名实现）：

```bash
JSCH_JAR=/path/to/jsch-0.2.26.jar BC_JAR=/path/to/bcprov-jdk18on-1.79.jar bash tests/host/keys.sh
```

覆盖 OpenSSH RSA / ECDSA / Ed25519、PEM、PKCS8，加密文件与首尾含空格的口令，
公钥提取和签名验签、原始加密状态、BOM / CRLF、公钥误选及文件大小限制，共 46 项。
Android instrumentation 中的密钥生成、导入、默认切换、删除与旧文件迁移使用独立缓存目录和首选项。
编译测试 APK 不表示这些 Android 场景已在设备运行。
