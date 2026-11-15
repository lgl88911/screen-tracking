import com.screentime.export.Json;
import com.screentime.export.SnapshotImporter;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 快照导入的解析与合并判定。
 *
 * 「重装后把仓库里的历史接回本机」全靠这一层：字段读错一天就少一天，
 * 合并方向判错就会把刚采集的新数据冲成旧的。所以样例 JSON 直接照 Exporter 的
 * 输出格式手写，包含转义、乱序、缺字段、聚合桶这几种真实会遇到的情况。
 */
public class ImportTest {

    private static int pass = 0;
    private static int fail = 0;

    private static final String SAMPLE =
            "{\n"
            + "  \"app\": \"屏幕时间导出\",\n"
            + "  \"package\": \"com.screentime.export\",\n"
            + "  \"version\": \"2.0\",\n"
            + "  \"exported_at\": \"2026-11-15 18:00:00\",\n"
            + "  \"note\": \"含\\\"引号\\\"、反斜杠\\\\与\\n换行\",\n"
            + "  \"system_daily_bucket_start_ms\": 1789,\n"
            + "  \"totals\": {\"days\": 2, \"screen_on_minutes\": 90},\n"
            + "  \"days\": [\n"
            + "    {\n"
            + "      \"date\": \"2026-11-14\",\n"
            + "      \"screen_on_minutes\": 60,\n"
            + "      \"screen_on_ms\": 3600000,\n"
            + "      \"unlocks\": 42,\n"
            + "      \"foreground_minutes\": 50,\n"
            + "      \"app_count\": 2,\n"
            + "      \"top_app\": \"抖音\",\n"
            + "      \"collected_at\": \"2026-11-15 06:12:00\",\n"
            + "      \"apps\": [\n"
            + "        {\"package\": \"com.example.a\", \"label\": \"抖音\", \"minutes\": 30, "
            + "\"foreground_ms\": 1800000, \"launches\": 5, \"last_used\": \"2026-11-14 23:10:00\"},\n"
            + "        {\"package\": \"com.example.b\", \"label\": \"微信\", \"minutes\": 20, "
            + "\"foreground_ms\": 1200000, \"launches\": 3, \"last_used\": \"2026-11-14 22:00:00\"}\n"
            + "      ]\n"
            + "    },\n"
            + "    {\n"
            + "      \"date\": \"2026-11-13\",\n"
            + "      \"screen_on_minutes\": 30,\n"
            + "      \"screen_on_ms\": 1800000,\n"
            + "      \"unlocks\": 7,\n"
            + "      \"foreground_minutes\": 25,\n"
            + "      \"app_count\": 1,\n"
            + "      \"collected_at\": \"2026-11-14 06:00:00\",\n"
            + "      \"apps\": [\n"
            + "        {\"package\": \"com.example.c\", \"label\": \"\", \"minutes\": 30, \"launches\": 1}\n"
            + "      ]\n"
            + "    }\n"
            + "  ],\n"
            + "  \"aggregates\": {\n"
            + "    \"weekly\": [\n"
            + "      {\"bucket_start\": \"2026-11-10 00:00:00\", \"package\": \"com.example.a\", "
            + "\"label\": \"抖音\", \"minutes\": 100, \"total_ms\": 6000000, "
            + "\"last_used\": \"2026-11-14 23:10:00\"}\n"
            + "    ],\n"
            + "    \"monthly\": [\n"
            + "      {\"bucket_start\": \"2026-11-01 00:00:00\", \"package\": \"com.example.b\", "
            + "\"minutes\": 240, \"total_ms\": 14400000}\n"
            + "    ]\n"
            + "  }\n"
            + "}\n";

