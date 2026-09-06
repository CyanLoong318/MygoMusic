package com.allmusic.source;

import com.allmusic.model.*;
import com.allmusic.util.HttpUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.Inflater;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 酷狗音乐音源
 */
public class KugouSource implements MusicSource {
    private static final Logger logger = LoggerFactory.getLogger("MygoMusic-Kugou");
    private static final Gson gson = new Gson();

    // Web 签名盐值（酷狗 Web 版 API）
    private static final String WEB_SIGN_SALT = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt";

    // 酷狗 KRC 歌词解密密钥（krc1 头之后按此表逐字节异或，再 zlib 解压）
    private static final int[] KRC_KEY = {
            0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
            0x51, 0x36, 0x31, 0x2d, 0xce, 0xd2, 0x6e, 0x69
    };

    private String cookie = "";
    private boolean loggedIn = false;
    private String qrKey = ""; // 二维码登录的 key
    private String qrMid = ""; // 设备 MID

    @Override
    public String getName() {
        return "kugou";
    }

    @Override
    public String getDisplayName() {
        return "酷狗";
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    /**
     * 获取通用请求头
     */
    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        headers.put("Referer", "https://www.kugou.com/");
        if (!cookie.isEmpty()) {
            headers.put("Cookie", cookie);
        }
        return headers;
    }

