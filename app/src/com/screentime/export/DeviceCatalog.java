package com.screentime.export;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把仓库的文件树整理成「仓库里有哪些设备」。
 *
 * 只发一次 git/trees?recursive=1 就能拿到全部设备的目录、日快照数量、最近一天，
 * 不必按设备逐个列目录 —— 设备多了以后那是 N 次往返。
 *
 * 纯 Java，不引用 android.* 也不引用 org.json，可在桌面 JDK 上单测。
 */
public final class DeviceCatalog {

    /** 一台设备在仓库里的概况。 */
    public static class Device {
        public String id = "";
        /** devices/&lt;id&gt;/latest.sce，没有则为 null。 */
        public String latestPath;
        /** 字典序最大的日快照，如 devices/x/2026/11/15.sce。 */
        public String newestDayPath;
        public int dayCount;
        public long dayBytes;

        /** 最近一天，格式 yyyy/MM/dd；没有日快照则为 null。 */
        public String newestDay() {
            if (newestDayPath == null) {
                return null;
            }
            String rest = newestDayPath.substring(SyncPaths.deviceDir(id).length());
            if (rest.endsWith(".sce")) {
                rest = rest.substring(0, rest.length() - 4);
            }
            return rest;
        }

        /** 一句话概况，给列表的第二行用。 */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            if (dayCount > 0) {
                sb.append(dayCount).append(" 天快照");
                String d = newestDay();
                if (d != null) {
                    sb.append(" · 最近 ").append(d);
                }
            } else if (latestPath != null) {
                sb.append("仅 latest.sce");
            } else {
                sb.append("无快照");
            }
            if (dayBytes > 0) {
                sb.append(" · ").append(size(dayBytes));
            }
            return sb.toString();
        }

        /** 列表里显示的一行：标识 + 概况。 */
        public String line() {
            return id + "（" + summary() + "）";
        }

        /** 有可下载的内容。 */
        public boolean hasData() {
            return latestPath != null || newestDayPath != null;
        }
    }

    private DeviceCatalog() {
    }

    /**
     * 解析 git/trees?recursive=1 的响应。树被 GitHub 截断（truncated）时
     * 仍返回已看到的部分 —— 少列几台设备，好过整页报错。
     */
    public static List<Device> parseTree(String treeJson) {
        List<Device> out = new ArrayList<Device>();
        if (treeJson == null) {
            return out;
        }
        Object root;
        try {
            root = Json.parse(treeJson);
        } catch (Throwable t) {
            return out;
        }
        if (!(root instanceof Map)) {
            return out;
        }
        Object tree = ((Map<?, ?>) root).get("tree");
        if (!(tree instanceof List)) {
            return out;
        }
        Map<String, Device> byId = new LinkedHashMap<String, Device>();
        for (Object o : (List<?>) tree) {
            if (!(o instanceof Map)) {
                continue;
            }
            Map<?, ?> e = (Map<?, ?>) o;
            if (!"blob".equals(e.get("type"))) {
                continue;
            }
            Object p = e.get("path");
            if (!(p instanceof String)) {
                continue;
            }
            long size = 0;
            Object s = e.get("size");
            if (s instanceof Number) {
                size = ((Number) s).longValue();
            }
            collect(byId, (String) p, size);
        }
        out.addAll(byId.values());
        Collections.sort(out, new java.util.Comparator<Device>() {
            @Override
            public int compare(Device a, Device b) {
                return a.id.compareTo(b.id);
            }
        });
        return out;
    }

    /**
     * 回退路径：解析 contents/devices 的响应（只在拿不到文件树时用，比如空仓库）。
     * 这种响应只有目录名，没有快照统计。
     */
    public static List<Device> parseContents(String contentsJson) {
        List<Device> out = new ArrayList<Device>();
        if (contentsJson == null) {
            return out;
        }
        Object root;
        try {
            root = Json.parse(contentsJson);
        } catch (Throwable t) {
            return out;
        }
        if (!(root instanceof List)) {
            return out;
        }
        List<String> ids = new ArrayList<String>();
        for (Object o : (List<?>) root) {
            if (!(o instanceof Map)) {
                continue;
            }
            Map<?, ?> e = (Map<?, ?>) o;
            if (!"dir".equals(e.get("type"))) {
                continue;
            }
            Object name = e.get("name");
            if (!(name instanceof String)) {
                continue;
            }
            String id = SyncPaths.sanitizeDeviceId((String) name);
            if (id != null && !ids.contains(id)) {
                ids.add(id);
            }
        }
        Collections.sort(ids);
        for (String id : ids) {
            Device d = new Device();
            d.id = id;
            out.add(d);
        }
        return out;
    }

    // ------------------------------------------------------------ 内部

    /** 把一条文件路径归到对应设备上；不属于 devices/ 的一律忽略。 */
    private static void collect(Map<String, Device> byId, String path, long size) {
        if (!path.startsWith(SyncPaths.ROOT)) {
            return;
        }
        String rest = path.substring(SyncPaths.ROOT.length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            return;
        }
        String id = SyncPaths.sanitizeDeviceId(rest.substring(0, slash));
        if (id == null) {
            return;
        }
        String inner = rest.substring(slash + 1);
        if (inner.length() == 0) {
            return;
        }
        Device d = byId.get(id);
        if (d == null) {
            d = new Device();
            d.id = id;
            byId.put(id, d);
        }
        if ("latest.sce".equals(inner)) {
            d.latestPath = path;
            return;
        }
        if (isDayPath(inner)) {
            d.dayCount++;
            d.dayBytes += size;
            if (d.newestDayPath == null || path.compareTo(d.newestDayPath) > 0) {
                d.newestDayPath = path;
            }
        }
    }

    /** 日快照的形态：yyyy/MM/dd.sce。其他内容（README、手工放的文件）不算。 */
    private static boolean isDayPath(String inner) {
        // "2026/11/15.sce" —— 14 个字符，前 10 位是日期，后 4 位固定
        if (!inner.endsWith(".sce") || inner.length() != 14) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            char c = inner.charAt(i);
            if (i == 4 || i == 7) {
                if (c != '/') {
                    return false;
                }
            } else if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }
}
