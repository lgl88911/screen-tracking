package com.screentime.export;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 累积存储。逐日数据按「整天覆盖」写入：同一日期重复采集结果幂等，
 * 系统保留窗口之外的旧日期不受影响 —— 历史只增不减。
 */
public class UsageStore extends SQLiteOpenHelper {

    public static final String DB_NAME = "screentime.db";
    public static final int DB_VERSION = 1;

    public UsageStore(Context ctx) {
        super(ctx.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE days(" +
                "day TEXT PRIMARY KEY," +
                "screen_on_ms INTEGER NOT NULL DEFAULT 0," +
                "unlock_count INTEGER NOT NULL DEFAULT 0," +
                "foreground_ms INTEGER NOT NULL DEFAULT 0," +
                "updated_at INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE usage(" +
                "day TEXT NOT NULL," +
                "pkg TEXT NOT NULL," +
                "label TEXT," +
                "foreground_ms INTEGER NOT NULL DEFAULT 0," +
                "launch_count INTEGER NOT NULL DEFAULT 0," +
                "last_used INTEGER NOT NULL DEFAULT 0," +
                "PRIMARY KEY(day, pkg))");
        db.execSQL("CREATE INDEX idx_usage_pkg ON usage(pkg)");
        db.execSQL("CREATE TABLE aggs(" +
                "kind TEXT NOT NULL," +
                "bucket INTEGER NOT NULL," +
                "pkg TEXT NOT NULL," +
                "label TEXT," +
                "total_ms INTEGER NOT NULL DEFAULT 0," +
                "last_used INTEGER NOT NULL DEFAULT 0," +
                "PRIMARY KEY(kind, bucket, pkg))");
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // 结构变更时保留历史：只补列，不删表
    }

    // ------------------------------------------------------------ 写入

    public void putMeta(String k, String v) {
        ContentValues cv = new ContentValues();
        cv.put("k", k);
        cv.put("v", v);
        getWritableDatabase().insertWithOnConflict("meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public String getMeta(String k, String def) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT v FROM meta WHERE k=?", new String[]{k});
            if (c.moveToFirst()) return c.getString(0);
            return def;
        } finally {
            if (c != null) c.close();
        }
    }