    /**
     * 安全解析JSON响应
     */
    private JsonObject parseResponseSafe(String response) {
        if (response == null || response.isEmpty()) return null;
        response = response.trim();
        if (!response.startsWith("{") && !response.startsWith("[")) {
            logger.warn("酷狗API返回非JSON响应: {}", response.length() > 200 ? response.substring(0, 200) + "..." : response);
            return null;
        }
        try {
            return gson.fromJson(response, JsonObject.class);
        } catch (Exception e) {
            logger.error("解析酷狗API响应失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public List<SongInfo> search(String keyword, int limit) {
        List<SongInfo> results = new ArrayList<>();

        // 尝试多个搜索端点
        results = searchV2(keyword, limit);
        if (!results.isEmpty()) return results;

        // 回退到旧版API
        results = searchV1(keyword, limit);
        return results;
    }

    /**
     * V2搜索 - complexsearch端点
     */
    private List<SongInfo> searchV2(String keyword, int limit) {
        List<SongInfo> results = new ArrayList<>();
        try {
            long clienttime = System.currentTimeMillis() / 1000;
            String url = "https://complexsearch.kugou.com/v2/search/song"
                    + "?callback=callback123"
                    + "&srcappid=2919"
                    + "&clientver=1000"
                    + "&clienttime=" + clienttime
                    + "&mid=" + clienttime
                    + "&uuid=" + clienttime
                    + "&dfid=-"
                    + "&keyword=" + keyword
                    + "&page=1"
                    + "&pagesize=" + limit
                    + "&bitrate=0"
                    + "&isfuzzy=0"
                    + "&inputtype=0"
                    + "&platform=WebFilter"
                    + "&userid=0"
                    + "&iscorrection=1"
                    + "&privilege_filter=0"
                    + "&filter=10"
                    + "&token="
                    + "&appid=1014";

            Map<String, String> headers = getHeaders();
            headers.put("Referer", "https://complexsearch.kugou.com/");

            String response = HttpUtil.get(url, headers);

            // 处理JSONP回调
            if (response.startsWith("callback123(")) {
                response = response.substring("callback123(".length());
                if (response.endsWith(");")) {
                    response = response.substring(0, response.length() - 2);
                } else if (response.endsWith(")")) {
                    response = response.substring(0, response.length() - 1);
                }
            }

            JsonObject json = parseResponseSafe(response);
            if (json == null) return results;

            if (json.has("data") && json.getAsJsonObject("data").has("lists")) {
                JsonArray lists = json.getAsJsonObject("data").getAsJsonArray("lists");
                parseSongList(lists, results);
            }
        } catch (Exception e) {
            logger.debug("酷狗V2搜索失败: " + e.getMessage());
        }
        return results;
    }

    /**
     * V1搜索 - 回退端点
     */
    private List<SongInfo> searchV1(String keyword, int limit) {
        List<SongInfo> results = new ArrayList<>();
        try {
            String url = "https://songsearch.kugou.com/song_search_v2"
                    + "?keyword=" + keyword
                    + "&page=1"
                    + "&pagesize=" + limit
                    + "&userid=0"
                    + "&clientver=20000"
                    + "&platform=WebFilter"
                    + "&filter=2";

            Map<String, String> headers = getHeaders();

            String response = HttpUtil.get(url, headers);
            JsonObject json = parseResponseSafe(response);
            if (json == null) return results;

            if (json.has("data") && json.getAsJsonObject("data").has("lists")) {
                JsonArray lists = json.getAsJsonObject("data").getAsJsonArray("lists");
                parseSongList(lists, results);
            }
        } catch (Exception e) {
            logger.debug("酷狗V1搜索失败: " + e.getMessage());
        }
        return results;
    }

    /**
     * 解析歌曲列表
     */
    private void parseSongList(JsonArray lists, List<SongInfo> results) {
        for (JsonElement element : lists) {
            JsonObject song = element.getAsJsonObject();

            String songId = song.has("FileHash") ? song.get("FileHash").getAsString()
                    : (song.has("hash") ? song.get("hash").getAsString() : "");
            String title = song.has("SongName") ? song.get("SongName").getAsString()
                    : (song.has("songname") ? song.get("songname").getAsString() : "");

            // 获取歌手信息
            String artist = "";
            if (song.has("SingerName")) {
                artist = song.get("SingerName").getAsString();
            } else if (song.has("singername")) {
                artist = song.get("singername").getAsString();
            } else if (song.has("singers")) {
                JsonArray singers = song.getAsJsonArray("singers");
                StringBuilder artistBuilder = new StringBuilder();
                for (int i = 0; i < singers.size(); i++) {
                    if (i > 0) artistBuilder.append(", ");
                    JsonObject singer = singers.get(i).getAsJsonObject();
                    artistBuilder.append(singer.has("name") ? singer.get("name").getAsString() : "");
                }
                artist = artistBuilder.toString();
            }

            // 获取专辑信息
            String album = song.has("AlbumName") ? song.get("AlbumName").getAsString()
                    : (song.has("album_name") ? song.get("album_name").getAsString() : "");

            // 获取时长
            long duration = 0;
            if (song.has("Duration")) {
                duration = song.get("Duration").getAsLong() * 1000;
            } else if (song.has("duration")) {
                duration = song.get("duration").getAsLong() * 1000;
            }

            // 获取封面
            String coverUrl = "";
            if (song.has("Image")) {
                coverUrl = song.get("Image").getAsString();
            } else if (song.has("image")) {
                coverUrl = song.get("image").getAsString();
            }

            if (!songId.isEmpty() && !title.isEmpty()) {
                results.add(new SongInfo(songId, title, artist, album, duration, "kugou", coverUrl, ""));
            }
        }
    }

    @Override
    public SongDetail getDetail(String songId) {
        // 使用移动端API获取歌曲详情（旧版API已废弃）
        return getDetailV2(songId);
    }

    /**
     * V2获取歌曲详情 - 使用移动端API
     */
    private SongDetail getDetailV2(String hash) {
        try {
            // 使用移动端API（无需签名验证）
            String url = "https://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash=" + hash;

            Map<String, String> headers = getHeaders();
            // 使用移动端User-Agent
            headers.put("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 13_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0 Mobile/15E148 Safari/604.1");

            logger.debug("尝试获取歌曲详情: {}", url);
            String response = HttpUtil.get(url, headers);
            logger.debug("API响应: {}", response != null ? response.substring(0, Math.min(500, response.length())) : "null");

            JsonObject json = parseResponseSafe(response);
            if (json == null) {
                logger.warn("移动API解析响应失败");
                return null;
            }

            // 检查错误码
            int errcode = json.has("errcode") ? json.get("errcode").getAsInt() : -1;
            if (errcode != 0) {
                logger.warn("移动API返回错误 errcode={}", errcode);
                return null;
            }

            // 移动API直接返回歌曲信息，没有嵌套的data字段
            String title = json.has("songName") ? json.get("songName").getAsString() : "";
            String artist = json.has("author_name") ? json.get("author_name").getAsString() : "";
            long duration = json.has("timeLength") ? json.get("timeLength").getAsLong() * 1000 : 0;
            String coverUrl = json.has("imgUrl") ? json.get("imgUrl").getAsString() : "";

            // 获取音频URL
            String audioUrl = json.has("url") ? json.get("url").getAsString() : "";

            // 如果audioUrl为空，尝试backup_url（可能是数组或对象）
            if (audioUrl.isEmpty() && json.has("backup_url")) {
                JsonElement backupElement = json.get("backup_url");
                if (backupElement.isJsonArray()) {
                    JsonArray backupUrls = backupElement.getAsJsonArray();
                    if (backupUrls.size() > 0) {
                        JsonElement first = backupUrls.get(0);
                        if (first.isJsonPrimitive()) {
                            audioUrl = first.getAsString();
                        } else if (first.isJsonObject()) {
                            audioUrl = first.getAsJsonObject().has("url") ? first.getAsJsonObject().get("url").getAsString() : "";
                        }
                        if (!audioUrl.isEmpty()) {
                            logger.debug("使用备用URL: {}", audioUrl);
                        }
                    }
                } else if (backupElement.isJsonObject()) {
                    JsonObject backupObj = backupElement.getAsJsonObject();
                    audioUrl = backupObj.has("url") ? backupObj.get("url").getAsString() : "";
                } else if (backupElement.isJsonPrimitive()) {
                    audioUrl = backupElement.getAsString();
                }
            }

            // 尝试其他URL字段
            if (audioUrl.isEmpty()) {
                String[] urlFields = {"squrl", "128url", "320url"};
                for (String field : urlFields) {
                    if (json.has(field)) {
                        audioUrl = json.get(field).getAsString();
                        if (!audioUrl.isEmpty()) {
                            logger.debug("使用{} URL: {}", field, audioUrl);
                            break;
                        }
                    }
                }
            }

            // 移动API拿不到可播放URL（多为VIP歌曲）：改用酷狗官方签名接口(play/songinfo)拿VIP直链
            if (audioUrl.isEmpty()) {
                // 签名接口可直接解锁VIP，优先尝试；失败再退回 play/getdata
                String albumAudioId = firstField(json, "album_audio_id", "audio_id", "albumAudioId");
                String albumId = firstField(json, "album_id", "req_albumid", "albumid");
                WebResult web = tryGetSignedPlayUrl(hash, albumAudioId, albumId);
                if (!web.ok()) {
                    logger.info("酷狗签名接口未拿到播放URL，回退 play/getdata 再试: {}, hash={}", title, hash);
                    web = tryGetWebPlayUrl(hash, json);
                }
                if (web.ok()) {
                    audioUrl = web.playUrl;
                    // 移动API对VIP歌曲常返回 timeLength=0，用Web接口的完整时长补上，避免被当作已播完
                    if (duration <= 0 && web.durationMs > 0) {
                        duration = web.durationMs;
                    }
                    logger.info("酷狗VIP歌曲通过签名接口(play/songinfo)获取到播放URL: {}", title);
                }
            }

            logger.debug("获取歌曲详情结果: title={}, artist={}, audioUrl={}", title, artist, audioUrl);

            // 获取歌词
            Lyrics lyrics = getLyrics(hash);

            // 检查是否VIP
            boolean isVip = json.has("pay_type") && json.get("pay_type").getAsInt() != 0;

            if (!title.isEmpty()) {
                logger.info("成功获取歌曲详情: {} - {}", title, artist);
                return new SongDetail(hash, title, artist, "", duration, "kugou", coverUrl, "",
                        audioUrl, lyrics, isVip, null);
            } else {
                logger.warn("移动API获取歌曲详情: title为空, hash={}", hash);
            }
        } catch (Exception e) {
            logger.error("V2获取歌曲详情失败: " + e.getMessage(), e);
        }
        return null;
    }

    /**
     * 使用酷狗官方签名接口 (play/songinfo) 获取可播放URL —— 可解锁VIP，不依赖 play/getdata。
     * 签名密钥与官网/客户端一致，配合已登录Cookie的 userid(KugooID) 和 token(t) 可直接拿到VIP直链。
     */
    private WebResult tryGetSignedPlayUrl(String hash, String albumAudioId, String albumId) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("appid", "1014");
            params.put("clientver", "20000");
            params.put("clienttime", String.valueOf(System.currentTimeMillis()));
            params.put("srcappid", "2919");

            // 设备ID：优先 mid，回退 kg_mid
            String mid = getCookieValue("mid");
            if (mid.isEmpty()) mid = getCookieValue("kg_mid");
            if (mid.isEmpty()) mid = String.valueOf(System.currentTimeMillis());
            params.put("mid", mid);
            params.put("uuid", mid);

            String dfid = getCookieValue("dfid");
            if (dfid.isEmpty()) dfid = getCookieValue("kg_dfid");
            params.put("dfid", dfid.isEmpty() ? "-" : dfid);

            params.put("platid", "4");
            params.put("userid", resolveUserid());
            params.put("token", resolveToken());
            params.put("hash", hash);
            if (albumId != null && !albumId.isEmpty()) params.put("album_id", albumId);
            if (albumAudioId != null && !albumAudioId.isEmpty()) params.put("album_audio_id", albumAudioId);

            String query = buildSignedQuery(params);
            String url = "https://wwwapi.kugou.com/play/songinfo?" + query;

            Map<String, String> headers = getHeaders();
            headers.put("Referer", "https://www.kugou.com/");

            logger.debug("请求酷狗签名播放接口: {}", url);
            String response = HttpUtil.get(url, headers);
            JsonObject json = parseResponseSafe(response);
            if (json == null) return WebResult.EMPTY;

            int errCode = 0;
            if (json.has("err_code") && json.get("err_code").isJsonPrimitive()) {
                errCode = json.get("err_code").getAsInt();
            }
            if (errCode != 0) {
                logger.warn("酷狗签名接口(play/songinfo)返回 err_code={} (hash={})", errCode, hash);
                return WebResult.EMPTY;
            }
            if (!json.has("data") || !json.get("data").isJsonObject()) return WebResult.EMPTY;

            JsonObject data = json.getAsJsonObject("data");
            String playUrl = data.has("play_url") && !data.get("play_url").isJsonNull()
                    ? data.get("play_url").getAsString() : "";
            if (playUrl.isEmpty() && data.has("play_backup_url") && !data.get("play_backup_url").isJsonNull()) {
                JsonElement backup = data.get("play_backup_url");
                if (backup.isJsonArray() && backup.getAsJsonArray().size() > 0) {
                    JsonElement first = backup.getAsJsonArray().get(0);
                    playUrl = first.isJsonPrimitive() ? first.getAsString()
                            : (first.isJsonObject() && first.getAsJsonObject().has("url")
                                    ? first.getAsJsonObject().get("url").getAsString() : "");
                } else if (backup.isJsonPrimitive()) {
                    playUrl = backup.getAsString();
                }
            }
            if (playUrl.isEmpty()) {
                logger.warn("酷狗签名接口(play/songinfo)未返回播放URL: hash={}", hash);
                return WebResult.EMPTY;
            }

            long durationMs = 0;
            if (data.has("timelength") && !data.get("timelength").isJsonNull()) {
                try { durationMs = data.get("timelength").getAsLong(); } catch (Exception ignore) {}
            }
            logger.info("酷狗签名接口获取VIP播放URL成功: hash={}, url长度={}", hash, playUrl.length());
            return new WebResult(playUrl, durationMs);
        } catch (Exception e) {
            logger.error("获取酷狗签名播放URL失败: " + e.getMessage(), e);
            return WebResult.EMPTY;
        }
    }

