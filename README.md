# sshterm —— Android native SSH 客户端

架构与实测基线见 [`../DESIGN.md`](../DESIGN.md)。本文件只讲**怎么构建**。

## 依赖 / 首次构建前必须做的一步

libvterm（MIT）以源码形式参与编译，但**不放进仓库**。首次构建前：

```bash
cd <本工程>/app/src/main/cpp
git clone --depth 1 git@github.com:neovim/libvterm.git libvterm
```

（rpi5 上 git 走 HTTPS+代理不通，走 SSH 通。）

## rpi5 构建（本机不做重活）

```bash
# 1) 推代码
scp -r <工作区>/sshterm/app-project rpi5:~/sshterm/

# 2) 首次：把 libvterm 放进 cpp/（若还没有）
ssh rpi5 'cd ~/sshterm/app-project/app/src/main/cpp && [ -d libvterm ] || git clone --depth 1 git@github.com:neovim/libvterm.git libvterm'

# 3) local.properties 指向 SDK
ssh rpi5 'echo sdk.dir=/home/northwind/android-sdk > ~/sshterm/app-project/local.properties'

# 4) 构建
ssh rpi5 'cd ~/sshterm/app-project && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64 PATH=/usr/lib/jvm/java-21-openjdk-arm64/bin:$PATH ~/termux-build/tools/gradle-9.2.1/bin/gradle assembleDebug --console=plain'
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 装机与取结果

```powershell
scp rpi5:~/sshterm/app-project/app/build/outputs/apk/debug/app-debug.apk D:\termux-build\sshterm-debug.apk
& "D:\Android\Sdk\platform-tools\adb.exe" -s EQMN89G6GI4XEEXK install -r D:\termux-build\sshterm-debug.apk
& "D:\Android\Sdk\platform-tools\adb.exe" -s EQMN89G6GI4XEEXK shell am start -n dev.northwind.sshterm/.MainActivity
# 结果在文件里（logcat 会被系统刷屏冲掉）：
& "D:\Android\Sdk\platform-tools\adb.exe" -s EQMN89G6GI4XEEXK pull /sdcard/Android/data/dev.northwind.sshterm/files/m1a.txt D:\termux-build\m1a.txt
```

## rpi5 上的环境约束（踩过，别改）

1. 内核 **16KB 页** → box64 按 4KB 对齐 x86_64 的 mmap → 官方 aapt2 会 `EINVAL`。
   故 `gradle.properties` 里固定 `android.aapt2FromMavenOverride=/usr/bin/aapt2`（Debian 原生 aarch64）。
2. 那个 aapt2 是 AOSP 14 → **`compileSdk` 只能 34**。
3. NDK 用 `/opt/ndk/r29`；若出现 `bin/clang` 缺失，补 `ln -s clang-22 clang`。

## 代码结构

| 文件 | 作用 |
|---|---|
| `app/src/main/cpp/libvterm_jni.c` | libvterm ↔ JNI 桥。设 `VTERM_DAMAGE_SCROLL`：滚动以 `moverect` 事件交出，damage 只覆盖新暴露区域；事件在 C 侧累积，Java 每帧各取一次（逐帧一次 JNI） |
| `app/src/main/cpp/Android.mk` | 编译 libvterm + 桥（`APP_ABI := arm64-v8a`） |
| `NativeTerm.java` | JNI 声明（含批量 `getRow`：逐格调用 JNI 会吃掉全部性能收益） |
| `TerminalView.java` | **行位图缓存渲染器**：槽位位图 + 屏幕行→槽位映射；搬运事件只轮转映射，damage 只重光栅化脏行，每帧 rows 次 `drawBitmap` |
| `MainActivity.java` | M1a 喂数据与计时（阶段 1 整屏重画、阶段 2 滚动），结果写文件 |
