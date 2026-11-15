# 屏幕数据同步方案设计（App 直连 GitHub 私有仓库）

状态：**已实现并验证**（v2.1，三档加密均跑通）。2026-11-15

## 1. 已确定的决策

| 决策项 | 结论 |
|---|---|
| 同步路线 | App 内直传 GitHub 私有仓库，不经 PC 中继 |
| 同步范围 | 全部 5 个导出文件，打包后上传 |
| 触发时机 | 采集后自动上传 + 手动按钮触发，两者都支持 |
| 加密模式 | **可选三档，默认不加密** |
| 存储格式 | 5 文件打成一个 zip，按所选模式封装为单个自描述文件 |
| 上传协议 | Git Data API 原子提交，一次请求序列一个 commit |

## 2. 威胁模型

保护对象：逐日逐应用屏幕使用时长明细。可推断作息、睡眠、社交频率、工作状态。
敏感度高于照片，因为是连续行为日志而非离散片段。

可能的泄露路径：

1. 云端服务商读取内容（GitHub 能看到仓库存在、文件大小、commit 时间）
2. 仓库可见性被误改为 public
3. 手机丢失或被物理接触
4. APK 被逆向提取凭据
5. 传输链路被截获

不防：具备 root 权限且拿到当前设备解锁状态的攻击者（应用层无解）。

## 3. 加密设计：三档可选

### 3.1 模式对照

| 模式 | 标识 | 手机持有的密钥材料 | 云端可见内容 | 自动化能力 | 主要代价 |
|---|---|---|---|---|---|
| 0 不加密（默认） | `0x00` | 无 | 完整明文 | 全自动 | 仓库一旦公开即全部泄露 |
| 1 密码加密 | `0x01` | 密码派生的 DEK（Keystore 缓存） | 密文 | 首次输入后期自动 | 弱密码可离线爆破，忘密码即失数据 |
| 2 公钥加密 | `0x02` | 仅 RSA 公钥 | 密文 | 全自动 | 私钥丢失即数据全废 |

默认选 0 的理由：这是个人私有仓库，内容只是使用时长，加密带来的密钥管理成本在多数情况下
超过收益。三种模式都实现，切换不需要改代码，只改配置。

### 3.2 为什么不用 age

age 是 Rust 实现，Android 无现成二进制。要用需引入 Bouncy Castle（提供 X25519），
而当前构建链是手工 `aapt2 → javac → d8`，零第三方依赖，塞 jar 会显著增加构建复杂度。
JCA 原生支持 RSA-OAEP、AES-GCM、PBKDF2，三档模式都不需要外部依赖。

### 3.3 统一容器格式

三种模式共用一个自描述头，PC 端解密脚本读 `mode` 字节自动分支，不需要用户告诉它用哪种：

```
magic   4B    "SCE1"
mode    1B    0x00 明文 / 0x01 密码 / 0x02 公钥
```

**mode 0（不加密）**

```
4B    BE uint32，zip 字节长度 L
L     zip 原始字节
```

保留长度字段是为了与加密模式结构一致，解析代码只有一条主路径。

**mode 1（密码）**

```
1B    salt 长度 S（固定 16）
S     salt，SecureRandom 生成
4B    BE uint32，PBKDF2 迭代次数
12B   GCM IV
rest  AES-256-GCM 密文 + 16B TAG
```

密钥派生：`PBKDF2WithHmacSHA256(password, salt, 210000) → 32B DEK`

**mode 2（公钥）**

```
4B    BE uint32，RSA 密文长度 N
N     RSA-OAEP(SHA-256) 加密的随机 DEK
12B   GCM IV
rest  AES-256-GCM 密文 + 16B TAG
```

DEK 每次随机生成，不复用。AES-256-GCM 无 padding，自带完整性校验。

### 3.4 各模式的密钥管理

**模式 1 的自动化问题与解法**

后台每 6 小时采集并上传，不能每次都弹密码框。做法：用户首次输入密码后，
把**派生出的 DEK**（不是密码本身）用 Android Keystore 的 AES 密钥加密缓存。
之后自动同步直接取用缓存，不再询问。

