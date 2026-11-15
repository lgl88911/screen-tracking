package com.screentime.export;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/**
 * 后台增量快照：每 6 小时跑一次采集入库（不导出，省事）。
 * setPersisted(true) 使任务在重启后仍保留，历史因此在系统清理窗口之外也能持续累积。
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
}
