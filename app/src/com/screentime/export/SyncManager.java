package com.screentime.export;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把导出内容打包、按所选模式封装、上传到私有仓库。
 *
 * 三档加密：
 *   0x00 不加密 —— 直接传 zip，上传前强制确认仓库为 private
 *   0x01 密码   —— 设置密码时算一次 DEK 并缓存，之后后台同步不再询问
 *   0x02 公钥   —— 手机只持有公钥，私钥留在 PC
 */
public final class SyncManager {

    public static final String PREF = "sce_sync";
    public static final String K_REPO = "repo";
    public static final String K_MODE = "mode";
    public static final String K_PUBKEY = "pubkey";
    public static final String K_DEVICE = "device";
    public static final String K_DEVICE_LOCKED = "device_locked";
    public static final String K_SUGGEST = "device_suggest";
    public static final String K_LAST_OK = "last_ok";
    public static final String K_LAST_ERR = "last_err";
    public static final String K_LAST_TRY = "last_try";

    /** 敏感材料，走 Keystore 加密后再落盘。 */
    public static final String S_TOKEN = "gh_token";
    public static final String S_DEK = "pw_dek";
    public static final String S_SALT = "pw_salt";

    private static final SecureRandom RNG = new SecureRandom();

    private SyncManager() {
    }

    // ------------------------------------------------------------ 配置

    public static class Config {
        public String repo = "";
        public int mode = Crypto.MODE_PLAIN;
        public String publicKeyB64 = "";
        public String device = "";

        public boolean hasRepo() {
            return GitHubClient.parseRepo(repo) != null;
        }
    }

    public static Config load(Context ctx) {
        SharedPreferences p = prefs(ctx);
        Config c = new Config();
        c.repo = p.getString(K_REPO, "");
        c.mode = p.getInt(K_MODE, Crypto.MODE_PLAIN);
        c.publicKeyB64 = p.getString(K_PUBKEY, "");
        c.device = sanitizeDeviceId(p.getString(K_DEVICE, ""));
        return c;
    }

    public static void setRepo(Context ctx, String repo) {
        prefs(ctx).edit().putString(K_REPO, repo == null ? "" : repo.trim()).apply();
    }

    public static void setMode(Context ctx, int mode) {
        prefs(ctx).edit().putInt(K_MODE, mode).apply();
    }

    public static void setPublicKey(Context ctx, String pemOrBase64) {
        prefs(ctx).edit().putString(K_PUBKEY, normalizePublicKey(pemOrBase64)).apply();
    }

    // ------------------------------------------------------------ 设备标识

    /**
     * 仓库内每台设备一个目录，避免多设备互相覆盖 latest.sce。
     *
     * 标识只确定一次：新装后由用户选择「新建本机标识」或「沿用仓库里已有的设备」，
     * 确定即写入 K_DEVICE 并锁死，之后没有任何入口能改 —— 一旦允许改，
     * 历史就会散在两个目录里，报表也就断了。
     */
    public static boolean isBound(Context ctx) {
        return sanitizeDeviceId(prefs(ctx).getString(K_DEVICE, "")) != null;
    }

    /** 当前标识。未绑定时返回建议值（只用于界面展示，不算已绑定）。 */
    public static String deviceId(Context ctx) {
        String d = sanitizeDeviceId(prefs(ctx).getString(K_DEVICE, ""));
        return d != null ? d : suggestedDeviceId(ctx);
    }

    /**
     * 建议标识：机型名 + 4 位随机后缀。单独存一份，
     * 否则界面每刷新一次就换一个名字，用户没法确认自己要绑的是哪个。
     */
    public static String suggestedDeviceId(Context ctx) {
        String s = sanitizeDeviceId(prefs(ctx).getString(K_SUGGEST, ""));
        if (s == null) {
            s = defaultDeviceId();
            prefs(ctx).edit().putString(K_SUGGEST, s).apply();
        }
        return s;
    }

    /**
     * 绑定设备标识。已绑定过就拒绝，返回 false —— 这是「不能修改」的唯一闸口，
     * 界面上不提供入口之外，方法本身也不允许二次调用成功。
     */
    public static boolean bind(Context ctx, String raw) {
        String d = SyncPaths.bindable(prefs(ctx).getString(K_DEVICE, ""), raw);
        if (d == null) {
            return false;
        }
        prefs(ctx).edit()
                .putString(K_DEVICE, d)
                .putBoolean(K_DEVICE_LOCKED, true)
                .remove(K_SUGGEST)
                .apply();
        return true;
    }

