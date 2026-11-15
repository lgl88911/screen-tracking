import com.screentime.export.SyncPaths;

/**
 * 设备标识清洗的边界用例。
 *
 * 这个标识会直接变成 git 路径的一部分（devices/&lt;id&gt;/…），
 * 所以既要挡住斜杠、点号这类会改变路径含义的字符，也要保证结果不为空。
 *
 * 只调用 SyncPaths 里的纯函数，不碰 android.*，桌面 JDK 直接跑。
 */
public class DeviceIdTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        eq("小写化", "phone-a", "PHONE-A");
        eq("空格转连字符", "note-12", "红米 Note 12");
        eq("连续分隔符折叠", "a-b", "a///b");
        eq("首尾连字符去掉", "abc", "--abc--");
        eq("点号被清掉", null, "..");
        eq("空串", null, "");
        eq("只有空格", null, "   ");
        // 清洗后是扁平名字，不含斜杠和点号，越不出 devices/ 这一层
        eq("路径穿越被拍平", "etc-passwd", "../../etc/passwd");
        eq("斜杠不保留", "a-b", "a/b");
        eq("下划线保留", "phone_1", "phone_1");
        eq("数字后缀保留", "pixel-7a-7f3a", "pixel-7a-7f3a");

        // 超长截断到 40 字符以内，且不能在截断处留下尾巴连字符
        StringBuilder longId = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            longId.append('a');
        }
        String cut = SyncPaths.sanitizeDeviceId(longId.toString());
        check("超长截断≤40", cut != null && cut.length() <= 40, String.valueOf(cut));
        check("截断后无尾连字符", cut != null && !cut.endsWith("-"), String.valueOf(cut));

        String withDashAtCut = SyncPaths.sanitizeDeviceId(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbb");
        check("跨界截断不留尾连字符",
                withDashAtCut != null && !withDashAtCut.endsWith("-"),
                String.valueOf(withDashAtCut));

        // 路径形状：每台设备一个目录，两台机器写不到同一个文件
        String dayA = SyncPaths.day("phone-a", 0L);
        String dayB = SyncPaths.day("phone-b", 0L);
        check("日快照带设备目录", "devices/phone-a/1970/01/01.sce".equals(dayA), dayA);
        check("两台设备路径不同", !dayA.equals(dayB), dayA + " vs " + dayB);
        check("latest 带设备目录",
                "devices/phone-a/latest.sce".equals(SyncPaths.latest("phone-a")),
                SyncPaths.latest("phone-a"));

        // 无论输入多脏，结果都不能带出目录层级
        String[] hostile = {"../x", "a/../../b", "..", "/etc", "./x", "  ", "红米 Note 12"};
        for (String h : hostile) {
            String s = SyncPaths.sanitizeDeviceId(h);
            boolean safe = s == null
                    || (!s.contains("/") && !s.contains("..") && !s.startsWith("."));
            check("越界安全 [" + h + "]", safe, String.valueOf(s));
        }

        // 默认标识：机型 + 4 位十六进制后缀，同型号两台机器也不会撞进同一目录
        String d1 = SyncPaths.defaultDeviceId("Redmi Note 12");
        String d2 = SyncPaths.defaultDeviceId("Redmi Note 12");
        check("默认标识形如 model-xxxx", d1.matches("redmi-note-12-[0-9a-f]{4}"), d1);
        check("两次生成不同", !d1.equals(d2), d1 + " vs " + d2);
        check("机型为空回退 phone", SyncPaths.defaultDeviceId(null).startsWith("phone-"),
                SyncPaths.defaultDeviceId(null));

        System.out.println();
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void eq(String name, String expect, String input) {
        String got = SyncPaths.sanitizeDeviceId(input);
        boolean ok = expect == null ? got == null : expect.equals(got);
        check(name, ok, "期望 " + expect + "，实际 " + got);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  ok   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + " — " + detail);
        }
    }
}
