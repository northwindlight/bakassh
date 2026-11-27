# 固定终端回放与性能基线

实际 SSH 客户端的端到端对照见 [2026-11-27 Android 真机滚动报告](android-scroll-20261127/README.md)。

当前目标是建立可复核基线。这里的 CPU 基准不测屏幕 FPS、GPU 上传或真实 SSH 响应时间。
完整渲染优化与真机呈现验收见 [plan.md](../plan.md)。

## 五种固定负载

| 名称 | 内容 | 主要用途 |
| --- | --- | --- |
| scroll | 中文 / ASCII 混合行，整屏上滚 | 行位图复用、历史写入成本 |
| rewrite | 每帧重写所有行，帧号变化 | 高变化率重建成本 |
| unchanged | 每帧重写完全相同的文字 / 样式 | 最终内容去重的潜在收益 |
| region | 固定头尾，只滚动中间区域 | 局部滚动与缓存正确性 |
| styles | 文字相同，颜色 / 背景 / 属性切换 | 防止仅按文字判断未变化 |

字节源为 `shared/dev/northwind/bakassh/TerminalWorkload.java`，Android 和主机共用，不包含真实会话资料。
主机检查每种负载的语义、最终帧号、固定行，以及整包 / 切包输入后的相同最终单元格。

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64 bash tests/host/replay.sh > /tmp/replay-12.jsonl
```

默认 12 帧、三种网格，共 15 项。JSONL 包含输入字节 SHA-256 与最终单元格 SHA-256。
帧数可以显式指定；与下面 Android 默认参数比较时应使用 `bash tests/host/replay.sh 90`。

## Android 软件 Canvas 基准

在没有活动 SSH 会话的测试进程中执行。不会读取保存的主机、启动 SSH 或改写用户配置。
初始画面绘制一次后进行 warmup，再记录指定帧数；当前不覆盖冷启动成本。

```bash
gradle :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e mode local-replay -e columns 154 -e rows 134 -e width 1186 -e warmup 30 -e frames 60 dev.northwind.bakassh.test/dev.northwind.bakassh.ZoomInstrumentation
adb exec-out run-as dev.northwind.bakassh cat cache/local-replay.json > /tmp/android-replay.json
```

默认运行五种负载；`-e scenario scroll` 可单独运行一项。`columns`、`rows`、`width` 必须形成要求的实际网格，
不满足最小字号限制就报错，不自动减少列数。335×307 离屏压力测试可使用足够宽的 `width`，并记录实际像素大小。

报告包含：

- 实际网格、像素宽高、字号与单元格尺寸，warmup / 测量帧数。
- 解析与软件绘制同步耗时的平均值及 P50 / P95 / P99（`parse_elapsed_ms` / `draw_software_elapsed_ms`）。
- 测量区间内重建行数、绘制行数，输入字节 / 最终单元格 / 最终 ARGB 像素校验值。
- `presentation_measured=false`、`gpu_upload_measured=false`、`ssh_used=false`。

输入 SHA、总回放帧数及网格相同时，最终单元格 SHA 应与主机结果一致；同设备 A/B 比较还需核对字号、
视口和像素 SHA。最终像素摘要的计算放在计时区间外。画面变化需要解释，不能只接受耗时下降。

计时使用主线程上的 `System.nanoTime()`，包含调度抢占；这是同步软件路径的墙钟耗时，
并非精确的线程 CPU 使用时间。输入构造、主线程派发和结果摘要不计入。

Perfetto 可用 `bakaSSH.replay.parse` / `bakaSSH.replay.draw-software` 区间定位 CPU 工作；
真实显示更新率仍要在带硬件呈现的场景中统计 FrameTimeline，不能取 `1000 / 软件绘制毫秒数` 充当 FPS。
`TerminalView` 旧 `RENDER fps` 日志记录的是绘制调用频率，也不等于有效画面呈现率。

## 当前验证边界

2026-11-12：主机 15 项回放检查通过；90 帧回放另在 UBSan 下全部通过，未报告未定义行为。
Android 入口已加入测试 APK 并通过编译。
本轮没有 ADB 设备，尚未采集 Android 软件基线或重新测量屏幕 FPS。
旧现场数据 30.35 / 32.11 FPS 来自 0.1.2，不是当前版本实测。
