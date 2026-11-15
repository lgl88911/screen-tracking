package com.screentime.export;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

/**
 * 导出包的封装与解封装，支持三种模式。
 *
 * 容器格式（自描述，PC 端读 mode 字节自动分支）：
 * <pre>
 *   magic   4B   "SCE1"
 *   mode    1B   0x00 明文 / 0x01 密码 / 0x02 公钥
 *
 *   mode 0:  [4B len][zip 原始字节]
 *   mode 1:  [1B saltLen][salt][4B iter][12B iv][GCM 密文]
 *   mode 2:  [4B rsaLen][RSA-OAEP(DEK)][12B iv][GCM 密文]
 * </pre>
 *
 * 本类不引用任何 android.* 类型，可脱离设备在桌面 JDK 直接单测。
 */
public final class Crypto {

    public static final byte MODE_PLAIN = 0x00;
    public static final byte MODE_PASSWORD = 0x01;
    public static final byte MODE_KEY = 0x02;

    public static final int PBKDF2_ITER = 210000;

    private static final byte[] MAGIC = { 'S', 'C', 'E', '1' };
    private static final int SALT_LEN = 16;
    private static final int IV_LEN = 12;
    private static final int DEK_LEN = 32;
    private static final int TAG_BITS = 128;
    private static final String RSA_TRANSFORM = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";

    /** OAEP 的 MGF1 必须显式指定 SHA-256：Java 默认用 SHA-1，与 Python 侧对不上。 */
    private static final OAEPParameterSpec OAEP_SHA256 = new OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

    private static final SecureRandom RNG = new SecureRandom();

    private Crypto() {
    }

    // ---------------------------------------------------------------- 打包

    /**
     * 把多个文件打成 zip。条目时间戳固定为 0，使相同内容产出相同字节，便于比对与去重。
     */
    public static byte[] zip(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ZipOutputStream zos = new ZipOutputStream(bos);
        try {
            for (Map.Entry<String, byte[]> en : files.entrySet()) {
                ZipEntry ze = new ZipEntry(en.getKey());
                ze.setTime(0L);
                zos.putNextEntry(ze);
                zos.write(en.getValue());
                zos.closeEntry();
            }
            zos.finish();
        } finally {
            zos.close();
        }
        return bos.toByteArray();
    }

