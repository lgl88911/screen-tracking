package com.screentime.export;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

/**
 * 前台使用事件流重建。纯 Java，不引用 android.*，可在桌面 JDK 直接单测。
 *
 * 事件类型常量取值与 android.app.usage.UsageEvents.Event 对齐：
 *   ACTIVITY_RESUMED / MOVE_TO_FOREGROUND = 1
 *   ACTIVITY_PAUSED  / MOVE_TO_BACKGROUND  = 2
 *   SCREEN_INTERACTIVE                     = 15
 *   SCREEN_NON_INTERACTIVE                 = 16
 *   KEYGUARD_HIDDEN                        = 18
 *   DEVICE_SHUTDOWN                        = 26
 *   DEVICE_STARTUP                         = 27
 */
public class UsageAnalyzer {

    public static final int EV_RESUMED = 1;
    public static final int EV_PAUSED = 2;
    public static final int EV_SCREEN_ON = 15;
    public static final int EV_SCREEN_OFF = 16;
    public static final int EV_KEYGUARD_HIDDEN = 18;
    public static final int EV_SHUTDOWN = 26;
    public static final int EV_STARTUP = 27;

    /** 同包名两次前台会话间隔超过该阈值，才算一次新的「启动」。 */
    public static final long NEW_SESSION_GAP_MS = 60000L;

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    public static final class Ev {
        public final int type;
        public final long ts;
        public final String pkg;

        public Ev(int type, long ts, String pkg) {
            this.type = type;
            this.ts = ts;
            this.pkg = pkg == null ? "" : pkg;
        }

        @Override
        public String toString() {
            return type + "@" + ts + "(" + pkg + ")";
        }
    }

    public static final class AppStat {
        public String pkg;
        public long foregroundMs;
        public int launchCount;
        public long lastUsed;

        public AppStat(String pkg) {
            this.pkg = pkg;
        }
    }

    public static final class DayStat {
        public String day;              // yyyy-MM-dd（本地时区）
        public long screenOnMs;
        public int unlockCount;
        public final Map<String, AppStat> apps = new LinkedHashMap<String, AppStat>();

        public DayStat(String day) {
            this.day = day;
        }

        public AppStat app(String pkg) {
            AppStat a = apps.get(pkg);
            if (a == null) {
                a = new AppStat(pkg);
                apps.put(pkg, a);
            }
            return a;
        }

        public long totalForegroundMs() {
            long sum = 0;
            for (AppStat a : apps.values()) sum += a.foregroundMs;
            return sum;
        }

        public int appCount() {
            int n = 0;
            for (AppStat a : apps.values()) if (a.foregroundMs > 0) n++;
            return n;
        }

        public AppStat topApp() {
            AppStat best = null;
            for (AppStat a : apps.values()) {
                if (a.foregroundMs <= 0) continue;
                if (best == null || a.foregroundMs > best.foregroundMs) best = a;
            }
            return best;
        }
    }

    public static final class Result {
        public final Map<String, DayStat> days = new TreeMap<String, DayStat>();
        public long fromTs;
        public long toTs;

        public DayStat day(String key, boolean create) {
            DayStat d = days.get(key);
            if (d == null && create) {
                d = new DayStat(key);
                days.put(key, d);
            }
            return d;
        }
    }

    private final Calendar cal = Calendar.getInstance();
    private final SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    public UsageAnalyzer(TimeZone tz) {
        TimeZone zone = tz == null ? TimeZone.getDefault() : tz;
        cal.setTimeZone(zone);
        dayFmt.setTimeZone(zone);
        dayFmt.setLenient(true);
    }

    // ---------------------------------------------------------------- 时间工具

    long dayStart(long ts) {
        cal.setTimeInMillis(ts);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    long nextDayStart(long ts) {
        cal.setTimeInMillis(ts);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.DAY_OF_MONTH, 1);
        return cal.getTimeInMillis();
    }

    String dayKey(long ts) {
        return dayFmt.format(new Date(ts));
    }

    // ---------------------------------------------------------------- 主算法