缓存失效的场景：用户主动改密码、清除应用数据、更换设备。这三种情况都需要重新输入一次。

Keystore 中能拿到设备解锁状态时优先用 `setUserAuthenticationRequired(false)` 的普通密钥，
避免后台任务因锁屏而无法解密。

**模式 2 的密钥体系**

- RSA 4096
- 私钥 `~/.config/screentime/private.pem`，仅存 PC，权限 600，离线备份一份
- 公钥 `~/.config/screentime/public.pem`，导入手机

手机只持有公钥，即使 APK 被逆向、手机丢失，攻击者只能加密不能解密。

**模式 0**

无密钥材料。手机端只保存仓库地址与 PAT。

### 3.5 明文打包

用 `java.util.zip.ZipOutputStream`（JDK 原生）把 5 个文件打成一个 zip，deflate 压缩。

实测参考：当前整套 5 文件 509 KB → zip 后 74.7 KB（压缩率 85%），其中 `aggregates.csv` 占大头
（系统原始桶含大量重复字段）。加密头部另加约 40～550 字节（salt / IV / RSA 包裹）。

不用 tar 的原因：tar 无压缩，需额外接 GZIPOutputStream，且 Android 侧解 tar 需手写
512 字节头解析；zip 一步到位，PC 端 `zipfile` 直接解。

## 4. GitHub 上传协议

### 4.1 用 Git Data API 而非 Contents API

Contents API 传 5 个文件需要 5 次 PUT = 5 个 commit，中途断网留下半套数据，
且每次要 GET 拿 sha。Git Data API 可做原子提交：

```
1. GET  /repos/{owner}/{repo}/git/ref/heads/main
   → 得到当前 commit sha

2. GET  /repos/{owner}/{repo}/git/commits/{sha}
   → 得到 tree sha

3. POST /repos/{owner}/{repo}/git/blobs
   body: {"content": "<base64>", "encoding": "base64"}
   → 得到 blob sha

4. POST /repos/{owner}/{repo}/git/trees
   body: {"base_tree": "<tree sha>", "tree": [{"path": "2026/11/15.sce",
          "mode": "100644", "type": "blob", "sha": "<blob sha>"}]}
   → 得到新 tree sha

5. POST /repos/{owner}/{repo}/git/commits
   body: {"message": "...", "tree": "<new tree sha>", "parents": ["<commit sha>"]}

6. PATCH /repos/{owner}/{repo}/git/refs/heads/main
   body: {"sha": "<new commit sha>"}
```

六步中前五步都不改变仓库状态，最后一步才移动指针，天然原子。

### 4.1.1 空仓库要先引导出首个提交

对一个刚创建、还没有任何提交的仓库，上面的第 1 步会返回
`409 {"message":"Git Repository is empty."}`，而且这不是并发冲突——此时整个
Git Data API 都不可用，`POST /git/blobs` 同样是 409。只有 Contents API 能落下
第一个提交。

所以遇到空仓库时先插入一步引导，再走原流程：

```
0. PUT /repos/{owner}/{repo}/contents/README.md
   body: {"message": "初始化数据仓库", "content": "<base64>", "branch": "main"}
   → 生成初始提交，仓库有了 HEAD
```

之后重新读取 `GET /repos/{owner}/{repo}` 拿实际默认分支（空仓库的
`default_branch` 可能为 `null`，而 Android 的 `JSONObject.optString` 对 JSON null
会返回字符串 `"null"`，不处理就会去查 `refs/heads/null`），再按第 1～6 步正常提交。

引导只在仓库首次上传时发生一次，代价是数据仓库里多一个 `README.md`
和一条「初始化数据仓库」提交，说明文件本身也顺便写清了这个仓库的用途与取回方式。

### 4.2 仓库结构

多台设备共用一个仓库，每台一个目录：

```
repo/
  devices/
    <设备标识>/
      2026/11/15.sce      每日一份，约 75 KB
      2026/11/16.sce
      latest.sce          该设备最新一份副本
```

设备标识**只确定一次**（`SyncPaths.bindable` 是唯一闸口：已有标识时一律返回 null）：

