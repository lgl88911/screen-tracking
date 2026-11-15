package com.screentime.export;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GitHub 私有仓库客户端，走 Git Data API 做原子提交。
 *
 * 单日上传只产生一个 commit，六步中前五步不改仓库状态，最后一步才移动 ref，
 * 中途失败不会留下半套数据。
 */
public class GitHubClient {

    private static final String API = "https://api.github.com";
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 30000;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static class RepoInfo {
        public String defaultBranch = "main";
        public boolean isPrivate;
        public String fullName;
    }

    public static class ApiException extends IOException {
        public final int status;

        public ApiException(int status, String message) {
            super("HTTP " + status + ": " + message);
            this.status = status;
        }
    }

    private final String token;
    private final String owner;
    private final String repo;

    public GitHubClient(String token, String owner, String repo) {
        this.token = token;
        this.owner = owner;
        this.repo = repo;
    }

    // ------------------------------------------------------------ 仓库信息

    /** 用于上传前确认仓库为 private，避免明文包进入公开仓库。 */
    public RepoInfo repoInfo() throws IOException {
        JSONObject o = getJson("/repos/" + owner + "/" + repo, null);
        RepoInfo info = new RepoInfo();
        info.isPrivate = o.optBoolean("private", false);
        info.fullName = o.optString("full_name", owner + "/" + repo);
        // 空仓库的 default_branch 可能为 null，而 Android 的 optString 对 JSON null
        // 会返回字符串 "null"，不处理就会去查 refs/heads/null。
        String branch = o.optString("default_branch", "");
        if (branch == null || branch.length() == 0
                || "null".equals(branch) || "undefined".equals(branch)) {
            branch = "main";
        }
        info.defaultBranch = branch;
        return info;
    }

    // ------------------------------------------------------------ 提交

    private static final byte[] BOOTSTRAP_README = (
            "# screen tracking data\n"
            + "\n"
            + "本仓库保存屏幕使用数据的每日快照，由手机端自动上传，不含代码。\n"
            + "\n"
            + "## 目录\n"
            + "\n"
            + "每台设备一个目录，多设备共用同一仓库时互不覆盖：\n"
            + "\n"
            + "```\n"
            + "devices/\n"
            + "  <设备标识>/\n"
            + "    2026/11/15.sce    当日全量快照\n"
            + "    2026/11/16.sce\n"
            + "    latest.sce        该设备最新一份副本\n"
            + "```\n"
            + "\n"
            + "设备标识在同步设置里可改，默认由机型加随机后缀生成。\n"
            + "\n"
            + "## 文件格式\n"
            + "\n"
            + "`.sce` 是自描述容器：`SCE1` + 1 字节模式（0x00 明文 / 0x01 密码 / 0x02 公钥）\n"
            + "+ 内容。里面是一个 zip，含 `screentime.json` 与 4 份 CSV。\n"
            + "\n"
            + "## 取回\n"
            + "\n"
            + "```bash\n"
            + "python3 tools/decrypt.py latest.sce -o out                 # 明文\n"
            + "python3 tools/decrypt.py latest.sce -o out -p '密码'        # 密码模式\n"
            + "python3 tools/decrypt.py latest.sce -o out -k private.pem  # 公钥模式\n"
            + "```\n"
            ).getBytes(Charset.forName("UTF-8"));