    /** 整天覆盖写入：先删该日，再插入，保证多次运行结果一致。 */
    public void mergeDay(UsageAnalyzer.DayStat d, java.util.Map<String, String> labels, long now) {
        if (d == null) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("days", "day=?", new String[]{d.day});
            db.delete("usage", "day=?", new String[]{d.day});

            ContentValues cv = new ContentValues();
            cv.put("day", d.day);
            cv.put("screen_on_ms", d.screenOnMs);
            cv.put("unlock_count", d.unlockCount);
            cv.put("foreground_ms", d.totalForegroundMs());
            cv.put("updated_at", now);
            db.insert("days", null, cv);

            for (UsageAnalyzer.AppStat a : d.apps.values()) {
                if (a.foregroundMs <= 0 && a.launchCount <= 0) continue;
                ContentValues uv = new ContentValues();
                uv.put("day", d.day);
                uv.put("pkg", a.pkg);
                uv.put("label", labels == null ? null : labels.get(a.pkg));
                uv.put("foreground_ms", a.foregroundMs);
                uv.put("launch_count", a.launchCount);
                uv.put("last_used", a.lastUsed);
                db.insert("usage", null, uv);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * 聚合桶写入：只覆盖本次返回的桶区间，区间之外的旧桶保留。
     * 系统对 weekly/monthly/yearly 的保留窗口有限（每类只有几个桶），若整类删重插，
     * 历史桶永远无法累积 —— 而跨月/跨年的老桶正是唯一能拿到的长历史。
     */
    public void mergeAggs(String kind, List<Collector.Agg> aggs, java.util.Map<String, String> labels) {
        if (aggs == null || aggs.isEmpty()) return;
        java.util.LinkedHashSet<Long> buckets = new java.util.LinkedHashSet<Long>();
        for (Collector.Agg a : aggs) buckets.add(a.bucket);
        StringBuilder in = new StringBuilder();
        String[] args = new String[buckets.size() + 1];
        args[0] = kind;
        int i = 1;
        for (Long b : buckets) {
            if (i > 1) in.append(',');
            in.append('?');
            args[i++] = String.valueOf(b);
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            // 只覆盖本次确实返回的桶，未返回的老桶保留 —— 长历史全靠这些桶累积
            db.delete("aggs", "kind=? AND bucket IN (" + in + ")", args);
            for (Collector.Agg a : aggs) {
                ContentValues cv = new ContentValues();
                cv.put("kind", kind);
                cv.put("bucket", a.bucket);
                cv.put("pkg", a.pkg);
                cv.put("label", labels == null ? null : labels.get(a.pkg));
                cv.put("total_ms", a.totalMs);
                cv.put("last_used", a.lastUsed);
                db.insertWithOnConflict("aggs", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ------------------------------------------------------------ 从仓库快照导入

    public static class ImportStat {
        public int daysNew;
        public int daysUpdated;
        public int daysSkipped;
        public int appRows;
        public int aggRows;
        public String from;
        public String to;
        public long totalScreenOnMs;

        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("导入 ").append(daysNew + daysUpdated).append(" 天");
            if (from != null) {
                sb.append("（").append(from).append(" ~ ").append(to).append("）");
            }
            sb.append("\n新增 ").append(daysNew).append(" 天 / 更新 ").append(daysUpdated)
                    .append(" 天 / 跳过 ").append(daysSkipped).append(" 天");
            sb.append("\n应用记录 ").append(appRows).append(" 条 / 周期聚合 ").append(aggRows).append(" 条");
            return sb.toString();
        }
    }

    /**
     * 把仓库快照合并进本地库：逐日比较 updated_at，快照更新才覆盖。
     *
     * 用「整天删后重插」而不是逐列更新，与采集侧语义一致：同一天重复导入结果不变。
     * 聚合桶同样只覆盖快照里出现的桶，其他类型的旧桶保留。
     */
    public ImportStat importSnapshot(SnapshotImporter.Snapshot snap) {
        ImportStat st = new ImportStat();
        if (snap == null) {
            return st;
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (SnapshotImporter.Day d : snap.days) {
                long local = localUpdatedAt(db, d.date);
                if (!SnapshotImporter.shouldReplace(local, d.collectedAt)) {
                    st.daysSkipped++;
                    continue;
                }
                boolean existed = local > 0;
                db.delete("days", "day=?", new String[]{d.date});
                db.delete("usage", "day=?", new String[]{d.date});

                ContentValues cv = new ContentValues();
                cv.put("day", d.date);
                cv.put("screen_on_ms", d.screenOnMs);
                cv.put("unlock_count", d.unlocks);
                cv.put("foreground_ms", d.foregroundMs);
                cv.put("updated_at", d.collectedAt);
                db.insert("days", null, cv);

                for (SnapshotImporter.App a : d.apps) {
                    if (a.foregroundMs <= 0 && a.launches <= 0) continue;
                    ContentValues uv = new ContentValues();
                    uv.put("day", d.date);
                    uv.put("pkg", a.pkg);
                    uv.put("label", a.label);
                    uv.put("foreground_ms", a.foregroundMs);
                    uv.put("launch_count", a.launches);
                    uv.put("last_used", a.lastUsed);
                    db.insert("usage", null, uv);
                    st.appRows++;
                }
                if (existed) {
                    st.daysUpdated++;
                } else {
                    st.daysNew++;
                }
                st.totalScreenOnMs += d.screenOnMs;
                if (st.from == null || d.date.compareTo(st.from) < 0) st.from = d.date;
                if (st.to == null || d.date.compareTo(st.to) > 0) st.to = d.date;
            }

            // 聚合桶按 kind 分组，只删快照里返回的桶
            Map<String, java.util.LinkedHashSet<Long>> buckets =
                    new java.util.LinkedHashMap<String, java.util.LinkedHashSet<Long>>();
            java.util.HashSet<String> aggKeys = new java.util.HashSet<String>();
            for (SnapshotImporter.Agg a : snap.aggs) {
                java.util.LinkedHashSet<Long> set = buckets.get(a.kind);
                if (set == null) {
                    set = new java.util.LinkedHashSet<Long>();
                    buckets.put(a.kind, set);
                }
                set.add(a.bucket);
            }
            for (Map.Entry<String, java.util.LinkedHashSet<Long>> en : buckets.entrySet()) {
                StringBuilder in = new StringBuilder();
                String[] args = new String[en.getValue().size() + 1];
                args[0] = en.getKey();
                int i = 1;
                for (Long b : en.getValue()) {
                    if (i > 1) in.append(',');
                    in.append('?');
                    args[i++] = String.valueOf(b);
                }
                db.delete("aggs", "kind=? AND bucket IN (" + in + ")", args);
            }
            for (SnapshotImporter.Agg a : snap.aggs) {
                ContentValues cv = new ContentValues();
                cv.put("kind", a.kind);
                cv.put("bucket", a.bucket);
                cv.put("pkg", a.pkg);
                cv.put("label", a.label);
                cv.put("total_ms", a.totalMs);
                cv.put("last_used", a.lastUsed);
                db.insertWithOnConflict("aggs", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                // 快照里同一个桶会出现多次（每周/每月桶被逐日重复导出），
                // 这里按主键去重计数，否则提示的条数会比库里实际行数多一倍
                aggKeys.add(a.kind + '\u0001' + a.bucket + '\u0001' + a.pkg);
            }
            st.aggRows = aggKeys.size();

            ContentValues meta = new ContentValues();
            meta.put("k", "import_at");
            meta.put("v", String.valueOf(System.currentTimeMillis()));
            db.insertWithOnConflict("meta", null, meta, SQLiteDatabase.CONFLICT_REPLACE);

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return st;
    }

    private static long localUpdatedAt(SQLiteDatabase db, String day) {
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT updated_at FROM days WHERE day=?", new String[]{day});
            return c.moveToFirst() ? c.getLong(0) : 0L;
        } finally {
            if (c != null) c.close();
        }
    }

    // ------------------------------------------------------------ 读取

    public static class DayRow {
        public String day;
        public long screenOnMs;
        public int unlockCount;
        public long foregroundMs;
        public long updatedAt;
    }

    public static class AppRow {
        public String day;
        public String pkg;
        public String label;
        public long foregroundMs;
        public int launchCount;
        public long lastUsed;
    }

    public static class AggRow {
        public String kind;
        public long bucket;
        public String pkg;
        public String label;
        public long totalMs;
        public long lastUsed;
    }

    public List<DayRow> readDays() {
        List<DayRow> out = new ArrayList<DayRow>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT day, screen_on_ms, unlock_count, foreground_ms, updated_at FROM days ORDER BY day", null);
            while (c.moveToNext()) {
                DayRow r = new DayRow();
                r.day = c.getString(0);
                r.screenOnMs = c.getLong(1);
                r.unlockCount = c.getInt(2);
                r.foregroundMs = c.getLong(3);
                r.updatedAt = c.getLong(4);
                out.add(r);
            }
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    public List<AppRow> readUsage() {
        List<AppRow> out = new ArrayList<AppRow>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT day, pkg, label, foreground_ms, launch_count, last_used FROM usage " +
                            "ORDER BY day, foreground_ms DESC", null);
            while (c.moveToNext()) {
                AppRow r = new AppRow();
                r.day = c.getString(0);
                r.pkg = c.getString(1);
                r.label = c.isNull(2) ? null : c.getString(2);
                r.foregroundMs = c.getLong(3);
                r.launchCount = c.getInt(4);
                r.lastUsed = c.getLong(5);
                out.add(r);
            }
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    public List<AggRow> readAggs() {
        List<AggRow> out = new ArrayList<AggRow>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT kind, bucket, pkg, label, total_ms, last_used FROM aggs ORDER BY kind, bucket, total_ms DESC", null);
            while (c.moveToNext()) {
                AggRow r = new AggRow();
                r.kind = c.getString(0);
                r.bucket = c.getLong(1);
                r.pkg = c.getString(2);
                r.label = c.isNull(3) ? null : c.getString(3);
                r.totalMs = c.getLong(4);
                r.lastUsed = c.getLong(5);
                out.add(r);
            }
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    // ------------------------------------------------------------ 区间查询（数据查看页用）

    public static class RangeSummary {
        public long screenOnMs;
        public long foregroundMs;
        public int unlockCount;
        public int dayCount;
    }

    public static class AppTotal {
        public String pkg;
        public String label;
        public long foregroundMs;
        public int launches;
        public int activeDays;
        public long lastUsed;
    }

    public static class DayApp {
        public String day;
        public long foregroundMs;
        public int launches;
    }

    public String firstDay() {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT MIN(day) FROM days", null);
            return c.moveToFirst() && !c.isNull(0) ? c.getString(0) : null;
        } finally {
            if (c != null) c.close();
        }
    }

    public String lastDay() {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT MAX(day) FROM days", null);
            return c.moveToFirst() && !c.isNull(0) ? c.getString(0) : null;
        } finally {
            if (c != null) c.close();
        }
    }

    public RangeSummary summary(String from, String to) {
        RangeSummary r = new RangeSummary();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT IFNULL(SUM(screen_on_ms),0) s, IFNULL(SUM(unlock_count),0) u,"
                            + " COUNT(*) n, IFNULL(SUM(foreground_ms),0) f"
                            + " FROM days WHERE day>=? AND day<=?", new String[]{from, to});
            if (c.moveToFirst()) {
                r.screenOnMs = c.getLong(0);
                r.unlockCount = c.getInt(1);
                r.dayCount = c.getInt(2);
                r.foregroundMs = c.getLong(3);
            }
        } finally {
            if (c != null) c.close();
        }
        return r;
    }

    public List<AppTotal> appTotals(String from, String to) {
        List<AppTotal> out = new ArrayList<AppTotal>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT pkg, MAX(label) lb, SUM(foreground_ms) ms, SUM(launch_count) lc,"
                            + " SUM(CASE WHEN foreground_ms>0 THEN 1 ELSE 0 END) ad, MAX(last_used) lu"
                            + " FROM usage WHERE day>=? AND day<=? GROUP BY pkg"
                            + " ORDER BY ms DESC", new String[]{from, to});
            while (c.moveToNext()) {
                AppTotal a = new AppTotal();
                a.pkg = c.getString(0);
                a.label = c.isNull(1) ? c.getString(0) : c.getString(1);
                a.foregroundMs = c.getLong(2);
                a.launches = c.getInt(3);
                a.activeDays = c.getInt(4);
                a.lastUsed = c.getLong(5);
                out.add(a);
            }
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    public List<DayApp> appDaily(String pkg, String from, String to) {
        List<DayApp> out = new ArrayList<DayApp>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT day, foreground_ms, launch_count FROM usage"
                            + " WHERE pkg=? AND day>=? AND day<=? ORDER BY day", new String[]{pkg, from, to});
            while (c.moveToNext()) {
                DayApp d = new DayApp();
                d.day = c.getString(0);
                d.foregroundMs = c.getLong(1);
                d.launches = c.getInt(2);
                out.add(d);
            }
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    public List<DayRow> daysInRange(String from, String to) {
        List<DayRow> out = new ArrayList<DayRow>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT day, screen_on_ms, unlock_count, foreground_ms, updated_at FROM days"
                            + " WHERE day>=? AND day<=? ORDER BY day", new String[]{from, to});
            while (c.moveToNext()) {
                DayRow r = new DayRow();
                r.day = c.getString(0);
                r.screenOnMs = c.getLong(1);
                r.unlockCount = c.getInt(2);
                r.foregroundMs = c.getLong(3);
                r.updatedAt = c.getLong(4);
                out.add(r);
            }
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    public int countDays() {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM days", null);
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            if (c != null) c.close();
        }
    }

    public int countRows(String table) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + table, null);
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            if (c != null) c.close();
        }
    }

    public void clearAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("days", null, null);
            db.delete("usage", null, null);
            db.delete("aggs", null, null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }
}
