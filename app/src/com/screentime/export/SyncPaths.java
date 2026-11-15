package com.screentime.export;

import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 仓库内的路径与设备标识规则。多台设备共用一个仓库时，每台写进自己的目录，
 * 互不覆盖 latest.sce。
 *
 * 单独成一个类是为了能在桌面 JDK 上单测：这里不引用 android.* 也不引用 org.json。
 * 路径规则错了会静默写到错误位置、或者把两台设备混在一起，属于值得用测试钉住的部分。
 */
public final class SyncPaths {

    /** 所有设备都挂在这个前缀下，便于和 README 等仓库文件区分。 */
    public static final String ROOT_NAME = "devices";
    public static final String ROOT = ROOT_NAME + "/";

    private static final SecureRandom RNG = new SecureRandom();

    private SyncPaths() {
    }

    public static String deviceDir(String device) {
        return ROOT + device + "/";
    }

    /** 日快照：devices/phone-a/2026/11/15.sce */
    public static String day(String device, long ts) {
        String d = new SimpleDateFormat("yyyy/MM/dd", Locale.US).format(new Date(ts));
        return deviceDir(device) + d + ".sce";
    }

    /** 该设备最新一份副本：devices/phone-a/latest.sce */
    public static String latest(String device) {
        return deviceDir(device) + "latest.sce";
    }

    /**
     * 把用户输入或机型名清洗成可安全放进 git 路径的设备标识。
     *
     * 规则：只留小写字母、数字、`-`、`_`；其它字符（空格、斜杠、点、中文等）
     * 折叠成单个 `-`；去掉首尾 `-`；超过 40 字符截断。
     * 清洗后为空、或只剩点号时返回 null，由调用方回退到默认名。
     */
    public static String sanitizeDeviceId(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.US);
        if (s.length() == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder(s.length());
        boolean lastDash = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-';
            if (ok) {
                sb.append(c);
                lastDash = (c == '-');
            } else if (!lastDash) {
                sb.append('-');
                lastDash = true;
            }
        }
        String out = sb.toString().replaceAll("^-+|-+$", "");
        if (out.length() == 0 || ".".equals(out) || "..".equals(out)) {
            return null;
        }
        if (out.length() > 40) {
            out = out.substring(0, 40).replaceAll("-+$", "");
        }
        return out.length() == 0 ? null : out;
    }

    /**
     * 一次性绑定的判定：已有标识就一律拒绝，返回 null；否则返回清洗后的候选值。
     *
     * 「不能改」这条规则放在这里而不是界面里，是因为界面入口可以有好几个
     * （首次引导、同步页、以后可能的后台恢复），闸口只留一个才不会漏。
     */
    public static String bindable(String existingRaw, String candidateRaw) {
        if (sanitizeDeviceId(existingRaw) != null) {
            return null;
        }
        return sanitizeDeviceId(candidateRaw);
    }

    /**
     * 首次同步时按机型生成设备标识，加 4 位随机后缀，
     * 这样同型号的两台机器也不会写进同一个目录。生成后固定不变。
     */
    public static String defaultDeviceId(String model) {
        String base = sanitizeDeviceId(model);
        if (base == null) {
            base = "phone";
        }
        if (base.length() > 30) {
            base = base.substring(0, 30).replaceAll("-+$", "");
        }
        return base + "-" + String.format(Locale.US, "%04x", RNG.nextInt(0x10000));
    }
}