| 场景 | 首次启动的选项 | 结果 |
|---|---|---|
| 这台手机第一次用 | 新建本机标识 | 按「机型 + 4 位随机后缀」生成，写入 prefs 并锁定 |
| 重装应用 | 从仓库选择已有设备 | 沿用目录名，可同时把历史快照拉回本机 |

未绑定时后台同步会拒绝执行而不是顺手生成一个新标识 —— 否则用户再没机会指回原有目录。
清洗规则：只保留小写字母、数字、`-`、`_`，其它字符折叠成单个 `-`，去掉首尾 `-`，
超过 40 字符截断；清洗后为空则回退到 `phone-xxxx`。这样标识只会是一段扁平名字，
不可能越出 `devices/` 这一层。

多设备由此带来的两个问题及对策：

- **互相覆盖**：`latest.sce` 在各自目录里，不存在覆盖；`base_tree` 取的是当前 HEAD，
  只增改本批文件，别人的目录不受影响。
- **同时上传**：两台设备几乎同时提交时，后者的 `PATCH /git/refs` 会返回 422/409。
  客户端对这类错误自动重试 3 次（每次重新读取 HEAD 后整批重做），
  blob 按内容寻址不会重复占空间。

扩展名统一 `.sce`，加密与否由文件头 `mode` 字节标明，不靠扩展名区分。

`screentime.json` 本身含全部历史日期，每天一份新快照即天然版本备份，无需增量逻辑。

容量估算：75 KB/天/设备 → 单设备一年约 27 MB、365 个 commit。GitHub 仓库软限制 1 GB。

### 4.2.1 反向：把历史接回本机

`latest.sce` 是全量快照，所以一次 `GET /contents/devices/<id>/latest.sce`
（`Accept: application/vnd.github.raw`，不受 1 MB 的 JSON 体上限限制）
就能还原完整历史；只有 `latest.sce` 缺失时才回退到 `git/trees?recursive=1`
里字典序最大的日快照。

解包链路：`SCE1` 头 → 按 `mode` 解密 → zip → `screentime.json` → 入库。

| 模式 | 手机端能否导入 | 处理 |
|---|---|---|
| 0x00 明文 | 能 | 直接解 |
| 0x01 密码 | 能 | 先用 Keystore 里缓存的 DEK 试（省 21 万轮 PBKDF2），失败再向用户要密码 |
| 0x02 公钥 | 不能 | 私钥只在电脑上，明确报错并指向 `tools/decrypt.py` |

合并规则：逐日比较快照的 `collected_at` 与本地 `updated_at`，新的胜出；相等则跳过。
这样重装后的空库被全量接收，而刚采集完再拉旧快照不会把新数据冲掉。
写入沿用「整天删后重插」，同一天重复导入结果不变。

### 4.3 网络

目标域名 `api.github.com`。国内通常可直连，稳定性不保证（被干扰的主要是
`raw.githubusercontent.com`）。必须有失败重试，不能采集完传一次就丢弃。

## 5. Token 管理

- 类型：fine-grained PAT
- 范围：单个仓库，权限仅 `Contents: Read and write`
- 有效期：90 天，到期轮换

存储方式（三档加密共用）：

1. Android Keystore 生成 AES 密钥（有 TEE 时硬件保护）
2. 用该密钥 AES-GCM 加密 PAT
3. 密文写入 SharedPreferences

即使 `shared_prefs` 被提取，得到的也是密文；Keystore 中的密钥无法导出。

## 6. 触发时机（用户已确认：两者都支持）

| 触发源 | 行为 |
|---|---|
| 采集完成后 | 自动上传，失败进入重试队列 |
| 主界面「立即同步」按钮 | 手动上传当前最新快照 |
| 后台 Job | 每 6 小时，网络约束 `NETWORK_TYPE_ANY` |

重试队列放在 SQLite 的 meta 表，记录 `pending_upload_path`、`upload_attempts`、
`last_upload_success`。后台 Job 每次启动先尝试补传。

## 7. 需要改动的现有设定