    /**
     * 空仓库引导：GitHub 对一个尚无任何提交的仓库会拒绝整个 Git Data API
     * （blob / tree / commit 全部返回 409 "Git Repository is empty."），
     * 只有 Contents API 能落下第一个提交。所以先写一个说明文件，让仓库有 HEAD，
     * 之后的正常提交才能走原子化的 Git Data API。
     */
    private void bootstrapInitialCommit(String branch) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("message", "初始化数据仓库");
            body.put("content", Base64.encodeToString(BOOTSTRAP_README, Base64.NO_WRAP));
            if (branch != null && branch.length() > 0) {
                body.put("branch", branch);
            }
        } catch (JSONException e) {
            throw new IOException("构造初始化请求失败", e);
        }
        String path = "/repos/" + owner + "/" + repo + "/contents/README.md";
        try {
            putJson(path, body);
        } catch (ApiException e) {
            // 空仓库指定 branch 可能返回 422（分支尚不存在），去掉 branch 再试一次
            if (e.status == 422 && body.has("branch")) {
                body.remove("branch");
                putJson(path, body);
            } else {
                throw e;
            }
        }
    }

    /**
     * 把多个文件放进同一个 commit 推上去。
     *
     * @param files  仓库内路径 → 文件字节
     * @param message commit 说明
     * @return 新 commit 的 sha
     */
    public String commit(Map<String, byte[]> files, String message) throws IOException {
        // 多台设备共用一个仓库时，两台同时上传会让后者的 ref 移动失败（422/409）。
        // 这类冲突重试即可：重新读取 HEAD 后整批重做，blob 按内容去重不会重复占空间。
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return commitOnce(files, message);
            } catch (ApiException e) {
                if (e.status != 409 && e.status != 422) {
                    throw e;
                }
                last = e;
                if (attempt < 2) {
                    try {
                        Thread.sleep(500L * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw last == null ? new IOException("提交失败") : last;
    }

    private String commitOnce(Map<String, byte[]> files, String message) throws IOException {
        RepoInfo info = repoInfo();
        String branch = info.defaultBranch;

        // 1. 当前分支指向的 commit。
        //    仓库刚建好、一个提交都没有时，GitHub 对 ref 查询返回 409
        //    （{"message":"Git Repository is empty."}），这不是并发冲突。
        String headSha = null;
        try {
            JSONObject ref = getJson("/repos/" + owner + "/" + repo + "/git/ref/heads/" + branch, null);
            headSha = ref.getJSONObject("object").getString("sha");
        } catch (ApiException e) {
            if (e.status != 409 && e.status != 404) {
                throw e;
            }
            headSha = null;   // 空仓库，从零开始
        } catch (JSONException e) {
            throw new IOException("分支 " + branch + " 的响应缺少 sha 字段", e);
        }

        // 2. 空仓库先引导出首个提交，再重新取 HEAD
        if (headSha == null) {
            bootstrapInitialCommit(branch);
            RepoInfo again = repoInfo();
            if (again.defaultBranch != null && again.defaultBranch.length() > 0) {
                branch = again.defaultBranch;
            }
            JSONObject ref = getJson("/repos/" + owner + "/" + repo + "/git/ref/heads/" + branch, null);
            try {
                headSha = ref.getJSONObject("object").getString("sha");
            } catch (JSONException e) {
                throw new IOException("初始化后仍取不到分支 " + branch + " 的 sha", e);
            }
        }

        // 3. 该 commit 的 tree
        String baseTree = null;
        if (headSha != null) {
            JSONObject headCommit = getJson("/repos/" + owner + "/" + repo + "/git/commits/" + headSha, null);
            try {
                baseTree = headCommit.getJSONObject("tree").getString("sha");
            } catch (JSONException e) {
                throw new IOException("commit 响应缺少 tree 字段", e);
            }
        }

        // 3. 逐个上传 blob
        JSONArray tree = new JSONArray();
        for (Map.Entry<String, byte[]> en : files.entrySet()) {
            JSONObject blobBody = new JSONObject();
            try {
                blobBody.put("content", Base64.encodeToString(en.getValue(), Base64.NO_WRAP));
                blobBody.put("encoding", "base64");
            } catch (JSONException e) {
                throw new IOException("构造 blob 请求失败", e);
            }
            JSONObject blob = postJson("/repos/" + owner + "/" + repo + "/git/blobs", blobBody);
            try {
                JSONObject entry = new JSONObject();
                entry.put("path", en.getKey());
                entry.put("mode", "100644");
                entry.put("type", "blob");
                entry.put("sha", blob.getString("sha"));
                tree.put(entry);
            } catch (JSONException e) {
                throw new IOException("构造 tree 失败", e);
            }
        }

        // 4. 新 tree（有父提交时基于旧 tree，只增改本批文件；空仓库则不带 base_tree）
        try {
            JSONObject treeBody = new JSONObject();
            if (baseTree != null) {
                treeBody.put("base_tree", baseTree);
            }
            treeBody.put("tree", tree);
            JSONObject newTree = postJson("/repos/" + owner + "/" + repo + "/git/trees", treeBody);
            String newTreeSha = newTree.getString("sha");

            // 5. 新 commit（空仓库没有父提交）
            JSONObject commitBody = new JSONObject();
            commitBody.put("message", message);
            commitBody.put("tree", newTreeSha);
            JSONArray parents = new JSONArray();
            if (headSha != null) {
                parents.put(headSha);
            }
            commitBody.put("parents", parents);
            JSONObject newCommit = postJson("/repos/" + owner + "/" + repo + "/git/commits", commitBody);
            String newCommitSha = newCommit.getString("sha");

            // 6. 移动分支指针，这一步才真正改变仓库。
            //    空仓库还没有这个分支，要 POST 创建而不是 PATCH 移动。
            JSONObject refBody = new JSONObject();
            refBody.put("sha", newCommitSha);
            if (headSha == null) {
                refBody.put("ref", "refs/heads/" + branch);
                postJson("/repos/" + owner + "/" + repo + "/git/refs", refBody);
            } else {
                request("PATCH", "/repos/" + owner + "/" + repo + "/git/refs/heads/" + branch,
                        refBody.toString());
            }
            return newCommitSha;
        } catch (JSONException e) {
            throw new IOException("构造提交失败", e);
        }
    }

    // ------------------------------------------------------------ HTTP

    private JSONObject getJson(String path, String ignored) throws IOException {
        return parse(request("GET", path, null));
    }

    private JSONObject postJson(String path, JSONObject body) throws IOException {
        return parse(request("POST", path, body.toString()));
    }

    private JSONObject putJson(String path, JSONObject body) throws IOException {
        return parse(request("PUT", path, body.toString()));
    }

    private JSONObject parse(String s) throws IOException {
        try {
            return new JSONObject(s);
        } catch (JSONException e) {
            throw new IOException("响应不是合法 JSON: " + head(s), e);
        }
    }

    private String request(String method, String path, String body) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(API + path).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            conn.setRequestProperty("User-Agent", "screentime-export");

            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] raw = body.getBytes(UTF8);
                conn.setFixedLengthStreamingMode(raw.length);
                OutputStream os = conn.getOutputStream();
                try {
                    os.write(raw);
                } finally {
                    os.close();
                }
            }

            int code = conn.getResponseCode();
            String text = read(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
            if (code < 200 || code >= 300) {
                throw new ApiException(code, explain(code, text));
            }
            return text;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String explain(int code, String body) {
        String hint;
        if (code == 401) {
            hint = "Token 无效或已过期";
        } else if (code == 403) {
            hint = "Token 权限不足（需要该仓库的 Contents 读写）";
        } else if (code == 404) {
            hint = "仓库不存在，或 Token 无权访问";
        } else if (code == 409 || code == 422) {
            String lower = body == null ? "" : body.toLowerCase(java.util.Locale.US);
            // 空仓库的 ref 查询也会返回 409，但 commit() 已按空仓库处理，
            // 走到这里通常是真的并发写入冲突。
            hint = lower.contains("empty") ? "仓库还没有任何提交，请重试" : "分支被并发修改，稍后重试";
        } else {
            hint = "请求失败";
        }
        return hint + " — " + head(body);
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
        } finally {
            in.close();
        }
        return new String(bos.toByteArray(), UTF8);
    }

    private static String head(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').trim();
        return t.length() <= 180 ? t : t.substring(0, 180) + "…";
    }

    /** 把 owner/repo 拆开，允许用户直接粘贴 GitHub 的 clone 地址。 */
    public static String[] parseRepo(String input) {
        if (input == null) {
            return null;
        }
        String s = input.trim();
        if (s.startsWith("git@github.com:")) {
            s = s.substring("git@github.com:".length());
        } else if (s.startsWith("https://github.com/")) {
            s = s.substring("https://github.com/".length());
        } else if (s.startsWith("http://github.com/")) {
            s = s.substring("http://github.com/".length());
        }
        if (s.endsWith(".git")) {
            s = s.substring(0, s.length() - 4);
        }
        s = s.replaceAll("/+$", "");
        String[] parts = s.split("/");
        if (parts.length != 2 || parts[0].length() == 0 || parts[1].length() == 0) {
            return null;
        }
        return parts;
    }

    public static String commitMessage(long ts, String modeName) {
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US);
        return "屏幕数据快照 " + f.format(new java.util.Date(ts)) + "（" + modeName + "）";
    }

    public static Map<String, byte[]> one(String path, byte[] data) {
        Map<String, byte[]> m = new LinkedHashMap<String, byte[]>();
        m.put(path, data);
        return m;
    }
}
