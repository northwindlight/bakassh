# UI：Material 3 / Pixel 风格

> 本文记录 2026-10-17 的历史实现。当前页面结构与导入流程见 [2026-10-18 改动](Pixel界面与密钥导入-20261018.md)。

## 设计原则

- **跟随系统深浅色**：主界面用 `Theme.Material3.DayNight`，浅色调色板在 `values/colors.xml`，
  深色在 `values-night/colors.xml`。不再有任何「永远是黑」的硬编码背景。
- **壁纸取色（Monet）**：主机列表在 `onCreate` 里调用
  `DynamicColors.applyToActivityIfAvailable()`，Android 12+ 上自动继承壁纸色调。
  终端界面不参与取色，固定深色。
- **一律用主题属性**：布局和 drawable 里写 `?attr/colorSurface`、`?attr/colorOnSurface`
  这类属性，不直接引用调色板资源。这样 DayNight、Monet 和终端独立主题三套场景都不会串色。
- **标准 Material 3 组件**：
  - 按钮高度 40dp、圆角由组件默认提供；对话框底部是右对齐的文字按钮 / 主操作按钮，
    不再使用整行铺满的 50dp 大药丸。
  - 文本输入框使用 M3 默认 filled 样式（顶部 4dp 圆角 + 底部指示线）。
  - 认证方式用两端连接的分段按钮（公钥 / 密码）。
  - 主机列表为 16dp 圆角卡片、圆形图标、Extended FAB。
  - 所有新建/编辑/密钥/设置面板使用 28dp 顶角圆角 BottomSheet。

## 关键实现点

- `TerminalView` 必须同时提供 `(Context, AttributeSet)` 构造函数，否则 XML 膨胀直接
  `InflateException`。这是 0.1.4 打开终端必崩的根因。
- 主机卡片自身是 clickable 的 `MaterialCardView`，会吃掉 `ListView` 的 `OnItemClick`；
  因此点击/长按在 `getView` 里显式绑到行上。
- 终端是全屏 Activity：根布局按 `displayCutout` 与 `navigationBars` inset 加 padding，
  顶栏不会钻进挖孔，扩展按键不会被手势条盖住。
- 弹窗统一 `SOFT_INPUT_ADJUST_RESIZE`，键盘弹出时表单不被遮挡。

## 验收

- `assembleDebug` / `lintDebug` 通过。
- `tests/host/run.sh` 19 项 JNI 回归全部 PASS。
- 真机验证：打开主机列表 → 新建主机 → 连接终端 → 扩展按键 / 复制 / 缩放。
