import com.screentime.export.DeviceCatalog;

import java.util.List;

/**
 * 「列出仓库里所有设备」的解析逻辑。
 *
 * 这一层错了不会报错，只会少列一台设备或多报几天 —— 用户在重装后看到的列表
 * 跟他记忆里的对不上，却不知道是哪里错了。所以样例直接照 GitHub git/trees 的
 * 真实响应形态手写：目录项（type=tree）、无关文件、超长截断、大小写目录名。
 */
public class DeviceCatalogTest {

    private static int pass = 0;
    private static int fail = 0;

    /** 两台设备 + 一个无关文件 + 一个目录项，模拟真实 tree 响应。 */
    private static final String TREE =
            "{\n"
            + "  \"sha\": \"abc123\",\n"
            + "  \"url\": \"https://api.github.com/repos/o/r/git/trees/abc123\",\n"
            + "  \"truncated\": false,\n"
            + "  \"tree\": [\n"
            + "    {\"path\": \"README.md\", \"mode\": \"100644\", \"type\": \"blob\", \"size\": 120},\n"
            + "    {\"path\": \"devices\", \"mode\": \"040000\", \"type\": \"tree\"},\n"
            + "    {\"path\": \"devices/22101320c-6f5a\", \"mode\": \"040000\", \"type\": \"tree\"},\n"
            + "    {\"path\": \"devices/22101320c-6f5a/latest.sce\", \"type\": \"blob\", \"size\": 76400},\n"
            + "    {\"path\": \"devices/22101320c-6f5a/2026/11/13.sce\", \"type\": \"blob\", \"size\": 70000},\n"
            + "    {\"path\": \"devices/22101320c-6f5a/2026/11/14.sce\", \"type\": \"blob\", \"size\": 72000},\n"
            + "    {\"path\": \"devices/22101320c-6f5a/2026/11/15.sce\", \"type\": \"blob\", \"size\": 76400},\n"
            + "    {\"path\": \"devices/phone-b-1a2b\", \"type\": \"tree\"},\n"
            + "    {\"path\": \"devices/phone-b-1a2b/latest.sce\", \"type\": \"blob\", \"size\": 1024},\n"
            + "    {\"path\": \"devices/phone-b-1a2b/README.txt\", \"type\": \"blob\", \"size\": 10}\n"
            + "  ]\n"
            + "}";

    public static void main(String[] args) {
        System.out.println("DeviceCatalog 测试");

        List<DeviceCatalog.Device> devs = DeviceCatalog.parseTree(TREE);
        check("两台设备", devs.size() == 2, "实际 " + devs.size());
        check("按标识排序", devs.get(0).id.equals("22101320c-6f5a")
                && devs.get(1).id.equals("phone-b-1a2b"), devs.get(0).id + "," + devs.get(1).id);

        DeviceCatalog.Device a = devs.get(0);
        check("日快照计数只算 yyyy/MM/dd.sce", a.dayCount == 3, "实际 " + a.dayCount);
        check("最新一天取字典序最大", "2026/11/15".equals(a.newestDay()), "实际 " + a.newestDay());
        check("有 latest.sce", a.latestPath != null
                && a.latestPath.equals("devices/22101320c-6f5a/latest.sce"), "" + a.latestPath);
        check("大小按日快照求和（不含 latest 副本）", a.dayBytes == 70000 + 72000 + 76400,
                "实际 " + a.dayBytes);
        check("有可下载内容", a.hasData(), "false");

        DeviceCatalog.Device b = devs.get(1);
        check("只有 latest 时仍算一台设备", b.dayCount == 0 && b.hasData(), "dayCount=" + b.dayCount);
        check("只有 latest 的概况", b.summary().startsWith("仅 latest.sce"), b.summary());
        check("最新一天为 null", b.newestDay() == null, "" + b.newestDay());

        // 列表里的一行：标识 + 概况，不能把 id 显示丢
        String line = a.line();
        check("列表行含标识与概况", line.startsWith("22101320c-6f5a（3 天快照")
                && line.contains("最近 2026/11/15"), line);

        // 目录项（type=tree）不该被当成文件，否则每台设备会多算一天
        check("目录项不计入日快照", a.dayCount == 3, "实际 " + a.dayCount);

        // 无关文件与非法路径
        check("README 不计入任何设备", devs.size() == 2, "实际 " + devs.size());
        List<DeviceCatalog.Device> junk = DeviceCatalog.parseTree(
                "{\"tree\":[{\"path\":\"devices/x/2026/1/5.sce\",\"type\":\"blob\"},"
                        + "{\"path\":\"devices/x/2026/11/15.sce.bak\",\"type\":\"blob\"},"
                        + "{\"path\":\"devices/x/notes.md\",\"type\":\"blob\"},"
                        + "{\"path\":\"other/x/2026/11/15.sce\",\"type\":\"blob\"}]}");
        check("位数不足/后缀不对/前缀不对都不算日快照",
                junk.size() == 1 && junk.get(0).dayCount == 0, "设备数 " + junk.size()
                        + " 快照数 " + (junk.isEmpty() ? "-" : junk.get(0).dayCount));

        // 目录名大小写与非法字符：清洗成合法标识后再比对，避免出现两个目录被当成两台设备
        List<DeviceCatalog.Device> messy = DeviceCatalog.parseTree(
                "{\"tree\":[{\"path\":\"devices/22101320C-6F5A/latest.sce\",\"type\":\"blob\"},"
                        + "{\"path\":\"devices/22101320c-6f5a/2026/11/15.sce\",\"type\":\"blob\"}]}");
        check("大写目录与同名小写目录合并为一台",
                messy.size() == 1 && messy.get(0).id.equals("22101320c-6f5a"),
                "设备数 " + messy.size());

        // 截断的树仍要能用
        List<DeviceCatalog.Device> trunc = DeviceCatalog.parseTree(
                "{\"truncated\":true,\"tree\":[{\"path\":\"devices/p-1/latest.sce\",\"type\":\"blob\"}]}");
        check("树被截断仍返回已看到的设备", trunc.size() == 1, "实际 " + trunc.size());

        // 异常输入一律当「没有设备」，不能抛到界面上
        check("非法 JSON 返回空", DeviceCatalog.parseTree("{oops").isEmpty(), "非空");
        check("null 返回空", DeviceCatalog.parseTree(null).isEmpty(), "非空");
        check("缺 tree 字段返回空", DeviceCatalog.parseTree("{\"sha\":\"x\"}").isEmpty(), "非空");

        // 回退路径：contents/devices 只给目录名，没有统计
        List<DeviceCatalog.Device> byContents = DeviceCatalog.parseContents(
                "[{\"name\":\"phone-b-1a2b\",\"type\":\"dir\"},"
                        + "{\"name\":\"22101320c-6f5a\",\"type\":\"dir\"},"
                        + "{\"name\":\"README.md\",\"type\":\"file\"}]");
        check("contents 回退只取目录且排序", byContents.size() == 2
                && byContents.get(0).id.equals("22101320c-6f5a"), "实际 " + byContents.size());
        check("contents 回退没有统计信息",
                byContents.get(0).dayCount == 0 && byContents.get(0).latestPath == null, "有统计");

        System.out.println();
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void check(String name, boolean ok) {
        check(name, ok, "断言失败");
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
