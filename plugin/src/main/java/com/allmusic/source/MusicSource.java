package com.allmusic.source;

import com.allmusic.model.*;

import java.util.List;

/**
 * 音源接口
 */
public interface MusicSource {

    /**
     * 获取音源名称
     */
    String getName();

    /**
     * 获取音源显示名称
     */
    String getDisplayName();

    /**
     * 是否已启用
     */
    boolean isEnabled();

    /**
     * 搜索歌曲
     */
    List<SongInfo> search(String keyword, int limit);

    /**
     * 获取歌曲详情
     */
    SongDetail getDetail(String songId);

    /**
     * 获取音频直链
     */
    String getAudioUrl(String songId);

    /**
     * 获取歌词
     */
    Lyrics getLyrics(String songId);

    /**
     * 登录
     */
    LoginResult login(LoginMethod method);

    /**
     * 轮询二维码登录状态（默认不支持，返回失败）
     */
    default LoginResult pollQrCodeLogin(int timeoutSeconds) {
        return LoginResult.failure("该平台不支持二维码登录");
    }

    /**
     * 使用Cookie登录
     */
    LoginResult loginWithCookie(String cookie);

    /**
     * 是否已登录
     */
    boolean isLoggedIn();

    /**
     * 刷新登录态
     */
    void refreshSession();

    /**
     * 登出
     */
    void logout();

    /**
     * 获取登录状态描述
     */
    String getLoginStatus();

    /**
     * 获取当前保存的 Cookie（用于重启后持久化恢复；默认无）
     */
    default String getSavedCookie() {
        return "";
    }

    /**
     * 从持久化恢复登录态（默认调用 loginWithCookie）
     */
    default void restoreSession(String savedCookie) {
        if (savedCookie != null && !savedCookie.isEmpty()) {
            loginWithCookie(savedCookie);
        }
    }
}
