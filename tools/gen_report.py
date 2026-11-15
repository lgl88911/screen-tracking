import json, glob, os, datetime

src = 'export/exports/screentime.json'
d = json.load(open(src, encoding='utf-8'))
days = d['days']
tot = d['totals']

# 应用总计
agg = {}
for day in days:
    for a in day['apps']:
        k = a['package']
        v = agg.setdefault(k, {'label': a['label'], 'ms': 0, 'days': 0, 'launch': 0})
        v['ms'] += a['foreground_ms']
        v['launch'] += a['launches']
        if a['foreground_ms'] > 0: v['days'] += 1
top = sorted(agg.items(), key=lambda kv: -kv[1]['ms'])[:12]

maxmin = max(x['screen_on_minutes'] for x in days) or 1
maxtop = top[0][1]['ms'] / 60000 if top else 1

def hm(m):
    return f"{int(m)//60} 小时 {int(m)%60} 分" if m >= 60 else f"{int(m)} 分"

bars = []
for x in days:
    h = max(2, int(x['screen_on_minutes'] / maxmin * 190))
    bars.append(f"""<div class="col"><div class="val">{hm(x['screen_on_minutes'])}</div>
      <div class="bar" style="height:{h}px"></div>
      <div class="lbl">{x['date'][5:]}</div>
      <div class="sub">{x['unlocks']} 次解锁</div></div>""")

approws = []
for pkg, v in top:
    w = max(1.5, v['ms'] / 60000 / maxtop * 100)
    approws.append(f"""<div class="arow"><div class="aname">{v['label']}</div>
      <div class="atrack"><div class="afill" style="width:{w:.1f}%"></div></div>
      <div class="aval">{hm(v['ms']/60000)}</div><div class="aday">{v['days']} 天</div></div>""")

trows = []
for x in days:
    trows.append(f"""<tr><td>{x['date']}</td><td class="num">{hm(x['screen_on_minutes'])}</td>
      <td class="num">{x['unlocks']}</td><td class="num">{x['app_count']}</td>
      <td>{x['top_app']}</td><td class="num">{x['top_app_minutes']} 分</td></tr>""")

avg = tot['screen_on_minutes'] / max(1, len(days))
html = f"""<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>屏幕时间导出报告</title>
<style>
*{{box-sizing:border-box;margin:0;padding:0}}
body{{background:#f7f7f8;color:#18181b;font:15px/1.6 -apple-system,"PingFang SC","Microsoft YaHei",sans-serif;padding:40px 24px}}
.wrap{{max-width:920px;margin:0 auto}}
h1{{font-size:26px;font-weight:600;letter-spacing:-.02em}}
.meta{{color:#71717a;font-size:13px;margin-top:6px}}
.cards{{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin:26px 0}}
.card{{background:#fff;border:1px solid #e4e4e7;border-radius:12px;padding:16px 18px}}
.card .k{{font-size:12px;color:#71717a}}
.card .v{{font-size:22px;font-weight:600;margin-top:4px;font-variant-numeric:tabular-nums}}
section{{background:#fff;border:1px solid #e4e4e7;border-radius:12px;padding:22px 24px;margin-bottom:18px}}
h2{{font-size:15px;font-weight:600;margin-bottom:18px}}
.chart{{display:flex;align-items:flex-end;gap:8px;height:250px;padding-top:8px}}
.col{{flex:1;display:flex;flex-direction:column;align-items:center;justify-content:flex-end;height:100%}}
.bar{{width:100%;max-width:52px;background:#2563eb;border-radius:4px 4px 0 0;transition:.2s}}
.lbl{{font-size:12px;color:#3f3f46;margin-top:6px;font-variant-numeric:tabular-nums}}
.sub{{font-size:11px;color:#a1a1aa}}
.val{{font-size:11px;color:#71717a;margin-bottom:4px;white-space:nowrap}}
.arow{{display:grid;grid-template-columns:120px 1fr 92px 56px;align-items:center;gap:12px;padding:7px 0}}
.aname{{font-size:13px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}}
.atrack{{background:#f4f4f5;border-radius:4px;height:12px;overflow:hidden}}
.afill{{background:#2563eb;height:100%;border-radius:4px}}
.aval{{font-size:12px;text-align:right;font-variant-numeric:tabular-nums;color:#3f3f46}}
.aday{{font-size:12px;color:#a1a1aa;text-align:right}}
table{{width:100%;border-collapse:collapse;font-size:13px}}
th{{text-align:left;color:#71717a;font-weight:500;font-size:12px;padding:6px 4px;border-bottom:1px solid #e4e4e7}}
td{{padding:7px 4px;border-bottom:1px solid #f4f4f5;font-variant-numeric:tabular-nums}}
.num{{text-align:right}}
tr:last-child td{{border-bottom:none}}
.note{{font-size:12px;color:#71717a;line-height:1.8}}
.note b{{color:#3f3f46;font-weight:600}}
</style></head><body><div class="wrap">
<h1>屏幕时间导出报告</h1>
<div class="meta">数据源 android.app.usage.UsageStatsManager · 导出时间 {d['exported_at']} · 覆盖 {days[0]['date']} 至 {days[-1]['date']}</div>

<div class="cards">
  <div class="card"><div class="k">覆盖天数</div><div class="v">{tot['days']} 天</div></div>
  <div class="card"><div class="k">屏幕总时长</div><div class="v">{tot['screen_on_hours']} 小时</div></div>
  <div class="card"><div class="k">日均</div><div class="v">{hm(avg)}</div></div>
  <div class="card"><div class="k">应用记录</div><div class="v">{tot['app_records']} 条</div></div>
</div>

<section><h2>每日屏幕使用</h2><div class="chart">{''.join(bars)}</div></section>
<section><h2>应用使用排行（累计）</h2>{''.join(approws)}</section>
<section><h2>逐日明细</h2><table>
<tr><th>日期</th><th class="num">屏幕时长</th><th class="num">解锁</th><th class="num">应用数</th><th>使用最多</th><th class="num">时长</th></tr>
{''.join(trows)}
</table></section>

<section><h2>口径说明</h2><div class="note">
<b>时长来源</b>：前台会话事件重建（RESUMED/PAUSED、亮灭屏、解锁、关机），灭屏与采集时刻均收口，跨午夜按自然日切分。<br>
<b>启动次数</b>：按前台会话计数，同包名间隔 60 秒内不算新启动。<br>
<b>日界</b>：本报告按本地零点切分；系统自己的日桶起点为 {d['system_daily_bucket_start']}，两者不同，
与手机自带屏幕时间对账时以 <code>aggregates</code> 中的系统桶为准。<br>
<b>历史长度</b>：系统逐日记录仅保留约 10 天，本报告为多次采集的累积结果；安装本应用之前已被系统清理的日期无法恢复。
</div></section>
</div></body></html>"""
open('report.html','w',encoding='utf-8').write(html)
print('生成 report.html', len(html), '字节')
print('覆盖', days[0]['date'], '~', days[-1]['date'], '| 总时长', tot['screen_on_hours'], '小时')
