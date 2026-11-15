package com.screentime.export;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/**
 * 后台快照：每 6 小时跑一次采集入库，已配置仓库时顺带同步一份。
 *
 * 刻意不设网络约束（NETWORK_TYPE_NONE 表示「不要求网络」，是最容易被调度的档位）：
 * 采集受系统 11 天窗口限制，断一次就是永久缺失；而同步每次都是全量快照，
 * 这次传不上去下次补即可。所以绝不能让网络状态挡住采集。
 */
public class AutoCollectService extends JobService {

    private static final String TAG = "ScreentimeAuto";
    private static final int JOB_ID = 0x5C7E;

    public static boolean schedule(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return false;
            JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, AutoCollectService.class))
                    .setPeriodic(6L * 60 * 60 * 1000)
                    .setPersisted(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                    .build();
            return js.schedule(job) == JobScheduler.RESULT_SUCCESS;
        } catch (Throwable t) {
            Log.w(TAG, "schedule failed", t);
            return false;
        }
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean again = false;
                if (Collector.hasUsageAccess(AutoCollectService.this)) {
                    Collector.RunResult rr = Collector.collectAndStore(AutoCollectService.this);
                    Log.i(TAG, "auto collect days=" + rr.dayCount + " events=" + rr.eventCount
                            + " err=" + rr.error);
                    if (rr.error == null) {
                        String err = trySync();
                        if (err != null) {
                            Log.w(TAG, "sync: " + err);
                        }
                    }
                } else {
                    Log.w(TAG, "usage access denied, skip");
                }
                jobFinished(params, again);
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    /** 返回 null 表示成功或未配置；否则返回失败原因供日志记录。 */
    private String trySync() {
        try {
            SyncManager.Config c = SyncManager.load(this);
            if (!c.hasRepo() || SyncManager.getToken(this) == null) {
                return null;
            }
            if (c.mode == Crypto.MODE_PASSWORD && !SyncManager.hasPassword(this)) {
                return "密码模式缺少密码，跳过";
            }
            SyncManager.Result r = SyncManager.syncNow(this);
            return r.ok ? null : r.message;
        } catch (Throwable t) {
            return String.valueOf(t);
        }
    }
}