    private static int rank(int type) {
        switch (type) {
            case EV_SCREEN_ON:
            case EV_KEYGUARD_HIDDEN:
            case EV_STARTUP:
                return 0;   // 亮屏 / 解锁先于前台切换
            case EV_RESUMED:
                return 1;
            case EV_PAUSED:
                return 2;
            case EV_SCREEN_OFF:
                return 3;
            case EV_SHUTDOWN:
                return 4;
            default:
                return 5;
        }
    }

    public Result analyze(List<Ev> input, long now) {
        List<Ev> evs = new ArrayList<Ev>(input);
        Collections.sort(evs, new Comparator<Ev>() {
            @Override
            public int compare(Ev a, Ev b) {
                if (a.ts != b.ts) return a.ts < b.ts ? -1 : 1;
                return rank(a.type) - rank(b.type);
            }
        });

        Result r = new Result();
        r.fromTs = evs.isEmpty() ? now : evs.get(0).ts;
        r.toTs = now;

        String curPkg = null;
        long curStart = 0;
        long screenStart = -1;
        String lastPkg = null;
        long lastEnd = Long.MIN_VALUE / 2;

        for (int i = 0; i < evs.size(); i++) {
            Ev e = evs.get(i);
            long ts = e.ts > now ? now : e.ts;
            switch (e.type) {
                case EV_RESUMED: {
                    if (curPkg != null) {
                        accumulate(r, curPkg, curStart, ts);
                        lastPkg = curPkg;
                        lastEnd = ts;
                    } else {
                        // 上一次会话已收口（PAUSED/灭屏），lastPkg/lastEnd 已就位
                    }
                    curPkg = e.pkg;
                    curStart = ts;
                    if (!e.pkg.equals(lastPkg) || ts - lastEnd > NEW_SESSION_GAP_MS) {
                        r.day(dayKey(ts), true).app(e.pkg).launchCount++;
                    }
                    lastPkg = e.pkg;
                    lastEnd = ts;
                    break;
                }
                case EV_PAUSED: {
                    if (curPkg != null && curPkg.equals(e.pkg)) {
                        accumulate(r, curPkg, curStart, ts);
                        lastPkg = curPkg;
                        lastEnd = ts;
                        curPkg = null;
                    }
                    break;
                }
                case EV_SCREEN_OFF:
                case EV_SHUTDOWN: {
                    if (curPkg != null) {
                        accumulate(r, curPkg, curStart, ts);
                        lastPkg = curPkg;
                        lastEnd = ts;
                        curPkg = null;
                    }
                    if (screenStart >= 0) {
                        accumulateScreen(r, screenStart, ts);
                        screenStart = -1;
                    }
                    break;
                }
                case EV_SCREEN_ON: {
                    if (screenStart < 0) screenStart = ts;
                    break;
                }
                case EV_KEYGUARD_HIDDEN: {
                    r.day(dayKey(ts), true).unlockCount++;
                    break;
                }
                default:
                    break;
            }
        }

        // 末尾收口：仍在进行的会话截断到采集时刻
        if (curPkg != null) accumulate(r, curPkg, curStart, now);
        if (screenStart >= 0) accumulateScreen(r, screenStart, now);

        return r;
    }

    /** 跨午夜的区间按自然日切分归属。 */
    void accumulate(Result r, String pkg, long from, long to) {
        if (pkg == null || pkg.length() == 0 || to <= from) return;
        long cursor = from;
        int guard = 0;
        while (cursor < to && guard++ < 5000) {
            long de = nextDayStart(cursor);
            long end = Math.min(to, de);
            if (end <= cursor) end = to;
            DayStat d = r.day(dayKey(cursor), true);
            AppStat a = d.app(pkg);
            a.foregroundMs += (end - cursor);
            if (end > a.lastUsed) a.lastUsed = end;
            cursor = end;
        }
    }

    void accumulateScreen(Result r, long from, long to) {
        if (to <= from) return;
        long cursor = from;
        int guard = 0;
        while (cursor < to && guard++ < 5000) {
            long de = nextDayStart(cursor);
            long end = Math.min(to, de);
            if (end <= cursor) end = to;
            r.day(dayKey(cursor), true).screenOnMs += (end - cursor);
            cursor = end;
        }
    }

    /** 供单测/调用方使用的日长常量。 */
    public static long dayMs() {
        return DAY_MS;
    }
}
