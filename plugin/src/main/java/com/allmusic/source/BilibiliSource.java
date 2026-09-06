package com.allmusic.source;

import com.allmusic.AllMusicPlugin;
import com.allmusic.config.ConfigManager;
import com.allmusic.model.*;
import com.allmusic.util.FfmpegUtil;
import com.allmusic.util.HttpUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bilibili 音源
 */
public class BilibiliSource implements MusicSource {
    private static final Logger logger = LoggerFactory.getLogger("MygoMusic-Bilibili");
    private static final String BASE_URL = "https://www.bilibili.com";
    private static final String API_URL = "https://api.bilibili.com";
    private static final String PASSPORT_URL = "https://passport.bilibili.com";
    private static final Gson gson = new Gson();

    private final ConfigManager configManager;
    private String cookie = "";
    private boolean loggedIn = false;
    private String qrKey = ""; // 二维码登录的 qrcode_key
    // 字幕歌词缓存：键 = bvid#p页码。B站对同视频短时间重复请求会返回空 subtitle_url（限流降级），
    // 因此首次成功取到的字幕缓存复用，避免队列播放时重新请求导致"拿到了却没歌词"。
    private final java.util.concurrent.ConcurrentHashMap<String, Lyrics> subtitleCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    // WBI签名相关
    private String wbiImgKey = "";
    private String wbiSubKey = "";
    private long wbiLastFetch = 0;
    private static final long WBI_CACHE_MS = 30 * 60 * 1000; // 30分钟缓存

