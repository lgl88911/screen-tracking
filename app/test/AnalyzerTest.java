import com.screentime.export.UsageAnalyzer;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/** 桌面单测：算法层不引用 android.*，用 JDK 直接跑。 */
public class AnalyzerTest {

    static final TimeZone TZ = TimeZone.getTimeZone("Asia/Shanghai");
    static int pass = 0;
    static int fail = 0;

    static long at(int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance(TZ);
        c.clear();
        c.set(y, mo - 1, d, h, mi, 0);
        return c.getTimeInMillis();
    }

    static void check(String name, long actual, long expect) {
        if (actual == expect) {
            pass++;
            System.out.println("  ok   " + name + " = " + actual);
        } else {
            fail++;
            System.out.println("  FAIL " + name + " = " + actual + " (期望 " + expect + ")");
        }
    }

    static void checkStr(String name, String actual, String expect) {
        if (expect.equals(actual)) {
            pass++;
            System.out.println("  ok   " + name + " = " + actual);
        } else {
            fail++;
            System.out.println("  FAIL " + name + " = " + actual + " (期望 " + expect + ")");
        }
    }

    static UsageAnalyzer.Ev ev(int type, long ts, String pkg) {
        return new UsageAnalyzer.Ev(type, ts, pkg);
    }

    public static void main(String[] args) {
        UsageAnalyzer an = new UsageAnalyzer(TZ);
        final int MIN = 60000;

        // ---------------------------------------------------------- 1. 基础会话
        System.out.println("[1] 单段前台会话");
        {
            List<UsageAnalyzer.Ev> evs = new ArrayList<UsageAnalyzer.Ev>();
            long t0 = at(2026, 11, 10, 9, 0);
            evs.add(ev(UsageAnalyzer.EV_SCREEN_ON, t0, "android"));
            evs.add(ev(UsageAnalyzer.EV_KEYGUARD_HIDDEN, t0 + 2000, "com.android.systemui"));
            evs.add(ev(UsageAnalyzer.EV_RESUMED, t0 + 5000, "com.example.chat"));
            evs.add(ev(UsageAnalyzer.EV_PAUSED, t0 + 5 * MIN, "com.example.chat"));
            evs.add(ev(UsageAnalyzer.EV_SCREEN_OFF, t0 + 6 * MIN, "android"));
            UsageAnalyzer.Result r = an.analyze(evs, at(2026, 11, 10, 12, 0));
            UsageAnalyzer.DayStat d = r.days.get("2026-11-10");
            check("前台时长(ms)", d.app("com.example.chat").foregroundMs, 5 * MIN - 5000);
            check("启动次数", d.app("com.example.chat").launchCount, 1);
            check("解锁次数", d.unlockCount, 1);
            check("亮屏时长", d.screenOnMs, 6 * MIN);
        }

        // ---------------------------------------------------------- 2. 跨午夜切分
        System.out.println("[2] 跨午夜会话按自然日切分");
        {
            List<UsageAnalyzer.Ev> evs = new ArrayList<UsageAnalyzer.Ev>();
            long start = at(2026, 11, 10, 23, 40);
            evs.add(ev(UsageAnalyzer.EV_RESUMED, start, "com.example.game"));
            evs.add(ev(UsageAnalyzer.EV_PAUSED, at(2026, 11, 11, 0, 20), "com.example.game"));
            UsageAnalyzer.Result r = an.analyze(evs, at(2026, 11, 11, 1, 0));
            check("10 日归属(ms)", r.days.get("2026-11-10").app("com.example.game").foregroundMs, 20 * MIN);
            check("11 日归属(ms)", r.days.get("2026-11-11").app("com.example.game").foregroundMs, 20 * MIN);
            check("10 日总前台", r.days.get("2026-11-10").totalForegroundMs(), 20 * MIN);
            check("启动只在起始日记一次", r.days.get("2026-11-11").app("com.example.game").launchCount, 0);
        }

        // ---------------------------------------------------------- 3. 灭屏收口
        System.out.println("[3] 灭屏时未等到 PAUSED 也要收口");
        {
            List<UsageAnalyzer.Ev> evs = new ArrayList<UsageAnalyzer.Ev>();
            long t0 = at(2026, 11, 10, 22, 0);
            evs.add(ev(UsageAnalyzer.EV_SCREEN_ON, t0, "android"));
            evs.add(ev(UsageAnalyzer.EV_RESUMED, t0, "com.example.video"));
            evs.add(ev(UsageAnalyzer.EV_SCREEN_OFF, t0 + 15 * MIN, "android"));
            // 之后整夜无事件，采集时刻为次日 8 点
            UsageAnalyzer.Result r = an.analyze(evs, at(2026, 11, 11, 8, 0));
            check("前台时长(ms)", r.days.get("2026-11-10").app("com.example.video").foregroundMs, 15 * MIN);
            check("亮屏时长(ms)", r.days.get("2026-11-10").screenOnMs, 15 * MIN);
            check("次日不应有记录", r.days.containsKey("2026-11-11") ? 1 : 0, 0);
        }

        // ---------------------------------------------------------- 4. 末尾收口
        System.out.println("[4] 采集时仍在进行 -> 截断到采集时刻");
        {
            List<UsageAnalyzer.Ev> evs = new ArrayList<UsageAnalyzer.Ev>();
            long t0 = at(2026, 11, 10, 8, 0);
            evs.add(ev(UsageAnalyzer.EV_RESUMED, t0, "com.android.chrome"));
            long now = t0 + 7 * MIN;
            UsageAnalyzer.Result r = an.analyze(evs, now);
            check("前台时长(ms)", r.days.get("2026-11-10").app("com.android.chrome").foregroundMs, 7 * MIN);
        }

        // ---------------------------------------------------------- 5. 启动去重
        System.out.println("[5] 前台会话计数口径");
        {
            List<UsageAnalyzer.Ev> evs = new ArrayList<UsageAnalyzer.Ev>();
            long t0 = at(2026, 11, 10, 9, 0);
            // 30 秒内页面切换又回来 -> 仍算 1 次
            evs.add(ev(UsageAnalyzer.EV_RESUMED, t0, "com.example.chat"));
            evs.add(ev(UsageAnalyzer.EV_PAUSED, t0 + 10000, "com.example.chat"));
            evs.add(ev(UsageAnalyzer.EV_RESUMED, t0 + 15000, "com.example.chat"));
            // 2 分钟后再来 -> 第 2 次
            evs.add(ev(UsageAnalyzer.EV_PAUSED, t0 + 60000, "com.example.chat"));
            evs.add(ev(UsageAnalyzer.EV_RESUMED, t0 + 180000, "com.example.chat"));
            evs.add(ev(UsageAnalyzer.EV_PAUSED, t0 + 200000, "com.example.chat"));
            // 切到别的应用再回来 -> 也算新的
            evs.add(ev(UsageAnalyzer.EV_RESUMED, t0 + 210000, "com.android.settings"));
            evs.add(ev(UsageAnalyzer.EV_RESUMED, t0 + 220000, "com.example.chat"));
            evs.add(ev(UsageAnalyzer.EV_PAUSED, t0 + 240000, "com.example.chat"));
            UsageAnalyzer.Result r = an.analyze(evs, at(2026, 11, 10, 12, 0));
            check("微信启动次数", r.days.get("2026-11-10").app("com.example.chat").launchCount, 3);
            check("设置启动次数", r.days.get("2026-11-10").app("com.android.settings").launchCount, 1);
        }

        // ---------------------------------------------------------- 6. 同刻事件排序
        System.out.println("[6] 同时刻事件排序：亮屏先于前台切换");
        {
            List<UsageAnalyzer.Ev> evs = new ArrayList<UsageAnalyzer.Ev>();
            long t0 = at(2026, 11, 10, 7, 0);
            evs.add(ev(UsageAnalyzer.EV_RESUMED, t0, "com.example.launcher"));
            evs.add(ev(UsageAnalyzer.EV_SCREEN_ON, t0, "android"));
            evs.add(ev(UsageAnalyzer.EV_SCREEN_OFF, t0 + 60 * MIN, "android"));
            UsageAnalyzer.Result r = an.analyze(evs, at(2026, 11, 10, 12, 0));
            check("亮屏时长(ms)", r.days.get("2026-11-10").screenOnMs, 60 * MIN);
            check("桌面前台时长(ms)", r.days.get("2026-11-10").app("com.example.launcher").foregroundMs, 60 * MIN);
        }

        // ---------------------------------------------------------- 7. 关机收口
        System.out.println("[7] 关机收口");
        {
            List<UsageAnalyzer.Ev> evs = new ArrayList<UsageAnalyzer.Ev>();
            long t0 = at(2026, 11, 10, 20, 0);
            evs.add(ev(UsageAnalyzer.EV_SCREEN_ON, t0, "android"));
            evs.add(ev(UsageAnalyzer.EV_RESUMED, t0, "com.example.pay"));
            evs.add(ev(UsageAnalyzer.EV_SHUTDOWN, t0 + 3 * MIN, "android"));
            evs.add(ev(UsageAnalyzer.EV_STARTUP, t0 + 600 * MIN, "android"));
            UsageAnalyzer.Result r = an.analyze(evs, at(2026, 11, 11, 9, 0));
            check("前台时长(ms)", r.days.get("2026-11-10").app("com.example.pay").foregroundMs, 3 * MIN);
            checkStr("无跨日污染", r.days.get("2026-11-11") == null ? "null" : "有", "null");
        }

        // ---------------------------------------------------------- 8. 乱序输入
        System.out.println("[8] 事件乱序输入应先排序");
        {
            List<UsageAnalyzer.Ev> evs = new ArrayList<UsageAnalyzer.Ev>();
            long t0 = at(2026, 11, 10, 10, 0);
            evs.add(ev(UsageAnalyzer.EV_PAUSED, t0 + 4 * MIN, "com.example.notes"));
            evs.add(ev(UsageAnalyzer.EV_RESUMED, t0, "com.example.notes"));
            UsageAnalyzer.Result r = an.analyze(evs, at(2026, 11, 10, 12, 0));
            check("前台时长(ms)", r.days.get("2026-11-10").app("com.example.notes").foregroundMs, 4 * MIN);
        }

        // ---------------------------------------------------------- 9. 亮屏跨午夜
        System.out.println("[9] 亮屏区间跨午夜切分");
        {
            List<UsageAnalyzer.Ev> evs = new ArrayList<UsageAnalyzer.Ev>();
            evs.add(ev(UsageAnalyzer.EV_SCREEN_ON, at(2026, 11, 10, 23, 0), "android"));
            evs.add(ev(UsageAnalyzer.EV_SCREEN_OFF, at(2026, 11, 11, 1, 0), "android"));
            UsageAnalyzer.Result r = an.analyze(evs, at(2026, 11, 11, 8, 0));
            check("10 日亮屏(ms)", r.days.get("2026-11-10").screenOnMs, 60 * MIN);
            check("11 日亮屏(ms)", r.days.get("2026-11-11").screenOnMs, 60 * MIN);
        }

        // ---------------------------------------------------------- 10. 空输入
        System.out.println("[10] 空事件流");
        {
            UsageAnalyzer.Result r = an.analyze(new ArrayList<UsageAnalyzer.Ev>(), System.currentTimeMillis());
            check("无日记录", r.days.size(), 0);
        }

        System.out.println();
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }
}
