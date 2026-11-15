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
     * 未设置时按机型加随机后缀生成一次并固定下来。
     */
    public static String deviceId(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String d = sanitizeDeviceId(p.getString(K_DEVICE, ""));
        if (d == null) {
            d = defaultDeviceId();
            p.edit().putString(K_DEVICE, d).apply();
        }
        return d;
    }

    public static void setDeviceId(Context ctx, String raw) {
        String d = sanitizeDeviceId(raw);
        if (d != null) {
            prefs(ctx).edit().putString(K_DEVICE, d).apply();
        }
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

    private static String shortSha(String sha) {
        return (sha == null || sha.length() < 7) ? String.valueOf(sha) : sha.substring(0, 7);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