    public static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<String, byte[]>();
        ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip));
        try {
            ZipEntry ze;
            byte[] buf = new byte[8192];
            while ((ze = zis.getNextEntry()) != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int n;
                while ((n = zis.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                out.put(ze.getName(), bos.toByteArray());
                zis.closeEntry();
            }
        } finally {
            zis.close();
        }
        return out;
    }

    // ------------------------------------------------------------ 三档封装

    public static byte[] sealPlain(byte[] payload) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(payload.length + 16);
        bos.write(MAGIC, 0, MAGIC.length);
        bos.write(MODE_PLAIN);
        writeInt(bos, payload.length);
        bos.write(payload, 0, payload.length);
        return bos.toByteArray();
    }

    public static byte[] sealPassword(byte[] payload, char[] password)
            throws GeneralSecurityException {
        byte[] salt = new byte[SALT_LEN];
        RNG.nextBytes(salt);
        byte[] dek = deriveKey(password, salt, PBKDF2_ITER);
        return sealWithDek(payload, dek, salt);
    }

    public static byte[] sealKey(byte[] payload, byte[] publicKeyDer)
            throws GeneralSecurityException {
        PublicKey pub = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(publicKeyDer));
        byte[] dek = new byte[DEK_LEN];
        RNG.nextBytes(dek);

        Cipher rsa = Cipher.getInstance(RSA_TRANSFORM);
        rsa.init(Cipher.ENCRYPT_MODE, pub, OAEP_SHA256);
        byte[] wrapped = rsa.doFinal(dek);

        byte[] iv = new byte[IV_LEN];
        RNG.nextBytes(iv);
        byte[] ct = gcm(Cipher.ENCRYPT_MODE, dek, iv, payload);

        ByteArrayOutputStream bos = new ByteArrayOutputStream(ct.length + wrapped.length + 32);
        bos.write(MAGIC, 0, MAGIC.length);
        bos.write(MODE_KEY);
        writeInt(bos, wrapped.length);
        bos.write(wrapped, 0, wrapped.length);
        bos.write(iv, 0, IV_LEN);
        bos.write(ct, 0, ct.length);
        return bos.toByteArray();
    }

    /**
     * 用已有的 DEK 与 salt 封装。salt 会写进文件头，解密方用「密码 + salt」重算即可得到同一 DEK。
     * 这样密码模式的解算只在用户设置密码时做一次，后台自动同步不必持有密码原文。
     */
    public static byte[] sealWithDek(byte[] payload, byte[] dek, byte[] salt)
            throws GeneralSecurityException {
        byte[] iv = new byte[IV_LEN];
        RNG.nextBytes(iv);
        byte[] ct = gcm(Cipher.ENCRYPT_MODE, dek, iv, payload);

        ByteArrayOutputStream bos = new ByteArrayOutputStream(ct.length + 64);
        bos.write(MAGIC, 0, MAGIC.length);
        bos.write(MODE_PASSWORD);
        bos.write(salt.length);
        bos.write(salt, 0, salt.length);
        writeInt(bos, PBKDF2_ITER);
        bos.write(iv, 0, IV_LEN);
        bos.write(ct, 0, ct.length);
        return bos.toByteArray();
    }

    // ------------------------------------------------------------ 解封装

    public static int modeOf(byte[] sce) throws GeneralSecurityException {
        checkMagic(sce);
        return sce[MAGIC.length] & 0xFF;
    }

    /**
     * @param password 仅 mode 1 需要
     * @param privKeyDer 仅 mode 2 需要（PKCS#8）
     */
    public static byte[] open(byte[] sce, char[] password, byte[] privKeyDer)
            throws GeneralSecurityException {
        checkMagic(sce);
        int mode = sce[MAGIC.length] & 0xFF;
        int p = MAGIC.length + 1;

        if (mode == MODE_PLAIN) {
            int len = readInt(sce, p);
            p += 4;
            if (p + len > sce.length) {
                throw new GeneralSecurityException("明文长度越界");
            }
            byte[] out = new byte[len];
            System.arraycopy(sce, p, out, 0, len);
            return out;
        }

        if (mode == MODE_PASSWORD) {
            if (password == null) {
                throw new GeneralSecurityException("该文件用密码加密，需要提供密码");
            }
            int saltLen = sce[p] & 0xFF;
            p += 1;
            byte[] salt = new byte[saltLen];
            System.arraycopy(sce, p, salt, 0, saltLen);
            p += saltLen;
            int iter = readInt(sce, p);
            p += 4;
            byte[] dek = deriveKey(password, salt, iter);
            return openGcm(sce, p, dek);
        }

        if (mode == MODE_KEY) {
            if (privKeyDer == null) {
                throw new GeneralSecurityException("该文件用公钥加密，需要提供私钥");
            }
            int wrappedLen = readInt(sce, p);
            p += 4;
            byte[] wrapped = new byte[wrappedLen];
            System.arraycopy(sce, p, wrapped, 0, wrappedLen);
            p += wrappedLen;

            PrivateKey priv = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(privKeyDer));
            Cipher rsa = Cipher.getInstance(RSA_TRANSFORM);
            rsa.init(Cipher.DECRYPT_MODE, priv, OAEP_SHA256);
            byte[] dek = rsa.doFinal(wrapped);
            return openGcm(sce, p, dek);
        }

        throw new GeneralSecurityException("未知的加密模式: " + mode);
    }

    /**
     * 用已经缓存的 DEK 解开密码模式的文件。本机设置过同一密码时走这里，
     * 省掉一次 21 万轮 PBKDF2；解不开（换了密码或换了设备）再退回 {@link #open} 用密码算。
     */
    public static byte[] openWithDek(byte[] sce, byte[] dek) throws GeneralSecurityException {
        if (dek == null || dek.length != DEK_LEN) {
            throw new GeneralSecurityException("DEK 长度不对");
        }
        checkMagic(sce);
        int mode = sce[MAGIC.length] & 0xFF;
        if (mode != MODE_PASSWORD) {
            throw new GeneralSecurityException("该文件不是密码模式");
        }
        int p = MAGIC.length + 1;
        int saltLen = sce[p] & 0xFF;
        p += 1 + saltLen + 4;         // saltLen + salt + iter
        return openGcm(sce, p, dek);
    }

    private static byte[] openGcm(byte[] sce, int off, byte[] dek)
            throws GeneralSecurityException {
        if (off + IV_LEN >= sce.length) {
            throw new GeneralSecurityException("密文长度不足");
        }
        byte[] iv = new byte[IV_LEN];
        System.arraycopy(sce, off, iv, 0, IV_LEN);
        byte[] ct = new byte[sce.length - off - IV_LEN];
        System.arraycopy(sce, off + IV_LEN, ct, 0, ct.length);
        return gcm(Cipher.DECRYPT_MODE, dek, iv, ct);
    }

    // ------------------------------------------------------------ 原语

    public static byte[] deriveKey(char[] password, byte[] salt, int iter)
            throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iter, DEK_LEN * 8);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] gcm(int opmode, byte[] dek, byte[] iv, byte[] input)
            throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(opmode, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(TAG_BITS, iv));
        return c.doFinal(input);
    }

    private static void checkMagic(byte[] sce) throws GeneralSecurityException {
        if (sce == null || sce.length < MAGIC.length + 1) {
            throw new GeneralSecurityException("文件过短，不是 SCE 容器");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (sce[i] != MAGIC[i]) {
                throw new GeneralSecurityException("magic 不匹配，不是 SCE 容器");
            }
        }
    }

    private static void writeInt(ByteArrayOutputStream bos, int v) {
        bos.write((v >>> 24) & 0xFF);
        bos.write((v >>> 16) & 0xFF);
        bos.write((v >>> 8) & 0xFF);
        bos.write(v & 0xFF);
    }

    private static int readInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24)
                | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFF);
    }

    // ------------------------------------------------------------ 辅助

    public static char[] passwordOf(String s) {
        return s == null ? null : s.toCharArray();
    }

    public static byte[] utf8(String s) {
        return s.getBytes(Charset.forName("UTF-8"));
    }
}
