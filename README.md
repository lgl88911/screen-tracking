# 屏幕时间导出（ScreentimeExport）

从 Android 手机导出系统级屏幕使用历史数据。原生应用，无 root、无 Gradle、无第三方依赖。

## 为什么需要装 App

MIUI「屏幕时间管理」自己的历史库在 `com.xiaomi.misettings` 私有目录，只有 signature 级权限能读。可用的公开数据源是系统的
`UsageStatsManager`：它保存逐日明细，但**系统只保留约 10 天**（实测 daily 文件 10 个，weekly 4 个，monthly 6 个，yearly 3 个）。

所以「一次性导出全部历史」在无 root 前提下不存在。本 App 的做法是**累积**：

1. 首次运行回溯 30 天（实际拿到系统还留着的约 11 天），把逐日结果写入自己的 SQLite，**按日期整日覆盖**（幂等，多跑几次自我纠正）；
2. `JobScheduler` 每 6 小时后台跑一次（`setPersisted(true)`，重启后仍有效），把新数据固化；
3. 装入之前的、已被系统清理的日期无法恢复 —— 这是 Android 的保留策略，无 root 无解。

同时 App 会把系统自己的 weekly / monthly / yearly 聚合桶原样导出，这部分能回溯更远（本机 monthly 到 5 月，yearly 到 2024 年 8 月）。

## 构建

```bash
bash app/build.sh          # 产出 app/build/screentime.apk
```

依赖：JDK 17（`~/jdk`）、`build-tools r34`（`~/android-sdk/android-14`）、`platform-33`（`~/android-sdk/android-13`）。
构建链是 `aapt2 link → javac → d8 → 追加 classes.dex → zipalign → apksigner`，几个必须注意的点：

- `javac` 要 `-bootclasspath $PLATFORM/android.jar -source 8 -target 8`
- Java 8 lambda 在 d8 下会缺 metafactory，代码里一律用匿名内部类
- `aapt2` 产出的 APK **不含 dex**，必须手工 `zip` 追加 `classes.dex`，否则报 `base.apk code is missing`
- 容器内 JVM 需要 `_JAVA_OPTIONS="-Xmx256m -Xss512k"`，否则 `pthread_create failed`
- 仓库所在文件系统不支持原地 rename，签名必须落在 `/tmp` 暂存区（build.sh 已处理）

## 安装与授权

```bash
export ADB_LIBUSB=1                       # 本机 adb 必须开 libusb 后端才认设备
python3 miui_install_confirm.py app/build/screentime.apk   # 自动点掉 MIUI「继续安装」弹窗
adb shell appops set com.screentime.export GET_USAGE_STATS allow
```

`appops` 这一句是关键：shell 可以直接给第三方应用授予「使用情况访问」，不需要到设置里手点。

## 使用

打开「屏幕时间导出」：

- **采集并导出** —— 拉事件流、重建、入库、导出 5 个文件
- **查看使用报告** —— 按时间段看屏幕使用情况，见下节
- **开启后台自动采集** —— 排一个每 6 小时的可持久 Job
- 打开应用时若距上次采集超过 6 小时，会**静默补采**（只入库、不导出），状态区提示「已静默补采」
- 状态区显示覆盖区间、累计屏幕时长、上次采集时间

## 数据查看（按时间段出报表）

首页点「查看使用报告（可按时间段筛选）」进入 `ReportActivity`，不依赖数据库之外的东西，查库是毫秒级。

进入后按 `7 天` / `30 天` / `全部` / `自定义` 切换统计范围，内容分总时间、每日时长、应用排名三块。
起点早于库里第一天时自动收到第一天，避免出现空档。

**三块内容**（对应需求里的总时间 / 排名时间 / 指定应用时间）：

| 区块 | 内容 |
|---|---|
| 总时间 | 屏幕总时长、日均、解锁次数（含日均）、使用应用数 |
| 每日屏幕时长 | 区间内每天的竖条图，标注分钟数与日期，一眼看出波动 |
| 排名时间 | 应用按时长降序，含条形、时长、占屏幕总时长百分比 |
| 指定应用时间 | **点任一行展开**：包名、累计、日均（按活跃天数）、启动次数、活跃天数，以及该应用逐日时长条形 |

口径与导出文件一致：系统前台时长、跨午夜按自然日切分、启动次数按前台会话计数。
`ReportActivity` 未导出（`android:exported="false"`），只能从 App 内进入，`adb shell am start` 会被 Permission Denial 拒绝。

导出同时写两个位置：

- `下载/ScreentimeExport/` —— MediaStore 公共目录，手机端文件管理器可见
- `/sdcard/Android/data/com.screentime.export/files/exports/` —— 供 `adb pull`

```bash
adb pull /sdcard/Android/data/com.screentime.export/files/exports/ ./export/
```

## 保留多久

