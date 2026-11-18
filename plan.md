# bakaSSH 终端核心与渲染重构计划（一次到位版）

2026-11-19 重写，取代 2026-10-16 的分阶段版本。原计划“先测量、再按 A/B 结果选路线”；
本版在动手前就确定目标架构，测量只用来**验收**和**定位回归**，不再用来探索方向。
现状审阅见 [性能阶段准备](docs/性能阶段准备-20261112.md)，回放工具见 [基准说明](benchmarks/README.md)。

## 结论

保留仿 fbcon 的核心设计：固定单元格网格、位置只由列号决定、按行缓存、滚动只搬行。
要改的是它没学到 fbcon 的部分，以及卡住输入的锁：

| 现状 | 问题 | 目标 |
|---|---|---|
| 字形缓存为 ARGB 位图，键含 24 位前景色，满 6000 张整体清空 | 真彩色 TUI 下缓存抖动；同一字形每种颜色都栅格化一次 | 字形只按「字素簇 + 字体变体 + 宽度」缓存，颜色在绘制时上；交给平台 Skia 的 GPU 字形图集 |
| 每个槽位一张整宽 ARGB 行位图，`rasterizeRow` 逐格重画整行 | 改一格重画整行；每次变化都要整行上传纹理 | 每行一个 `RenderNode`（显示列表），只重录变化的行；无像素上传 |
| `getRow` 只取 `chars[0]` | 组合字符、ZWJ emoji、变体选择符丢失 | 单元格携带字素簇 ID，整簇绘制，仍按格子落位 |
| 全部 JNI 方法 `static synchronized`，绘制全程持有 `NativeTerm.class` | 所有会话共用一把锁；收包、鼠标、后台会话都要等整帧绘制 | 每个终端独立锁；一次 JNI 取帧快照后立即解锁 |
| damage 只到行 | 光标附近一格变化也是整行失效 | damage 到列区间，快照只拷贝变化的格子 |
| 稳态绘制与捏合缩放是两条路径（行位图 / `drawLiveText`） | 两套代码、两套缺陷 | 同一条路径：字号变化只是让可见行重录 |

不写自定义 OpenGL / Vulkan 渲染器。用的是平台 HWUI 的显示列表与文字管线，问题在设计，不在 GPU。

## 不变量（任何改动都不能破坏）

1. **网格确定性**：每个格子的 x 只由 `col × cellW` 决定，不累加字体步进；宽度以 libvterm 给出的 `width` 为准。
2. **CJK 严格两格**：双宽字形落在两格内；字形过宽时等比缩小，**永不拉伸**填满格子。
   **需要拼接的字符不用字体画**：方块元素 U+2580–259F 已在 0.4.7 改为按整数格子边界画矩形
   （字体墨迹恰好等于小数步进，取整落位会漏出 1px 缝，Claude Code 的像素 logo 最明显）。
   新渲染器沿用同一套 `BlockElements` 边界规则，并评估把制表符 U+2500–257F、Powerline 分隔符
   U+E0B0–E0B3、旧式计算符号 U+1FB00 区段也改为程序绘制，验收用逐像素覆盖回归而非肉眼。
3. **TUI 正确性**：滚动区域、固定面板、备用屏幕、鼠标模式、bracketed paste 的现有行为与回归全部保留。
4. **覆盖层不改行列**：侧栏、状态卡片、选择菜单不触发 resize / PTY 通知。
5. **逐帧真实缩放**：捏合期间按实际字号绘制并同步网格，不用旧画面放大冒充。
6. **字节流顺序**：SSH 字节、终端应答、键入顺序不变；可以合并过时的帧，不能丢数据。

## 目标架构

```
SSH 读线程 ──pushBytes(≤16 KiB/次)──▶ 终端核心（每会话一把锁）
                                          │  libvterm + 历史 + 簇表 + 累积的 move / damage
UI 线程 Choreographer 帧 ──snapshot()──────┘  一次 JNI，拷出增量后立即解锁
   │
   ├─ 应用 move：只改「屏幕行 → 槽位」映射（沿用现有 applyMove）
   ├─ 重录变化的槽位：RenderNode.beginRecording → 背景段 / 文字段 / 装饰线
   └─ onDraw：按映射 drawRenderNode × rows，再画光标 / 选区覆盖层
RenderThread：回放显示列表，Skia 字形图集负责字形缓存与着色
```

### 1. 终端核心（native）

- **每会话独立锁。** C 侧每个终端一把 `pthread_mutex`；Java 侧每个会话一个 `TerminalCore` 持有句柄与生命周期锁，
  去掉 `NativeTerm` 的 `static synchronized`。销毁与正在进行的快照互斥，句柄失效后调用安全返回。
