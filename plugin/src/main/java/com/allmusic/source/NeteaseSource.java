package com.allmusic.source;

import com.allmusic.model.*;
import com.allmusic.util.HttpUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网易云音乐音源
 */
public class NeteaseSource implements MusicSource {
    private static final Logger logger = LoggerFactory.getLogger("MygoMusic-Netease");
    private static final String BASE_URL = "https://music.163.com";
    private static final Gson gson = new Gson();

    private String cookie = "";
    private boolean loggedIn = false;
    private String qrKey = ""; // 二维码登录的 unikey

    @Override
    public String getName() {
        return "netease";
    }

    @Override
    public String getDisplayName() {
        return "网易云";
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public List<SongInfo> search(String keyword, int limit) {
        List<SongInfo> results = new ArrayList<>();
        try {
            // 关键词必须编码：中文、空格、& # + 等字符直接拼进 URL 会把查询串截断或改变语义
            String url = BASE_URL + "/api/search/get/web?s="
                    + URLEncoder.encode(keyword, StandardCharsets.UTF_8) + "&type=1&limit=" + limit;
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", BASE_URL);
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

            String response = HttpUtil.get(url, headers);
            JsonObject json = gson.fromJson(response, JsonObject.class);

            if (json.has("result") && json.getAsJsonObject("result").has("songs")) {
                JsonArray songs = json.getAsJsonObject("result").getAsJsonArray("songs");
                for (JsonElement element : songs) {
                    JsonObject song = element.getAsJsonObject();
                    String songId = song.get("id").getAsString();
                    String title = song.get("name").getAsString();

                    // 获取歌手信息
                    String artist = "";
                    if (song.has("artists")) {
                        JsonArray artists = song.getAsJsonArray("artists");
                        StringBuilder artistBuilder = new StringBuilder();
                        for (int i = 0; i < artists.size(); i++) {
                            if (i > 0) artistBuilder.append(", ");
                            artistBuilder.append(artists.get(i).getAsJsonObject().get("name").getAsString());
                        }
                        artist = artistBuilder.toString();
                    }

                    // 获取专辑信息
                    String album = "";
                    if (song.has("album")) {
                        album = song.getAsJsonObject("album").get("name").getAsString();
                    }

                    // 获取时长
                    long duration = song.has("duration") ? song.get("duration").getAsLong() : 0;

                    // 获取封面
                    String coverUrl = "";
                    if (song.has("album") && song.getAsJsonObject("album").has("picUrl")) {
                        coverUrl = song.getAsJsonObject("album").get("picUrl").getAsString();
                    }

                    results.add(new SongInfo(songId, title, artist, album, duration, "netease", coverUrl, ""));
                }
            }
        } catch (Exception e) {
            logger.error("网易云搜索失败: " + e.getMessage(), e);
        }
        return results;
    }

    @Override
    public SongDetail getDetail(String songId) {
        try {
            // 获取歌曲详情
            String detailUrl = BASE_URL + "/api/song/detail?ids=[" + songId + "]";
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", BASE_URL);
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

            String detailResponse = HttpUtil.get(detailUrl, headers);
            JsonObject detailJson = gson.fromJson(detailResponse, JsonObject.class);

            if (detailJson.has("songs") && detailJson.getAsJsonArray("songs").size() > 0) {
                JsonObject song = detailJson.getAsJsonArray("songs").get(0).getAsJsonObject();

                String title = song.get("name").getAsString();
                String artist = "";
                if (song.has("artists")) {
                    JsonArray artists = song.getAsJsonArray("artists");
                    StringBuilder artistBuilder = new StringBuilder();
                    for (int i = 0; i < artists.size(); i++) {
                        if (i > 0) artistBuilder.append(", ");
                        artistBuilder.append(artists.get(i).getAsJsonObject().get("name").getAsString());
                    }
                    artist = artistBuilder.toString();
                }

                String album = "";
                if (song.has("album")) {
                    album = song.getAsJsonObject("album").get("name").getAsString();
                }

                long duration = song.has("duration") ? song.get("duration").getAsLong() : 0;

                String coverUrl = "";
                if (song.has("album") && song.getAsJsonObject("album").has("picUrl")) {
                    coverUrl = song.getAsJsonObject("album").get("picUrl").getAsString();
                }

                // 获取音频URL
                String audioUrl = getAudioUrl(songId);

                // 获取歌词
                Lyrics lyrics = getLyrics(songId);

                // 检查是否VIP歌曲
                boolean isVip = song.has("fee") && song.get("fee").getAsInt() == 1;

                return new SongDetail(songId, title, artist, album, duration, "netease", coverUrl, "",
                        audioUrl, lyrics, isVip, null);
            }
        } catch (Exception e) {
            logger.error("获取歌曲详情失败: " + e.getMessage(), e);
        }
        return null;
    }

    @Override
    public String getAudioUrl(String songId) {
        try {
            // 使用多个API端点尝试获取音频URL（注意：必须用ids=[songId]复数格式）
            String[] apiEndpoints = {
                BASE_URL + "/api/song/enhance/player/url?ids=[" + songId + "]&br=320000",
                BASE_URL + "/api/song/enhance/player/url?ids=[" + songId + "]&br=128000",
                BASE_URL + "/api/song/enhance/player/url?ids=[" + songId + "]&br=96000"
            };

            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", BASE_URL);
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
            headers.put("Accept", "application/json, text/plain, */*");
            headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");

            if (!cookie.isEmpty()) {
                headers.put("Cookie", cookie);
            }

            for (String url : apiEndpoints) {
                try {
                    logger.debug("尝试获取音频URL: {}", url);
                    String response = HttpUtil.get(url, headers);
                    if (response == null || response.isEmpty()) {
                        logger.debug("API返回空响应: {}", url);
                        continue;
                    }

                    JsonObject json = HttpUtil.fromJsonLenient(response, JsonObject.class);
                    logger.debug("API响应: {}", response.substring(0, Math.min(500, response.length())));

                    if (json.has("data") && json.getAsJsonArray("data").size() > 0) {
                        JsonObject data = json.getAsJsonArray("data").get(0).getAsJsonObject();

                        // 检查返回码
                        int code = data.has("code") ? data.get("code").getAsInt() : -1;
                        if (code != 200) {
                            logger.debug("API返回错误码: {}", code);
                            continue;
                        }

                        if (data.has("url") && !data.get("url").isJsonNull()) {
                            String audioUrl = data.get("url").getAsString();
                            if (!audioUrl.isEmpty()) {
                                logger.info("获取音频URL成功: {} (长度={})", songId, audioUrl.length());
                                return audioUrl;
                            }
                        }
                        // 检查是否需要VIP
                        if (data.has("freeTrialInfo") && !data.get("freeTrialInfo").isJsonNull()) {
                            logger.warn("歌曲 {} 可能需要VIP", songId);
                        }
                        // 检查是否需要登录
                        if (data.has("needLogin") && data.get("needLogin").getAsBoolean()) {
                            logger.warn("歌曲 {} 需要登录", songId);
                        }
                    }
                } catch (Exception e) {
                    logger.debug("API端点失败: {} - {}", url, e.getMessage());
                }
            }

            logger.warn("所有API端点都无法获取音频URL，歌曲可能需要登录或VIP: {}", songId);
        } catch (Exception e) {
            logger.error("获取音频URL失败: " + e.getMessage(), e);
        }
        return null;
    }

    @Override
    public Lyrics getLyrics(String songId) {
        try {
            String url = BASE_URL + "/api/song/lyric?id=" + songId + "&lv=1&tv=1";
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", BASE_URL);
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

            String response = HttpUtil.get(url, headers);
            JsonObject json = gson.fromJson(response, JsonObject.class);

            String lrcText = "";
            String tlyricText = "";

            if (json.has("lrc") && json.getAsJsonObject("lrc").has("lyric")) {
                lrcText = json.getAsJsonObject("lrc").get("lyric").getAsString();
            }

            if (json.has("tlyric") && json.getAsJsonObject("tlyric").has("lyric")) {
                tlyricText = json.getAsJsonObject("tlyric").get("lyric").getAsString();
            }

            return parseLrc(lrcText, tlyricText);
        } catch (Exception e) {
            logger.error("获取歌词失败: " + e.getMessage(), e);
        }
        return new Lyrics(null, "", false);
    }

    /** LRC 一行：开头可能连着写多个时间戳（副歌重复），后面才是歌词文本 */
    private static final Pattern LRC_LINE_PATTERN =
            Pattern.compile("^((?:\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?\\])+)(.*)$");
    /** 从上面那串时间戳里逐个取出 [分:秒.毫秒] */
    private static final Pattern LRC_TIME_PATTERN =
            Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");

    /**
     * 收集一段 LRC 文本里所有的「时间戳 → 歌词」。
     *
     * 一行上的每个时间戳都要收：形如 [00:12.34][01:20.56]同一句副歌，
     * 旧实现用 find() 只取第一个，副歌第二遍开始整段没有歌词。
     */
    private void collectLrc(String text, Map<Long, String> out) {
        if (text == null || text.isEmpty()) return;
        for (String rawLine : text.split("\n")) {
            Matcher lineMatcher = LRC_LINE_PATTERN.matcher(rawLine.trim());
            if (!lineMatcher.matches()) continue;

            String content = lineMatcher.group(2).trim();
            if (content.isEmpty()) continue; // 纯时间戳行（空行/元信息），不产生歌词

            Matcher timeMatcher = LRC_TIME_PATTERN.matcher(lineMatcher.group(1));
            while (timeMatcher.find()) {
                out.put(lrcTimestamp(timeMatcher.group(1), timeMatcher.group(2), timeMatcher.group(3)), content);
            }
        }
    }

    /**
     * 时间戳转毫秒。毫秒位可能是 1/2/3 位：2 位按 10ms 计，1 位按 100ms 计。
     */
    private static long lrcTimestamp(String minutes, String seconds, String fraction) {
        long millis = 0;
        if (fraction != null && !fraction.isEmpty()) {
            millis = Long.parseLong(fraction);
            if (fraction.length() == 2) millis *= 10;
            else if (fraction.length() == 1) millis *= 100;
        }
        return Long.parseLong(minutes) * 60_000L + Long.parseLong(seconds) * 1000L + millis;
    }

    /**
     * 解析LRC歌词
     */
    private Lyrics parseLrc(String lrcText, String tlyricText) {
        List<LyricsLine> lines = new ArrayList<>();

        // 解析原文歌词
        Map<Long, String> lrcMap = new HashMap<>();
        collectLrc(lrcText, lrcMap);

        // 解析翻译歌词
        Map<Long, String> tlyricMap = new HashMap<>();
        collectLrc(tlyricText, tlyricMap);
        boolean hasTranslation = !tlyricMap.isEmpty();

        // 合并歌词
        for (Map.Entry<Long, String> entry : lrcMap.entrySet()) {
            long timestamp = entry.getKey();
            String text = entry.getValue();
            String translation = tlyricMap.get(timestamp);

            lines.add(new LyricsLine(timestamp, text, translation));
        }

        // 按时间戳排序
        lines.sort((a, b) -> Long.compare(a.getTimestamp(), b.getTimestamp()));

        return new Lyrics(lines, lrcText, hasTranslation);
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
            // 获取二维码key
            String keyUrl = BASE_URL + "/api/login/qrcode/unikey?type=1";
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", BASE_URL);
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

            String keyResponse = HttpUtil.get(keyUrl, headers);
            JsonObject keyJson = gson.fromJson(keyResponse, JsonObject.class);

            if (keyJson.has("unikey")) {
                String unikey = keyJson.get("unikey").getAsString();
                this.qrKey = unikey;
                String qrCodeUrl = "https://music.163.com/login?codekey=" + unikey;

                logger.info("二维码登录已生成, unikey={}", unikey);
                return LoginResult.waitingForQrCode(qrCodeUrl,
                        "请使用网易云音乐APP扫描二维码登录");
            } else {
                logger.warn("获取二维码key失败: {}", keyResponse);
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

                String url = BASE_URL + "/api/login/qrcode/client/login?key=" + qrKey + "&type=1";
                Map<String, String> headers = new HashMap<>();
                headers.put("Referer", BASE_URL);
                headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

                HttpUtil.HttpResponse response = HttpUtil.getWithHeaders(url, headers);
                if (response == null || response.body == null || response.body.isEmpty()) {
                    continue;
                }

                JsonObject json = gson.fromJson(response.body, JsonObject.class);
                int code = json.has("code") ? json.get("code").getAsInt() : -1;

                switch (code) {
                    case 801:
                        logger.debug("等待扫码...");
                        break;
                    case 802:
                        logger.info("已扫码，等待确认...");
                        break;
                    case 803: {
                        // 登录成功。cookie 有两个来源：
                        //  1) 响应体里的 cookie 字段（这个接口习惯把登录 cookie 直接塞在 JSON 里）
                        //  2) Set-Cookie 响应头（可能有多条，必须全部收齐，response.header() 只给最后一条）
                        String rawCookie = json.has("cookie") && !json.get("cookie").isJsonNull()
                                ? json.get("cookie").getAsString() : null;
                        String extractedCookie = extractCookie(rawCookie);
                        if (extractedCookie == null || extractedCookie.isEmpty()) {
                            extractedCookie = extractCookies(response.setCookies);
                        }

                        if (extractedCookie == null || extractedCookie.isEmpty()) {
                            this.qrKey = "";
                            logger.warn("网易云扫码已确认，但响应里没有可用的登录 Cookie（Set-Cookie 数={}）",
                                    response.setCookies.size());
                            return LoginResult.failure("扫码已确认，但未能取得登录 Cookie，请重试");
                        }

                        this.cookie = extractedCookie;
                        this.loggedIn = true;
                        this.qrKey = "";
                        logger.info("网易云二维码登录成功");
                        return LoginResult.success(null, extractedCookie, "网易云登录成功");
                    }
                    case 800:
                        this.qrKey = "";
                        return LoginResult.failure("二维码已过期，请重新登录");
                    default:
                        logger.debug("未知状态码: {}", code);
                        break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.debug("轮询登录状态失败: {}", e.getMessage());
            }
        }

        this.qrKey = "";
        return LoginResult.failure("登录超时，请重试");
    }

    /** 是否网易云登录态所需的 cookie */
    private static boolean isLoginCookie(String name) {
        return name != null && (name.equalsIgnoreCase("MUSIC_U") || name.equalsIgnoreCase("MUSIC_A")
                || name.equalsIgnoreCase("__csrf") || name.equalsIgnoreCase("NMTID"));
    }

    /**
     * 从响应体给的 cookie 串中提取登录 Cookie（形如 "MUSIC_U=xxx; Max-Age=…; Path=/; Domain=…"）
     */
    private String extractCookie(String rawCookie) {
        if (rawCookie == null || rawCookie.isEmpty()) {
            return null;
        }
        StringBuilder cookieBuilder = new StringBuilder();
        for (String part : rawCookie.split(";")) {
            String trimmed = part.trim();
            int eq = trimmed.indexOf('=');
            if (eq > 0 && isLoginCookie(trimmed.substring(0, eq).trim())) {
                if (cookieBuilder.length() > 0) cookieBuilder.append("; ");
                cookieBuilder.append(trimmed);
            }
        }
        return cookieBuilder.length() > 0 ? cookieBuilder.toString() : null;
    }

    /**
     * 从多条 Set-Cookie 头中提取登录 Cookie。
     * 每条头只取开头的 name=value —— 后面的 Path/Expires/Domain 是属性，不是 cookie 本身。
     */
    private String extractCookies(List<String> setCookies) {
        if (setCookies == null || setCookies.isEmpty()) return null;
        StringBuilder cookieBuilder = new StringBuilder();
        for (String header : setCookies) {
            if (header == null || header.isEmpty()) continue;
            int semi = header.indexOf(';');
            String pair = (semi >= 0 ? header.substring(0, semi) : header).trim();
            int eq = pair.indexOf('=');
            if (eq > 0 && isLoginCookie(pair.substring(0, eq).trim())) {
                if (cookieBuilder.length() > 0) cookieBuilder.append("; ");
                cookieBuilder.append(pair);
            }
        }
        return cookieBuilder.length() > 0 ? cookieBuilder.toString() : null;
    }

    @Override
    public LoginResult loginWithCookie(String cookie) {
        this.cookie = cookie;
        this.loggedIn = true;
        return LoginResult.success(null, cookie, "Cookie登录成功");
    }

    @Override
    public boolean isLoggedIn() {
        return loggedIn;
    }

    @Override
    public void refreshSession() {
        // 网易云Cookie通常不需要主动刷新
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