    /**
     * 构造 play/songinfo 的带签名查询串：key按字母排序，signature=SALT+拼串+SALT 的 md5。
     */
    private String buildSignedQuery(Map<String, String> params) throws Exception {
        TreeMap<String, String> sorted = new TreeMap<>(params);
        // 计算签名（不含 signature 字段）
        String signature = generateWebSignature(params);
        sorted.put("signature", signature);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (sb.length() > 0) sb.append("&");
            sb.append(URLEncoder.encode(e.getKey(), "UTF-8"))
              .append("=")
              .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), "UTF-8"));
        }
        return sb.toString();
    }

    /**
     * 使用酷狗 Web 播放接口 (play/getdata) 获取可播放URL（用于VIP/需登录歌曲）。
     * 依赖已通过 Cookie 登录的账号。
     */
    private WebResult tryGetWebPlayUrl(String hash, JsonObject mobileJson) {
        try {
            // 移动接口可能使用不同字段名，多取几个备选
            String albumAudioId = firstField(mobileJson, "album_audio_id", "audio_id", "albumAudioId");
            String albumId = firstField(mobileJson, "album_id", "req_albumid", "albumid");
            long now = System.currentTimeMillis();
            // 酷狗Web接口要求携带与请求一致的匿名设备ID（mid/kg_mid 用同一32位hex设备ID）
            String mid = UUID.randomUUID().toString().replace("-", "").toUpperCase().substring(0, 32);
            String dfid = UUID.randomUUID().toString().replace("-", "").toUpperCase().substring(0, 32);

            // 从已登录 Cookie 中提取 token / userid，一并传给播放接口（VIP 校验用）
            String tokenValue = resolveToken();
            String useridValue = resolveUserid();

            String url = "https://wwwapi.kugou.com/yy/index.php"
                    + "?r=play/getdata"
                    + "&callback="
                    + "&hash=" + hash
                    + "&mid=" + mid
                    + "&dfid=" + dfid
                    + "&platid=4"
                    + "&appid=1014"
                    + "&album_audio_id=" + albumAudioId
                    + "&album_id=" + albumId
                    + (!tokenValue.isEmpty() ? "&token=" + tokenValue : "")
                    + (!useridValue.isEmpty() ? "&userid=" + useridValue : "")
                    + "&_=" + now;

            Map<String, String> headers = getHeaders();
            headers.put("Referer", "https://www.kugou.com/yy/index.php");
            // 关键：必须带 kg_mid/kg_dfid 匿名设备Cookie，否则酷狗报30020/20010。已登录Cookie追加在后
            String anonCookie = "kg_mid=" + mid + "; kg_dfid=" + dfid + "; kg_dfid_collect=" + dfid;
            if (cookie != null && !cookie.isEmpty()) {
                anonCookie += "; " + cookie;
            }
            headers.put("Cookie", anonCookie);

            String response = HttpUtil.get(url, headers);
            JsonObject json = parseResponseSafe(response);
            if (json == null) return WebResult.EMPTY;

            // 记录任何非 0 的 err_code，便于诊断（即使带 data 也会出现，如 30020 未登录）
            int errCode = 0;
            if (json.has("err_code") && json.get("err_code").isJsonPrimitive()) {
                errCode = json.get("err_code").getAsInt();
            }
            if (errCode != 0) {
                logger.warn("酷狗Web接口返回 err_code={} (hash={})", errCode, hash);
                if (errCode == 30020) {
                    logger.warn("30020：酷狗Web接口提示未登录/风控。请用酷狗官网(www.kugou.com)登录的VIP账号Cookie执行 /mm login kugou <cookie>，Cookie需包含 token、userid");
                } else if (errCode == 20010) {
                    logger.warn("20010：酷狗Web接口缺少有效播放参数/登录态。已带匿名设备Cookie仍报此码，多为登录Cookie无效或该账号无此VIP歌曲播放权限");
                }
            }
            if (!json.has("data")) return WebResult.EMPTY;

            // 无播放权限时 data 可能为对象也可能为空数组，统一安全处理，避免类型转换崩溃
            JsonElement dataEl = json.get("data");
            if (dataEl == null || dataEl.isJsonNull()) return WebResult.EMPTY;
            if (!dataEl.isJsonObject()) {
                logger.warn("酷狗Web接口 data 非对象(可能是空数组，账号无该VIP歌曲权限)，hash={}", hash);
                return WebResult.EMPTY;
            }
            JsonObject data = dataEl.getAsJsonObject();

            String playUrl = data.has("play_url") && !data.get("play_url").isJsonNull()
                    ? data.get("play_url").getAsString() : "";
            if (playUrl.isEmpty() && data.has("play_backup_url") && !data.get("play_backup_url").isJsonNull()) {
                JsonElement backup = data.get("play_backup_url");
                if (backup.isJsonArray() && backup.getAsJsonArray().size() > 0) {
                    JsonElement first = backup.getAsJsonArray().get(0);
                    playUrl = first.isJsonPrimitive() ? first.getAsString()
                            : (first.isJsonObject() && first.getAsJsonObject().has("url")
                                    ? first.getAsJsonObject().get("url").getAsString() : "");
                } else if (backup.isJsonPrimitive()) {
                    playUrl = backup.getAsString();
                }
            }

            // Web 接口会返回完整时长（VIP 歌曲移动端 timeLength 常为 0）
            long durationMs = 0;
            if (data.has("timelength") && !data.get("timelength").isJsonNull()) {
                try {
                    durationMs = data.get("timelength").getAsLong();
                } catch (Exception ignore) {}
            }

            if (playUrl.isEmpty()) {
                logger.warn("酷狗Web接口未返回播放URL(可能未登录VIP账号或Cookie无效)，hash={}", hash);
            }
            return new WebResult(playUrl, durationMs);
        } catch (Exception e) {
            logger.error("获取酷狗Web播放URL失败: " + e.getMessage(), e);
            return WebResult.EMPTY;
        }
    }

    /** Web 播放接口返回结果 */
    private static class WebResult {
        static final WebResult EMPTY = new WebResult("", 0L);

        final String playUrl;
        final long durationMs;

        WebResult(String playUrl, long durationMs) {
            this.playUrl = playUrl;
            this.durationMs = durationMs;
        }

        boolean ok() {
            return playUrl != null && !playUrl.isEmpty();
        }
    }

    /**
     * V1获取歌曲详情 (回退)
     */
    private SongDetail getDetailV1(String hash) {
        try {
            long clienttime = System.currentTimeMillis();
            String url = "https://wwwapi.kugou.com/yy/index.php"
                    + "?r=play/getdata"
                    + "&hash=" + hash
                    + "&mid=" + clienttime
                    + "&appid=1014"
                    + "&token=";
            Map<String, String> headers = getHeaders();

            logger.debug("V1获取歌曲详情请求: {}", url);
            String response = HttpUtil.get(url, headers);
            logger.debug("V1获取歌曲详情响应: {}", response != null ? response.substring(0, Math.min(500, response.length())) : "null");

            JsonObject json = parseResponseSafe(response);
            if (json == null) {
                logger.warn("V1获取歌曲详情: 解析响应失败");
                return null;
            }

            if (json.has("data")) {
                JsonObject data = json.getAsJsonObject("data");

                String title = data.has("song_name") ? data.get("song_name").getAsString() : "";
                String artist = data.has("author_name") ? data.get("author_name").getAsString() : "";
                String album = data.has("album_name") ? data.get("album_name").getAsString() : "";
                long duration = data.has("timelength") ? data.get("timelength").getAsLong() : 0;
                String coverUrl = data.has("img") ? data.get("img").getAsString() : "";
                String audioUrl = data.has("play_url") ? data.get("play_url").getAsString() : "";

                logger.debug("V1获取歌曲详情结果: title={}, artist={}, audioUrl={}", title, artist, audioUrl);

                // 如果audioUrl为空，尝试其他字段
                if (audioUrl.isEmpty()) {
                    audioUrl = data.has("play_backup_url") ? data.get("play_backup_url").getAsString() : "";
                }

                // 获取歌词
                Lyrics lyrics = getLyrics(hash);

                // 检查是否VIP
                boolean isVip = data.has("is_free_part") && data.get("is_free_part").getAsInt() != 0;

                return new SongDetail(hash, title, artist, album, duration, "kugou", coverUrl, "",
                        audioUrl, lyrics, isVip, null);
            } else {
                logger.warn("V1获取歌曲详情: 响应中没有data字段");
            }
        } catch (Exception e) {
            logger.error("V1获取歌曲详情失败: " + e.getMessage(), e);
        }
        return null;
    }

    @Override
    public String getAudioUrl(String songId) {
        try {
            SongDetail detail = getDetail(songId);
            if (detail != null && detail.getAudioUrl() != null && !detail.getAudioUrl().isEmpty()) {
                return detail.getAudioUrl();
            }
        } catch (Exception e) {
            logger.error("获取音频URL失败: " + e.getMessage(), e);
        }
        return null;
    }

    @Override
    public Lyrics getLyrics(String songId) {
        try {
            String url = "https://krcs.kugou.com/search"
                    + "?ver=1"
                    + "&man=yes"
                    + "&client=mobi"
                    + "&keyword=&duration=&hash=" + songId
                    + "&album_audio_id="
                    + "&lrc_t=0";

            Map<String, String> headers = getHeaders();

            String response = HttpUtil.get(url, headers);
            JsonObject json = parseResponseSafe(response);
            if (json == null || !json.has("candidates")) return new Lyrics(null, "", false);

            JsonArray candidates = json.getAsJsonArray("candidates");
            if (candidates.size() == 0) return new Lyrics(null, "", false);

            JsonObject first = candidates.get(0).getAsJsonObject();
            String id = first.has("id") ? first.get("id").getAsString() : "";
            String accesskey = first.has("accesskey") ? first.get("accesskey").getAsString() : "";

            // 下载歌词正文：优先 KRC（内含逐行译文），失败再回退 LRC
            if (!id.isEmpty() && !accesskey.isEmpty()) {
                Lyrics parsed = downloadLyrics(id, accesskey, headers);
                if (parsed != null && !parsed.isEmpty()) {
                    logger.info("获取酷狗歌词成功: {} 行{}",
                            parsed.getLines() == null ? 0 : parsed.getLines().size(),
                            parsed.hasTranslation() ? " (含译文)" : "");
                    return parsed;
                }
                logger.warn("酷狗歌词下载失败: id={}, 将返回空歌词", id);
            } else {
                // 个别候选直接带 content
                String direct = first.has("content") ? first.get("content").getAsString() : "";
                if (!direct.isEmpty()) {
                    Lyrics parsed = parseLrc(direct);
                    if (!parsed.isEmpty()) return parsed;
                }
            }
        } catch (Exception e) {
            logger.error("获取歌词失败: " + e.getMessage(), e);
        }
        return new Lyrics(null, "", false);
    }

    /**
     * 先尝试 fmt=krc（带逐行译文），失败再尝试 fmt=lrc
     */
    private Lyrics downloadLyrics(String id, String accesskey, Map<String, String> headers) {
        // 1) KRC：需解密
        try {
            String krcUrl = "https://lyrics.kugou.com/download"
                    + "?ver=1&client=pc"
                    + "&id=" + id
                    + "&accesskey=" + accesskey
                    + "&fmt=krc"
                    + "&charset=utf8";
            String krcResponse = HttpUtil.get(krcUrl, headers);
            JsonObject krcJson = parseResponseSafe(krcResponse);
            if (krcJson != null && krcJson.has("content")) {
                String krcText = decodeKrcContent(krcJson.get("content").getAsString());
                if (krcText != null && !krcText.isEmpty()) {
                    Lyrics parsed = parseKrc(krcText);
                    if (parsed != null && !parsed.isEmpty()) {
                        return parsed;
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("酷狗KRC歌词下载/解析失败: {}", e.getMessage());
        }

        // 2) LRC 回退
        try {
            String lrcUrl = "https://lyrics.kugou.com/download"
                    + "?ver=1&client=pc"
                    + "&id=" + id
                    + "&accesskey=" + accesskey
                    + "&fmt=lrc"
                    + "&charset=utf8";
            String lrcResponse = HttpUtil.get(lrcUrl, headers);
            JsonObject lrcJson = parseResponseSafe(lrcResponse);
            if (lrcJson != null && lrcJson.has("content")) {
                String lrcText = decodeBase64(lrcJson.get("content").getAsString());
                if (lrcText != null && !lrcText.isEmpty()) {
                    return parseLrc(lrcText);
                }
            }
        } catch (Exception e) {
            logger.debug("酷狗LRC歌词下载失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 解码并解密 KRC 原始文本（krc1 头 + 异或 + zlib 解压）
     */
    private String decodeKrcContent(String base64Content) {
        try {
            byte[] raw = Base64.getDecoder().decode(base64Content.replaceAll("\\s", ""));
            int off = 0;
            if (raw.length >= 4 && raw[0] == 'k' && raw[1] == 'r' && raw[2] == 'c' && raw[3] == '1') {
                off = 4;
            }
            byte[] enc = new byte[raw.length - off];
            System.arraycopy(raw, off, enc, 0, enc.length);
            for (int i = 0; i < enc.length; i++) {
                enc[i] ^= (byte) KRC_KEY[i % KRC_KEY.length];
            }
            return inflateToString(enc);
        } catch (Exception e) {
            logger.warn("酷狗KRC解密失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * zlib 解压为 UTF-8 文本
     */
    private String inflateToString(byte[] data) {
        Inflater inflater = new Inflater();
        inflater.setInput(data);
        ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(64, data.length));
        byte[] buf = new byte[4096];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n <= 0) break;
                baos.write(buf, 0, n);
            }
        } catch (Exception e) {
            logger.warn("酷狗KRC解压失败: {}", e.getMessage());
            return null;
        } finally {
            inflater.end();
        }
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * base64 解码
     */
    private String decodeBase64(String content) {
        if (content == null) return null;
        try {
            return new String(Base64.getDecoder().decode(content.replaceAll("\\s", "")), StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.warn("酷狗歌词base64解码失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 取 JSON 对象中第一个存在的字符串字段值（可给多个备选字段名）
     */
    private String firstField(JsonObject obj, String... keys) {
        if (obj == null) return "";
        for (String k : keys) {
            if (obj.has(k) && !obj.get(k).isJsonNull()) {
                try {
                    return obj.get(k).getAsString();
                } catch (Exception ignore) {}
            }
        }
        return "";
    }

    /**
     * 从 Cookie 字符串中取指定项的值
     */
    private String getCookieValue(String name) {
        if (cookie == null || cookie.isEmpty()) return "";
        for (String part : cookie.split(";")) {
            String pair = part.trim();
            int idx = pair.indexOf('=');
            if (idx > 0 && pair.substring(0, idx).trim().equals(name)) {
                return pair.substring(idx + 1).trim();
            }
        }
        return "";
    }

    /**
     * 解析酷狗用户ID：新版Cookie用 KugooID/UserName，旧版用 userid/openid
     */
    private String resolveUserid() {
        String v = getCookieValue("userid");
        if (v.isEmpty()) v = getCookieValue("KugooID");
        if (v.isEmpty()) v = getCookieValue("UserName");
        if (v.isEmpty()) v = getCookieValue("openid");
        return v;
    }

    /**
     * 解析酷狗登录令牌：新版Cookie用 t(加盐登录态)，旧版用 token
     */
    private String resolveToken() {
        String v = getCookieValue("token");
        if (v.isEmpty()) v = getCookieValue("t");
        return v;
    }

    /**
     * 解析LRC歌词
     */
    private Lyrics parseLrc(String lrcText) {
        List<LyricsLine> lines = new ArrayList<>();
        Pattern pattern = Pattern.compile("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\](.*)");

        for (String line : lrcText.split("\n")) {
            Matcher matcher = pattern.matcher(line.trim());
            if (matcher.find()) {
                long minutes = Long.parseLong(matcher.group(1));
                long seconds = Long.parseLong(matcher.group(2));
                long millis = Long.parseLong(matcher.group(3));
                if (matcher.group(3).length() == 2) millis *= 10;

                long timestamp = minutes * 60 * 1000 + seconds * 1000 + millis;
                String text = matcher.group(4).trim();

                if (!text.isEmpty()) {
                    lines.add(new LyricsLine(timestamp, text, null));
                }
            }
        }

        lines.sort((a, b) -> Long.compare(a.getTimestamp(), b.getTimestamp()));
        return new Lyrics(lines, lrcText, false);
    }

    /**
     * 解析 KRC 文本（含 [language:...] 逐行译文）。
     * 每行格式: [startMs,durMs]<offset,duration,accent>文本...  按行序号与译文数组对齐。
     */
    private Lyrics parseKrc(String krcText) {
        if (krcText == null || krcText.isEmpty()) return null;

        // 收集所有语言的逐行译文（transLangs: 每个语言一个按行索引的译文数组）
        List<List<String>> transLangs = new ArrayList<>();
        Pattern langPattern = Pattern.compile("\\[language:([^\\]]*)\\]");
        Matcher langMatcher = langPattern.matcher(krcText);
        while (langMatcher.find()) {
            try {
                String decoded = decodeBase64(langMatcher.group(1));
                if (decoded == null || decoded.isEmpty()) continue;
                JsonObject langJson = gson.fromJson(decoded, JsonObject.class);
                if (langJson == null || !langJson.has("content")) continue;
                JsonArray contents = langJson.getAsJsonArray("content");
                for (JsonElement ce : contents) {
                    if (!ce.isJsonObject()) continue;
                    JsonObject co = ce.getAsJsonObject();
                    if (!co.has("lyricContent")) continue;
                    JsonArray lc = co.getAsJsonArray("lyricContent");
                    List<String> arr = new ArrayList<>(lc.size());
                    for (JsonElement e : lc) {
                        StringBuilder sb = new StringBuilder();
                        if (e.isJsonArray()) {
                            for (JsonElement x : e.getAsJsonArray()) {
                                if (x.isJsonPrimitive()) sb.append(x.getAsString());
                            }
                        } else if (e.isJsonPrimitive()) {
                            sb.append(e.getAsString());
                        }
                        arr.add(sb.toString().trim());
                    }
                    transLangs.add(arr);
                }
            } catch (Exception ignore) {
                // 单个语言块解析失败不影响整体
            }
        }

        Pattern segPattern = Pattern.compile("\\[(\\d+),(\\d+)\\](.*)");
        List<LyricsLine> lines = new ArrayList<>();
        boolean hasTranslation = false;
        int ordinal = 0; // 段序号，与译文数组对齐（含开头无唱的元数据段）

        for (String rawLine : krcText.split("\n")) {
            Matcher m = segPattern.matcher(rawLine.trim());
            if (!m.find()) continue;

            long startMs;
            try {
                startMs = Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                ordinal++;
                continue;
            }
            String content = m.group(3);
            String text = reconstructKrcText(content);
            String translation = pickKrcTranslation(ordinal, text, transLangs);
            ordinal++;

            if (text.isEmpty()) continue;
            if (translation != null && !translation.isEmpty()) {
                hasTranslation = true;
            }
            lines.add(new LyricsLine(startMs, text, translation));
        }

        if (lines.isEmpty()) return null;
        lines.sort((a, b) -> Long.compare(a.getTimestamp(), b.getTimestamp()));
        return new Lyrics(lines, krcText, hasTranslation);
    }

    /**
     * 从 KRC 段内容重建纯文本（拼接 <..> 标签后的字符）
     */
    private String reconstructKrcText(String content) {
        if (content == null || content.isEmpty()) return "";
        if (!content.contains("<")) {
            return content.trim();
        }
        Pattern tkPattern = Pattern.compile("<[^>]*>([^<]*)");
        Matcher m = tkPattern.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            sb.append(m.group(1));
        }
        return sb.toString().trim();
    }

    /**
     * 选择第 ordinal 段的译文：优先中文且不同于原词；否则取第一个非空且不同于原词的译文
     */
    private String pickKrcTranslation(int ordinal, String text, List<List<String>> transLangs) {
        String fallback = null;
        for (List<String> lang : transLangs) {
            if (ordinal >= lang.size()) continue;
            String t = lang.get(ordinal);
            if (t == null || t.isEmpty()) continue;
            if (t.equals(text)) continue; // 译文与原词相同（如原词就是中文）
            if (fallback == null) fallback = t;
            if (containsChinese(t)) return t;
        }
        return fallback;
    }

    /**
     * 是否含中文字符
     */
    private boolean containsChinese(String text) {
        for (char c : text.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
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
            // 生成设备 MID
            this.qrMid = String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits()));

            // 构建 key 生成请求参数
            Map<String, String> params = new HashMap<>();
            params.put("appid", "1014");
            params.put("type", "1");
            params.put("plat", "4");
            params.put("qrcode_txt", "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=1005&");
            params.put("srcappid", "2919");
            params.put("clientver", "20489");
            params.put("clienttime", String.valueOf(System.currentTimeMillis() / 1000));
            params.put("mid", qrMid);
            params.put("uuid", "-");
            params.put("dfid", "-");

            // 计算 Web 签名
            String signature = generateWebSignature(params);
            params.put("signature", signature);

            // 构建 URL
            StringBuilder urlBuilder = new StringBuilder("https://login-user.kugou.com/v2/qrcode?");
            boolean first = true;
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (!first) urlBuilder.append("&");
                urlBuilder.append(entry.getKey()).append("=").append(entry.getValue());
                first = false;
            }

            Map<String, String> headers = getHeaders();
            headers.put("Referer", "https://h5.kugou.com/");

            String response = HttpUtil.get(urlBuilder.toString(), headers);
            JsonObject json = parseResponseSafe(response);
            if (json == null) {
                logger.warn("酷狗二维码生成失败: 解析响应失败");
                return LoginResult.failure("获取二维码失败");
            }

            if (json.has("data")) {
                JsonObject data = json.getAsJsonObject("data");
                String qrcode = data.has("qrcode") ? data.get("qrcode").getAsString() : "";

                if (!qrcode.isEmpty()) {
                    this.qrKey = qrcode;
                    String qrCodeUrl = "https://h5.kugou.com/apps/loginQRCode/html/index.html?qrcode=" + qrcode;
                    logger.info("酷狗二维码登录已生成, key={}", qrcode);
                    return LoginResult.waitingForQrCode(qrCodeUrl, "请使用酷狗音乐APP扫描二维码登录");
                }
            }

            logger.warn("酷狗二维码生成失败: {}", response != null ? response.substring(0, Math.min(300, response.length())) : "null");
            return LoginResult.failure("获取二维码失败");
        } catch (Exception e) {
            logger.error("获取二维码失败: " + e.getMessage(), e);
            return LoginResult.failure("获取二维码失败: " + e.getMessage());
        }
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

                // 构建请求参数
                Map<String, String> params = new HashMap<>();
                params.put("plat", "4");
                params.put("appid", "1005");
                params.put("srcappid", "2919");
                params.put("qrcode", qrKey);
                params.put("clientver", "20489");
                params.put("clienttime", String.valueOf(System.currentTimeMillis() / 1000));
                params.put("mid", qrMid);
                params.put("uuid", "-");
                params.put("dfid", "-");

                // 计算 Web 签名
                String signature = generateWebSignature(params);
                params.put("signature", signature);

                // 构建 URL
                StringBuilder urlBuilder = new StringBuilder("https://login-user.kugou.com/v2/get_userinfo_qrcode?");
                boolean first = true;
                for (Map.Entry<String, String> entry : params.entrySet()) {
                    if (!first) urlBuilder.append("&");
                    urlBuilder.append(entry.getKey()).append("=").append(entry.getValue());
                    first = false;
                }

                Map<String, String> headers = getHeaders();
                headers.put("Referer", "https://h5.kugou.com/");

                String response = HttpUtil.get(urlBuilder.toString(), headers);
                JsonObject json = parseResponseSafe(response);
                if (json == null) continue;

                int errorCode = json.has("error_code") ? json.get("error_code").getAsInt() : -1;
                if (errorCode != 0) {
                    logger.debug("酷狗轮询返回错误: error_code={}", errorCode);
                    continue;
                }

                if (!json.has("data")) continue;
                JsonObject data = json.getAsJsonObject("data");
                int status = data.has("status") ? data.get("status").getAsInt() : -1;

                switch (status) {
                    case 0:
                        // 二维码过期或无效
                        this.qrKey = "";
                        return LoginResult.failure("二维码已失效，请重新登录");
                    case 1:
                        logger.debug("酷狗二维码等待扫码...");
                        break;
                    case 2:
                        logger.info("酷狗二维码已扫码，等待确认...");
                        break;
                    case 4: {
                        // 授权登录成功，返回 token 和 userid
                        String token = data.has("token") ? data.get("token").getAsString() : "";
                        String userid = data.has("userid") ? data.get("userid").getAsString() : "";

                        if (!token.isEmpty()) {
                            this.cookie = "token=" + token + "; userid=" + userid;
                            this.loggedIn = true;
                            this.qrKey = "";
                            logger.info("酷狗二维码登录成功, userid={}", userid);
                            return LoginResult.success(token, this.cookie, "酷狗登录成功");
                        } else {
                            logger.warn("酷狗登录成功但未获取到token, data={}", data);
                            this.qrKey = "";
                            return LoginResult.failure("登录成功但未获取到token");
                        }
                    }
                    default:
                        logger.debug("酷狗二维码未知状态: {}", status);
                        break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.debug("酷狗轮询登录状态失败: {}", e.getMessage());
            }
        }

        this.qrKey = "";
        return LoginResult.failure("登录超时，请重试");
    }

    /**
     * 生成酷狗 Web 版 API 签名
     */
    private String generateWebSignature(Map<String, String> params) {
        // 按 key 字母排序，拼接为 key=value 连续字符串
        StringBuilder paramsString = new StringBuilder();
        new TreeMap<>(params).forEach((key, value) -> paramsString.append(key).append("=").append(value));

        String toHash = WEB_SIGN_SALT + paramsString + WEB_SIGN_SALT;
        return md5(toHash);
    }

    /**
     * MD5 哈希
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

    @Override
    public LoginResult loginWithCookie(String cookie) {
        this.cookie = cookie;
        this.loggedIn = true;
        String tok = resolveToken();
        String uid = resolveUserid();
        boolean hasToken = !tok.isEmpty();
        boolean hasUid = !uid.isEmpty();
        int eqCount = cookie == null ? 0 : cookie.split(";").length;
        logger.info("酷狗Cookie登录：token={} userid={} | cookie段数={} 长度={} 是否含'='={}",
                hasToken, hasUid, eqCount,
                cookie == null ? 0 : cookie.length(),
                cookie != null && cookie.contains("="));
        logger.info("酷狗Cookie解析：userid={}, token前缀={}...", uid,
                hasToken ? tok.substring(0, Math.min(12, tok.length())) : "");
        if (hasToken && hasUid) {
            return LoginResult.success(null, cookie, "Cookie登录成功");
        }
        String miss = (hasToken ? "" : "token ") + (hasUid ? "" : "userid");
        logger.warn("酷狗Cookie未含关键登录字段：缺失[{}]。请用酷狗官网(www.kugou.com)登录VIP账号后，从浏览器F12→网络→复制其Cookie请求头整串，粘贴为 /mm login kugou <cookie>（新版Cookie的登录字段名是 KugooID 和 t，插件已兼容识别）", miss.trim());
        return LoginResult.success(null, cookie, "Cookie已保存，但未检测到完整登录字段: " + miss.trim() + "（VIP歌曲可能仍无法播放）");
    }

    @Override
    public boolean isLoggedIn() {
        return loggedIn;
    }

    @Override
    public void refreshSession() {
        // 酷狗Cookie通常不需要主动刷新
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