- **限制单次解析量。** `pushBytes` 每次最多处理 16 KiB 后释放锁，长输出时快照和键入等待不超过一个小块的解析时间。
  查询应答仍在收到时立即回包（现有 `receive` 语义不变）。
- **列区间 damage。** `cb_damage` 记录矩形；合并为每行至多若干个 `[c0, c1)` 区间（同行区间重叠或相邻即合并，
  超过 4 段退化为整行）。`moverect` 维持现有 `VTERM_DAMAGE_SCROLL` 语义，溢出时退化为全屏失效（现有行为）。
- **字素簇表。** 单码点格子的 ID 就是码点；`chars[1] != 0` 的格子按 UTF-32 序列查表，返回 `0x4000_0000 | index`。
  表按会话保存、只增不改，新条目随下一次快照下发；超过 65 536 条时清表并触发全屏重录。
  历史行本来就保存完整 `VTermScreenCell`，回滚视图同样走簇 ID。
- **东亚模糊宽度设置。** 新增「模糊宽度字符按 1 格 / 2 格」选项（默认 1，与常见远端 locale 一致），
  只影响 libvterm 的宽度判定。TUI 错位多数来自本地与远端 `wcwidth` 不一致，这是 CJK 用户需要的开关，渲染层不做补偿。

### 2. 帧快照协议

一次 JNI 调用 `int snapshot(long h, int[] buf)`，缓冲不足时返回 `-需要的长度`，调用方扩容后重试：

```
header : geometryVersion, cols, rows, scrollOffset, cursorRow, cursorCol, cursorVisible|shape,
         flags(全屏失效 / 备用屏 / 鼠标模式), nMoves, nSpans, nNewClusters
moves  : nMoves × 8 int（与现有 takeMoveOps 相同）
spans  : nSpans × [row, c0, c1]，随后紧跟该区间的格子
cell   : 4 int = clusterId, fgRGB, bgRGB, attrs | width << 16   （反显已在 native 处理）
clusters: nNewClusters × [id, len, utf32 × len]
```

- 快照只拷贝变化的格子；Java 侧为每个槽位保存整行格子数组（`int[cols × 4]`），按区间就地更新，
  录制时读这份数组，录制期间不再碰 native。
- `geometryVersion` 在 resize / 字号变化时递增，版本不符的快照整体丢弃并全屏重录，避免新旧几何混用。
- 去掉 `takeMoveOps` / `takeDamageRows` / `getRow` / `getCursor` 的逐项调用和逐帧 `getScrollOffset`。

### 3. 渲染层

- **槽位 = RenderNode。** 保留现有「屏幕行 → 槽位」映射和 `applyMove`；槽位内容从 `Bitmap` 换成 `RenderNode`。
  滚动仍然只改映射，不重录内容。
- **录制一行：**
  1. 背景：合并连续同色格子为一个 `drawRect`，默认背景色的段不画。
  2. 文字：按（字体变体，前景色）分组。API 31+ 用 `TextRunShaper` 对每个簇整形一次并缓存
     （簇 ID + 变体 → 字体与字形 ID），再用 `Canvas.drawGlyphs` 按格子坐标一次画完一组；
     API 29–30 每个非空格子一次 `drawText`，x 仍取 `col × cellW`。
     过宽字形（实测宽度 > `width × cellW`）单独 `save / scale / restore`，等比缩小，不拉伸。
  3. 下划线 / 删除线：同一分组合并为矩形段。
- **字体回退：** Cascadia Mono → Noto Sans Mono CJK SC → 系统回退（整形结果给出实际字体）。
  CJK 字形由 Skia 图集按字号缓存，颜色在绘制时上，不再有「每种颜色一张位图」。
- **光标与选区：** 光标、选区高亮、选择把手是独立覆盖层，每帧直接画，不进入行显示列表。
- **选择期间冻结：** 进入选择时停止消费快照（native 继续累积 move / damage），显示的仍是冻结时的 RenderNode；
  退出选择时一次应用。替代现在整屏 ARGB 快照位图，复制文本沿用 `TerminalSelection` 的不可变快照。
- **缩放合一：** 捏合时每帧更新字号与网格，`geometryVersion` 递增，可见行全部重录。不再有 `drawLiveText` 分支；
  松手后无需「恢复行缓存」，因为录制结果就是缓存。

### 4. 线程与背压

- 快照在 UI 线程的 Choreographer 帧回调里取（保持 `postInvalidateOnAnimation`），持锁时间只有拷贝增量。
- 录制在 UI 线程，回放在 RenderThread。录制量与变化行数成正比，固定面板不变的行零成本。
- UI 落后时 native 自然合并 damage，不排队、不丢字节；后台会话只累积，不录制。

### 5. IO 路径（顺带核对）

- 核对 JSch socket 是否开启 `TCP_NODELAY`，未开则开启，键入延迟不应受 Nagle 影响。
- 读线程 64 KiB 缓冲保留，交给核心时按 16 KiB 分块。
- 键入走现有单线程写执行器，顺序不变。

