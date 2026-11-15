package com.screentime.export;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 同步配置页：仓库、Token、三档加密模式、设备标识绑定、立即同步。 */
public class SyncActivity extends Activity {

    /** 主界面「从仓库选择已有设备」跳过来时带上，进来就弹选择框。 */
    public static final String EXTRA_PICK = "pick";

    private static final int ID_BASE = 1000;

    private EditText repoField;
    private TextView deviceValue;
    private TextView deviceMeta;
    private Button bindNewBtn;
    private Button pickBtn;
    private Button pullBtn;
    private EditText tokenField;
    private EditText pwField;
    private EditText pubKeyField;
    private RadioGroup modeGroup;
    private TextView status;
    private Button syncBtn;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(28));
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("同步到私有仓库");
        title.setTextSize(20);
        title.setTextColor(Color.rgb(20, 20, 20));
        root.addView(title, lp(-1, -2));

        TextView hint = new TextView(this);
        hint.setText("把每日快照打包上传到 GitHub 私有仓库。加密三档可选，切换不需重装。");
        hint.setTextSize(12);
        hint.setTextColor(Color.rgb(130, 130, 130));
        hint.setPadding(0, dp(6), 0, dp(16));
        root.addView(hint, lp(-1, -2));

        root.addView(label("仓库"), lp(-1, -2));
        repoField = input("owner/repo，或直接粘贴 clone 地址");
        repoField.setSingleLine(true);
        root.addView(repoField, lp(-1, dp(46)));

        root.addView(label("设备标识（本机在仓库里的目录名，确定后不可修改）"), lp(-1, -2));
        deviceValue = new TextView(this);
        deviceValue.setTextSize(16);
        deviceValue.setTextColor(Color.rgb(25, 25, 25));
        deviceValue.setTypeface(android.graphics.Typeface.MONOSPACE);
        deviceValue.setPadding(dp(4), dp(4), dp(4), dp(2));
        deviceValue.setTextIsSelectable(true);
        root.addView(deviceValue, lp(-1, -2));

        deviceMeta = new TextView(this);
        deviceMeta.setTextSize(12);
        deviceMeta.setTextColor(Color.rgb(130, 130, 130));
        deviceMeta.setPadding(dp(4), 0, dp(4), dp(10));
        root.addView(deviceMeta, lp(-1, -2));

        bindNewBtn = new Button(this);
        bindNewBtn.setAllCaps(false);
        bindNewBtn.setTextSize(13);
        bindNewBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                bindNew();
            }
        });
        root.addView(bindNewBtn, lp(-1, dp(42)));

        pickBtn = new Button(this);
        pickBtn.setText("从仓库选择已有设备");
        pickBtn.setAllCaps(false);
        pickBtn.setTextSize(13);
        pickBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickRemoteDevice();
            }
        });
        root.addView(pickBtn, lp(-1, dp(42)));

        pullBtn = new Button(this);
        pullBtn.setText("从仓库下载本机历史数据");
        pullBtn.setAllCaps(false);
        pullBtn.setTextSize(13);
        pullBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runPull(null, null);
            }
        });
        root.addView(pullBtn, lp(-1, dp(42)));

        root.addView(label("Token（fine-grained，仅需该仓库 Contents 读写）"), lp(-1, -2));        tokenField = input("留空则不修改已保存的 Token");
        tokenField.setSingleLine(true);
        tokenField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(tokenField, lp(-1, dp(46)));

        root.addView(label("加密方式"), lp(-1, -2));
        modeGroup = new RadioGroup(this);
        modeGroup.setOrientation(RadioGroup.VERTICAL);
        addMode(Crypto.MODE_PLAIN, "不加密（默认）");
        addMode(Crypto.MODE_PASSWORD, "密码加密");
        addMode(Crypto.MODE_KEY, "公钥加密（私钥留在电脑）");
        root.addView(modeGroup, lp(-1, -2));

        root.addView(label("密码（仅密码模式，已设置则留空）"), lp(-1, -2));
        pwField = input("至少 8 位，忘记后无法恢复历史数据");
        pwField.setSingleLine(true);
        pwField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(pwField, lp(-1, dp(46)));

        root.addView(label("公钥（仅公钥模式，粘贴 public.pem 全文）"), lp(-1, -2));
        pubKeyField = input("-----BEGIN PUBLIC KEY-----");
        pubKeyField.setSingleLine(false);
        pubKeyField.setMinLines(3);
        pubKeyField.setGravity(Gravity.TOP);
        root.addView(pubKeyField, lp(-1, dp(90)));

        Button saveBtn = new Button(this);
        saveBtn.setText("保存配置");
        saveBtn.setAllCaps(false);
        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveConfig();
            }
        });
        root.addView(saveBtn, lp(-1, dp(46)));

        syncBtn = new Button(this);
        syncBtn.setText("立即同步");
        syncBtn.setAllCaps(false);
        syncBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startSync();
            }
        });
        root.addView(syncBtn, lp(-1, dp(48)));

        Button clearBtn = new Button(this);
        clearBtn.setText("清除 Token 与密码");
        clearBtn.setAllCaps(false);
        clearBtn.setTextSize(13);
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClear();
            }
        });
        root.addView(clearBtn, lp(-1, dp(42)));

        status = new TextView(this);
        status.setTextSize(13);
        status.setTextColor(Color.rgb(50, 50, 50));
        status.setPadding(0, dp(18), 0, dp(8));
        status.setTextIsSelectable(true);
        root.addView(status, lp(-1, -2));

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        loadConfig();

        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_PICK, false)
                && !SyncManager.isBound(this)) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    pickRemoteDevice();
                }
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    // ------------------------------------------------------------ 配置读写

    private void loadConfig() {
        SyncManager.Config c = SyncManager.load(this);
        repoField.setText(c.repo);
        pubKeyField.setText(c.publicKeyB64);
        refreshDeviceSection();
        // hint 每次都要按实际状态重设，否则清除凭据后界面仍显示「已保存」
        tokenField.setHint(SyncManager.getToken(this) != null
                ? "已保存，留空则不修改" : "留空则不修改已保存的 Token");
        pwField.setHint(SyncManager.hasPassword(this)
                ? "已设置，留空则不修改" : "至少 8 位，忘记后无法恢复历史数据");
        RadioButton rb = (RadioButton) modeGroup.findViewById(ID_BASE + c.mode);
        if (rb != null) {
            rb.setChecked(true);
        }
    }

    private int selectedMode() {
        int id = modeGroup.getCheckedRadioButtonId();
        return id < 0 ? Crypto.MODE_PLAIN : id - ID_BASE;
    }

    private void saveConfig() {
        String repo = repoField.getText().toString().trim();
        if (repo.length() > 0 && GitHubClient.parseRepo(repo) == null) {
            toast("仓库地址无效，应形如 owner/repo");
            return;
        }
        SyncManager.setRepo(this, repo);

        String token = tokenField.getText().toString().trim();
        if (token.length() > 0) {
            SyncManager.setToken(this, token);
            tokenField.setText("");
            tokenField.setHint("已保存，留空则不修改");
        }

        int mode = selectedMode();
        SyncManager.setMode(this, mode);

        if (mode == Crypto.MODE_KEY) {
            String pub = SyncManager.normalizePublicKey(pubKeyField.getText().toString());
            if (pub.length() < 100) {
                toast("请粘贴完整的 public.pem 内容");
                return;
            }
            SyncManager.setPublicKey(this, pub);
        }

        String pw = pwField.getText().toString();
        if (mode == Crypto.MODE_PASSWORD && pw.length() > 0) {
            if (pw.length() < 8) {
                toast("密码至少 8 位");
                return;
            }
            final String p = pw;
            toast("正在派生密钥…");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        SyncManager.setPassword(SyncActivity.this, p.toCharArray());
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                pwField.setText("");
                                pwField.setHint("已设置，留空则不修改");
                                toast("密码已设置");
                            }
                        });
                    } catch (final Throwable t) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                toast("设置密码失败：" + t.getMessage());
                            }
                        });
                    }
                }
            }).start();
        }

        toast("配置已保存");
        refreshStatus();
    }

    // ------------------------------------------------------------ 设备标识

    private void refreshDeviceSection() {
        boolean bound = SyncManager.isBound(this);
        String id = SyncManager.deviceId(this);
        deviceValue.setText(id);
        if (bound) {
            deviceMeta.setText("已锁定，此后不可修改。仓库目录 " + SyncPaths.deviceDir(id));
        } else {
            deviceMeta.setText("尚未确定。这台手机第一次用选「新建」；重装应用选「从仓库选择」，"
                    + "会列出仓库里的全部设备，选中哪台就用它的标识。");
        }
        bindNewBtn.setText("用本机新标识：" + SyncManager.suggestedDeviceId(this));
        bindNewBtn.setVisibility(bound ? View.GONE : View.VISIBLE);
        pickBtn.setText("从仓库选择设备并恢复数据");
        pickBtn.setVisibility(bound ? View.GONE : View.VISIBLE);
        pullBtn.setText("从仓库重新下载本机数据");
        pullBtn.setVisibility(bound ? View.VISIBLE : View.GONE);
    }

    private void bindNew() {
        final String id = SyncManager.suggestedDeviceId(this);
        new AlertDialog.Builder(this)
                .setTitle("确定用这个标识？")
                .setMessage("设备标识：" + id
                        + "\n\n确定后不能再修改。此后所有采集数据都会写进仓库的 "
                        + SyncPaths.deviceDir(id) + " 目录。")
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (SyncManager.bind(SyncActivity.this, id)) {
                            toast("已绑定 " + id);
                            refreshDeviceSection();
                            refreshStatus();
                        } else {
                            toast("绑定失败：标识已被占用或不合规");
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 列出仓库里已有的设备目录，选中即绑定；可选同时把历史快照拉回本机。 */
    private void pickRemoteDevice() {
        if (busy) {
            return;
        }
        // 重装后第一次进来时仓库和 Token 都还是空的，这里就地补填，
        // 不必让用户先去下面找输入框再回来按按钮。
        if (!SyncManager.load(this).hasRepo() || SyncManager.getToken(this) == null) {
            askConnection();
            return;
        }
        loadRemoteDevices();
    }

    /** 就地填仓库地址与 Token，填完直接接着列设备。 */
    private void askConnection() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        TextView tip = new TextView(this);
        tip.setText("列出仓库中的设备需要仓库地址和 Token，两者只保存在本机。");
        tip.setTextSize(12);
        tip.setTextColor(Color.rgb(130, 130, 130));
        tip.setPadding(0, 0, 0, dp(8));
        box.addView(tip, lp(-1, -2));

        final EditText repoIn = input("owner/repo，或 clone 地址");
        repoIn.setSingleLine(true);
        repoIn.setText(SyncManager.load(this).repo);
        box.addView(repoIn, lp(-1, dp(46)));

        final EditText tokenIn = input("Token（fine-grained，Contents 读写）");
        tokenIn.setSingleLine(true);
        tokenIn.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(tokenIn, lp(-1, dp(46)));

        new AlertDialog.Builder(this)
                .setTitle("连接仓库")
                .setView(box)
                .setPositiveButton("连接并列出设备", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String repo = repoIn.getText().toString().trim();
                        if (GitHubClient.parseRepo(repo) == null) {
                            toast("仓库地址无效，应形如 owner/repo");
                            return;
                        }
                        String token = tokenIn.getText().toString().trim();
                        if (token.length() == 0 && SyncManager.getToken(SyncActivity.this) == null) {
                            toast("请填写 Token");
                            return;
                        }
                        SyncManager.setRepo(SyncActivity.this, repo);
                        if (token.length() > 0) {
                            SyncManager.setToken(SyncActivity.this, token);
                        }
                        repoField.setText(repo);
                        loadConfig();
                        loadRemoteDevices();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void loadRemoteDevices() {
        busy = true;
        pickBtn.setEnabled(false);
        status.setText("正在读取仓库里的设备…");

        new Thread(new Runnable() {
            @Override
            public void run() {
                final java.util.List<DeviceCatalog.Device> devs =
                        new java.util.ArrayList<DeviceCatalog.Device>();
                String err = null;
                try {
                    devs.addAll(SyncManager.listRemoteDevices(SyncActivity.this));
                } catch (Throwable t) {
                    err = t.getMessage() == null ? t.toString() : t.getMessage();
                }
                final String fErr = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        pickBtn.setEnabled(true);
                        if (fErr != null) {
                            status.setText("读取失败\n" + fErr);
                            toast("读取失败");
                            return;
                        }
                        if (devs.isEmpty()) {
                            status.setText("仓库里还没有任何设备目录。\n"
                                    + "这是第一次上传，用「新建」即可；\n"
                                    + "若之前确实上传过，先确认仓库地址和 Token 是否指向同一个仓库。");
                            toast("仓库里没有可选的设备");
                            return;
                        }
                        showDeviceChooser(devs);
                    }
                });
            }
        }).start();
    }

    private void showDeviceChooser(final java.util.List<DeviceCatalog.Device> devs) {
        status.setText("仓库里共 " + devs.size() + " 台设备，选中哪一台就用它的标识：");

        String[] items = new String[devs.size()];
        for (int i = 0; i < devs.size(); i++) {
            items[i] = devs.get(i).line();
        }
        new AlertDialog.Builder(this)
                .setTitle("仓库中的设备（" + devs.size() + "）")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        confirmBindExisting(devs.get(which));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmBindExisting(final DeviceCatalog.Device dev) {
        String body = dev.id + "\n" + dev.summary()
                + "\n\n绑定后本机标识就是 " + dev.id + "，此后不可修改。"
                + "现在是否同时把它在仓库里的最新快照下载到本机？";
        new AlertDialog.Builder(this)
                .setTitle("使用这台设备")
                .setMessage(body)
                .setPositiveButton("绑定并恢复数据", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (doBind(dev.id)) {
                            runPull(null, null, dev);
                        }
                    }
                })
                .setNegativeButton("仅绑定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        doBind(dev.id);
                    }
                })
                .show();
    }

    private boolean doBind(String dev) {
        if (!SyncManager.bind(this, dev)) {
            toast("绑定失败");
            return false;
        }
        toast("已绑定 " + dev);
        refreshDeviceSection();
        refreshStatus();
        return true;
    }

    // ------------------------------------------------------------ 拉回历史

    private void runPull(final String password, final SyncManager.Pull retry) {
        runPull(password, retry, null);
    }

    private void runPull(final String password, final SyncManager.Pull retry,
                         final DeviceCatalog.Device hint) {
        if (busy) {
            return;
        }
        if (!SyncManager.isBound(this)) {
            toast("先确定设备标识");
            return;
        }
        busy = true;
        pullBtn.setEnabled(false);
        status.setText(retry == null ? "正在下载并导入…" : "正在解密并导入…");

        new Thread(new Runnable() {
            @Override
            public void run() {
                final String device = SyncManager.deviceId(SyncActivity.this);
                final SyncManager.Pull p = (retry == null || !retry.ok)
                        ? SyncManager.pullNow(SyncActivity.this, device, hint, password)
                        : SyncManager.unpack(SyncActivity.this, retry, password);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        pullBtn.setEnabled(true);
                        if (p.needPassword) {
                            status.setText("该设备的数据用密码加密。");
                            askPassword(p);
                            return;
                        }
                        toast(p.ok ? "历史数据已导入" : "导入失败");
                        // 先刷新状态再追加结果：refreshStatus 会整段重写，顺序反了就看不到导入结果
                        refreshStatus();
                        status.setText(status.getText() + "\n\n"
                                + (p.ok ? "导入结果\n" : "导入失败\n") + p.message);
                        if (p.ok) {
                            showRestoreResult(p);
                        }
                    }
                });
            }
        }).start();
    }

    /** 恢复完成后的收尾提示：标识已锁定、数据落到本机、之后正常采集即可。 */
    private void showRestoreResult(SyncManager.Pull p) {
        new AlertDialog.Builder(this)
                .setTitle("恢复完成")
                .setMessage("设备标识：" + SyncManager.deviceId(this) + "（已锁定）\n\n"
                        + p.message + "\n\n"
                        + "之后这台手机的采集会继续写进 "
                        + SyncPaths.deviceDir(SyncManager.deviceId(this))
                        + "，报表里已经能看到恢复回来的历史。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private void askPassword(final SyncManager.Pull pending) {
        final EditText input = input("上传时使用的密码");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this)
                .setTitle("输入密码")
                .setMessage("仓库里的快照是密码模式，需要当时的密码才能解开。")
                .setView(input)
                .setPositiveButton("解密导入", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String pw = input.getText().toString();
                        if (pw.length() == 0) {
                            toast("密码不能为空");
                            return;
                        }
                        runPull(pw, pending);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------ 同步

    private void startSync() {
        if (busy) {
            return;
        }
        if (!SyncManager.load(this).hasRepo()) {
            toast("先填写仓库地址并保存");
            return;
        }
        if (SyncManager.getToken(this) == null) {
            toast("先填写 Token 并保存");
            return;
        }
        if (!SyncManager.isBound(this)) {
            toast("先确定设备标识：新建，或从仓库选择已有设备");
            return;
        }
        int mode = SyncManager.load(this).mode;
        if (mode == Crypto.MODE_PASSWORD && !SyncManager.hasPassword(this)) {
            toast("密码模式尚未设置密码");
            return;
        }

        busy = true;
        syncBtn.setEnabled(false);
        syncBtn.setText("同步中…");
        status.setText("正在打包、加密并上传…");

        new Thread(new Runnable() {
            @Override
            public void run() {
                final SyncManager.Result r = SyncManager.syncNow(SyncActivity.this);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        syncBtn.setEnabled(true);
                        syncBtn.setText("立即同步");
                        status.setText(r.ok ? "同步成功\n" + r.message
                                : "同步失败\n" + r.message);
                        Toast.makeText(SyncActivity.this, r.ok ? "已同步" : "同步失败",
                                Toast.LENGTH_SHORT).show();
                        refreshStatus();
                    }
                });
            }
        }).start();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("清除凭据")
                .setMessage("将删除设备上保存的 Token 和密码派生密钥，仓库地址保留。\n"
                        + "已上传到仓库的历史数据不受影响。")
                .setPositiveButton("清除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        SyncManager.setToken(SyncActivity.this, null);
                        SyncManager.clearPassword(SyncActivity.this);
                        loadConfig();
                        refreshStatus();
                        toast("已清除 Token 与密码");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshStatus() {
        SyncManager.Config c = SyncManager.load(this);
        long ok = SyncManager.lastSuccess(this);
        String err = SyncManager.lastError(this);

        StringBuilder sb = new StringBuilder();
        sb.append("当前模式：").append(SyncManager.modeName(c.mode)).append('\n');
        sb.append("仓库：").append(c.repo.length() == 0 ? "未设置" : c.repo).append('\n');
        if (SyncManager.isBound(this)) {
            String id = SyncManager.deviceId(this);
            sb.append("设备标识：").append(id).append("（已锁定）\n");
            sb.append("设备目录：").append(SyncPaths.deviceDir(id)).append('\n');
        } else {
            sb.append("设备标识：未确定（候选 ").append(SyncManager.suggestedDeviceId(this))
                    .append("，也可从仓库选择已有设备）\n");
        }
        sb.append("Token：").append(SyncManager.getToken(this) == null ? "未设置" : "已保存").append('\n');
        if (ok > 0) {
            sb.append("上次成功：").append(ago(ok)).append("（")
                    .append(new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(ok)))
                    .append("）\n");
            long hours = (System.currentTimeMillis() - ok) / 3600000L;
            if (hours >= 48) {
                sb.append("⚠ 已超过 48 小时未成功同步\n");
            }
        } else {
            sb.append("上次成功：从未\n");
        }
        if (err != null && err.length() > 0) {
            sb.append("上次错误：").append(err).append('\n');
        }
        if (c.mode == Crypto.MODE_PLAIN) {
            sb.append("\n注意：不加密时上传的是明文，仓库必须保持 private。");
        }
        status.setText(sb.toString());
    }

    private static String ago(long ts) {
        long min = (System.currentTimeMillis() - ts) / 60000L;
        if (min < 1) {
            return "刚刚";
        }
        if (min < 60) {
            return min + " 分钟前";
        }
        long h = min / 60;
        if (h < 24) {
            return h + " 小时前";
        }
        return (h / 24) + " 天前";
    }

    // ------------------------------------------------------------ UI 辅助

    private void addMode(int mode, String text) {
        RadioButton rb = new RadioButton(this);
        // 不能直接用 0 做 id：RadioGroup 会把 0 视同「未选中」，模式 0 永远选不上
        rb.setId(ID_BASE + mode);
        rb.setText(text);
        rb.setTextSize(14);
        rb.setPadding(0, dp(6), 0, dp(6));
        modeGroup.addView(rb, lp(-1, -2));
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12);
        t.setTextColor(Color.rgb(120, 120, 120));
        t.setPadding(0, dp(14), 0, dp(4));
        return t;
    }

    private EditText input(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(Color.rgb(170, 170, 170));
        e.setTextSize(14);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setBackgroundColor(Color.rgb(247, 247, 248));
        return e;
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