| 类别 | 上限 | 说明 |
|---|---|---|
| 逐日明细（本应用自己的库） | **无上限** | 只要 App 不卸载、不清数据，就一直累积 |
| 单次能回溯 | **约 11 天** | 系统只留 10 个 daily 文件 + 今天，装 App 之前且已被清理的日期无解 |
| weekly / monthly / yearly 聚合 | 本机实测回 **2026-05**、**2024-08** | 系统每类只留几个桶，靠本应用滚动累积不会再丢 |

决定「会不会断档」的是采集频率，不是存储：系统窗口 11 天，只要超过 11 天没采集，中间那段就永久丢失。
所以应用做了两层兜底 —— 每 6 小时的后台 Job，以及**每次打开 App 时若距上次采集超过 6 小时就静默补采**（不导出文件）。
后台 Job 被 MIUI 省电策略掐掉时，只要你偶尔打开一次 App 就不会断档。

存储开销：约 35 行/天、每年约 1.3 万行，十年也不过几 MB，可以忽略。

导出文件用**固定文件名**（永远是当前全量快照），不会因反复导出而堆文件；历史累积在数据库里而不是文件多版本里。

## 同步到私有仓库（可选）

首页「同步到私有仓库（加密可选）」进入配置页。把每日快照打包上传到 GitHub 私有仓库，
历史因此在手机之外多一份异地副本。

**加密三档**，随时切换，不需要重装：

| 模式 | 手机持有 | 云端可见 | 自动化 |
|---|---|---|---|
| 不加密（默认） | 无 | 完整明文 | 全自动；上传前会确认仓库为 private，不是则拒绝 |
| 密码加密 | 密码派生的 DEK（Keystore 缓存） | 密文 | 首次输入密码，之后后台自动复用 |
| 公钥加密 | 仅 RSA 公钥 | 密文 | 全自动，私钥留在电脑 |

三档共用同一个自描述容器（`SCE1` + 模式字节 + 内容），扩展名统一 `.sce`。
PC 端 `tools/decrypt.py` 读模式字节自动分支，不需要告诉它用哪种。

**配置**：仓库填 `owner/repo` 或直接粘贴 clone 地址；Token 用 fine-grained PAT，
只授该仓库 `Contents: Read and write`，建议 90 天轮换。Token 与密码派生密钥都经
Android Keystore 加密后才落盘。

**上传时机**：采集后自动（含后台 Job 与启动补采），也可在配置页点「立即同步」。

**多设备**：仓库里每台设备一个目录，共用同一个仓库也不会互相覆盖：

```
devices/
  22101320c-6f5a/          设备标识，绑定后不可修改
    2026/11/15.sce         当日全量快照
    latest.sce             该设备最新一份
```

**设备标识只定一次**。第一次打开应用会要求确定本机标识，两条路：

- 这台手机第一次用 → 新建本机标识，按「机型 + 4 位随机后缀」生成（如 `22101320c-6f5a`）；
- 重装应用 → 从仓库里列出已有设备目录，选中即沿用它，可顺手把该设备的历史快照拉回本机。

确定之后没有任何入口能改：换标识会让历史散在两个目录里，报表也就断了。
只允许小写字母、数字、`-`、`_`，其它字符折叠成 `-`，清洗后为空则回退到 `phone-xxxx`。
两台设备同时上传触发 ref 冲突时会自动重试（重新读取 HEAD 后整批重做），单次上传仍是一个 commit。

**把历史接回本机**：配置页「从仓库下载本机历史数据」会取 `devices/<标识>/latest.sce`
（缺失时回退到最新的日快照），解密解包后按天合并进本地库。同一天比较快照的 `collected_at`
与本地 `updated_at`，新的胜出 —— 重装后的空库会被全量接收，刚采集完再拉旧快照也不会把新数据冲掉。
`latest.sce` 本身就是全量快照，所以一次请求即可还原完整历史。

公钥模式的文件手机解不开（私钥只在电脑上），这条路径会明确报错；密码模式会提示输入当时的密码，
本机缓存过同一密码时则直接用缓存的 DEK 跳过 21 万轮 PBKDF2。

**在电脑上解开**：

```bash
python3 tools/genkeys.py -o ~/.config/screentime/              # 仅公钥模式需要
python3 tools/decrypt.py devices/<设备标识>/latest.sce -o out/  # 模式自动识别，密码模式会提示输入
```

完整设计（容器字节布局、六步 API、密钥管理、风险对策）见 `docs/sync-design.md`。

## 导出文件

| 文件 | 内容 |
|---|---|
| `screentime.json` | 全量：元数据 + totals + 逐日逐应用 + 四类周期聚合 |
| `daily_summary.csv` | 每天一行：屏幕时长、解锁次数、使用应用数、使用最多的应用 |
| `app_daily.csv` | 逐日逐应用：时长、启动次数、最后使用时间 |
| `app_total.csv` | 应用维度汇总：累计时长、活跃天数、日均、启动次数 |
| `aggregates.csv` | 系统自己的 daily/weekly/monthly/yearly 桶，原样输出 |

