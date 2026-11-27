# 在手机上翻远端历史：六款 Android SSH 客户端实测

2026-11-27，在同一台 OPPO PLB110（Android 17，物理屏 1256×2760）上依次连接同一台 Raspberry Pi 5 Model B Rev 1.1，运行 tmux 3.5a。测试包含可重建的中文战国历史、现场 `zg-mixed`，以及 Claude Code 和 Codex CLI 各一个长会话的分支。

**bakaSSH 0.4.8 的优势是手指滑动能翻到远端正文。** 在 209×193 PTY 上，四项场景都观察到了历史位移。固定中文历史约 59 FPS，Termux 约 23 FPS。**流畅度标杆是 Termius 8.0.2**：它也通过四项位移检查，而且在战国、Claude、Codex 三个现场里呈现得更密、更均匀。

## 同网格结果

下表为三轮共九次滑动的整段平均 FPS，含每次抬手后的 150 ms。前四款按终端 Activity 的 FrameTimeline 计数，后两款按独立终端 SurfaceView 的 SurfaceFlinger BLAST 呈现计数。两种口径在下节用显示时点交叉复核。固定历史窗口为 200×160，现场和长会话内容区为 209×192。

| 客户端 | 固定历史 | 战国现场 | Claude 长会话 | Codex 长会话 |
| --- | ---: | ---: | ---: | ---: |
| bakaSSH 0.4.8 | 58.87 | 58.69 | 56.01 | 61.59 |
| Termux 0.118.3 | 23.21 | 0.75，独立复测 0.60 | 24.08 | 14.06 |
| JuiceSSH 3.2.2 | 6.10，后两轮无呈现帧 | 原生崩溃，另一次 ANR | 40.10 | ANR |
| ConnectBot 1.11.0 | 无触摸位移 | 无触摸位移 | 未检出位移 | 未检出位移 |
| Termius 8.0.2 | 80.07 | 85.55 | 87.26 | 88.93 |
| Termigo 1.3.0 | 未证实远端位移 | 未证实远端位移 | 未证实远端位移 | 未证实远端位移 |

ConnectBot 的翻页键可移动 Claude 正文，Codex 也出现整页更替，两者均可输入和退格。Termigo 经原生会话选择器进入战国、再切换 vscroll，两次图层更新分别为 78.22 和 77.49 FPS，远端位置均未变化。若产品提供本地回看，它与改变 tmux 或 TUI 的远端阅读位置是两种功能。

## bakaSSH 有 60 FPS 上限吗？

只取手指移动区间，以 bakaSSH 应用帧的 display token 找到显示帧结束时点，对照 Termius 终端图层的实际呈现时点。每次滑动单独算帧间隔，再合并分位数。P95 表示 95% 的相邻呈现间隔在该值以内。

| 战国现场，209×193 | 移动期间 FPS | 中位帧间隔 | P95 帧间隔 |
| --- | ---: | ---: | ---: |
| bakaSSH | 64.06 | 10.96 ms | 21.95 ms |
| Termius | 85.08 | 10.95 ms | 11.19 ms |

两者常见的呈现间隔都在 11 ms 左右。间隔较长时，bakaSSH 达到约 22 ms，滑动中更容易感觉到一次停顿。bakaSSH 在 335×282 的 Claude 长会话里移动阶段达到 72.04 FPS，足以排除这组测试中存在统一的 60 FPS 硬上限。轨迹没有把差距进一步定位到 SSH 输入、终端解析、绘制或系统调度中的某一步。逐项显示计数见 [display-results.json](display-results.json)，回算见 [display_frames.py](display_frames.py)。

## 协议、稳定性与字号

- **直接 SSH 应答。** rpi5 在相同的 200×160 区域写入固定字节，每帧末尾查询光标位置。32 行滚动 / 160 行重写的中位耗时分别为 bakaSSH 75.2 / 74.5 ms，Termius 64.4 / 73.9 ms。整区重写的应答耗时很接近，但实际滑动仍有差距。Termigo 的五项光标回报均与预期不符，未计其吞吐。
- **JuiceSSH 稳定性。** 在战国现场计划连续滑动 300 秒，首次触摸后 25.95 秒、第 27 次手势进行时收到 SIGTRAP。调用栈经过 Android 文字布局缓存。另两次输入 ANR 都记录到主线程在发送鼠标输入时等待写入队列。原生崩溃与两次 ANR 分开计数。
- **大网格与可读性。** 209 列时，同屏宽每列只有 80 列时约 38% 的宽度。阅读样张另取 Termius 57×51、81×73 和 Termigo 77×70。同日按要求关闭了 bakaSSH 的连接，缺少它在阅读字号下的匹配对照。335×282 仅测 bakaSSH，Codex 这组还保留了另一个后台 Termux 客户端。
- **使用边界。** 这是单机、单系统版本、前台触摸的实测。各客户端为达到同一 PTY 使用了不同字号或逻辑分辨率。合成历史适合重复比较，现场会话、网络和动态刷新率会随时间变化。

## 首次使用印象

设备已保存 rpi5，点主机卡片即可进入配置好的战国会话，再用手指翻回上一段正文。这个路径是 bakaSSH 最值得保留的地方：熟悉 SSH 的人可以直接按原有习惯工作，不必先学习客户端自己的 tmux 菜单。

全新安装仅核对了界面实现。空列表已经写有“还没有主机”和“点右下角添加一台”，添加页也能跟随默认密钥。一次更值得做的新手验证，是让第一次打开应用的人自行连通主机，再返回列表，问他此时 SSH 是否还在线。在本轮切换客户端时，需要额外核对后台连接和远端窗口尺寸。让人能一眼认出“仍在连接”并找到“断开”，会直接改善这个使用环节。

## 明细与复现

现代客户端的大网格组均将逻辑密度设为 180 dpi，Termius 逻辑分辨率为 1817×2933，Termigo 为 1072×2200。阅读组均使用物理分辨率，Termius 81 列为 400 dpi，Termigo 77 列为 560 dpi。跨设备重跑请以远端实际行列数校准。

- [传统客户端与 bakaSSH 明细](legacy-clients.md)含逐轮 FPS、帧间长尾、协议应答、长会话正文位移、PSS 快照及 JuiceSSH 故障时间线。
- [Termius、Termigo 明细和中文样张](modern-clients.md)含阅读字号、原生 tmux 入口、远端位置和终端图层计数。
- [可复现步骤](reproduce.txt)和[结果数据](results.json)提供固定负载、手势及统计。原始聊天内容、登录凭据、设备全量轨迹不随公开数据分发。
- 现代客户端在前台连接后，先用 `adb shell dumpsys SurfaceFlinger --list` 选中终端的 BLAST 图层。用 `python capture_surface.py LAYER run1 PACKAGE TTY REMOTE_DIR --targets zperf-fixed zg-mixed zperf-long-claude zperf-long-codex` 采样，再在控制端运行 `python display_frames.py run1.json REMOTE_DIR/zgperf-run1.pftrace --surface run1-sf.json`。使用 bakaSSH 同一命令回算时省略 `--surface` 参数。占位符换为本机取值，带空格的图层名需加引号。
- [10 月 16 日旧版实测](../../docs/滚动性能实测-20261016.md)和[11 月 12 日性能阶段准备](../../docs/性能阶段准备-20261112.md)保留原有条件，可用于追溯方法变化。
