package com.screentime.export;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/** 查询系统 UsageStats 原始数据 -> 转 POJO -> 调纯 Java 算法 -> 落库。 */
public class Collector {

    /** 系统逐日保留窗口约 10~11 天，回溯 30 天只会拿到这个窗口内的数据。 */
    public static final int EVENT_DAYS_BACK = 30;

    public static class Agg {
        public String pkg;
        public long bucket;     // 区间起点
        public long totalMs;
        public long lastUsed;
    }

    public static class Collected {
        public UsageAnalyzer.Result result;
        public Map<String, String> labels;
        public Map<String, List<Agg>> aggs;
        public long fromTs;
        public long toTs;
        public int eventCount;
        public int dayCount;
        /** 系统自己的「日」桶起点（实测不等于本地零点的设备，可为 0 表示未取到）。 */
        public long systemDayBucketStart;
    }

    // ------------------------------------------------------------ 权限

    public static boolean hasUsageAccess(Context ctx) {
        try {
            AppOpsManager aom = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            if (aom == null) return false;
            int mode;
            if (Build.VERSION.SDK_INT >= 29) {
                mode = aom.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(), ctx.getPackageName());
            } else {
                mode = aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(), ctx.getPackageName());
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------ 采集

    public static Collected collect(Context ctx, long now) {
        Collected out = new Collected();
        out.fromTs = now - EVENT_DAYS_BACK * 86400000L;
        out.toTs = now;

        UsageStatsManager usm =
                (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) throw new IllegalStateException("UsageStatsManager 不可用");

        // 1) 事件流
        List<UsageAnalyzer.Ev> evs = new ArrayList<UsageAnalyzer.Ev>();
        UsageEvents ue = usm.queryEvents(out.fromTs, now);
        if (ue != null) {
            UsageEvents.Event e = new UsageEvents.Event();
            while (ue.getNextEvent(e)) {
                int t = e.getEventType();
                if (t == UsageAnalyzer.EV_RESUMED || t == UsageAnalyzer.EV_PAUSED
                        || t == UsageAnalyzer.EV_SCREEN_ON || t == UsageAnalyzer.EV_SCREEN_OFF
                        || t == UsageAnalyzer.EV_KEYGUARD_HIDDEN || t == UsageAnalyzer.EV_SHUTDOWN
                        || t == UsageAnalyzer.EV_STARTUP) {
                    evs.add(new UsageAnalyzer.Ev(t, e.getTimeStamp(), safePkg(e)));
                }
            }
        }
        out.eventCount = evs.size();

        UsageAnalyzer analyzer = new UsageAnalyzer(TimeZone.getDefault());
        out.result = analyzer.analyze(evs, now);
        out.dayCount = out.result.days.size();

        // 2) 周期聚合（weekly/monthly/yearly 的系统保留窗口远长于逐日）
        out.aggs = new HashMap<String, List<Agg>>();
        out.aggs.put("daily", queryAgg(usm, UsageStatsManager.INTERVAL_DAILY, now - 40L * 86400000L, now));
        out.aggs.put("weekly", queryAgg(usm, UsageStatsManager.INTERVAL_WEEKLY, now - 120L * 86400000L, now));
        out.aggs.put("monthly", queryAgg(usm, UsageStatsManager.INTERVAL_MONTHLY, now - 800L * 86400000L, now));
        out.aggs.put("yearly", queryAgg(usm, UsageStatsManager.INTERVAL_YEARLY, now - 2000L * 86400000L, now));

        // 记录系统自己「日」桶的真实起点：部分机型（实测 MIUI 14）不是本地零点，
        // 直接体现为「手机自带屏幕时间里的今天」与按自然日统计的今天对不上。
        for (Agg a : out.aggs.get("daily")) {
            if (a.bucket > out.systemDayBucketStart) out.systemDayBucketStart = a.bucket;
        }

        // 3) 中文应用名
        Set<String> pkgs = new HashSet<String>();
        for (UsageAnalyzer.DayStat d : out.result.days.values()) pkgs.addAll(d.apps.keySet());
        for (List<Agg> list : out.aggs.values()) {
            for (Agg a : list) pkgs.add(a.pkg);
        }
        out.labels = resolveLabels(ctx, pkgs);

        return out;
    }

    private static String safePkg(UsageEvents.Event e) {
        try {
            String p = e.getPackageName();
            return p == null ? "" : p;
        } catch (Throwable t) {
            return "";
        }
    }

    private static List<Agg> queryAgg(UsageStatsManager usm, int interval, long begin, long end) {
        List<Agg> out = new ArrayList<Agg>();
        List<UsageStats> list;
        try {
            list = usm.queryUsageStats(interval, begin, end);
        } catch (Throwable t) {
            return out;
        }
        if (list == null) return out;
        for (UsageStats us : list) {
            long total = us.getTotalTimeInForeground();
            if (total <= 0) continue;
            Agg a = new Agg();
            a.pkg = us.getPackageName();
            a.bucket = us.getFirstTimeStamp();
            a.totalMs = total;
            a.lastUsed = us.getLastTimeUsed();
            out.add(a);
        }
        return out;
    }

    public static Map<String, String> resolveLabels(Context ctx, Set<String> pkgs) {
        Map<String, String> map = new HashMap<String, String>();
        PackageManager pm = ctx.getPackageManager();
        for (String pkg : pkgs) {
            if (pkg == null || pkg.length() == 0) continue;
            String label = null;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                CharSequence cs = pm.getApplicationLabel(ai);
                if (cs != null) label = cs.toString();
            } catch (Throwable ignore) {
                // 已卸载或不可见
            }
            map.put(pkg, label == null ? pkg : label);
        }
        return map;
    }

    // ------------------------------------------------------------ 一键采集 + 落库

    public static class RunResult {
        public int dayCount;
        public int eventCount;
        public int appRows;
        public int aggRows;
        public String firstDay;
        public String lastDay;
        public long totalScreenOnMs;
        public String error;
    }

    public static RunResult collectAndStore(Context ctx) {
        RunResult rr = new RunResult();
        long now = System.currentTimeMillis();
        try {
            Collected c = collect(ctx, now);
            UsageStore store = new UsageStore(ctx);
            try {
                for (UsageAnalyzer.DayStat d : c.result.days.values()) {
                    store.mergeDay(d, c.labels, now);
                }
                for (Map.Entry<String, List<Agg>> en : c.aggs.entrySet()) {
                    store.mergeAggs(en.getKey(), en.getValue(), c.labels);
                }
                store.putMeta("last_collect", String.valueOf(now));
                store.putMeta("last_event_count", String.valueOf(c.eventCount));
                store.putMeta("system_day_bucket", String.valueOf(c.systemDayBucketStart));

                rr.dayCount = c.dayCount;
                rr.eventCount = c.eventCount;
                rr.appRows = store.countRows("usage");
                rr.aggRows = store.countRows("aggs");
                List<UsageStore.DayRow> days = store.readDays();
                if (!days.isEmpty()) {
                    rr.firstDay = days.get(0).day;
                    rr.lastDay = days.get(days.size() - 1).day;
                    long sum = 0;
                    for (UsageStore.DayRow d : days) sum += d.screenOnMs;
                    rr.totalScreenOnMs = sum;
                }
            } finally {
                store.close();
            }
        } catch (Throwable t) {
            rr.error = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        return rr;
    }
}
