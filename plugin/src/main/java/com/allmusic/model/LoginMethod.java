package com.allmusic.model;

/**
 * 登录方式
 */
public enum LoginMethod {
    QR_CODE, // 二维码扫码登录
    COOKIE, // Cookie 手动导入
    TOKEN // Token 导入
}
