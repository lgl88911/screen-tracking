package com.screentime.export;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把仓库里取回的 screentime.json 还原成可入库的结构。
 *
 * 这里的规则决定了「重装后能不能把历史接上」，所以刻意做成不依赖 android.* 的纯 Java，
 * 由 ImportTest 在桌面 JDK 上覆盖：字段回退、时间解析、以及同一天该不该覆盖本地。
 */
public final class SnapshotImporter {

    private SnapshotImporter() {
    }

    public static class App {
        public String pkg = "";
        public String label;
        public long foregroundMs;
        public int launches;
        public long lastUsed;
    }

    public static class Day {
        public String date = "";
        public long screenOnMs;
        public int unlocks;
        public long collectedAt;      // 该日数据最后一次被采集的时间
        public long foregroundMs;     // 由各应用前台时长求和，与采集端口径一致
        public final List<App> apps = new ArrayList<App>();
    }

    public static class Agg {
        public String kind = "";
        public long bucket;
        public String pkg = "";
        public String label;
        public long totalMs;
        public long lastUsed;
    }

    public static class Snapshot {
        public String exportedAt = "";
        public long exportedAtMs;
        public String note = "";
        public final List<Day> days = new ArrayList<Day>();
        public final List<Agg> aggs = new ArrayList<Agg>();

        public String firstDay() {
            return days.isEmpty() ? null : days.get(0).date;
        }

        public String lastDay() {
            return days.isEmpty() ? null : days.get(days.size() - 1).date;
        }
    }

    // ------------------------------------------------------------ 解析

    public static Snapshot parse(byte[] utf8) throws Exception {
        if (utf8 == null || utf8.length == 0) {
            throw new IllegalArgumentException("快照内容为空");
        }
        return parse(new String(utf8, "UTF-8"));
    }

    public static Snapshot parse(String text) throws Exception {
        Object root = Json.parse(text);
        if (!(root instanceof Map)) {
            throw new IllegalArgumentException("快照根节点不是对象");
        }
        Map<?, ?> r = (Map<?, ?>) root;

        Snapshot snap = new Snapshot();
        snap.exportedAt = str(r.get("exported_at"));
        snap.exportedAtMs = isoToMs(snap.exportedAt);
        snap.note = str(r.get("note"));

        Object daysObj = r.get("days");
        if (daysObj instanceof List) {
            for (Object o : (List<?>) daysObj) {
                if (!(o instanceof Map)) {
                    continue;
                }
                Day d = dayOf((Map<?, ?>) o);
                if (d != null) {
                    snap.days.add(d);
                }
            }
        }
        // 导出按日期升序写，但外部改过的副本不保证，统一排序后交给入库层
        java.util.Collections.sort(snap.days, new java.util.Comparator<Day>() {
            @Override
            public int compare(Day a, Day b) {
                return a.date.compareTo(b.date);
            }
        });

        Object aggObj = r.get("aggregates");
        if (aggObj instanceof Map) {
            for (Map.Entry<?, ?> en : ((Map<?, ?>) aggObj).entrySet()) {
                String kind = str(en.getKey());
                if (!(en.getValue() instanceof List)) {
                    continue;
                }
                for (Object o : (List<?>) en.getValue()) {
                    if (!(o instanceof Map)) {
                        continue;
                    }
                    Map<?, ?> m = (Map<?, ?>) o;
                    Agg a = new Agg();
                    a.kind = kind;
                    a.bucket = isoToMs(str(m.get("bucket_start")));
                    a.pkg = str(m.get("package"));
                    a.label = nullIfEmpty(str(m.get("label")));
                    a.totalMs = num(m.get("total_ms"), minutesToMs(m.get("minutes")));
                    a.lastUsed = isoToMs(str(m.get("last_used")));
                    if (a.pkg.length() > 0 && a.bucket > 0) {
                        snap.aggs.add(a);
                    }
                }
            }
        }
        return snap;
    }

    private static Day dayOf(Map<?, ?> m) {
        Day d = new Day();
        d.date = str(m.get("date"));
        if (d.date.length() < 8) {
            return null;      // 没有日期无法按天覆盖，直接跳过
        }
        d.screenOnMs = num(m.get("screen_on_ms"), minutesToMs(m.get("screen_on_minutes")));
        d.unlocks = (int) num(m.get("unlocks"), 0);
        d.collectedAt = isoToMs(str(m.get("collected_at")));

        Object appsObj = m.get("apps");
        if (appsObj instanceof List) {
            for (Object o : (List<?>) appsObj) {
                if (!(o instanceof Map)) {
                    continue;
                }
                Map<?, ?> am = (Map<?, ?>) o;
                App a = new App();
                a.pkg = str(am.get("package"));
                a.label = nullIfEmpty(str(am.get("label")));
                a.foregroundMs = num(am.get("foreground_ms"), minutesToMs(am.get("minutes")));
                a.launches = (int) num(am.get("launches"), 0);
                a.lastUsed = isoToMs(str(am.get("last_used")));
                if (a.pkg.length() == 0) {
                    continue;
                }
                d.apps.add(a);
                d.foregroundMs += a.foregroundMs;
            }
        }
        // 老版本导出没有逐应用的 foreground_ms 时，用日级前台合计兜底
        if (d.foregroundMs == 0) {
            d.foregroundMs = minutesToMs(m.get("foreground_minutes"));
        }
        return d;
    }

    // ------------------------------------------------------------ 合并判定

    /**
     * 同一天本地已有数据时，是否用快照覆盖。
     *
     * 快照里的 collected_at 是采集端最后一次写这一天的时刻，本地 updated_at 同理。
     * 谁更晚谁更新：重装后的空库会被全量接收；刚采集完再拉旧快照也不会把新数据冲掉。
     */
    public static boolean shouldReplace(long localUpdatedAt, long importedCollectedAt) {
        if (localUpdatedAt <= 0) {
            return true;              // 本地没有这一天
        }
        if (importedCollectedAt <= 0) {
            return false;             // 快照没写采集时间，宁可不动本地
        }
        return importedCollectedAt > localUpdatedAt;
    }

    // ------------------------------------------------------------ 取值辅助

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String nullIfEmpty(String s) {
        return s == null || s.length() == 0 ? null : s;
    }

    private static long num(Object primary, long fallback) {
        if (primary instanceof Number) {
            return ((Number) primary).longValue();
        }
        if (primary instanceof String) {
            try {
                return Long.parseLong(((String) primary).trim());
            } catch (NumberFormatException ignore) {
            }
        }
        return fallback;
    }

    private static long minutesToMs(Object minutes) {
        return num(minutes, 0) * 60000L;
    }

    /** 解析导出里的 "yyyy-MM-dd HH:mm:ss"；空串或格式不符返回 0。 */
    public static long isoToMs(String s) {
        if (s == null || s.length() < 10) {
            return 0;
        }
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        f.setLenient(false);
        try {
            Date d = f.parse(s);
            return d == null ? 0 : d.getTime();
        } catch (ParseException e) {
            return 0;
        }
    }

    /** 供测试与调试：把快照压平成 (日期 → 前台毫秒) 便于比对。 */
    public static Map<String, Long> dayTotals(Snapshot snap) {
        Map<String, Long> m = new LinkedHashMap<String, Long>();
        for (Day d : snap.days) {
            m.put(d.date, d.foregroundMs);
        }
        return m;
    }
}