    /** 老版本升级上来的没有 K_DEVICE_LOCKED，但有标识就算锁定：行为与新版一致。 */
    public static boolean isLocked(Context ctx) {
        return isBound(ctx) && prefs(ctx).getBoolean(K_DEVICE_LOCKED, true);
    }

    /** 名称清洗规则在 SyncPaths 里，那边不依赖 android，可桌面单测。 */
    public static String sanitizeDeviceId(String raw) {
        return SyncPaths.sanitizeDeviceId(raw);
    }

    private static String defaultDeviceId() {
        String model;
        try {
            model = android.os.Build.MODEL;
        } catch (Throwable t) {
            model = null;
        }
        return SyncPaths.defaultDeviceId(model);
    }

    public static void setToken(Context ctx, String token) {
        SecretStore.put(ctx, S_TOKEN, token == null ? null : token.trim());
    }

    public static String getToken(Context ctx) {
        return SecretStore.get(ctx, S_TOKEN);
    }

    /**
     * 设置密码：生成 salt，派生 DEK，两者都用 Keystore 加密保存。
     * 之后后台同步直接用缓存的 DEK，不再需要密码原文。
     */
    public static void setPassword(Context ctx, char[] password)
            throws GeneralSecurityException {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        byte[] dek = Crypto.deriveKey(password, salt, Crypto.PBKDF2_ITER);
        SecretStore.put(ctx, S_SALT, Base64.encodeToString(salt, Base64.NO_WRAP));
        SecretStore.put(ctx, S_DEK, Base64.encodeToString(dek, Base64.NO_WRAP));
    }

    public static boolean hasPassword(Context ctx) {
        return SecretStore.get(ctx, S_DEK) != null && SecretStore.get(ctx, S_SALT) != null;
    }

    public static void clearPassword(Context ctx) {
        SecretStore.remove(ctx, S_DEK);
        SecretStore.remove(ctx, S_SALT);
    }

