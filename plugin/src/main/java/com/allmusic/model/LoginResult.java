package com.allmusic.model;

/**
 * 登录结果
 */
public class LoginResult {
    private final boolean success;
    private final String message;
    private final String qrCodeUrl; // 二维码URL
    private final String loginUrl; // 登录跳转链接
    private final String token; // 登录成功后的token
    private final String cookie; // 登录成功后的cookie

    private LoginResult(boolean success, String message, String qrCodeUrl, String loginUrl, String token, String cookie) {
        this.success = success;
        this.message = message;
        this.qrCodeUrl = qrCodeUrl;
        this.loginUrl = loginUrl;
        this.token = token;
        this.cookie = cookie;
    }

    public boolean isSuccess() { return success; }
    public String getMessage() { return message; }
    public String getQrCodeUrl() { return qrCodeUrl; }
    public String getLoginUrl() { return loginUrl; }
    public String getToken() { return token; }
    public String getCookie() { return cookie; }

    public boolean hasQrCode() {
        return qrCodeUrl != null && !qrCodeUrl.isEmpty();
    }

    public boolean hasLoginUrl() {
        return loginUrl != null && !loginUrl.isEmpty();
    }

    public static LoginResult waitingForQrCode(String qrCodeUrl, String message) {
        return new LoginResult(false, message, qrCodeUrl, null, null, null);
    }

    public static LoginResult waitingForLogin(String loginUrl, String message) {
        return new LoginResult(false, message, null, loginUrl, null, null);
    }

    public static LoginResult success(String token, String cookie, String message) {
        return new LoginResult(true, message, null, null, token, cookie);
    }

    public static LoginResult failure(String message) {
        return new LoginResult(false, message, null, null, null, null);
    }
}