    // 混淆密钥表
    private static final int[] MIXIN_KEY_ENC_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    };

    // 生成buvid3用于反爬
    private String generateBuvid3() {
        return String.format("%08x-%04x-%04x-%04x-%08x%04x",
                (int) (Math.random() * 0xFFFFFFFF),
                (int) (Math.random() * 0xFFFF),
                (int) (Math.random() * 0xFFFF),
                (int) (Math.random() * 0xFFFF),
                (int) (Math.random() * 0xFFFFFFFF),
                (int) (Math.random() * 0xFFFF));
    }

    /**
     * 获取带反爬headers的请求头
     */
    private Map<String, String> getAntiScrapingHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", BASE_URL);
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        headers.put("Origin", BASE_URL);

        // 构建cookie，包含必要的反爬标识
        StringBuilder cookieBuilder = new StringBuilder();
        if (!cookie.isEmpty()) {
            cookieBuilder.append(cookie);
        }
        // 添加buvid3（如果cookie中没有）
        if (!cookie.contains("buvid3")) {
            if (cookieBuilder.length() > 0) cookieBuilder.append("; ");
            cookieBuilder.append("buvid3=").append(generateBuvid3());
        }
        // 添加b_nut
        if (!cookie.contains("b_nut")) {
            if (cookieBuilder.length() > 0) cookieBuilder.append("; ");
            cookieBuilder.append("b_nut=100");
        }
        headers.put("Cookie", cookieBuilder.toString());

        return headers;
    }

    public BilibiliSource(ConfigManager configManager) {
        this.configManager = configManager;
    }

    @Override
    public String getName() {
        return "bilibili";
    }

    @Override
    public String getDisplayName() {
        return "B站";
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    /**
     * 获取WBI签名所需的密钥
     */
    private void fetchWbiKeys() {
        try {
            long now = System.currentTimeMillis();
            if (now - wbiLastFetch < WBI_CACHE_MS && !wbiImgKey.isEmpty()) {
                return; // 使用缓存
            }

            String url = API_URL + "/x/web-interface/nav";
            Map<String, String> headers = getAntiScrapingHeaders();

            String response = HttpUtil.get(url, headers);
            JsonObject json = gson.fromJson(response, JsonObject.class);

            if (json.has("data")) {
                JsonObject data = json.getAsJsonObject("data");
                if (data.has("wbi_img")) {
                    JsonObject wbiImg = data.getAsJsonObject("wbi_img");
                    String imgUrl = wbiImg.has("img_url") ? wbiImg.get("img_url").getAsString() : "";
                    String subUrl = wbiImg.has("sub_url") ? wbiImg.get("sub_url").getAsString() : "";

                    // 从URL中提取key (去掉路径和扩展名)
                    wbiImgKey = imgUrl.substring(imgUrl.lastIndexOf("/") + 1, imgUrl.lastIndexOf("."));
                    wbiSubKey = subUrl.substring(subUrl.lastIndexOf("/") + 1, subUrl.lastIndexOf("."));
                    wbiLastFetch = now;

                    logger.info("获取WBI密钥成功");
                }
            }
        } catch (Exception e) {
            logger.error("获取WBI密钥失败: " + e.getMessage(), e);
        }
    }

    /**
     * 生成混合密钥
     */
    private String getMixinKey() {
        String rawKey = wbiImgKey + wbiSubKey;
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < 32 && i < MIXIN_KEY_ENC_TAB.length; i++) {
            int idx = MIXIN_KEY_ENC_TAB[i];
            if (idx < rawKey.length()) {
                key.append(rawKey.charAt(idx));
            }
        }
        return key.toString();
    }

    /**
     * 计算MD5
     */
    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 对参数进行WBI签名
     */
    private Map<String, String> signParams(Map<String, String> params) {
        fetchWbiKeys();

        if (wbiImgKey.isEmpty() || wbiSubKey.isEmpty()) {
            logger.warn("WBI密钥未获取，使用原始参数");
            return params;
        }

        // 添加wts时间戳
        long wts = System.currentTimeMillis() / 1000;
        params.put("wts", String.valueOf(wts));

        // 按key排序
        List<String> keys = new ArrayList<>(params.keySet());
        java.util.Collections.sort(keys);

        // 拼接参数
        StringBuilder query = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) query.append("&");
            String key = keys.get(i);
            String value = params.get(key);
            // URL编码，但保留某些字符
            value = value.replaceAll("[!'()*]", "");
            query.append(key).append("=").append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        }

        // 添加混合密钥
        String mixinKey = getMixinKey();
        String signStr = query.toString() + mixinKey;

        // 计算MD5
        String wRid = md5(signStr);
        params.put("w_rid", wRid);

        return params;
    }

    @Override
    public List<SongInfo> search(String keyword, int limit) {
        List<SongInfo> results = new ArrayList<>();
        try {
            // 使用 search/all/v2 端点（search/type 需要WBI签名，已被封锁）
            String searchUrl = API_URL + "/x/web-interface/search/all/v2?keyword="
                    + URLEncoder.encode(keyword, StandardCharsets.UTF_8) + "&page=1&page_size=" + limit;

            Map<String, String> headers = getAntiScrapingHeaders();

            logger.debug("尝试搜索API: {}", searchUrl);
            String response = HttpUtil.get(searchUrl, headers);

            JsonObject json = parseResponseSafe(response);
            if (json == null) {
                logger.warn("B站搜索API返回无效响应");
                return results;
            }

            if (json.has("code") && json.get("code").getAsInt() != 0) {
                String message = json.has("message") ? json.get("message").getAsString() : "未知错误";
                logger.warn("B站搜索API返回错误: code={}, message={}", json.get("code").getAsInt(), message);
                return results;
            }

            if (!json.has("data")) {
                logger.warn("B站搜索响应中没有data字段");
                return results;
            }

            JsonObject data = json.getAsJsonObject("data");
            if (!data.has("result")) {
                logger.warn("B站搜索响应中没有result字段");
                return results;
            }

            // search/all/v2 的 result 是一个分组列表，需要找到 video 分组
            JsonArray resultGroups = data.getAsJsonArray("result");
            for (JsonElement groupElement : resultGroups) {
                JsonObject group = groupElement.getAsJsonObject();
                String resultType = group.has("result_type") ? group.get("result_type").getAsString() : "";

                if (!"video".equals(resultType)) {
                    continue; // 跳过非视频分组
                }

                JsonArray videos = group.has("data") ? group.getAsJsonArray("data") : null;
                if (videos == null) continue;

                logger.info("B站搜索成功，找到{}个视频结果", videos.size());
                for (JsonElement element : videos) {
                    JsonObject video = element.getAsJsonObject();

                    String bvid = video.has("bvid") ? video.get("bvid").getAsString() : "";
                    String title = video.has("title") ? video.get("title").getAsString() : "";

                    // 清理HTML标签（如 <em class="keyword">）
                    title = title.replaceAll("<[^>]+>", "");

                    // 获取作者
                    String author = video.has("author") ? video.get("author").getAsString() : "";

                    // 获取时长 (格式: "mm:ss")
                    long duration = 0;
                    if (video.has("duration")) {
                        String durationStr = video.get("duration").getAsString();
                        String[] parts = durationStr.split(":");
                        if (parts.length == 2) {
                            try {
                                duration = (Long.parseLong(parts[0]) * 60 + Long.parseLong(parts[1])) * 1000;
                            } catch (NumberFormatException ignored) {}
                        }
                    }

                    // 获取封面
                    String coverUrl = video.has("pic") ? video.get("pic").getAsString() : "";
                    if (coverUrl.startsWith("//")) {
                        coverUrl = "https:" + coverUrl;
                    }

                    if (!bvid.isEmpty() && !title.isEmpty()) {
                        // 分P数标记：>1 时存入 extra，GUI 据此标注并可列出分P
                        int pages = video.has("pages") && !video.get("pages").isJsonNull()
                                ? video.get("pages").getAsInt() : 1;
                        String extra = pages > 1 ? String.valueOf(pages) : "";
                        results.add(new SongInfo(bvid, title, author, "", duration, "bilibili", coverUrl, extra));
                    }
                }
                break; // 找到video分组后退出
            }
        } catch (Exception e) {
            logger.error("B站搜索失败: " + e.getMessage(), e);
        }
        return results;
    }

    /**
     * 安全解析JSON响应 - 处理B站API可能返回非标准JSON的情况
     */
    private JsonObject parseResponseSafe(String response) {
        if (response == null || response.isEmpty()) {
            return null;
        }

        response = response.trim();

        // 检查是否是JSON对象
        if (!response.startsWith("{")) {
            // 可能是HTML错误页面或其他格式
            logger.warn("B站API返回非JSON响应: {}", response.length() > 200 ? response.substring(0, 200) + "..." : response);
            return null;
        }

        try {
            return gson.fromJson(response, JsonObject.class);
        } catch (Exception e) {
            logger.error("解析B站API响应失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public SongDetail getDetail(String songId) {
        // 支持分P：id 可携带页码后缀，如 "BV1xx411c7mD#p2"
        if (songId != null && songId.contains("#p")) {
            int hashIdx = songId.indexOf("#p");
            String bvid = songId.substring(0, hashIdx);
            int page = 1;
            try {
                page = Integer.parseInt(songId.substring(hashIdx + 2));
            } catch (Exception ignore) {}
            if (page < 1) page = 1;
            return getDetail(bvid, page);
        }
        return getDetail(songId, 1);
    }

    /**
     * 获取视频指定分P 的详情（page 从 1 开始；单P视频忽略 page）
     */
    public SongDetail getDetail(String bvid, int page) {
        try {
            // 获取视频详情
            String url = API_URL + "/x/web-interface/view?bvid=" + bvid;
            Map<String, String> headers = getAntiScrapingHeaders();

            String response = HttpUtil.get(url, headers);
            JsonObject json = parseResponseSafe(response);
            if (json == null) return null;

            if (json.has("data")) {
                JsonObject data = json.getAsJsonObject("data");

                String title = data.has("title") ? data.get("title").getAsString() : "";
                String author = data.has("owner") && data.getAsJsonObject("owner").has("name")
                        ? data.getAsJsonObject("owner").get("name").getAsString() : "";
                long wholeDurationMs = data.has("duration") ? data.get("duration").getAsLong() * 1000 : 0;
                String coverUrl = data.has("pic") ? data.get("pic").getAsString() : "";

                // 分P列表：取指定 P 的 cid 与单P时长（分P的音频/字幕/时长各不相同）
                JsonArray pages = data.has("pages") && data.get("pages").isJsonArray()
                        ? data.getAsJsonArray("pages") : null;
                int totalPages = pages != null ? pages.size() : 1;
                int idx = Math.max(0, Math.min(totalPages - 1, page - 1));
                String cid = "";
                long duration = wholeDurationMs;
                if (pages != null && pages.size() > 0) {
                    JsonObject pg = pages.get(idx).getAsJsonObject();
                    if (pg.has("cid")) cid = pg.get("cid").getAsString();
                    // 单P时长（秒）：view 接口每个分P自带 duration 字段
                    if (pg.has("duration") && !pg.get("duration").isJsonNull()) {
                        try {
                            long secs = pg.get("duration").getAsLong();
                            if (secs > 0) duration = secs * 1000;
                        } catch (Exception ignore) {}
                    }
                }
                if (cid.isEmpty() && data.has("cid")) cid = data.get("cid").getAsString();
                if (cid.isEmpty()) {
                    logger.warn("获取B站CID为空: bvid={}, page={}", bvid, page);
                    return null;
                }

                // 选中非第一P时，在标题后标注分P，便于识别
                boolean explicitPage = page > 1;
                String displayTitle = title;
                if (explicitPage) {
                    displayTitle = title + " §7(P" + page + "/" + totalPages + ")";
                }

                // 获取音频URL（复用已知 cid，避免重复请求）
                String audioUrl = getAudioUrl(bvid, cid);

                // 获取该分P字幕作为歌词（不包含AI字幕；需B站登录Cookie）
                Lyrics lyrics = fetchSubtitlesAsLyrics(bvid, cid, duration, page);

                // 返回的 songId 保留页码后缀，保证队列/重播仍解析到同一分P
                String detailId = explicitPage ? bvid + "#p" + page : bvid;
                String extra = totalPages > 1 ? String.valueOf(totalPages) : "";

                return new SongDetail(detailId, displayTitle, author, "", duration, "bilibili", coverUrl, extra,
                        audioUrl, lyrics, false, null);
            }
        } catch (Exception e) {
            logger.error("获取视频详情失败: " + e.getMessage(), e);
        }
        return null;
    }

    @Override
    public String getAudioUrl(String bvid) {
        return getAudioUrl(bvid, null);
    }

    /**
     * 获取音频URL（knownCid 非空时复用，避免再请求一次 view API）
     */
    private String getAudioUrl(String bvid, String knownCid) {
        try {
            // 首先尝试获取视频的音频流
            logger.debug("尝试获取B站音频URL: bvid={}", bvid);
            String cid = (knownCid != null && !knownCid.isEmpty()) ? knownCid : getCid(bvid);
            if (cid == null) {
                logger.warn("无法获取CID: {}", bvid);
                return null;
            }
            logger.debug("获取到CID: bvid={}, cid={}", bvid, cid);

            // 获取音频流地址
            String url = API_URL + "/x/player/playurl?bvid=" + bvid + "&cid=" + cid + "&fnval=16";
            Map<String, String> headers = getAntiScrapingHeaders();

            logger.debug("请求音频流: {}", url);
            String response = HttpUtil.get(url, headers);
            JsonObject json = parseResponseSafe(response);
            if (json == null) {
                logger.warn("获取音频流返回空响应");
                return null;
            }

            logger.debug("音频流响应: {}", response.substring(0, Math.min(500, response.length())));

            // 检查返回码
            int code = json.has("code") ? json.get("code").getAsInt() : -1;
            if (code != 0) {
                String message = json.has("message") ? json.get("message").getAsString() : "未知错误";
                logger.warn("获取音频流API返回错误: code={}, message={}", code, message);
            }

            if (json.has("data") && json.getAsJsonObject("data").has("dash")) {
                JsonObject dash = json.getAsJsonObject("data").getAsJsonObject("dash");

                // 获取音频流
                if (dash.has("audio")) {
                    JsonArray audioArray = dash.getAsJsonArray("audio");
                    logger.debug("找到{}个音频流", audioArray.size());
                    if (audioArray.size() > 0) {
                        JsonObject audioObj = audioArray.get(0).getAsJsonObject();
                        String audioUrl = audioObj.has("baseUrl") ? audioObj.get("baseUrl").getAsString()
                                : (audioObj.has("base_url") ? audioObj.get("base_url").getAsString() : "");
                        if (!audioUrl.isEmpty()) {
                            logger.info("获取B站音频URL成功: bvid={}, url长度={}", bvid, audioUrl.length());
                            // m4s 是 AAC 格式，客户端无法直接播放，需转码为 MP3
                            return transcodeAudioToMp3(bvid, cid, audioUrl);
                        } else {
                            logger.warn("音频流URL为空");
                        }
                    }
                } else {
                    logger.warn("DASH响应中没有audio字段");
                }
            } else {
                logger.warn("响应中没有DASH数据");
            }

            // 如果无法直接获取音频，尝试使用ffmpeg转码
            logger.info("无法直接获取音频，尝试使用ffmpeg转码: {}", bvid);
            return transcodeWithFfmpeg(bvid, cid);
        } catch (Exception e) {
            logger.error("获取音频URL失败: " + e.getMessage(), e);
        }
        return null;
    }

    /**
     * 获取视频CID
     */
    private String getCid(String bvid) {
        try {
            String url = API_URL + "/x/web-interface/view?bvid=" + bvid;
            Map<String, String> headers = getAntiScrapingHeaders();

            String response = HttpUtil.get(url, headers);
            JsonObject json = parseResponseSafe(response);
            if (json == null) return null;

            if (json.has("data") && json.getAsJsonObject("data").has("cid")) {
                return json.getAsJsonObject("data").get("cid").getAsString();
            }
        } catch (Exception e) {
            logger.error("获取CID失败: " + e.getMessage(), e);
        }
        return null;
    }

    /**
     * 列出某B站视频的全部分P（每P一条），供GUI选择分P。
     * 标题格式："P3 · <该P标题>"，id 带 #p页码，携带该P时长。
     */
    public List<SongInfo> listParts(String bvid) {
        List<SongInfo> parts = new ArrayList<>();
        try {
            String url = API_URL + "/x/web-interface/view?bvid=" + bvid;
            JsonObject json = parseResponseSafe(HttpUtil.get(url, getAntiScrapingHeaders()));
            if (json == null || !json.has("data")) return parts;
            JsonObject data = json.getAsJsonObject("data");
            String author = data.has("owner") && data.getAsJsonObject("owner").has("name")
                    ? data.getAsJsonObject("owner").get("name").getAsString() : "";
            JsonArray pages = data.has("pages") && data.get("pages").isJsonArray()
                    ? data.getAsJsonArray("pages") : null;
            if (pages == null || pages.size() == 0) return parts;
            int total = pages.size();
            for (int i = 0; i < total; i++) {
                JsonObject pg = pages.get(i).getAsJsonObject();
                String partTitle = pg.has("part") ? pg.get("part").getAsString() : "P" + (i + 1);
                String cid = pg.has("cid") ? pg.get("cid").getAsString() : "";
                long dur = 0;
                if (pg.has("duration") && !pg.get("duration").isJsonNull()) {
                    try { dur = pg.get("duration").getAsLong() * 1000; } catch (Exception ignore) {}
                }
                if (cid.isEmpty()) continue;
                String title = "P" + (i + 1) + " · " + partTitle;
                parts.add(new SongInfo(bvid + "#p" + (i + 1), title, author, "", dur, "bilibili", "", ""));
            }
        } catch (Exception e) {
            logger.error("获取分P列表失败: " + e.getMessage(), e);
        }
        return parts;
    }

    private Lyrics emptyLyrics() {
        return new Lyrics(null, "", false);
    }

    /**
     * 查询视频分P数（用于关键字搜索结果标注；单P返回1）
     */
    public int getPageCount(String bvid) {
        try {
            String url = API_URL + "/x/web-interface/view?bvid=" + bvid;
            JsonObject json = parseResponseSafe(HttpUtil.get(url, getAntiScrapingHeaders()));
            if (json != null && json.has("data") && json.getAsJsonObject("data").has("pages")
                    && json.getAsJsonObject("data").get("pages").isJsonArray()) {
                return json.getAsJsonObject("data").getAsJsonArray("pages").size();
            }
        } catch (Exception e) {
            logger.debug("查询分P数失败: {} - {}", bvid, e.getMessage());
        }
        return 1;
    }

    /**
     * 判断某个字幕是否为 AI 生成字幕（AI字幕不显示）
     */
    private boolean isAiSubtitle(JsonObject s) {
        try {
            if (s.has("ai_type") && s.get("ai_type").isJsonPrimitive()) {
                try { if (s.get("ai_type").getAsInt() == 1) return true; } catch (Exception ignore) {}
            }
            if (s.has("from_ai") && s.get("from_ai").isJsonPrimitive()) {
                try { if (s.get("from_ai").getAsInt() == 1) return true; } catch (Exception ignore) {}
            }
            if (s.has("ai_status")) {
                JsonElement st = s.get("ai_status");
                if (st.isJsonPrimitive()) {
                    try {
                        if (st.getAsInt() == 1 || st.getAsInt() == 2) return true;
                    } catch (Exception ignore) {}
                }
            }
            String lan = s.has("lan") ? s.get("lan").getAsString() : "";
            if (lan.startsWith("ai-")) return true;
            if (s.has("subtitle_url")) {
                String u = s.get("subtitle_url").getAsString();
                if (u.contains("/ai_subtitle/")) return true;
            }
            if (s.has("lan_doc") && s.get("lan_doc").getAsString().contains("自动生成")) return true;
        } catch (Exception e) {
            logger.warn("判断AI字幕失败: " + e.getMessage());
        }
        return false;
    }

    /**
     * 获取视频字幕并转换为歌词（仅人工字幕，不含AI字幕）
     */
    private Lyrics fetchSubtitlesAsLyrics(String bvid, String cid, long durationMs, int page) {
        return fetchSubtitlesAsLyrics(bvid, cid, durationMs, page, 1);
    }

    /**
     * attempt：第几次尝试。B站对字幕内容有"残缺降级"（列表在但内容只剩占位/空），
     * 行数过少时等一会重试（每次重新拉字幕列表拿新URL），最多3次。
     */
    private Lyrics fetchSubtitlesAsLyrics(String bvid, String cid, long durationMs, int page, int attempt) {
        String cacheKey = bvid + "#p" + page;
        Lyrics cached = subtitleCache.get(cacheKey);
        if (cached != null) {
            logger.debug("使用B站字幕缓存: {}", cacheKey);
            return cached;
        }
        try {
            if (cid == null || cid.isEmpty()) return emptyLyrics();

            // 1. 请求字幕列表（B站字幕需登录Cookie(SESSDATA)才会返回，未登录恒为空数组）
            String v2Url = API_URL + "/x/player/v2?bvid=" + bvid + "&cid=" + cid;
            JsonObject json = null;
            JsonObject tryJson = null;
            try {
                String resp = HttpUtil.get(v2Url, getAntiScrapingHeaders());
                tryJson = parseResponseSafe(resp);
            } catch (Exception e) {
                logger.warn("B站字幕接口请求异常: bvid={}, err={}", bvid, e.getMessage());
            }
            if (tryJson != null && tryJson.has("data")) json = tryJson;
            if (json == null) {
                logger.warn("B站字幕接口未返回数据: bvid={} (code={})", bvid,
                        tryJson != null && tryJson.has("code") && !tryJson.get("code").isJsonNull()
                                ? String.valueOf(tryJson.get("code").getAsInt()) : "未知");
                return emptyLyrics();
            }
            JsonObject data = json.getAsJsonObject("data");
            if (data == null || !data.has("subtitle")) return emptyLyrics();
            JsonObject subtitle = data.getAsJsonObject("subtitle");
            if (subtitle == null || !subtitle.has("subtitles")) return emptyLyrics();
            JsonArray subs = subtitle.getAsJsonArray("subtitles");
            if (subs == null || subs.size() == 0) {
                if (!loggedIn) {
                    logger.info("B站字幕列表为空：当前未登录B站，字幕接口(需SESSDATA)不会返回任何字幕。若该视频确有CC字幕，请先执行 /mm login bilibili <Cookie> 再试");
                } else {
                    logger.info("B站该视频没有字幕: bvid={}", bvid);
                }
                return emptyLyrics();
            }

            // 2. 挑选字幕：只显示人工字幕（中文优先），不显示AI字幕（AI内容可能与视频不符）
            String manualAny = null;
            String manualZh = null;
            int trackNo = 0;
            for (JsonElement e : subs) {
                trackNo++;
                if (e == null || !e.isJsonObject()) continue;
                JsonObject s = e.getAsJsonObject();
                String lan = s.has("lan") ? s.get("lan").getAsString() : "";
                String lanDoc = s.has("lan_doc") ? s.get("lan_doc").getAsString() : "";
                String subUrl = s.has("subtitle_url") ? s.get("subtitle_url").getAsString() : "";
                boolean ai = isAiSubtitle(s);
                logger.info("B站字幕轨道#{}：lan={} lan_doc={} ai_type={} from_ai={} ai_status={} 类别={} url={}",
                        trackNo, lan, lanDoc,
                        s.has("ai_type") ? String.valueOf(s.get("ai_type")) : "无",
                        s.has("from_ai") ? String.valueOf(s.get("from_ai")) : "无",
                        s.has("ai_status") ? String.valueOf(s.get("ai_status")) : "无",
                        ai ? "AI(不显示)" : "人工",
                        subUrl.length() > 70 ? subUrl.substring(0, 70) : subUrl);
                if (ai) continue; // AI字幕一律不显示
                if (subUrl.isEmpty()) continue;
                if (manualAny == null) manualAny = subUrl;
                if (lan.startsWith("zh") && manualZh == null) manualZh = subUrl;
            }
            String chosenUrl = manualZh != null ? manualZh : manualAny;
            if (chosenUrl == null) {
                logger.info("该分P无人工字幕(AI字幕按要求不显示): bvid={}, 字幕轨道数={}", bvid, subs.size());
                return emptyLyrics();
            }

            // 3. 下载字幕内容 JSON
            String subUrl = chosenUrl.startsWith("//") ? "https:" + chosenUrl : chosenUrl;
            Map<String, String> headers = getAntiScrapingHeaders();
            headers.put("Referer", "https://www.bilibili.com/video/" + bvid);
            JsonObject subJson = parseResponseSafe(HttpUtil.get(subUrl, headers));
            if (subJson == null || !subJson.has("body")) {
                logger.info("B站字幕内容获取失败: bvid={}", bvid);
                return emptyLyrics();
            }
            JsonArray body = subJson.getAsJsonArray("body");
            if (body == null || body.size() == 0) return emptyLyrics();

            // 4. 判断时间单位 (秒 or 毫秒): 取最大 to 时间，与视频总时长比较取接近者
            double maxVal = 0;
            for (JsonElement e : body) {
                if (e != null && e.isJsonObject() && e.getAsJsonObject().has("to")) {
                    maxVal = Math.max(maxVal, e.getAsJsonObject().get("to").getAsDouble());
                }
            }
            boolean isSeconds;
            if (durationMs > 0) {
                // 与视频总时长比较，取更接近的单位
                isSeconds = Math.abs(maxVal - durationMs / 1000.0) <= Math.abs(maxVal - durationMs);
            } else {
                // 无时长参考时：B站CC字幕时间多为秒（正常视频 < 1万秒），超过阈值视为毫秒
                isSeconds = maxVal < 10000;
            }
            double factor = isSeconds ? 1000.0 : 1.0;

            // 5. 转换为歌词行
            List<LyricsLine> lines = new ArrayList<>();
            for (JsonElement e : body) {
                if (e == null || !e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                double from = o.has("from") ? o.get("from").getAsDouble() : 0;
                String content = o.has("content") ? o.get("content").getAsString() : "";
                if (content.isEmpty()) continue;
                content = content.replace("\\n", " ").replace("\n", " ").trim();
                content = content.replaceAll("<[^>]*>", "").trim(); // 去内嵌样式
                if (content.isEmpty()) continue;
                lines.add(new LyricsLine((long) (from * factor), content, null));
            }
            if (lines.isEmpty()) {
                logger.warn("B站字幕内容为空(疑似限流残缺): bvid={}, page={}, 第{}次", bvid, page, attempt);
                return attempt < 3 ? retrySubtitle(bvid, cid, durationMs, page, attempt) : emptyLyrics();
            }

            // 保证时间升序，避免字幕行乱序导致歌词跳变/串行
            lines.sort(java.util.Comparator.comparingLong(LyricsLine::getTimestamp));

            // 行数过少：很可能是B站"残缺降级"给的内容占位，重试拿新URL
            if (lines.size() <= 1 && attempt < 3) {
                logger.warn("B站字幕行数过少({})，疑似限流残缺，重试: bvid={}, 第{}次", lines.size(), bvid, attempt);
                return retrySubtitle(bvid, cid, durationMs, page, attempt);
            }

            logger.info("获取B站字幕成功并作为歌词: bvid={}, 行数={}, 单位={}", bvid, lines.size(), isSeconds ? "秒" : "毫秒");
            // 打印带时间戳的前几行，便于核对是否与歌声同步
            for (int i = 0; i < Math.min(5, lines.size()); i++) {
                LyricsLine ll = lines.get(i);
                logger.info("  字幕样例[{}] 时间={}s: {}",
                        i, ll.getTimestamp() / 1000.0, ll.getText());
            }
            Lyrics lyrics = new Lyrics(lines, null, false);
            // 行数过少的结果不入缓存，避免缓存污染的1行字幕
            if (lines.size() >= 2) {
                subtitleCache.put(cacheKey, lyrics);
            }
            return lyrics;
        } catch (Exception e) {
            logger.error("获取B站字幕失败: " + e.getMessage(), e);
        }
        return emptyLyrics();
    }

    private Lyrics retrySubtitle(String bvid, String cid, long durationMs, int page, int attempt) {
        try {
            Thread.sleep(1200L * attempt); // 间隔重试
        } catch (InterruptedException ignore) {
            Thread.currentThread().interrupt();
        }
        return fetchSubtitlesAsLyrics(bvid, cid, durationMs, page, attempt + 1);
    }

    /**
     * 使用ffmpeg将B站音频流(m4s/AAC)转码为MP3，并通过HTTP服务器提供
     */
    private String transcodeAudioToMp3(String bvid, String cid, String audioUrl) {
        String ffmpegPath = configManager.getFfmpegPath();
        String cacheDir = configManager.getFfmpegCacheDir();
        // 缓存文件名带上 cid（分P），避免不同分P混用同一份音频
        String cacheBase = bvid + "_" + cid;

        // 检查缓存
        File cachedMp3 = new File(cacheDir, cacheBase + ".mp3");
        if (cachedMp3.exists()) {
            logger.info("使用缓存文件: {}", cachedMp3.getAbsolutePath());
            return AllMusicPlugin.getInstance().getHttpFileServer().getFileUrl(cachedMp3);
        }

        if (!FfmpegUtil.isAvailable(ffmpegPath)) {
            logger.error("ffmpeg不可用，无法转码B站音频");
            return null;
        }

        try {
            // 下载音频到临时文件
            String tempAudio = cacheDir + "/" + cacheBase + "_temp.m4s";
            logger.info("开始下载B站音频: {}", bvid);
            downloadFile(audioUrl, tempAudio);

            // 使用ffmpeg转码为MP3
            logger.info("开始转码B站音频为MP3: {}", bvid);
            boolean success = FfmpegUtil.extractAudio(ffmpegPath, tempAudio, cachedMp3.getAbsolutePath());

            // 删除临时文件
            new File(tempAudio).delete();

            if (success) {
                FfmpegUtil.cleanCache(cacheDir, configManager.getFfmpegCacheMaxSize());
                return AllMusicPlugin.getInstance().getHttpFileServer().getFileUrl(cachedMp3);
            }
        } catch (Exception e) {
            logger.error("B站音频转码失败: " + e.getMessage(), e);
        }
        return null;
    }

    /**
     * 使用ffmpeg转码视频为MP3
     */
    private String transcodeWithFfmpeg(String bvid, String cid) {
        String ffmpegPath = configManager.getFfmpegPath();
        String cacheDir = configManager.getFfmpegCacheDir();

        // 检查ffmpeg是否可用
        if (!FfmpegUtil.isAvailable(ffmpegPath)) {
            logger.error("ffmpeg不可用，请检查配置");
            return null;
        }

        // 检查缓存（文件名带 cid，分P各自独立）
        String cacheBase = bvid + "_" + cid;
        String cachedFile = cacheDir + "/" + cacheBase + ".mp3";
        if (new File(cachedFile).exists()) {
            logger.info("使用缓存文件: {}", cachedFile);
            return AllMusicPlugin.getInstance().getHttpFileServer().getFileUrl(new File(cachedFile));
        }

        try {
            // 获取视频下载URL
            String videoUrl = getVideoDownloadUrl(bvid, cid);
            if (videoUrl == null) {
                logger.error("无法获取视频下载URL");
                return null;
            }

            // 下载视频到临时文件
            String tempVideo = cacheDir + "/" + cacheBase + "_temp.mp4";
            logger.info("开始下载视频: {}", bvid);
            downloadFile(videoUrl, tempVideo);

            // 使用ffmpeg转码
            logger.info("开始转码: {}", bvid);
            boolean success = FfmpegUtil.extractAudio(ffmpegPath, tempVideo, cachedFile);

            // 删除临时文件
            new File(tempVideo).delete();

            if (success) {
                // 清理旧缓存
                FfmpegUtil.cleanCache(cacheDir, configManager.getFfmpegCacheMaxSize());
                return AllMusicPlugin.getInstance().getHttpFileServer().getFileUrl(new File(cachedFile));
            }
        } catch (Exception e) {
            logger.error("转码失败: " + e.getMessage(), e);
        }
        return null;
    }

    /**
     * 获取视频下载URL
     */
    private String getVideoDownloadUrl(String bvid, String cid) {
        try {
            String url = API_URL + "/x/player/playurl?bvid=" + bvid + "&cid=" + cid + "&qn=16&fnval=0";
            Map<String, String> headers = getAntiScrapingHeaders();

            String response = HttpUtil.get(url, headers);
            JsonObject json = parseResponseSafe(response);
            if (json == null) return null;

            if (json.has("data") && json.getAsJsonObject("data").has("durl")) {
                JsonArray durl = json.getAsJsonObject("data").getAsJsonArray("durl");
                if (durl.size() > 0) {
                    return durl.get(0).getAsJsonObject().get("url").getAsString();
                }
            }
        } catch (Exception e) {
            logger.error("获取视频下载URL失败: " + e.getMessage(), e);
        }
        return null;
    }

    /**
     * 下载文件
     */
    private void downloadFile(String url, String savePath) throws Exception {
        // 确保目录存在
        new File(savePath).getParentFile().mkdirs();

        // 使用OkHttp下载
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(url)
                .addHeader("Referer", BASE_URL)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build();

        try (okhttp3.Response response = HttpUtil.getClient().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("下载失败: " + response.code());
            }

            java.io.InputStream inputStream = response.body().byteStream();
            java.io.FileOutputStream outputStream = new java.io.FileOutputStream(savePath);

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }

            outputStream.close();
            inputStream.close();
        }
    }

    @Override
    public Lyrics getLyrics(String bvid) {
        // B站视频通常没有歌词
        return new Lyrics(null, "", false);
    }

    @Override
    public LoginResult login(LoginMethod method) {
        if (method == LoginMethod.QR_CODE) {
            return loginWithQrCode();
        }
        return LoginResult.failure("不支持的登录方式");
    }

    private LoginResult loginWithQrCode() {
        try {
            // B站二维码登录API（使用 passport 域名）
            String url = PASSPORT_URL + "/x/passport-login/web/qrcode/generate";
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", BASE_URL);
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

            String response = HttpUtil.get(url, headers);
            JsonObject json = parseResponseSafe(response);
            if (json == null) return LoginResult.failure("获取二维码失败");

            if (json.has("code") && json.get("code").getAsInt() != 0) {
                String message = json.has("message") ? json.get("message").getAsString() : "未知错误";
                return LoginResult.failure("获取二维码失败: " + message);
            }

            if (json.has("data")) {
                JsonObject data = json.getAsJsonObject("data");
                String qrcodeKey = data.has("qrcode_key") ? data.get("qrcode_key").getAsString() : "";
                String url1 = data.has("url") ? data.get("url").getAsString() : "";

                if (!qrcodeKey.isEmpty()) {
                    this.qrKey = qrcodeKey;
                    logger.info("B站二维码登录已生成, qrcode_key={}", qrcodeKey);
                    return LoginResult.waitingForQrCode(url1, "请使用B站APP扫描二维码登录");
                }
            }
        } catch (Exception e) {
            logger.error("获取二维码失败: " + e.getMessage(), e);
        }
        return LoginResult.failure("获取二维码失败");
    }

    @Override
    public LoginResult pollQrCodeLogin(int timeoutSeconds) {
        if (qrKey.isEmpty()) {
            return LoginResult.failure("没有待确认的二维码");
        }

        long start = System.currentTimeMillis();
        long timeout = timeoutSeconds * 1000L;

        while (System.currentTimeMillis() - start < timeout) {
            try {
                Thread.sleep(2000); // 每2秒轮询一次

                String url = PASSPORT_URL + "/x/passport-login/web/qrcode/poll?qrcode_key=" + qrKey;
                Map<String, String> headers = new HashMap<>();
                headers.put("Referer", BASE_URL);
                headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

                HttpUtil.HttpResponse response = HttpUtil.getWithHeaders(url, headers);
                if (response == null || response.body == null || response.body.isEmpty()) {
                    continue;
                }

                JsonObject json = parseResponseSafe(response.body);
                if (json == null) continue;

                if (!json.has("data")) continue;
                JsonObject data = json.getAsJsonObject("data");
                int code = data.has("code") ? data.get("code").getAsInt() : -1;

                switch (code) {
                    case 86101:
                        logger.debug("B站二维码等待扫码...");
                        break;
                    case 86090:
                        logger.info("B站二维码已扫码，等待确认...");
                        break;
                    case 0: {
                        // 登录成功，从 Set-Cookie 中提取 cookie
                        String extractedCookie = extractBiliCookie(response.setCookie);
                        if (extractedCookie != null && !extractedCookie.isEmpty()) {
                            this.cookie = extractedCookie;
                            this.loggedIn = true;
                            this.qrKey = "";
                            logger.info("B站二维码登录成功");
                            return LoginResult.success(null, extractedCookie, "B站登录成功");
                        } else {
                            // 尝试从 data.url 中提取
                            String urlData = data.has("url") ? data.get("url").getAsString() : "";
                            this.cookie = urlData;
                            this.loggedIn = true;
                            this.qrKey = "";
                            logger.warn("B站登录成功但未获取到完整Cookie, url={}", urlData);
                            return LoginResult.success(null, urlData, "B站登录成功（但未获取到完整Cookie）");
                        }
                    }
                    case 86038:
                        this.qrKey = "";
                        return LoginResult.failure("二维码已失效，请重新登录");
                    default:
                        logger.debug("B站二维码未知状态码: {}", code);
                        break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.debug("B站轮询登录状态失败: {}", e.getMessage());
            }
        }

        this.qrKey = "";
        return LoginResult.failure("登录超时，请重试");
    }

    /**
     * 从 Set-Cookie 头中提取 B站登录 Cookie
     */
    private String extractBiliCookie(String setCookie) {
        if (setCookie == null || setCookie.isEmpty()) {
            return null;
        }
        StringBuilder cookieBuilder = new StringBuilder();
        String[] parts = setCookie.split(";");
        for (String part : parts) {
            String trimmed = part.trim();
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                String name = trimmed.substring(0, eq);
                // 保留关键登录 cookie
                if (name.equalsIgnoreCase("SESSDATA") || name.equalsIgnoreCase("bili_jct")
                        || name.equalsIgnoreCase("DedeUserID") || name.equalsIgnoreCase("DedeUserID__ckMd5")
                        || name.equalsIgnoreCase("sid")) {
                    if (cookieBuilder.length() > 0) cookieBuilder.append("; ");
                    cookieBuilder.append(trimmed);
                }
            }
        }
        return cookieBuilder.length() > 0 ? cookieBuilder.toString() : null;
    }

    @Override
    public LoginResult loginWithCookie(String cookie) {
        this.cookie = cookie;
        this.loggedIn = true;
        boolean hasSess = cookie != null && cookie.contains("SESSDATA");
        boolean hasJct = cookie != null && cookie.contains("bili_jct");
        logger.info("B站Cookie登录：含SESSDATA={} bili_jct={}（字幕歌词必须含SESSDATA才会返回）", hasSess, hasJct);
        if (hasSess) {
            return LoginResult.success(null, cookie, "B站Cookie登录成功");
        }
        return LoginResult.success(null, cookie, "B站Cookie已保存，但未检测到 SESSDATA（将无法获取B站字幕歌词）");
    }

    @Override
    public boolean isLoggedIn() {
        return loggedIn;
    }

    @Override
    public void refreshSession() {
        // B站Cookie可能需要定期刷新
    }

    @Override
    public void logout() {
        this.cookie = "";
        this.loggedIn = false;
    }

    @Override
    public String getLoginStatus() {
        return loggedIn ? "已登录 (Cookie)" : "未登录";
    }

    @Override
    public String getSavedCookie() {
        return cookie;
    }
}
