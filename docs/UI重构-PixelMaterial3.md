# UI 重构：Pixel / Material 3 (Material You) 风格化设计

## 目标与设计语言

按用户需求将 bakaSSH 整体用户界面进行现代化重构，“越像新版 Pixel 越好”：
- **设计规范**：全面对齐 Google Pixel（Pixel 8 / Pixel 9，Android 14 / Android 15）的 Material Design 3 (Material You / M3) 设计语言。
- **色彩系统**：
  - 应用层接入 `DynamicColors.applyToActivitiesIfAvailable(this)`，在 Pixel / Android 12+ 设备上自动继承当前壁纸的 Monet 动态色彩提取。
  - 配置基于 Monet 色相精细调配的暗色多级色调表面（Surface、SurfaceContainer、SurfaceContainerHigh、SurfaceContainerHighest），告别传统生硬纯黑/高对比灰白。
  - 核心操作高亮采用 Google Blue（Primary / PrimaryContainer）与 Pixel Mint 绿（Tertiary / 状态指示）。
- **组件形态**：
  - 全面采用 **大圆角卡片**（16dp ~ 20dp CardView）、**药丸胶囊操作条 / Chips**（圆角 14dp~18dp）与 **Extended Floating Action Button（FAB）**。
  - 全面用 **Modal Bottom Sheet（底部抽屉，28dp 顶角圆角 + 顶部 Drag Handle）** 替代老式居中普通对话框。

## 详细改动

### 1. 主机列表界面（HostListActivity）
- **顶栏**：
  - Pixel 规范的大标题顶栏（30sp Medium/Bold），配有就绪状态呼吸指示绿点。
  - 顶部快速操作 Chip 栏：包含带矢量图标的 `[密钥管理]`、`[扩展按键]`、`[设置]` Action Chips。
- **主机卡片（item_host_card）**：
  - 20dp 圆角 `MaterialCardView`，柔和微描边与水波纹。
  - 左侧 44dp 圆形图标头像（Avatar），居中展示主题着色服务器图标。
  - 层次分明的双层排版：加粗标题 + 等宽微字号连接端点（`user@host:port`）。
  - 下方带有圆角胶囊 Chip：认证方式（公钥密钥名称/密码认证）及自启命令胶囊。
  - 右侧提供轻触操作入口，支持长按或点击呼出操作抽屉（立即连接、编辑配置、复制 SSH 命令、删除主机）。
- **空状态（Empty State）**：
  - 当无主机时，展示圆角图标、友好标题与说明。
- **悬浮按钮**：
  - 屏幕右下角配备 Extended FAB（"＋ 新建主机"），圆角 18dp，主题色高亮。

### 2. 交互抽屉与对话框（Bottom Sheets）
- **新建 / 编辑主机**：
  - 28dp 顶部圆角 BottomSheetDialog，内置顶部药丸拖动手柄。
  - 采用现代带圆角充填样式的 `TextInputLayout`。
  - 认证方式使用 `MaterialButtonToggleGroup` 药丸按钮切换（公钥认证 / 密码认证）。
  - 密码输入框内置显隐明密文眼睛图标切换。
  - 底部 FilledButton（保存配置）+ TonalButton（取消）药丸按钮。
- **密钥管理**：
  - 底部抽屉工作台，顶部提供快捷 "＋ 生成密钥"（Filled Tonal）与 "导入私钥"（Outlined）药丸按钮。
  - 密钥卡片列表：16dp 圆角卡片，展示算法、指纹、金黄色默认密钥徽标，支持查看/复制公钥、设为默认、重命名、删除。
  - 生成密钥对话框：支持 RSA-3072/2048/4096、ECDSA-256/384 算法选择、口令与注释。
  - 导入私钥对话框：支持 OpenSSH/PEM 多行私钥内容粘贴与口令解密。
- **设置与扩展按键**：
  - 统一重构成 28dp 圆角 BottomSheetDialog。

### 3. 终端操作界面（MainActivity）
- **顶部轻量悬浮药丸状态栏**：
  - 顶部通透悬浮的胶囊状态栏（高度 44dp，圆角 8dp，石墨深色半透明背景），显示绿色连通指示点、当前主机名、当前列数（如 `150列`）。
  - 右侧提供快捷操作按钮：`粘贴`、`键盘开关`、`断开退出`。
- **底部扩展按键栏（ExtraKeys）**：
  - 按键全面重构为 Material 3 Tonal 药丸按键（高度 44dp，圆角 12dp）。
  - **粘滞修饰键高亮反馈**：激活 `CTRL`、`ALT`、`SHIFT` 时，按键背景瞬间转为 Google 蓝（PrimaryContainer: `#004A77`），文字转为高对比淡蓝（`#C2E7FF`），提供清晰直观的 Pixel 式交互质感。
- **动态认证弹窗**：
  - 密码认证及私钥解锁提示统一升级为 Material 3 大圆角对话框 + TextInputLayout 密码切换。

## 验收结果
- `assembleDebug` / `assembleRelease` / `assembleDebugAndroidTest` 全部构建通过。
- `lintDebug` 静态检查通过。
- `tests/host/run.sh`（19 项 JNI 核心回归）全部 PASS。
- `tests/host/transport.sh`（4 项 loopback SSH 认证与 PTY 交互测试）全部 PASS。
