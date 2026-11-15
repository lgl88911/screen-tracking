package com.screentime.export;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 导出：JSON（完整）+ 4 份 CSV（汇总/逐应用/应用总计/周期聚合）。
 * 同时写 下载/ScreentimeExport（手机端可自取）与 app 外部私有目录 exports/（adb pull 可拉）。
 */
public class Exporter {

    public static final String SUB_DIR = "ScreentimeExport";

    public static class Result {
        public List<String> written = new ArrayList<String>();
        public List<String> failed = new ArrayList<String>();
        public String dirHint;
        public String stamp;
        public int dayCount;
        public int appRows;
        public long totalScreenOnMs;
    }

    public static Result exportAll(Context ctx) {
        Result res = new Result();
        UsageStore store = new UsageStore(ctx);
        try {
            List<UsageStore.DayRow> days = store.readDays();
            List<UsageStore.AppRow> usage = store.readUsage();
            List<UsageStore.AggRow> aggs = store.readAggs();

            res.dayCount = days.size();
            res.appRows = usage.size();
            long sum = 0;
            for (UsageStore.DayRow d : days) sum += d.screenOnMs;
            res.totalScreenOnMs = sum;

            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());

            long sysBucket = 0;
            try {
                sysBucket = Long.parseLong(store.getMeta("system_day_bucket", "0"));
            } catch (Throwable ignore) {
            }

            // 固定文件名：导出的永远是「当前全量快照」，历史累积在数据库里，
            // 不靠文件多版本，否则每天点一次就堆几百 KB。
            Map<String, byte[]> files = new LinkedHashMap<String, byte[]>();
            files.put("screentime.json",
                    buildJson(ctx, days, usage, aggs, sysBucket).getBytes("UTF-8"));
            files.put("daily_summary.csv", buildDailyCsv(days, usage).getBytes("UTF-8"));
            files.put("app_daily.csv", buildAppDailyCsv(usage).getBytes("UTF-8"));
            files.put("app_total.csv", buildAppTotalCsv(days, usage).getBytes("UTF-8"));
            files.put("aggregates.csv", buildAggCsv(aggs).getBytes("UTF-8"));
            res.stamp = stamp;

            for (Map.Entry<String, byte[]> en : files.entrySet()) {
                if (writeOne(ctx, en.getKey(), en.getValue())) res.written.add(en.getKey());
                else res.failed.add(en.getKey());
            }
            cleanLegacy(new File(ctx.getExternalFilesDir(null), "exports"));
            res.dirHint = "下载/" + SUB_DIR + "  |  adb pull /sdcard/Android/data/"
                    + ctx.getPackageName() + "/files/exports/";
            return res;
        } catch (Throwable t) {
            res.failed.add("EXPORT_ERROR " + t);
            return res;
        } finally {
            store.close();
        }
    }

    /** 清掉早期版本留下的带时间戳快照，避免长期运行后目录膨胀。 */
    private static void cleanLegacy(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        String[] prefixes = {"screentime-", "daily_summary-", "app_daily-",
                "app_total-", "aggregates-"};
        for (File f : fs) {
            String n = f.getName();
            for (String p : prefixes) {
                if (n.startsWith(p) && !f.delete()) break;
            }
        }
    }

    // ------------------------------------------------------------ 写文件

    private static boolean writeOne(Context ctx, String name, byte[] data) {
        boolean ok = false;
        // 1) 应用外部私有目录（adb pull 用，不需要任何权限）
        try {
            File dir = new File(ctx.getExternalFilesDir(null), "exports");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, name);
            FileOutputStream fos = new FileOutputStream(f);
            try {
                fos.write(data);
            } finally {
                fos.close();
            }
            ok = true;
        } catch (Throwable ignore) {
        }

        // 2) 公共下载目录（MediaStore，手机端文件管理器可见）
        if (Build.VERSION.SDK_INT >= 29) {
            OutputStream os = null;
            try {
                ContentResolver cr = ctx.getContentResolver();
                String rel = Environment.DIRECTORY_DOWNLOADS + "/" + SUB_DIR;
                // 固定文件名，先删同名旧项，否则 MediaStore 会生成「screentime (1).json」
                cr.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                                + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                        new String[]{name, rel + "/"});
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(MediaStore.MediaColumns.MIME_TYPE,
                        name.endsWith(".json") ? "application/json" : "text/csv");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri != null) {
                    os = cr.openOutputStream(uri);
                    os.write(data);
                    ok = true;
                }
            } catch (Throwable ignore) {
            } finally {
                if (os != null) try { os.close(); } catch (Throwable ignore) { }
            }
        }
        return ok;
    }

    // ------------------------------------------------------------ JSON

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String iso(long ts) {
        if (ts <= 0) return "";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(ts));
    }

    private static void kv(StringBuilder sb, String k, String v, boolean comma) {
        sb.append("      \"").append(k).append("\": \"").append(esc(v)).append('"');
        sb.append(comma ? ",\n" : "\n");
    }

    private static void kvel(StringBuilder sb, String k, long v, boolean comma) {
        sb.append("      \"").append(k).append("\": ").append(v);
        sb.append(comma ? ",\n" : "\n");
    }

    private static String buildJson(Context ctx, List<UsageStore.DayRow> days,
                                    List<UsageStore.AppRow> usage, List<UsageStore.AggRow> aggs,
                                    long sysBucket) throws Exception {        Map<String, List<UsageStore.AppRow>> byDay = new TreeMap<String, List<UsageStore.AppRow>>();
        for (UsageStore.AppRow r : usage) {
            List<UsageStore.AppRow> l = byDay.get(r.day);
            if (l == null) {
                l = new ArrayList<UsageStore.AppRow>();
                byDay.put(r.day, l);
            }
            l.add(r);
        }
        Map<String, List<UsageStore.AggRow>> byKind = new LinkedHashMap<String, List<UsageStore.AggRow>>();
        for (UsageStore.AggRow r : aggs) {
            List<UsageStore.AggRow> l = byKind.get(r.kind);
            if (l == null) {
                l = new ArrayList<UsageStore.AggRow>();
                byKind.put(r.kind, l);
            }
            l.add(r);
        }

        StringBuilder sb = new StringBuilder(1 << 20);
        sb.append("{\n");
        kv(sb, "app", "屏幕时间导出", true);
        kv(sb, "package", ctx.getPackageName(), true);
        kv(sb, "version", "2.0", true);
        kv(sb, "exported_at", iso(System.currentTimeMillis()), true);
        kv(sb, "source", "android.app.usage.UsageStatsManager", true);
        kv(sb, "note", "时长口径为系统前台时长（UsageStats.totalTimeInForeground 同源），由前台会话事件重建；"
                + "系统逐日记录仅保留约 10 天，本文件为多次采集的累积结果。"
                + "days 按本地自然日（零点）切分；aggregates 原样保留系统自己的周期桶，二者日界可能不同", true);
        kvel(sb, "system_daily_bucket_start_ms", sysBucket, true);
        kv(sb, "system_daily_bucket_start", iso(sysBucket), true);
        long totalScreenOn = 0;
        for (UsageStore.DayRow d : days) totalScreenOn += d.screenOnMs;
        sb.append("  \"totals\": {\n");
        kvel(sb, "days", days.size(), true);
        kvel(sb, "screen_on_minutes", Math.round(totalScreenOn / 60000.0), true);
        sb.append("      \"screen_on_hours\": ")
                .append(Math.round(totalScreenOn / 36000.0) / 100.0).append(",\n");
        kvel(sb, "app_records", usage.size(), false);
        sb.append("  },\n");
        sb.append("  \"days\": [\n");

        for (int i = 0; i < days.size(); i++) {
            UsageStore.DayRow d = days.get(i);
            List<UsageStore.AppRow> list = byDay.get(d.day);
            if (list == null) list = new ArrayList<UsageStore.AppRow>();

            long topMs = 0;
            String topName = "";
            for (UsageStore.AppRow a : list) {
                if (a.foregroundMs > topMs) {
                    topMs = a.foregroundMs;
                    topName = a.label == null ? a.pkg : a.label;
                }
            }

            sb.append("    {\n");
            kv(sb, "date", d.day, true);
            kvel(sb, "screen_on_minutes", Math.round(d.screenOnMs / 60000.0), true);
            kvel(sb, "screen_on_ms", d.screenOnMs, true);
            kvel(sb, "unlocks", d.unlockCount, true);
            kvel(sb, "foreground_minutes", Math.round(d.foregroundMs / 60000.0), true);
            kvel(sb, "app_count", list.size(), true);
            kv(sb, "top_app", topName, true);
            kvel(sb, "top_app_minutes", Math.round(topMs / 60000.0), true);
            kv(sb, "collected_at", iso(d.updatedAt), true);
            sb.append("      \"apps\": [\n");
            for (int j = 0; j < list.size(); j++) {
                UsageStore.AppRow a = list.get(j);
                sb.append("        {");
                sb.append("\"package\": \"").append(esc(a.pkg)).append("\", ");
                sb.append("\"label\": \"").append(esc(a.label == null ? a.pkg : a.label)).append("\", ");
                sb.append("\"minutes\": ").append(Math.round(a.foregroundMs / 60000.0)).append(", ");
                sb.append("\"foreground_ms\": ").append(a.foregroundMs).append(", ");
                sb.append("\"launches\": ").append(a.launchCount).append(", ");
                sb.append("\"last_used\": \"").append(iso(a.lastUsed)).append("\"");
                sb.append("}").append(j + 1 < list.size() ? "," : "").append("\n");
            }
            sb.append("      ]\n");
            sb.append("    }").append(i + 1 < days.size() ? "," : "").append("\n");
        }
        sb.append("  ],\n");

        sb.append("  \"aggregates\": {\n");
        int ki = 0;
        for (Map.Entry<String, List<UsageStore.AggRow>> en : byKind.entrySet()) {
            sb.append("    \"").append(esc(en.getKey())).append("\": [\n");
            List<UsageStore.AggRow> list = en.getValue();
            for (int j = 0; j < list.size(); j++) {
                UsageStore.AggRow a = list.get(j);
                sb.append("      {");
                sb.append("\"bucket_start\": \"").append(iso(a.bucket)).append("\", ");
                sb.append("\"package\": \"").append(esc(a.pkg)).append("\", ");
                sb.append("\"label\": \"").append(esc(a.label == null ? a.pkg : a.label)).append("\", ");
                sb.append("\"minutes\": ").append(Math.round(a.totalMs / 60000.0)).append(", ");
                sb.append("\"total_ms\": ").append(a.totalMs).append(", ");
                sb.append("\"last_used\": \"").append(iso(a.lastUsed)).append("\"");
                sb.append("}").append(j + 1 < list.size() ? "," : "").append("\n");
            }
            sb.append("    ]").append(++ki < byKind.size() ? "," : "").append("\n");
        }
        sb.append("  }\n");
        sb.append("}\n");
        return sb.toString();
    }

    // ------------------------------------------------------------ CSV

    private static final byte[] BOM = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private static String csv(String... cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(q(cells[i]));
        }
        sb.append("\r\n");
        return sb.toString();
    }

    private static String q(String s) {
        if (s == null) s = "";
        boolean need = s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        if (!need) return s;
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    private static String buildDailyCsv(List<UsageStore.DayRow> days, List<UsageStore.AppRow> usage) {
        Map<String, List<UsageStore.AppRow>> byDay = new TreeMap<String, List<UsageStore.AppRow>>();
        for (UsageStore.AppRow r : usage) {
            List<UsageStore.AppRow> l = byDay.get(r.day);
            if (l == null) {
                l = new ArrayList<UsageStore.AppRow>();
                byDay.put(r.day, l);
            }
            l.add(r);
        }
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        sb.append(csv("日期", "屏幕使用(分钟)", "屏幕使用(时:分)", "解锁次数", "前台合计(分钟)",
                "使用应用数", "使用最多的应用", "该应用(分钟)"));
        for (UsageStore.DayRow d : days) {
            List<UsageStore.AppRow> list = byDay.get(d.day);
            if (list == null) list = new ArrayList<UsageStore.AppRow>();
            long topMs = 0;
            String top = "";
            for (UsageStore.AppRow a : list) {
                if (a.foregroundMs > topMs) {
                    topMs = a.foregroundMs;
                    top = a.label == null ? a.pkg : a.label;
                }
            }
            sb.append(csv(d.day,
                    String.valueOf(Math.round(d.screenOnMs / 60000.0)),
                    hhmm(d.screenOnMs),
                    String.valueOf(d.unlockCount),
                    String.valueOf(Math.round(d.foregroundMs / 60000.0)),
                    String.valueOf(list.size()),
                    top,
                    String.valueOf(Math.round(topMs / 60000.0))));
        }
        return sb.toString();
    }

    private static String buildAppDailyCsv(List<UsageStore.AppRow> usage) {
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        sb.append(csv("日期", "包名", "应用名", "前台时长(分钟)", "前台时长(秒)", "启动次数", "最后使用时间"));
        for (UsageStore.AppRow a : usage) {
            sb.append(csv(a.day, a.pkg, a.label == null ? a.pkg : a.label,
                    String.valueOf(Math.round(a.foregroundMs / 60000.0)),
                    String.valueOf(a.foregroundMs / 1000),
                    String.valueOf(a.launchCount),
                    iso(a.lastUsed)));
        }
        return sb.toString();
    }

    private static String buildAppTotalCsv(List<UsageStore.DayRow> days, List<UsageStore.AppRow> usage) {
        Map<String, long[]> acc = new LinkedHashMap<String, long[]>(); // pkg -> {ms, launches, days, last}
        Map<String, String> labels = new LinkedHashMap<String, String>();
        for (UsageStore.AppRow a : usage) {
            long[] v = acc.get(a.pkg);
            if (v == null) {
                v = new long[]{0, 0, 0, 0};
                acc.put(a.pkg, v);
            }
            v[0] += a.foregroundMs;
            v[1] += a.launchCount;
            if (a.foregroundMs > 0) v[2]++;
            if (a.lastUsed > v[3]) v[3] = a.lastUsed;
            labels.put(a.pkg, a.label == null ? a.pkg : a.label);
        }
        List<Map.Entry<String, long[]>> list = new ArrayList<Map.Entry<String, long[]>>(acc.entrySet());
        java.util.Collections.sort(list, new java.util.Comparator<Map.Entry<String, long[]>>() {
            @Override
            public int compare(Map.Entry<String, long[]> x, Map.Entry<String, long[]> y) {
                long a = x.getValue()[0], b = y.getValue()[0];
                return a == b ? 0 : (a < b ? 1 : -1);
            }
        });
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        sb.append(csv("包名", "应用名", "累计时长(分钟)", "累计时长(时:分)", "活跃天数",
                "日均(活跃日,分钟)", "启动次数", "最后使用时间"));
        for (Map.Entry<String, long[]> en : list) {
            long[] v = en.getValue();
            double avg = v[2] > 0 ? (v[0] / 60000.0 / v[2]) : 0;
            sb.append(csv(en.getKey(), labels.get(en.getKey()),
                    String.valueOf(Math.round(v[0] / 60000.0)),
                    hhmm(v[0]),
                    String.valueOf(v[2]),
                    String.format(Locale.US, "%.1f", avg),
                    String.valueOf(v[1]),
                    iso(v[3])));
        }
        return sb.toString();
    }

    private static String buildAggCsv(List<UsageStore.AggRow> aggs) {
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        sb.append(csv("区间类型", "区间起点", "包名", "应用名", "时长(分钟)", "时长(时:分)", "最后使用时间"));
        for (UsageStore.AggRow a : aggs) {
            sb.append(csv(a.kind, iso(a.bucket), a.pkg, a.label == null ? a.pkg : a.label,
                    String.valueOf(Math.round(a.totalMs / 60000.0)),
                    hhmm(a.totalMs),
                    iso(a.lastUsed)));
        }
        return sb.toString();
    }

    private static String hhmm(long ms) {
        long min = ms / 60000;
        return String.format(Locale.US, "%d:%02d", min / 60, min % 60);
    }
}
