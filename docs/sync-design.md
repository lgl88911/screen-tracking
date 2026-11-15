# 屏幕数据同步方案设计（App 直连 GitHub 私有仓库）

状态：设计已定稿，尚未实现。2026-11-15

## 1. 已确定的决策

| 决策项 | 结论 |
|---|---|
| 同步路线 | App 内直传 GitHub 私有仓库，不经 PC 中继 |
| 同步范围 | 全部 5 个导出文件，打包加密后上传 |
| 触发时机 | 采集后自动上传 + 手动按钮触发，两者都支持 |
| 加密方式 | RSA-OAEP 包裹 DEK + AES-256-GCM，不用 age |
| 存储格式 | 5 文件打成一个 zip 再加密，单文件上传 |
| 上传协议 | Git Data API 原子提交，一次请求序列一个 commit |

## 2. 威胁模型

保护对象：逐日逐应用屏幕使用时长明细。可推断作息、睡眠、社交频率、工作状态。
敏感度高于照片，因为是连续行为日志而非离散片段。

需要防的：

1. 云端服务商读取内容（GitHub 能看到仓库存在、文件大小、commit 时间）
2. 手机丢失或被物理接触
3. APK 被逆向提取凭据
4. 传输链路被截获

不需要防的：具备 root 权限且拿到当前设备解锁状态的攻击者（应用层无解）。

## 3. 加密设计

### 3.1 为什么不用 age

age 是 Rust 实现，Android 无现成二进制。要用需引入 Bouncy Castle（提供 X25519），
而当前构建链是手工 `aapt2 → javac → d8`，零第三方依赖，塞 jar 会显著增加构建复杂度。
JCA 原生支持 RSA-OAEP 与 AES-GCM，无需任何依赖。

### 3.2 密钥体系

- 主密钥对：RSA 4096
  - 私钥 `~/.config/screentime/private.pem`，仅存 PC，权限 600，离线备份一份
  - 公钥 `~/.config/screentime/public.pem`，导入手机
- 手机只持有公钥。即使 APK 被逆向、手机丢失，攻击者只能加密不能解密。

### 3.3 文件格式

```
偏移  长度      内容
0     4         magic "SCE1"
4     4         BE uint32，RSA 密文长度 N
8     N         RSA-OAEP(SHA-256) 加密的 DEK（32 字节）
8+N   12        GCM IV
20+N  -         AES-256-GCM 密文 + 16 字节 TAG（附于末尾）
```

DEK 每次加密随机生成，不复用。AES 模式 GCM，无 padding，自带完整性校验。

### 3.4 明文打包

用 `java.util.zip.ZipOutputStream`（JDK 原生）把 5 个文件打成一个 zip，deflate 压缩。

实测参考：`screentime.json` 346 KB → 压缩后约 35 KB，整套 5 文件约 40 KB。

不用 tar 的原因：tar 无压缩，需额外接 GZIPOutputStream，且 Android 侧解 tar 需手写
512 字节头解析；zip 一步到位，PC 端 `unzip` 直接解。

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
   body: {"base_tree": "<tree sha>", "tree": [{"path": "2026/11/15.zip.enc",
          "mode": "100644", "type": "blob", "sha": "<blob sha>"}]}
   → 得到新 tree sha

5. POST /repos/{owner}/{repo}/git/commits
   body: {"message": "...", "tree": "<new tree sha>", "parents": ["<commit sha>"]}

6. PATCH /repos/{owner}/{repo}/git/refs/heads/main
   body: {"sha": "<new commit sha>"}
```

六步中前五步都不改变仓库状态，最后一步才移动指针，天然原子。

### 4.2 仓库结构

```
repo/
  2026/11/15.zip.enc        每日一份，约 40 KB
  2026/11/16.zip.enc
  latest.zip.enc            最新一份副本，便于快速取用