    /** 去掉 PEM 头尾与换行，只留 Base64 主体。 */
    public static String normalizePublicKey(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n")) {
            String t = line.trim();
            if (t.length() == 0 || t.startsWith("-----")) {
                continue;
            }
            sb.append(t);
        }
        return sb.toString();
    }

    public static String modeName(int mode) {
        if (mode == Crypto.MODE_PASSWORD) {
            return "密码加密";
        }
        if (mode == Crypto.MODE_KEY) {
            return "公钥加密";
        }
        return "不加密";
    }

    // ------------------------------------------------------------ 状态

    public static long lastSuccess(Context ctx) {
        return prefs(ctx).getLong(K_LAST_OK, 0L);
    }

    public static String lastError(Context ctx) {
        return prefs(ctx).getString(K_LAST_ERR, "");
    }

    public static long lastTry(Context ctx) {
        return prefs(ctx).getLong(K_LAST_TRY, 0L);
    }

    // ------------------------------------------------------------ 同步

    public static class Result {
        public boolean ok;
        public String message = "";
        public String commitSha = "";
        public String device = "";
        public long when;
        public int bytes;
    }

    /** 阻塞调用，必须在后台线程执行。 */
    public static Result syncNow(Context ctx) {
        Result r = new Result();
        r.when = System.currentTimeMillis();
        prefs(ctx).edit().putLong(K_LAST_TRY, r.when).apply();

        Config cfg = load(ctx);
        String[] parts = GitHubClient.parseRepo(cfg.repo);
        if (parts == null) {
            return fail(ctx, r, "仓库地址无效，应形如 owner/repo");
        }
        String token = getToken(ctx);
        if (token == null || token.length() == 0) {
            return fail(ctx, r, "未设置 Token");
        }
        // 未绑定就先拒绝：后台自动采集也会走到这里，若顺手生成了新标识，
        // 用户就再也没机会指回仓库里原有的设备目录了。
        if (!isBound(ctx)) {
            return fail(ctx, r, "尚未确定设备标识，请到同步设置里选择本机标识或仓库中的已有设备");
        }
        String device = deviceId(ctx);
        r.device = device;

        byte[] payload;
        try {
            Exporter.Built built = Exporter.buildAll(ctx);
            if (built.files.isEmpty()) {
                return fail(ctx, r, "本地库为空，先做一次采集");
            }
            payload = Crypto.zip(built.files);
        } catch (Throwable t) {
            return fail(ctx, r, "打包失败：" + t);
        }

        byte[] sce;
        try {
            sce = seal(ctx, cfg, payload);
        } catch (Throwable t) {
            return fail(ctx, r, t.getMessage() == null ? "加密失败" : t.getMessage());
        }
        r.bytes = sce.length;

        try {
            GitHubClient gh = new GitHubClient(token, parts[0], parts[1]);

            // 明文包只在确认仓库为 private 后才上传
            if (cfg.mode == Crypto.MODE_PLAIN) {
                GitHubClient.RepoInfo info = gh.repoInfo();
                if (!info.isPrivate) {
                    return fail(ctx, r, "仓库 " + info.fullName + " 不是 private，已拒绝明文上传");
                }
            }

            Map<String, byte[]> files = new LinkedHashMap<String, byte[]>();
            files.put(SyncPaths.day(device, r.when), sce);
            files.put(SyncPaths.latest(device), sce);
            String sha = gh.commit(files, GitHubClient.commitMessage(r.when, modeName(cfg.mode)));

            r.ok = true;
            r.commitSha = sha;
            r.message = "已上传 " + (sce.length / 1024 + 1) + " KB 到 "
                    + SyncPaths.deviceDir(device) + "，commit " + shortSha(sha);
            prefs(ctx).edit()
                    .putLong(K_LAST_OK, r.when)
                    .remove(K_LAST_ERR)
                    .apply();
            return r;
        } catch (Throwable t) {
            return fail(ctx, r, friendly(t));
        }
    }

    // ------------------------------------------------------------ 从仓库拉回

    public static class Pull {
        public boolean ok;
        /** 快照是密码模式，但没有可用的 DEK/密码，需要界面再问一次。 */
        public boolean needPassword;
        public String message = "";
        public String path = "";
        public int mode = -1;
        public byte[] sce;
        public UsageStore.ImportStat stat;
    }

    /**
     * 仓库里已有的设备（带快照数、最近日期），供首次绑定时挑选。
     * 未配置仓库或 Token 时抛 IOException，由界面引导用户补填。
     */
    public static java.util.List<DeviceCatalog.Device> listRemoteDevices(Context ctx)
            throws IOException {
        Config cfg = load(ctx);
        String[] parts = GitHubClient.parseRepo(cfg.repo);
        if (parts == null) {
            throw new IOException("仓库地址无效，应形如 owner/repo");
        }
        String token = getToken(ctx);
        if (token == null || token.length() == 0) {
            throw new IOException("未设置 Token");
        }
        return new GitHubClient(token, parts[0], parts[1]).listDeviceInfos();
    }

    /** 只下载，不解密：密码模式常需要先看到文件才能决定要不要问密码。 */
    public static Pull download(Context ctx, String device) {
        return download(ctx, device, null);
    }

    /**
     * @param hint 列目录时已拿到的设备概况，可省一次文件树请求；为 null 时按标识自己查。
     */
    public static Pull download(Context ctx, String device, DeviceCatalog.Device hint) {
        Pull p = new Pull();
        Config cfg = load(ctx);
        String[] parts = GitHubClient.parseRepo(cfg.repo);
        if (parts == null) {
            p.message = "仓库地址无效，应形如 owner/repo";
            return p;
        }
        String token = getToken(ctx);
        if (token == null || token.length() == 0) {
            p.message = "未设置 Token";
            return p;
        }
        try {
            GitHubClient gh = new GitHubClient(token, parts[0], parts[1]);
            p.sce = hint != null ? gh.downloadLatest(hint) : gh.downloadLatest(device);
            p.path = SyncPaths.latest(device);
            p.mode = Crypto.modeOf(p.sce);
            p.ok = true;
            return p;
        } catch (Throwable t) {
            p.message = friendlyPull(t);
            return p;
        }
    }

    /**
     * 解密 + 解析 + 入库。已下载的字节留在 Pull 里，密码问完可以直接重入，不必再下一次。
     */
    public static Pull unpack(Context ctx, Pull p, String password) {
        if (p == null || !p.ok || p.sce == null) {
            Pull bad = new Pull();
            bad.message = p == null ? "没有待处理的数据" : p.message;
            return bad;
        }
        Pull out = new Pull();
        out.path = p.path;
        out.sce = p.sce;
        out.mode = p.mode;

        byte[] payload;
        try {
            payload = payloadOf(ctx, p, password);
        } catch (NeedPasswordException e) {
            out.needPassword = true;
            out.message = e.getMessage();
            return out;
        } catch (Throwable t) {
            out.message = t.getMessage() == null ? "解密失败" : t.getMessage();
            return out;
        }

        try {
            java.util.Map<String, byte[]> files = Crypto.unzip(payload);
            byte[] json = files.get("screentime.json");
            if (json == null) {
                out.message = "快照里没有 screentime.json，可能不是本应用导出的包";
                return out;
            }
            SnapshotImporter.Snapshot snap = SnapshotImporter.parse(json);
            if (snap.days.isEmpty()) {
                out.message = "快照里没有逐日数据";
                return out;
            }
            UsageStore store = new UsageStore(ctx);
            try {
                out.stat = store.importSnapshot(snap);
            } finally {
                store.close();
            }
            int total = out.stat.daysNew + out.stat.daysUpdated;
            if (total == 0) {
                out.message = "本地已有同样或更新的数据，无需导入（快照 " + snap.days.size()
                        + " 天全部不比本地新）";
            } else {
                out.message = out.stat.describe();
            }
            out.ok = true;
            return out;
        } catch (Throwable t) {
            out.message = "导入失败：" + t;
            return out;
        }
    }

    /** 一步到位：下载 + 导入。密码模式未给密码时返回 needPassword。 */
    public static Pull pullNow(Context ctx, String device, String password) {
        return pullNow(ctx, device, null, password);
    }

    public static Pull pullNow(Context ctx, String device, DeviceCatalog.Device hint,
                               String password) {
        Pull d = download(ctx, device, hint);
        if (!d.ok) {
            return d;
        }
        return unpack(ctx, d, password);
    }

    private static class NeedPasswordException extends Exception {
        NeedPasswordException(String msg) {
            super(msg);
        }
    }

    private static byte[] payloadOf(Context ctx, Pull p, String password)
            throws GeneralSecurityException, IOException, NeedPasswordException {
        if (p.mode == Crypto.MODE_PLAIN) {
            return Crypto.open(p.sce, null, null);
        }
        if (p.mode == Crypto.MODE_PASSWORD) {
            // 本机设置过同一密码时直接用缓存的 DEK，免掉一次 21 万轮 PBKDF2
            String dekB64 = SecretStore.get(ctx, S_DEK);
            if (dekB64 != null) {
                try {
                    return Crypto.openWithDek(p.sce, Base64.decode(dekB64, Base64.NO_WRAP));
                } catch (GeneralSecurityException ignore) {
                    // DEK 对不上（换了密码或这是别的设备的数据），继续走密码分支
                }
            }
            if (password == null || password.length() == 0) {
                throw new NeedPasswordException("该设备的数据用密码加密，请输入当时的密码");
            }
            return Crypto.open(p.sce, password.toCharArray(), null);
        }
        if (p.mode == Crypto.MODE_KEY) {
            throw new GeneralSecurityException(
                    "该设备的数据用公钥加密，私钥只在电脑上，手机无法解密。"
                            + "请用 tools/decrypt.py 在电脑上解开后再导入");
        }
        throw new GeneralSecurityException("未知的加密模式: " + p.mode);
    }

    private static byte[] seal(Context ctx, Config cfg, byte[] payload)
            throws GeneralSecurityException, IOException {
        if (cfg.mode == Crypto.MODE_PASSWORD) {
            String dek = SecretStore.get(ctx, S_DEK);
            String salt = SecretStore.get(ctx, S_SALT);
            if (dek == null || salt == null) {
                throw new GeneralSecurityException("密码模式尚未设置密码");
            }
            return Crypto.sealWithDek(payload,
                    Base64.decode(dek, Base64.NO_WRAP),
                    Base64.decode(salt, Base64.NO_WRAP));
        }
        if (cfg.mode == Crypto.MODE_KEY) {
            String pub = cfg.publicKeyB64;
            if (pub == null || pub.length() == 0) {
                throw new GeneralSecurityException("公钥模式尚未导入公钥");
            }
            return Crypto.sealKey(payload, Base64.decode(pub, Base64.NO_WRAP));
        }
        return Crypto.sealPlain(payload);
    }

    private static Result fail(Context ctx, Result r, String msg) {
        r.ok = false;
        r.message = msg;
        prefs(ctx).edit().putString(K_LAST_ERR, msg).apply();
        return r;
    }

    private static String friendly(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.length() == 0) {
            m = t.getClass().getSimpleName();
        }
        if (t instanceof GitHubClient.ApiException) {
            return m;
        }
        return "上传失败：" + m;
    }

    private static String friendlyPull(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.length() == 0) {
            m = t.getClass().getSimpleName();
        }
        if (t instanceof GitHubClient.ApiException) {
            return m;
        }
        return "下载失败：" + m;
    }

    private static String shortSha(String sha) {
        return (sha == null || sha.length() < 7) ? String.valueOf(sha) : sha.substring(0, 7);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
