# Lei记账 · 项目说明（给 Claude 的上下文）

用户自用的安卓自动记账 App。用户说中文，回复用中文；代码注释也用中文，和现有代码保持一致。

## 用户环境
- 手机：魅族，Flyme AIOS 12.6.0.0A（Android 15 左右），国行无 GMS。
- 支付：微信、支付宝为主，也用银行卡（银行 App / 银行短信通知）。
- 用户曾设置过系统免打扰；微信「微信支付」公众号可能被设成消息免打扰（会导致微信不发通知）。
- 用户提到过人在东京，但账单都是人民币；**日元/币种问题问过、还没得到答复**，暂时按人民币处理。
- DeepSeek API 已在用（`deepseek-chat`），分类和备注能正常返回。

## 架构
```
core/  纯 Kotlin，可在 JVM 上单测（./gradlew :core:test）
  parser/  NotificationParser（微信/支付宝通知）、PageTextParser（支付成功页，严格版 + 快捷开关用的宽松版）、
           PaymentFlowParser（线上付款：收银台 + 购物 App 结果页）、BankNotificationParser（银行动账通知/短信）、
           AmountParser、Sanitizer（发给 AI 前给 ≥6 位数字打码）
  dedup/   BillMerger：同一笔支付多渠道捕获时合并（跨渠道 5 分钟，同渠道 90 秒；BANK 可和微信/支付宝合并）
  summary/ 日/周/月统计；ai/ DeepSeek 提示词；io/ CsvCodec（导入导出）
app/   Android（Kotlin + Compose + Room + WorkManager + ML Kit 中文 OCR）
  capture/ PayNotificationListener（通知）、PayPageService（无障碍：支付成功页、收银台→3 分钟「付款窗口」临时监听所有 App、截图 OCR）、
           RecognizeTileService + RecognizeTrampolineActivity（下拉快捷开关「识别记账」）、CaptureLog（本机识别日志）
  data/    Room v2（bills、summaries、capture_logs），BillRepository（去重保存、导入）
  ai/      DeepSeekClient、AiClassifyWorker、SummaryWorker（每天定时日报/周日周报/月末月报）
  ui/      Theme.kt（固定品牌色，不用动态取色）、Components.kt（PillSwitcher 等）、
           BillsContent / StatsContent / AppScaffold 都是无状态的，方便截图测试
```
- 无障碍服务用的是自己的类名 `PayPageService`。**不要**伪装成谷歌「随选朗读」去绕过微信白名单（AutoAccounting 的做法），这条被安全检查明确拦过。
- 隐私约定：微信聊天通知只记「收到过」，不记内容；其它 App 的通知只有像银行动账的才处理；日志里长数字打码。

## 编译与验证
- 云端环境**没有 Android SDK**，dl.google.com 被屏蔽，Maven Central 偶尔限流（429），所以 app 模块在本地编译不了。
- 全靠 GitHub Actions（`.github/workflows/android.yml`）：core 单测 → assembleDebug → 上传 APK 产物 → Robolectric 截图测试。
- 用 GitHub MCP 工具查看 CI：`actions_list`（runs/jobs）、`get_job_logs`。
- **看界面截图**：Actions 产物下载会被网络屏蔽，所以 CI 把截图转成 base64 打印在日志里。
  拿 job 日志时（`get_job_logs`，`return_content: true`，`tail_lines: 320`），结果会因为太大自动存成文件，
  再运行 `python3 tools/decode_ci_screenshots.py <那个文件> <输出目录>` 解码，最后用 Read 看图。
  截图用例在 `app/src/test/java/com/lei/accounting/ui/ScreenshotTest.kt`。
- 签名：固定 keystore `app/signing/lei-accounting.jks`（v0.5.0 起），这样能覆盖安装。**不要改签名**，否则用户又得卸载重装、丢数据。
- 每次发版改 `app/build.gradle.kts` 里的 versionCode / versionName。当前 v0.5.0（versionCode 5）。
- Room 改表结构要写 Migration（见 Db.kt 里的 MIGRATION_1_2），不能用破坏性迁移。
- 分支：`claude/peaceful-tesla-4oeyb3`。

## 待验证 / 待办
- 用户还没真机测过 v0.5.0：银行卡识别、快捷开关（改成用透明页面收起下拉面板）、扫码付款识别都还要看实际效果。
- 扫码付款漏记的根因没确认：还没拿到用户手机上的识别日志（里面有微信/支付宝页面的真实类名和能读到几条文字）。
  拿到后按日志调整 PayPageService 里的 PAY_PAGE_HINT / CASHIER_HINT。
- 淘宝这类把支付宝收银台嵌在自己 App 里的场景，自动识别覆盖不到（只能靠通知或快捷开关）。
- 用户对界面的要求：以 Awwwards / Webby / FWA 获奖作品为标准，从排版、留白、层级、色彩、动效、微交互、响应式、原创性自检并持续优化。
  已做三轮截图自检；动画手感只能等用户真机反馈。
