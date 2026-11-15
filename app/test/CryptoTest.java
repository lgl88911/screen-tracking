import com.screentime.export.Crypto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Crypto 三档封装的桌面单测：不依赖 android.*，直接跑 JVM。
 * 同时把样本文件写到 /tmp/sce-test/，供 decrypt.py 做跨语言往返验证。
 */
public class CryptoTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get("/tmp/sce-test");
        Files.createDirectories(dir);

        Map<String, byte[]> files = sample();
        byte[] zip = Crypto.zip(files);

        // 解包应还原全部 5 个文件
        Map<String, byte[]> back = Crypto.unzip(zip);
        check("zip 往返文件数", back.size() == 5);
        check("zip 往返内容", Arrays.equals(files.get("screentime.json"), back.get("screentime.json")));

        // 相同内容应产出相同 zip 字节（条目时间戳固定为 0）
        check("zip 可复现", Arrays.equals(zip, Crypto.zip(sample())));

        // ---- 模式 0：不加密
        byte[] p0 = Crypto.sealPlain(zip);
        Files.write(dir.resolve("plain.sce"), p0);
        check("plain 模式标识", Crypto.modeOf(p0) == Crypto.MODE_PLAIN);
        check("plain 还原", Arrays.equals(zip, Crypto.open(p0, null, null)));

        // ---- 模式 1：密码
        char[] pw = "hunter2-测试密码".toCharArray();
        byte[] p1 = Crypto.sealPassword(zip, pw);
        Files.write(dir.resolve("password.sce"), p1);
        check("password 模式标识", Crypto.modeOf(p1) == Crypto.MODE_PASSWORD);
        check("password 还原", Arrays.equals(zip, Crypto.open(p1, pw, null)));
        check("password 密文不含明文片段", new String(p1, "ISO-8859-1").indexOf("com.example.chat") < 0);

        boolean threw = false;
        try {
            Crypto.open(p1, "wrong-pass".toCharArray(), null);
        } catch (Exception e) {
            threw = true;
        }
        check("错误密码被拒", threw);

        // GCM 应检出篡改
        byte[] tampered = p1.clone();
        tampered[tampered.length - 1] ^= 1;
        threw = false;
        try {
            Crypto.open(tampered, pw, null);
        } catch (Exception e) {
            threw = true;
        }
        check("篡改被 GCM 检出", threw);

        // ---- 模式 2：公钥
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        byte[] pubDer = kp.getPublic().getEncoded();
        byte[] privDer = kp.getPrivate().getEncoded();

        byte[] p2 = Crypto.sealKey(zip, pubDer);
        Files.write(dir.resolve("key.sce"), p2);
        check("key 模式标识", Crypto.modeOf(p2) == Crypto.MODE_KEY);
        check("key 还原", Arrays.equals(zip, Crypto.open(p2, null, privDer)));

        threw = false;
        try {
            Crypto.open(p2, null, null);
        } catch (Exception e) {
            threw = true;
        }
        check("缺私钥时明确报错", threw);

        // 两种随机加密对同一明文应产出不同密文
        check("password 密文含随机 salt/iv", !Arrays.equals(p1, Crypto.sealPassword(zip, pw)));

        // 供 decrypt.py 使用
        Files.write(dir.resolve("private.pem"), pem("PRIVATE KEY", privDer));
        Files.write(dir.resolve("public.pem"), pem("PUBLIC KEY", pubDer));
        Files.write(dir.resolve("expect.zip"), zip);
        for (Map.Entry<String, byte[]> en : files.entrySet()) {
            Files.write(dir.resolve("expect-" + en.getKey()), en.getValue());
        }

        System.out.println();
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static Map<String, byte[]> sample() {
        Map<String, byte[]> m = new LinkedHashMap<String, byte[]>();
        m.put("screentime.json", Crypto.utf8("{\"days\":[{\"date\":\"2026-11-15\","
                + "\"apps\":[{\"package\":\"com.example.chat\",\"foreground_ms\":1234}]}]}"));
        m.put("daily_summary.csv", Crypto.utf8("\ufeff日期,屏幕时长\n2026-11-15,42\n"));
        m.put("app_daily.csv", Crypto.utf8("\ufeff日期,包名,时长\n2026-11-15,com.example.chat,1234\n"));
        m.put("app_total.csv", Crypto.utf8("\ufeff包名,累计\ncom.example.chat,1234\n"));
        m.put("aggregates.csv", Crypto.utf8("\ufeff类型,区间,包名,时长\ndaily,2026-11-15,com.example.chat,1234\n"));
        return m;
    }

    private static byte[] pem(String label, byte[] der) {
        String b64 = Base64.getMimeEncoder(64, new byte[] { '\n' }).encodeToString(der);
        String s = "-----BEGIN " + label + "-----\n" + b64 + "\n-----END " + label + "-----\n";
        return Crypto.utf8(s);
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  ok   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }
}