## 需要你决定

- **minSdk 提到 29（Android 10）？** `RenderNode` 公开 API 从 29 开始。提到 29 只需维护一条渲染路径（推荐）；
  保留 24 则 24–28 要继续维护现有行位图路径，代码量和测试面翻倍。
- **A/B 开关保留多久？** 建议验收版本保留隐藏开关（关于页长按版本号切换旧 / 新渲染器），
  同一台手机现场对比；验收通过后的下一个版本删除旧路径。

## 一次到位的实施顺序

同一个分支内按提交推进，每一步都保持主机回归、回放与 CI 截图全绿；最后一次合并发布。

1. **计时与追踪先行。** `Trace.beginSection` 标注 snapshot / 应用 move / 录制 / onDraw；
   `FrameMetrics` 采集 UI 与 RenderThread 耗时；回放基准输出 P50 / P95 / P99 与长帧计数。用当前实现录一份基线。
2. **native：每会话锁 + 分块解析。** Java 改为 `TerminalCore`。主机回归新增并发：读线程持续灌入时快照等待上界。
3. **native：列区间 damage + 快照协议 + 簇表。** 主机回归新增：区间合并、溢出退化、簇 ID 稳定性、
   组合字符 / ZWJ / VS16 往返、缓冲扩容重试、`geometryVersion` 丢弃。
4. **Java：行格子模型 + RenderNode 录制。** 旧渲染器保留在开关后面；回放逐帧比较新旧画面（见验收）。
5. **缩放合一、选择冻结、删除 `drawLiveText` 与快照位图。** 现有缩放 / 选择 / 光标回归全部迁移到新路径。
6. **模糊宽度设置与 TCP_NODELAY 核对。**
7. **真机验收**（OPPO PLB110 / Android 16），通过后发布；下一个版本删除旧渲染器与 A/B 开关。

## 验收

**正确性（必须全部通过）**

- 现有 57 项主机回归与 15 项固定回放（CI 共 72 项 PASS）、`ZoomInstrumentation` 默认与 `security` 模式不回退。
- 新旧渲染器在全部回放上逐帧比较：单元格内容、颜色、属性、光标位置一致；
  像素比较只允许字形边缘抗锯齿差异，任何格子的墨迹不得越出「格子 + 留白」范围（拼缝回归继续有效）。
- 组合字符、ZWJ emoji、VS16、模糊宽度两种设置下，tmux 分隔线与表格边框不错位。
- 选择期间远端持续输出，退出选择后画面与不选择时一致；捏合、取消、回滚视图与固定面板无残影。

**性能（同设备、同网格、同回放，与第 1 步基线比较）**

- 154×134 滚动回放：UI 线程每帧工作 P95 ≤ 8 ms，完整帧 P95 ≤ 16.7 ms，长帧比例显著低于基线。
- 中文密集整屏重写：完整帧 P95 ≤ 16.7 ms；字形缓存命中率不随颜色数下降。
- 真彩色负载：内存不随颜色种类增长；无周期性整体清缓存。
- 后台会话持续输出时，前台键入回显延迟不受影响（与无后台会话相比 P95 增量 ≤ 2 ms）。
- 335×307：无 ANR、无持续积压、内存平稳；不设 60 FPS 门槛。

以上性能数字是验收目标，需要真机 Perfetto / FrameMetrics 数据确认；
CI 模拟器只看趋势，不作为达标依据。

## 删除清单

- ARGB 字形缓存（`glyphCache` / `glyphFor`）与 6000 张清空逻辑
- 槽位行位图、`rasterizeRow`、`ROWDBG` 调试埋点
- `drawLiveText` 与松手后的行缓存重建
- 整屏选择快照位图
- `NativeTerm` 的 `static synchronized` 与逐项取帧方法
- 仓库根目录与 `libvterm/` 下误提交的 `undefined` 文件（旧 README 副本）

## 风险与对策

| 风险 | 对策 |
|---|---|
| `drawGlyphs` 与 `drawText` 在不同 API 上字形位置有细微差异 | 两条路径共用同一套逐格坐标与过宽缩放规则，回放像素比较分别跑 API 29 与 34 模拟器 |
| 极端网格下每帧绘制操作数很多 | 背景与文字按段合并；只重录变化的行；RenderThread 时间单独计量，超标时再评估按段合批 |
| 簇表无限增长 | 上限 65 536，超限清表并全屏重录，主机回归覆盖 |
| 每会话锁引入新的竞态 | `TerminalCore` 统一生命周期；销毁与快照互斥的回归在主机侧跑 ThreadSanitizer |
| 一次性改动面大 | 旧渲染器保留到验收结束；每个提交都保持回归与截图全绿 |
