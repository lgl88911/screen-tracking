package com.screentime.export;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 敏感配置的存取：Token 与密码模式派生的 DEK。
 *
 * 落盘的是密文，密钥在 Android Keystore 中且无法导出，
 * 即使 shared_prefs 被整目录读取也拿不到明文。
 */
public final class SecretStore {

    private static final String PREF = "sce_secrets";
    private static final String ALIAS = "sce_secret_key";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private SecretStore() {
    }

    public static void put(Context ctx, String key, String value) {
        if (value == null) {
            remove(ctx, key);
            return;
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, secretKey());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(value.getBytes("UTF-8"));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            prefs(ctx).edit().putString(key, Base64.encodeToString(out, Base64.NO_WRAP)).apply();
        } catch (Throwable t) {
            // 写入失败时退化为不保存，避免把明文落到 SharedPreferences
            prefs(ctx).edit().remove(key).apply();
        }
    }

    public static String get(Context ctx, String key) {
        String enc = prefs(ctx).getString(key, null);
        if (enc == null || enc.length() == 0) {
            return null;
        }
        try {
            byte[] all = Base64.decode(enc, Base64.NO_WRAP);
            if (all.length <= IV_LEN) {
                return null;
            }
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            byte[] ct = new byte[all.length - IV_LEN];
            System.arraycopy(all, IV_LEN, ct, 0, ct.length);

            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(c.doFinal(ct), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    public static void remove(Context ctx, String key) {
        prefs(ctx).edit().remove(key).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static SecretKey secretKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            KeyStore.Entry entry = ks.getEntry(ALIAS, null);
            if (entry instanceof KeyStore.SecretKeyEntry) {
                return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
            }
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }
}