| 位置 | 现状 | 结论 | 原因 |
|---|---|---|---|
| `AutoCollectService:27` | `NETWORK_TYPE_NONE` | **保持不动** | `NETWORK_TYPE_NONE` 意为「不要求网络」，是最容易被调度的档位。采集受系统 11 天窗口限制、断一次就永久缺失；同步每次都是全量快照、这次失败下次可补。因此绝不能让网络约束挡住采集，改为在 Job 内部「采集完再尝试同步，失败只记日志」 |
| `AndroidManifest.xml` | 无 INTERNET 权限 | 添加 `INTERNET` + `ACCESS_NETWORK_STATE` | 上传需要 |
| `build.sh:44` | `minSdk 23` | `minSdk 26` | 密码模式的 `PBKDF2WithHmacSHA256` 从 API 26 起才有（23 只有 SHA1 变体）；顺带解决 `java.util.Base64`、`java.nio.file` 不可用的问题 |

minSdk 提到 26（Android 8.0）的代价：Android 8.0 以下设备无法安装。
当前在网设备中该区间占比已很低，且本机为 Android 13，不受影响。
若必须保留 23，密码模式只能降到 `PBKDF2WithHmacSHA1` + 200k 迭代，安全性弱一档。

## 8. 实现清单

| 文件 | 实际行数 | 内容 |
|---|---|---|
| `Crypto.java` | 307 | zip 打包；三档封装与解包；PBKDF2 / AES-GCM / RSA-OAEP |
| `GitHubClient.java` | 290 | 六步 API，`HttpURLConnection` + org.json |
| `SecretStore.java` | 101 | Keystore 保护 Token 与密码派生 DEK |
| `SyncManager.java` | 265 | 配置读写、三档封装编排、上传与状态记录 |
| `SyncActivity.java` | 387 | 同步设置界面（仓库 / Token / 三档模式 / 立即同步 / 清除凭据） |
| `MainActivity.java` | +40 | 同步入口按钮、状态区展示上次同步时间、采集后自动上传 |
| `AutoCollectService.java` | +25 | 采集后尝试同步 |
| `tools/decrypt.py` | 126 | PC 端三档解密解包 |
| `tools/genkeys.py` | 64 | 生成 RSA 密钥对 |
| `app/test/CryptoTest.java` | 134 | 三档封装 14 项单测 + 跨语言往返样本 |

第二轮（多设备 + 一次性绑定 + 反向导入）新增：

| 文件 | 内容 |
|---|---|
| `SyncPaths.java` | 增加 `bindable()`：已有标识时一律拒绝改绑 |
| `GitHubClient.java` | 增加 `listDevices()` / `download()` / `downloadLatest()` / `newestDayFile()`；HTTP 层改为返回字节，支持 `raw` 媒体类型 |
| `Crypto.java` | 增加 `openWithDek()`：用缓存的 DEK 解密码模式文件 |
| `Json.java` | 极简 JSON 解析器（桌面可测） |
| `SnapshotImporter.java` | `screentime.json` → 可入库结构，含 `shouldReplace()` 合并判定 |
| `UsageStore.java` | 增加 `importSnapshot()`：逐日比较 `updated_at` 后整天覆盖 |
| `SyncManager.java` | 绑定状态机 + `download / unpack / pullNow` 三段式 |
| `SyncActivity.java` | 标识只读、选设备、拉回历史、密码弹窗 |
| `MainActivity.java` | 首次启动引导弹窗 |
| `app/test/ImportTest.java` | 41 项：字段回退、转义、聚合桶、合并方向、畸形输入 |

合计约 1740 行。APK 从 37 KB 增至 53 KB（无新增依赖）。

## 9. 界面设计

首页在「查看使用报告」之后增加「同步到私有仓库（加密可选）」，点进去是独立的 `SyncActivity`，
避免把表单挤在主界面：