```

`screentime.json` 本身含全部历史日期，每天一份新快照即天然版本备份，无需增量逻辑。

容量估算：40 KB/天 → 一年约 15 MB、365 个 commit。GitHub 仓库软限制 1 GB。

### 4.3 网络

目标域名 `api.github.com`。国内通常可直连，稳定性不保证（被干扰的主要是
`raw.githubusercontent.com`）。必须有失败重试，不能采集完传一次就丢弃。

## 5. Token 管理

- 类型：fine-grained PAT
- 范围：单个仓库，权限仅 `Contents: Read and write`
- 有效期：90 天，到期轮换

存储方式：

1. Android Keystore 生成 AES 密钥（API 23+ 支持，有 TEE 时硬件保护）
2. 用该密钥 AES-GCM 加密 PAT
3. 密文写入 SharedPreferences

即使 `shared_prefs` 被提取，得到的也是密文，Keystore 中的密钥无法导出。

## 6. 触发时机（用户已确认：两者都支持）

| 触发源 | 行为 |
|---|---|
| 采集完成后 | 自动上传，失败进入重试队列 |
| 主界面「立即同步」按钮 | 手动上传当前最新快照 |
| 后台 Job | 每 6 小时，网络约束 `NETWORK_TYPE_ANY` |

重试队列放在 SQLite 的 meta 表，记录 `pending_upload_path`、`upload_attempts`、
`last_upload_success`。后台 Job 每次启动先尝试补传。

## 7. 需要改动的现有设定

| 位置 | 现状 | 改为 | 原因 |
|---|---|---|---|
| `AutoCollectService:27` | `NETWORK_TYPE_NONE` | `NETWORK_TYPE_ANY` | 无网络约束时后台任务照跑，上传必然失败 |
| `AndroidManifest.xml` | 无 INTERNET 权限 | 添加 | 当前完全没有网络权限 |
| `build.sh:29` | `minSdk 23` | 保持 | Keystore AES 需 API 23；但 `java.util.Base64`、
`java.nio.file` 不可用，须用 `android.util.Base64` |

## 8. 实现清单

| 文件 | 行数估算 | 内容 |
|---|---|---|
| `Crypto.java` | 130 | zip 打包、AES-GCM、RSA-OAEP 封装 |
| `GitHubClient.java` | 200 | 上述六步 API，`HttpURLConnection` + 手写 JSON |
| `SecretStore.java` | 80 | Keystore 保护 PAT |
| `MainActivity.java` | +120 | 设置界面（owner/repo/token/公钥）、同步状态区 |
| `Collector.java` | +20 | 采集后触发上传，失败入队 |
| `AutoCollectService.java` | +15 | 网络约束、补传重试 |
| `tools/decrypt.py` | 70 | PC 端解密解包 |

合计约 550 行。APK 体积预计从 37 KB 增至 55 KB（无新增依赖）。

## 9. 风险与对策

**1. commit 时间戳泄露作息**
内容加密，但上传时刻在仓库中明文可见，连续数月足以还原活跃与睡眠时段。
对策：固定时段上传（如每日 03:00 前后随机 20 分钟），与使用时间解耦。

**2. 信任方向倒置**
原 PC 中继方案中手机不持有任何云端凭据；现方案手机持有写权限。
手机丢失时对方可写入、删改仓库内容（因密文而无法读取历史）。
对策：PAT 90 天轮换；定期 `git clone` 到 PC 冷备。

**3. 静默失败**
最危险的状态是「以为在同步，实际已失败数月」。
对策：主界面显示「上次同步成功：N 小时前」，超 48 小时标红。

**4. 网络可用性**
`api.github.com` 国内可直连但非 100% 稳定。
对策：失败入队重试，不丢弃。

**5. 采集本身断档**
同步只能搬运已有数据。MIUI 杀后台导致 Job 停摆超过 11 天，那段数据永久缺失，
加密与同步都无法补救。对策：保持「打开 App 时静默补采」；把后台任务在 MIUI
省电策略中设为「无限制」。

## 10. PC 端解密接口

```
python3 tools/decrypt.py <file.zip.enc> -k ~/.config/screentime/private.pem -o out/
# 输出 out/ 下 5 个原始文件
```

依赖 `cryptography`。解密流程：读 magic 与长度 → RSA-OAEP 解出 DEK →
AES-GCM 解密 → 字节流交给 `zipfile` 解包。
