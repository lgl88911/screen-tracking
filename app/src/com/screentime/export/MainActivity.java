package com.screentime.export;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 单页界面：采集进度、数据概览、导出入口。UI 全代码构建，不依赖 layout XML。 */
public class MainActivity extends Activity {

    private TextView status;
    private Button runBtn;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private static final long AUTO_INTERVAL_MS = 6L * 60 * 60 * 1000;
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(24));
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("屏幕时间导出");
        title.setTextSize(24);
        title.setTextColor(Color.rgb(20, 20, 20));
        root.addView(title, lp(-1, -2));

        TextView sub = new TextView(this);
        sub.setText("读取系统 UsageStats 使用记录，累积入库后导出 JSON / CSV");
        sub.setTextSize(13);
        sub.setTextColor(Color.rgb(120, 120, 120));
        sub.setPadding(0, dp(6), 0, dp(18));
        root.addView(sub, lp(-1, -2));

        runBtn = new Button(this);
        runBtn.setText("采集并导出");
        runBtn.setAllCaps(false);
        runBtn.setTextSize(16);
        runBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runCollect(true);
            }
        });
        root.addView(runBtn, lp(-1, dp(48)));

        Button permBtn = new Button(this);
        permBtn.setText("检查 / 开启「使用情况访问」");
        permBtn.setAllCaps(false);
        permBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openUsageSettings();
            }
        });
        root.addView(permBtn, lp(-1, dp(44)));

        Button schedBtn = new Button(this);
        schedBtn.setText("开启后台自动采集（每 6 小时）");
        schedBtn.setAllCaps(false);
        schedBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean ok = AutoCollectService.schedule(MainActivity.this);
                Toast.makeText(MainActivity.this,
                        ok ? "已排期：每 6 小时后台采集一次" : "后台采集排期失败", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(schedBtn, lp(-1, dp(44)));

        Button reportBtn = new Button(this);
        reportBtn.setText("查看使用报告（可按时间段筛选）");
        reportBtn.setAllCaps(false);
        reportBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, ReportActivity.class));
            }
        });
        root.addView(reportBtn, lp(-1, dp(44)));

        Button syncBtn = new Button(this);
        syncBtn.setText("同步到私有仓库（加密可选）");
        syncBtn.setAllCaps(false);
        syncBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SyncActivity.class));
            }
        });
        root.addView(syncBtn, lp(-1, dp(44)));

        status = new TextView(this);
        status.setTextSize(13);
        status.setTextColor(Color.rgb(40, 40, 40));
        status.setPadding(0, dp(18), 0, dp(8));
        status.setTextIsSelectable(true);
        root.addView(status, lp(-1, -2));

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        refreshStatus();
        maybeGuideDevice();
    }

    /**
     * 首次使用（含重装后数据被清空）先确定设备标识。
     *
     * 这一步必须挡在采集之前：标识一旦定下来就不能改，若等到后台自动同步时
     * 才顺手生成，用户就再没机会指回仓库里原有的设备目录。
     */
    private void maybeGuideDevice() {
        if (SyncManager.isBound(this)) {
            return;
        }
        final String suggest = SyncManager.suggestedDeviceId(this);
        new AlertDialog.Builder(this)
                .setTitle("首次使用：确定设备标识")
                .setMessage("每台手机在仓库里占一个目录，标识确定后不可修改。\n\n"
                        + "· 重装应用：选「从仓库恢复」，会列出仓库里的全部设备，"
                        + "选中哪台就用它的标识，并把这台设备的历史数据拉回本机\n"
                        + "· 这台手机第一次用：新建本机标识\n\n"
                        + "本机候选标识：" + suggest)
                .setPositiveButton("从仓库恢复", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        startActivity(new Intent(MainActivity.this, SyncActivity.class)
                                .putExtra(SyncActivity.EXTRA_PICK, true));
                    }
                })
                .setNeutralButton("新建本机标识", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (SyncManager.bind(MainActivity.this, suggest)) {
                            Toast.makeText(MainActivity.this, "已绑定 " + suggest,
                                    Toast.LENGTH_SHORT).show();
                            refreshStatus();
                        }
                    }
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        maybeAutoCollect();
    }

    /** 距上次采集超过 6 小时就静默补采一次，不导出文件。
     *  这是后台 Job 被系统省电策略掐掉时的兜底，避免超过系统 11 天窗口造成断档。 */
    private void maybeAutoCollect() {
        if (busy || !Collector.hasUsageAccess(this)) return;
        long last = 0;
        UsageStore s = new UsageStore(this);
        try {
            String v = s.getMeta("last_collect", null);
            if (v != null) last = Long.parseLong(v);
        } catch (Throwable ignore) {
        } finally {
            s.close();
        }
        if (System.currentTimeMillis() - last < AUTO_INTERVAL_MS) return;
        runCollect(false);
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        if (h > 0) p.topMargin = dp(6);
        return p;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ------------------------------------------------------------ 状态

    private void refreshStatus() {
        StringBuilder sb = new StringBuilder();
        boolean granted = Collector.hasUsageAccess(this);
        sb.append("使用情况访问权限：").append(granted ? "已授予" : "未授予（必须授予才能读取）").append('\n');

        UsageStore store = new UsageStore(this);
        try {
            List<UsageStore.DayRow> days = store.readDays();
            int appRows = store.countRows("usage");
            int aggRows = store.countRows("aggs");
            sb.append("本地库：").append(days.size()).append(" 天 / ")
                    .append(appRows).append(" 条应用记录 / ")
                    .append(aggRows).append(" 条周期聚合\n");
            if (!days.isEmpty()) {
                long total = 0;
                for (UsageStore.DayRow d : days) total += d.screenOnMs;
                sb.append("覆盖区间：").append(days.get(0).day).append(" ~ ")
                        .append(days.get(days.size() - 1).day).append('\n');
                sb.append("屏幕累计：").append(hhmm(total)).append('\n');
            }
            String last = store.getMeta("last_collect", null);
            if (last != null) {
                sb.append("上次采集：").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                        .format(new Date(Long.parseLong(last)))).append('\n');
            }
        } finally {
            store.close();
        }

        sb.append("设备标识：").append(SyncManager.isBound(this)
                ? SyncManager.deviceId(this) + "（已锁定）" : "未确定").append('\n');

        long syncOk = SyncManager.lastSuccess(this);
        if (syncOk > 0) {
            long hours = (System.currentTimeMillis() - syncOk) / 3600000L;
            sb.append("上次同步：").append(hours < 1 ? "1 小时内" : hours + " 小时前");
            if (hours >= 48) {
                sb.append("  ⚠ 已超 48 小时");
            }
            sb.append('\n');
        } else if (SyncManager.load(this).hasRepo()) {
            sb.append("上次同步：从未成功\n");
        }
        sb.append("\n导出位置：\n下载/ScreentimeExport/");
        status.setText(sb.toString());
    }

    /** 已配置仓库就顺带同步一次；未配置、未绑定或缺少必要密钥时返回 null。 */
    private String syncIfConfigured() {
        SyncManager.Config c = SyncManager.load(this);
        if (!c.hasRepo() || SyncManager.getToken(this) == null) {
            return null;
        }
        if (!SyncManager.isBound(this)) {
            return "未同步：尚未确定设备标识";
        }
        if (c.mode == Crypto.MODE_PASSWORD && !SyncManager.hasPassword(this)) {
            return "未同步：密码模式尚未设置密码";
        }
        SyncManager.Result r = SyncManager.syncNow(this);
        return r.ok ? "已同步：" + r.message : "同步失败：" + r.message;
    }

    private static String hhmm(long ms) {
        long min = ms / 60000;
        return (min / 60) + " 小时 " + (min % 60) + " 分";
    }

    private void openUsageSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开设置页：" + t, Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------ 采集

    private void runCollect(final boolean doExport) {
        if (!Collector.hasUsageAccess(this)) {
            status.setText("未获得「使用情况访问」权限。\n请先执行 adb shell appops set "
                    + getPackageName() + " GET_USAGE_STATS allow\n或点上方按钮到设置里手动开启。");
            return;
        }
        if (busy) return;
        busy = true;
        runBtn.setEnabled(false);
        runBtn.setText("采集中…");
        status.setText(doExport ? "正在读取系统使用记录…" : "正在后台补采…");

        new Thread(new Runnable() {
            @Override
            public void run() {
                Collector.RunResult rr = Collector.collectAndStore(MainActivity.this);
                final StringBuilder sb = new StringBuilder();
                if (rr.error != null) {
                    sb.append("采集失败：").append(rr.error);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            runBtn.setEnabled(true);
                            runBtn.setText("采集并导出");
                            status.setText(sb.toString());
                        }
                    });
                    return;
                }
                if (!doExport) {
                    sb.append("已静默补采（未导出）\n");
                    sb.append("事件数：").append(rr.eventCount).append('\n');
                    sb.append("本次覆盖：").append(rr.dayCount).append(" 天\n");
                    sb.append("库存：").append(rr.appRows).append(" 条应用记录 / ")
                            .append(rr.aggRows).append(" 条聚合\n");
                    String syncMsg = syncIfConfigured();
                    if (syncMsg != null) {
                        sb.append('\n').append(syncMsg).append('\n');
                    }
                    sb.append("\n需要文件时点上方「采集并导出」。");
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            runBtn.setEnabled(true);
                            runBtn.setText("采集并导出");
                            status.setText(sb.toString());
                        }
                    });
                    return;
                }
                sb.append("采集完成\n");
                sb.append("事件数：").append(rr.eventCount).append('\n');
                sb.append("本次覆盖：").append(rr.dayCount).append(" 天");
                if (rr.firstDay != null) {
                    sb.append("（").append(rr.firstDay).append(" ~ ").append(rr.lastDay).append("）");
                }
                sb.append('\n');
                sb.append("库存：").append(rr.appRows).append(" 条应用记录 / ")
                        .append(rr.aggRows).append(" 条聚合\n");

                final Exporter.Result er = Exporter.exportAll(MainActivity.this);
                sb.append("\n导出成功 ").append(er.written.size()).append(" 个文件：\n");
                for (String f : er.written) sb.append("· ").append(f).append('\n');
                if (!er.failed.isEmpty()) {
                    sb.append("失败：").append(er.failed.size()).append('\n');
                    for (String f : er.failed) sb.append("× ").append(f).append('\n');
                }
                sb.append('\n').append(er.dirHint);

                String syncMsg = syncIfConfigured();
                if (syncMsg != null) {
                    sb.append("\n\n").append(syncMsg);
                }

                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        runBtn.setEnabled(true);
                        runBtn.setText("采集并导出");
                        status.setText(sb.toString());
                        refreshStatus();
                    }
                });
            }
        }).start();
    }
}