```
同步到私有仓库

仓库      [owner/repo 或 clone 地址     ]
设备标识  22101320c-6f5a
          已锁定，此后不可修改。仓库目录 devices/22101320c-6f5a/
          [ 从仓库下载本机历史数据 ]        ← 已绑定时
Token     [••••••••••  留空则不修改     ]
加密方式  (•) 不加密（默认）
          ( ) 密码加密
          ( ) 公钥加密（私钥留在电脑）
密码      [仅密码模式，已设置则留空     ]
公钥      [粘贴 public.pem 全文         ]

[ 保存配置 ]
[ 立即同步 ]
[ 清除 Token 与密码 ]

当前模式：不加密
仓库：owner/repo
设备标识：22101320c-6f5a（已锁定）
Token：已保存
上次成功：3 小时前
注意：不加密时上传的是明文，仓库必须保持 private。
```

标识未确定时，那一块换成只读展示 + 两个按钮：

```
设备标识  22101320c-75c9
          尚未确定。这台手机第一次用选「新建」，重装应用选「从仓库选择」以接上原有历史。
          [ 用本机新标识：22101320c-75c9 ]
          [ 从仓库选择已有设备 ]
```

主界面首次启动（含重装后）弹「选择设备标识」，三个按钮：新建本机标识 / 从仓库选择 / 稍后。
选「从仓库选择」跳到 `SyncActivity` 并自动展开设备列表 —— 那里已经有仓库与 Token 的输入框，
不必在对话框里再摆一套表单。

标识一旦确定，两处都不再提供修改入口，`SyncManager.bind` 也直接返回 false。

选择「不加密」时状态区常驻一行明文警示。这是三档里唯一需要额外提醒的，
另外两档即使仓库被公开也读不出内容。

「清除 Token 与密码」带二次确认，用于换设备或轮换凭据；仓库地址保留。
实现时踩到一个点：`loadConfig` 必须每次都按实际状态重设 `hint`，否则清除后界面仍显示
「已保存」，看起来像没清掉。

## 10. 风险与对策

**1. 默认不加密 + 仓库误设 public = 全量泄露**
三档中只有模式 0 有这个风险，且泄露是完整可读的 JSON/CSV，不是密文。
对策：选择不加密时界面常驻警示；上传前可选择性检查仓库 `private` 字段
（`GET /repos/{owner}/{repo}` 返回 `private: true/false`），为 false 时拒绝上传并提示。

**2. commit 时间戳泄露作息**
即使内容加密，上传时刻在仓库中明文可见，连续数月足以还原活跃与睡眠时段。
对策：固定时段上传（如每日 03:00 前后随机 20 分钟），与使用时间解耦。

**3. 密码遗忘**
模式 1 没有找回机制，密码与 salt 都在文件头之外，云端拿不到任何提示。
对策：设置密码时强制要求一次性确认输入；界面提示「忘记密码无法恢复历史数据」。

**4. 信任方向倒置**
手机持有仓库写权限。手机丢失时对方可写入、删改仓库内容。
模式 0 下更严重，因为内容也是明文可读。
对策：PAT 90 天轮换；定期 `git clone` 到 PC 冷备；建议模式 2 配合。

**5. 静默失败**
最危险的状态是「以为在同步，实际已失败数月」。
对策：主界面显示「上次同步成功：N 小时前」，超 48 小时标红。

**6. 网络可用性**
`api.github.com` 国内可直连但非 100% 稳定。
对策：失败入队重试，不丢弃。

**7. 采集本身断档**
同步只能搬运已有数据。MIUI 杀后台导致 Job 停摆超过 11 天，那段数据永久缺失，
加密与同步都无法补救。对策：保持「打开 App 时静默补采」；把后台任务在 MIUI
省电策略中设为「无限制」。

## 11. PC 端解密接口

```bash
# 生成密钥对（仅模式 2 需要）
python3 tools/genkeys.py -o ~/.config/screentime/

# 解密：模式由文件头自动识别，无需指定
python3 tools/decrypt.py 2026/11/15.sce -o out/
#   mode 0 → 直接解包
#   mode 1 → 交互输入密码
#   mode 2 → 自动读 ~/.config/screentime/private.pem
```

依赖 `cryptography`。流程：读 magic 与 mode → 按模式取出 DEK →
AES-GCM 解密（或直取明文）→ 字节流交给 `zipfile` 解包。