CSV 带 BOM 的 UTF-8，Excel 直接打开不乱码。每次导出覆盖同名文件，`screentime.json` 里的 `exported_at` 标明导出时刻。

`bash tools/gen_report.py` 由 `screentime.json` 生成 `report.html` 可视化概览。

## 数据口径（重要）

1. **时长来源**：由 `queryEvents` 的 `ACTIVITY_RESUMED / PAUSED / SCREEN_INTERACTIVE / SCREEN_NON_INTERACTIVE / KEYGUARD_HIDDEN / DEVICE_SHUTDOWN` 事件重建前台会话，规则见 `UsageAnalyzer`：
   灭屏、关机、采集时刻都要收口；跨午夜的会话按自然日切分。
2. **启动次数**取「前台会话数」口径：相邻事件包名变化，或同包名间隔 > 60 秒才算一次。直接数 `ACTIVITY_RESUMED`
   会把应用内页面切换也算进去（微信能到 200 多次），严重偏高。
3. **日界可能不是本地零点**：部分机型的系统「日」桶起点与零点有偏移（`getFirstTimeStamp()` 实测可达数小时），
   此时系统 daily 统计的内容与「自该起点起」的事件重建吻合。
   App 的 `days` 按本地自然日切分，`aggregates` 保留系统原样桶，JSON 里 `system_daily_bucket_start` 给出该起点，便于和手机自带屏幕时间对账。
4. **与 MIUI 屏幕时间界面不保证逐秒一致**：MIUI 会做自己的平滑、合并与系统应用过滤，且只统计它认定的「应用」。

## 单测

```bash
bash app/test/run.sh          # 事件重建 22 项 + 设备标识 31 项 + 快照导入 41 项
bash app/test/run_crypto.sh   # 三档加密封装 14 项，并在 /tmp/sce-test 留下跨语言验证样本
```

`UsageAnalyzer`、`Crypto`、`SyncPaths`、`Json`、`SnapshotImporter` 都不引用 `android.*`，桌面 JDK 直接跑。
第一组覆盖跨午夜切分、灭屏/关机收口、末尾截断、启动去重、同时刻事件排序、乱序输入、空输入；
第二组覆盖设备标识清洗（斜杠、点号、中文、超长截断）、多设备路径互不重叠、以及「绑定一次后任何输入都改不动」；
第三组覆盖快照字段回退（缺 `foreground_ms` 按分钟算）、转义还原、聚合桶、合并方向判定与畸形 JSON。

`run_crypto.sh` 跑完可以用 `tools/decrypt.py` 解 `/tmp/sce-test/*.sce` 验证与 Python 侧互通。

## 目录

```
app/
  AndroidManifest.xml
  build.sh                    手工构建链
  src/com/screentime/export/
    UsageAnalyzer.java        纯 Java 事件流重建（可桌面单测）
    UsageStore.java           SQLite 累积存储，整日覆盖合并
    Collector.java            查询 UsageStats、补中文名、落库
    Exporter.java             JSON + 4 份 CSV，MediaStore / 外部目录双写
    ReportActivity.java       按时间段查看报表（总时间 / 排名 / 指定应用）
    Crypto.java               三档封装与解包（zip / PBKDF2 / AES-GCM / RSA-OAEP）
    GitHubClient.java         Contents/Git Data API：列设备、下载快照、六步原子提交
    SyncPaths.java            仓库路径、设备标识清洗与「只能绑一次」判定（可桌面单测）
    SecretStore.java          Keystore 保护 Token 与密码派生 DEK
    SyncManager.java          同步编排：打包 → 加密 → 上传；下载 → 解密 → 导入
    SyncActivity.java         同步设置界面（标识只读、选设备、拉回历史）
    Json.java                 极简 JSON 解析（可桌面单测）
    SnapshotImporter.java     screentime.json → 可入库结构 + 合并方向判定（可桌面单测）
    AutoCollectService.java   JobService 后台快照 + 顺带同步
    MainActivity.java         单页界面（首次启动引导确定设备标识）
  test/                       AnalyzerTest / CryptoTest / DeviceIdTest / ImportTest + run.sh
miui_install_confirm.py       MIUI 安装弹窗自动确认
tools/gen_report.py           由 screentime.json 生成 report.html
tools/genkeys.py              生成 RSA 密钥对（公钥加密模式）
tools/decrypt.py              PC 端三档解密解包
docs/sync-design.md           同步方案设计（容器格式、API、密钥管理、风险）
```

下列内容含真实使用数据或签名密钥，已在 `.gitignore` 中排除，不会进入版本库：

```
export/                       从手机拉回的导出结果（含真实应用与时长）
report.html                   由导出数据生成的可视化报告
app/keystore/                 签名密钥与口令文件
app/build/                    构建产物
```

克隆仓库后需要自行执行一次「采集并导出」才会产生 `export/` 与 `report.html`。
