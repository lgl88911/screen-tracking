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
        String branch = o.optString("default_branch", "main");
        info.defaultBranch = (branch == null || branch.length() == 0) ? "main" : branch;
        return info;
    }

    // ------------------------------------------------------------ 提交

    /**
     * 把多个文件放进同一个 commit 推上去。
     *
     * @param files  仓库内路径 → 文件字节
     * @param message commit 说明
     * @return 新 commit 的 sha
     */
    public String commit(Map<String, byte[]> files, String message) throws IOException {
        RepoInfo info = repoInfo();
        String branch = info.defaultBranch;

        // 1. 当前分支指向的 commit
        JSONObject ref = getJson("/repos/" + owner + "/" + repo + "/git/ref/heads/" + branch, null);
        String headSha;
        try {
            headSha = ref.getJSONObject("object").getString("sha");
        } catch (JSONException e) {
            throw new IOException("分支 " + branch + " 的响应缺少 sha 字段", e);
        }

        // 2. 该 commit 的 tree
        JSONObject headCommit = getJson("/repos/" + owner + "/" + repo + "/git/commits/" + headSha, null);
        String baseTree;
        try {
            baseTree = headCommit.getJSONObject("tree").getString("sha");
        } catch (JSONException e) {
            throw new IOException("commit 响应缺少 tree 字段", e);
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

        // 4. 新 tree（基于旧 tree，只增改本批文件）
        try {
            JSONObject treeBody = new JSONObject();
            treeBody.put("base_tree", baseTree);
            treeBody.put("tree", tree);
            JSONObject newTree = postJson("/repos/" + owner + "/" + repo + "/git/trees", treeBody);
            String newTreeSha = newTree.getString("sha");

            // 5. 新 commit
            JSONObject commitBody = new JSONObject();
            commitBody.put("message", message);
            commitBody.put("tree", newTreeSha);
            commitBody.put("parents", new JSONArray().put(headSha));
            JSONObject newCommit = postJson("/repos/" + owner + "/" + repo + "/git/commits", commitBody);
            String newCommitSha = newCommit.getString("sha");

            // 6. 移动分支指针，这一步才真正改变仓库
            JSONObject refBody = new JSONObject();
            refBody.put("sha", newCommitSha);
            request("PATCH", "/repos/" + owner + "/" + repo + "/git/refs/heads/" + branch,
                    refBody.toString());
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
            hint = "分支被并发修改，稍后重试";
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

    /** 生成仓库内路径：2026/11/15.sce */
    public static String dayPath(long ts) {
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.US);
        return f.format(new java.util.Date(ts)) + ".sce";
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
