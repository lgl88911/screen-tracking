package com.screentime.export;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 数据查看：按指定时间段出报表。
 * 三块内容 —— 总时间（汇总）、排名时间（应用排行）、指定应用时间（逐日明细）。
 * UI 全部代码构建，不依赖 layout XML。
 */
public class ReportActivity extends Activity {

    private static final int ACCENT = Color.rgb(37, 99, 235);
    private static final int INK = Color.rgb(24, 24, 27);
    private static final int MUTED = Color.rgb(113, 113, 122);
    private static final int FAINT = Color.rgb(161, 161, 170);
    private static final int LINE = Color.rgb(228, 228, 231);
    private static final int TRACK = Color.rgb(244, 244, 245);
    private static final int CARD = Color.WHITE;
    private static final int PAGE = Color.rgb(247, 247, 248);

    private static final SimpleDateFormat F = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    private LinearLayout body;
    private LinearLayout chipRow;
    private TextView rangeLabel;

    private int preset = 7;          // 7 / 30 / 0=全部 / -1=自定义
    private String from;
    private String to;
    private String selectedPkg;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAGE);

        // ---- 标题栏
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(CARD);
        bar.setPadding(dp(12), dp(14), dp(16), dp(14));

        TextView back = new TextView(this);
        back.setText("‹  返回");
        back.setTextSize(15);
        back.setTextColor(ACCENT);
        back.setPadding(dp(8), dp(4), dp(12), dp(4));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(back);

        TextView title = new TextView(this);
        title.setText("使用报告");
        title.setTextSize(18);
        title.setTextColor(INK);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        ScrollView sv = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(16), dp(16), dp(28));
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        ensureRange();
        build();
    }

    // ------------------------------------------------------------ 范围

    private String today() {
        return F.format(new Date());
    }

    private String shift(String day, int deltaDays) {
        try {
            Calendar c = Calendar.getInstance();
            c.setTime(F.parse(day));
            c.add(Calendar.DAY_OF_MONTH, deltaDays);
            return F.format(c.getTime());
        } catch (Throwable t) {
            return day;
        }
    }

    private void ensureRange() {
        UsageStore store = new UsageStore(this);
        String first = null, last = null;
        try {
            first = store.firstDay();
            last = store.lastDay();
        } finally {
            store.close();
        }
        if (from == null || to == null) {
            if (preset == 0 && first != null) {
                from = first;
                to = last != null ? last : today();
            } else {
                to = today();
                from = shift(to, -(preset <= 0 ? 6 : preset - 1));
            }
        }
        if (first != null && from.compareTo(first) < 0 && preset != -1) {
            // 区间早于有数据的日期时，起点收到第一天，避免出现空档
            from = first;
        }
    }

    private void applyPreset(int p) {
        preset = p;
        selectedPkg = null;
        if (p == 0) {
            UsageStore store = new UsageStore(this);
            try {
                String first = store.firstDay();
                String last = store.lastDay();
                from = first != null ? first : today();
                to = last != null ? last : today();
            } finally {
                store.close();
            }
        } else if (p > 0) {
            to = today();
            from = shift(to, -(p - 1));
        }
        build();
    }

    private void pickDate(final boolean isFrom) {
        Calendar c = Calendar.getInstance();
        try {
            c.setTime(F.parse(isFrom ? from : to));
        } catch (Throwable ignore) {
        }
        new DatePickerDialog(this, new DatePickerDialog.OnDateSetListener() {
            @Override
            public void onDateSet(DatePicker view, int year, int month, int dayOfMonth) {
                Calendar p = Calendar.getInstance();
                p.set(year, month, dayOfMonth, 0, 0, 0);
                String d = F.format(p.getTime());
                if (isFrom) {
                    from = d;
                    if (to != null && to.compareTo(from) < 0) to = from;
                } else {
                    to = d;
                    if (from != null && from.compareTo(to) > 0) from = to;
                }
                preset = -1;
                selectedPkg = null;
                build();
            }
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    // ------------------------------------------------------------ 绘制

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable round(int fill, int radiusDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) g.setStroke(dp(1), strokeColor);
        return g;
    }

    private TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(CARD, 12, LINE));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(12);
        c.setLayoutParams(lp);
        return c;
    }

    private void build() {
        body.removeAllViews();
        ensureRange();

        // ---- 范围切换
        chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"7 天", "30 天", "全部", "自定义"};
        final int[] vals = {7, 30, 0, -1};
        for (int i = 0; i < names.length; i++) {
            final int p = vals[i];
            boolean on = (preset == p) || (p == -1 && preset == -1);
            TextView chip = text(names[i], 13, on ? Color.WHITE : INK, false);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(14), dp(7), dp(14), dp(7));
            chip.setBackground(round(on ? ACCENT : CARD, 16, on ? ACCENT : LINE));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(8);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (p == -1) {
                        pickDate(true);
                    } else {
                        applyPreset(p);
                    }
                }
            });
            chipRow.addView(chip);
        }
        body.addView(chipRow, new LinearLayout.LayoutParams(-1, -2));

        rangeLabel = text("", 12, MUTED, false);
        rangeLabel.setPadding(dp(2), dp(10), 0, dp(12));
        body.addView(rangeLabel);

        // 自定义范围下给出起点/终点两个可点按钮，否则只能改起点
        if (preset == -1) {
            LinearLayout dateRow = new LinearLayout(this);
            dateRow.setOrientation(LinearLayout.HORIZONTAL);
            dateRow.setPadding(0, 0, 0, dp(12));
            dateRow.addView(pill("起点 " + from, true));
            dateRow.addView(pill("终点 " + to, false));
            body.addView(dateRow);
        }

        UsageStore store = new UsageStore(this);
        try {
            UsageStore.RangeSummary sum = store.summary(from, to);
            List<UsageStore.AppTotal> apps = store.appTotals(from, to);
            List<UsageStore.DayRow> days = store.daysInRange(from, to);

            rangeLabel.setText(from + " ~ " + to + "　·　有数据 " + sum.dayCount + " 天");

            if (sum.dayCount == 0) {
                LinearLayout c = card();
                c.addView(text("这段时间没有数据", 15, INK, true));
                c.addView(text("系统逐日记录只保留约 11 天，请先在首页采集。", 12, MUTED, false));
                body.addView(c);
                return;
            }

            // ---- 总时间
            body.addView(sectionTitle("总时间"));
            long avg = sum.dayCount > 0 ? sum.screenOnMs / sum.dayCount : 0;
            LinearLayout row1 = new LinearLayout(this);
            row1.setOrientation(LinearLayout.HORIZONTAL);
            row1.addView(statCard("屏幕总时长", hhmm(sum.screenOnMs), "占区间每日累计"));
            row1.addView(statCard("日均", hhmm(avg), "按有数据天数摊"));
            body.addView(row1);

            LinearLayout row2 = new LinearLayout(this);
            row2.setOrientation(LinearLayout.HORIZONTAL);
            row2.addView(statCard("解锁次数", sum.unlockCount + " 次",
                    sum.dayCount > 0 ? "日均 " + (sum.unlockCount / sum.dayCount) + " 次" : ""));
            row2.addView(statCard("使用应用", apps.size() + " 个", "有前台记录的去重包名"));
            body.addView(row2);

            // ---- 每日趋势
            if (days.size() > 1) {
                body.addView(sectionTitle("每日屏幕时长（分钟）"));
                LinearLayout c = card();
                c.addView(dailyBars(days));
                body.addView(c);
            }

            // ---- 排名时间
            body.addView(sectionTitle("排名时间"));
            LinearLayout listCard = card();
            long maxMs = apps.isEmpty() ? 1 : apps.get(0).foregroundMs;
            for (int i = 0; i < apps.size(); i++) {
                if (i > 0) listCard.addView(divider());
                listCard.addView(appRow(i + 1, apps.get(i), maxMs, sum.screenOnMs, store));
            }
            body.addView(listCard);

            body.addView(text("时长口径：系统前台时长，由前台会话事件重建；跨午夜的会话按自然日切分。"
                    + "点击任一行可看该应用在区间内的逐日明细。", 11, FAINT, false));

        } finally {
            store.close();
        }
    }

    private TextView sectionTitle(String s) {
        TextView t = text(s, 15, INK, true);
        t.setPadding(dp(2), dp(6), 0, dp(10));
        return t;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(Color.rgb(243, 243, 245));
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
        return v;
    }

    /** 起点/终点选择按钮 */
    private TextView pill(String label, final boolean isFrom) {
        TextView t = text(label, 12, INK, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(7), dp(12), dp(7));
        t.setBackground(round(CARD, 16, LINE));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickDate(isFrom);
            }
        });
        return t;
    }

    private LinearLayout statCard(String k, String v, String sub) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(CARD, 12, LINE));
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.rightMargin = dp(10);
        lp.bottomMargin = dp(10);
        c.setLayoutParams(lp);
        c.addView(text(k, 11, MUTED, false));
        TextView val = text(v, 19, INK, true);
        val.setPadding(0, dp(3), 0, 0);
        c.addView(val);
        if (sub != null && sub.length() > 0) {
            TextView s = text(sub, 10, FAINT, false);
            s.setPadding(0, dp(2), 0, 0);
            c.addView(s);
        }
        return c;
    }

    /** 每日屏幕时长竖条 */
    private LinearLayout dailyBars(List<UsageStore.DayRow> days) {
        LinearLayout chart = new LinearLayout(this);
        chart.setOrientation(LinearLayout.HORIZONTAL);
        chart.setGravity(Gravity.BOTTOM);
        chart.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(140)));

        long max = 1;
        for (UsageStore.DayRow d : days) if (d.screenOnMs > max) max = d.screenOnMs;

        for (UsageStore.DayRow d : days) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -1, 1f);
            clp.rightMargin = dp(4);
            col.setLayoutParams(clp);

            TextView v = text(String.valueOf(Math.round(d.screenOnMs / 60000.0)), 9, MUTED, false);
            v.setGravity(Gravity.CENTER);
            col.addView(v);

            View bar = new View(this);
            int h = (int) Math.max(dp(3), dp(96) * (d.screenOnMs / (double) max));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, h);
            bar.setBackground(round(ACCENT, 3, 0));
            bar.setLayoutParams(blp);
            col.addView(bar);

            TextView lbl = text(d.day.substring(5), 9, FAINT, false);
            lbl.setGravity(Gravity.CENTER);
            lbl.setPadding(0, dp(4), 0, 0);
            col.addView(lbl);
            chart.addView(col);
        }
        return chart;
    }

    private LinearLayout appRow(final int rank, final UsageStore.AppTotal a, long maxMs,
                               long totalScreenMs, UsageStore store) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(9), 0, dp(9));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView idx = text(String.valueOf(rank), 11, FAINT, false);
        idx.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(22), -2);
        head.addView(idx, ilp);

        TextView name = text(a.label, 13, INK, false);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView dur = text(hhmm(a.foregroundMs), 12, INK, true);
        head.addView(dur);

        TextView pct = text(String.format(Locale.US, "%d%%",
                totalScreenMs > 0 ? Math.round(a.foregroundMs * 100.0 / totalScreenMs) : 0), 10, FAINT, false);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(52), -2);
        pct.setGravity(Gravity.RIGHT);
        head.addView(pct, plp);

        row.addView(head);

        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setWeightSum(100f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, dp(6));
        tlp.topMargin = dp(5);
        tlp.leftMargin = dp(22);
        track.setLayoutParams(tlp);

        float pctFill = maxMs > 0 ? (float) (a.foregroundMs * 100.0 / maxMs) : 0;
        if (pctFill < 1.5f) pctFill = 1.5f;
        if (pctFill > 100f) pctFill = 100f;

        View fill = new View(this);
        fill.setBackground(round(ACCENT, 3, 0));
        track.addView(fill, new LinearLayout.LayoutParams(0, -1, pctFill));

        View rest = new View(this);
        rest.setBackground(round(TRACK, 3, 0));
        track.addView(rest, new LinearLayout.LayoutParams(0, -1, 100f - pctFill));
        row.addView(track);

        boolean open = a.pkg.equals(selectedPkg);
        if (open) {
            row.addView(appDetail(a, store));
        }

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectedPkg = a.pkg.equals(selectedPkg) ? null : a.pkg;
                build();
            }
        });
        return row;
    }

    /** 指定应用的区间明细：累计 / 日均 / 启动次数 + 逐日时长 */
    private LinearLayout appDetail(UsageStore.AppTotal a, UsageStore store) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(PAGE, 10, 0));
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        lp.leftMargin = dp(22);
        box.setLayoutParams(lp);

        List<UsageStore.DayApp> daily = store.appDaily(a.pkg, from, to);

        long avg = a.activeDays > 0 ? a.foregroundMs / a.activeDays : 0;
        box.addView(text(a.pkg, 10, FAINT, false));
        box.addView(text("累计 " + hhmm(a.foregroundMs) + "　·　日均 " + hhmm(avg), 11, INK, false));
        box.addView(text("启动 " + a.launches + " 次　·　活跃 " + a.activeDays + " 天", 11, MUTED, false));

        long max = 1;
        for (UsageStore.DayApp d : daily) if (d.foregroundMs > max) max = d.foregroundMs;

        for (UsageStore.DayApp d : daily) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
            llp.topMargin = dp(6);
            line.setLayoutParams(llp);

            TextView day = text(d.day.substring(5), 10, MUTED, false);
            line.addView(day, new LinearLayout.LayoutParams(dp(42), -2));

            LinearLayout track = new LinearLayout(this);
            track.setOrientation(LinearLayout.HORIZONTAL);
            track.setWeightSum(100f);
            float p = (float) (d.foregroundMs * 100.0 / max);
            if (p < 1f && d.foregroundMs > 0) p = 1f;
            View f = new View(this);
            f.setBackground(round(ACCENT, 2, 0));
            track.addView(f, new LinearLayout.LayoutParams(0, dp(5), p));
            View r = new View(this);
            r.setBackground(round(Color.rgb(235, 235, 238), 2, 0));
            track.addView(r, new LinearLayout.LayoutParams(0, dp(5), 100f - p));
            line.addView(track, new LinearLayout.LayoutParams(0, -2, 1f));

            TextView v = text(hhmm(d.foregroundMs), 10, INK, false);
            v.setGravity(Gravity.RIGHT);
            line.addView(v, new LinearLayout.LayoutParams(dp(62), -2));

            box.addView(line);
        }
        return box;
    }

    private String hhmm(long ms) {
        long min = ms / 60000;
        if (min >= 60) return (min / 60) + " 小时 " + (min % 60) + " 分";
        if (min > 0) return min + " 分";
        return (ms / 1000) + " 秒";
    }
}