    public static void main(String[] args) throws Exception {
        // ---------------- 结构
        SnapshotImporter.Snapshot s = SnapshotImporter.parse(SAMPLE);
        check("两天都被读出", s.days.size() == 2, "实际 " + s.days.size());
        check("按日期升序重排", "2026-11-13".equals(s.firstDay()), String.valueOf(s.firstDay()));
        check("末日正确", "2026-11-14".equals(s.lastDay()), String.valueOf(s.lastDay()));

        SnapshotImporter.Day d14 = s.days.get(1);
        check("屏幕时长按毫秒读", d14.screenOnMs == 3600000L, String.valueOf(d14.screenOnMs));
        check("解锁次数", d14.unlocks == 42, String.valueOf(d14.unlocks));
        check("前台合计由各应用求和", d14.foregroundMs == 3000000L,
                String.valueOf(d14.foregroundMs));
        check("应用条数", d14.apps.size() == 2, String.valueOf(d14.apps.size()));

        SnapshotImporter.App a0 = d14.apps.get(0);
        check("包名", "com.example.a".equals(a0.pkg), a0.pkg);
        check("应用名保留中文", "抖音".equals(a0.label), String.valueOf(a0.label));
        check("应用时长", a0.foregroundMs == 1800000L, String.valueOf(a0.foregroundMs));
        check("启动次数", a0.launches == 5, String.valueOf(a0.launches));
        check("最后使用时间解析", a0.lastUsed == ms("2026-11-14 23:10:00"),
                String.valueOf(a0.lastUsed));

        // 缺 foreground_ms 时按 minutes 回退；空 label 归一为 null
        SnapshotImporter.Day d13 = s.days.get(0);
        check("缺 foreground_ms 时按分钟回退", d13.foregroundMs == 1800000L,
                String.valueOf(d13.foregroundMs));
        check("空应用名归一为 null", d13.apps.get(0).label == null,
                String.valueOf(d13.apps.get(0).label));
        check("collected_at 解析", d13.collectedAt == ms("2026-11-14 06:00:00"),
                String.valueOf(d13.collectedAt));

        // ---------------- 转义
        check("字符串转义还原", s.note.contains("\"引号\"") && s.note.contains("\\")
                && s.note.contains("\n"), s.note);

        // ---------------- 聚合
        check("聚合条数", s.aggs.size() == 2, String.valueOf(s.aggs.size()));
        SnapshotImporter.Agg w = null;
        SnapshotImporter.Agg m = null;
        for (SnapshotImporter.Agg a : s.aggs) {
            if ("weekly".equals(a.kind)) w = a;
            if ("monthly".equals(a.kind)) m = a;
        }
        check("weekly 桶存在", w != null, "null");
        if (w != null) {
            check("weekly 桶起点", w.bucket == ms("2026-11-10 00:00:00"), String.valueOf(w.bucket));
            check("weekly 时长", w.totalMs == 6000000L, String.valueOf(w.totalMs));
            check("weekly 应用名", "抖音".equals(w.label), String.valueOf(w.label));
        }
        check("monthly 桶存在", m != null, "null");
        if (m != null) {
            check("monthly 缺 total_ms 时按分钟回退", m.totalMs == 14400000L,
                    String.valueOf(m.totalMs));
            check("monthly 缺 label 归一为 null", m.label == null, String.valueOf(m.label));
        }

        // ---------------- 合并方向
        check("本地没有这天 → 覆盖", SnapshotImporter.shouldReplace(0, 1000));
        check("快照更新 → 覆盖", SnapshotImporter.shouldReplace(1000, 2000));
        check("本地更新 → 跳过", !SnapshotImporter.shouldReplace(2000, 1000));
        check("时间相同 → 跳过", !SnapshotImporter.shouldReplace(2000, 2000));
        check("快照没写采集时间 → 不动本地", !SnapshotImporter.shouldReplace(2000, 0));

        // ---------------- 异常输入
        check("空内容报错", throwsOn(""));
        check("非对象根节点报错", throwsOn("[1,2,3]"));
        check("字符串未闭合报错", throwsOn("{\"a\":\"b}"));
        check("尾部多余内容报错", throwsOn("{\"a\":1} x"));
        check("空对象不炸", SnapshotImporter.parse("{}").days.isEmpty());

        // BOM 容忍：用户手工转存过的副本常带 BOM
        SnapshotImporter.Snapshot bom = SnapshotImporter.parse("\uFEFF{\"days\":[]}");
        check("BOM 被跳过", bom.days.isEmpty());

        // 没有 apps 的日期也要收下，屏幕时长仍可用
        SnapshotImporter.Snapshot bare = SnapshotImporter.parse(
                "{\"days\":[{\"date\":\"2026-11-01\",\"screen_on_ms\":60000,"
                        + "\"collected_at\":\"2026-11-02 06:00:00\"}]}");
        check("无应用的日期也保留", bare.days.size() == 1 && bare.days.get(0).screenOnMs == 60000L,
                String.valueOf(bare.days.size()));

        // ---------------- 纯 JSON 解析器
        Object o = Json.parse("{\"a\":[1,2.5,true,false,null,\"s\"]}");
        Map<?, ?> m2 = (Map<?, ?>) o;
        List<?> arr = (List<?>) m2.get("a");
        check("整数解析为 Long", arr.get(0) instanceof Long && ((Long) arr.get(0)) == 1L,
                arr.get(0).getClass().getName());
        check("小数解析为 Double", arr.get(1) instanceof Double
                && Math.abs((Double) arr.get(1) - 2.5) < 1e-9, String.valueOf(arr.get(1)));
        check("布尔与 null", Boolean.TRUE.equals(arr.get(2)) && Boolean.FALSE.equals(arr.get(3))
                && arr.get(4) == null, String.valueOf(arr.get(4)));
        check("负数与指数", ((Long) Json.parse("-12")).longValue() == -12L, "负数");
        check("\\u 转义", "中".equals(Json.parse("\"\\u4e2d\"")), "unicode");

        System.out.println();
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static long ms(String s) throws Exception {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(s).getTime();
    }

    private static boolean throwsOn(String text) {
        try {
            SnapshotImporter.parse(text);
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private static void check(String name, boolean ok) {
        check(name, ok, "断言失败");
    }

    private static void check(String name, boolean ok, String detail) {        if (ok) {
            pass++;
            System.out.println("  ok   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + " — " + detail);
        }
    }
}
