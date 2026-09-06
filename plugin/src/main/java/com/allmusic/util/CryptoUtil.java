package com.allmusic.util;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 加密工具类 (AES-256-GCM)
 */
public class CryptoUtil {
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;

    /**
     * 生成密钥
     */
    public static String generateKey() throws Exception {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256);
        SecretKey secretKey = keyGen.generateKey();
        return Base64.getEncoder().encodeToString(secretKey.getEncoded());
    }

    /**
     * 加密
     */
    public static String encrypt(String data, String base64Key) throws Exception {
        byte[] key = Base64.getDecoder().decode(base64Key);
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");

        byte[] iv = new byte[IV_LENGTH];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, parameterSpec);

        byte[] encrypted = cipher.doFinal(data.getBytes("UTF-8"));

        // 将 IV 和加密数据拼接
        byte[] result = new byte[IV_LENGTH + encrypted.length];
        System.arraycopy(iv, 0, result, 0, IV_LENGTH);
        System.arraycopy(encrypted, 0, result, IV_LENGTH, encrypted.length);

        return Base64.getEncoder().encodeToString(result);
    }

    /**
     * 解密
     */
    public static String decrypt(String encryptedData, String base64Key) throws Exception {
        byte[] key = Base64.getDecoder().decode(base64Key);
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");

        byte[] decoded = Base64.getDecoder().decode(encryptedData);

        byte[] iv = new byte[IV_LENGTH];
        System.arraycopy(decoded, 0, iv, 0, IV_LENGTH);

        byte[] encrypted = new byte[decoded.length - IV_LENGTH];
        System.arraycopy(decoded, IV_LENGTH, encrypted, 0, encrypted.length);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, parameterSpec);

        byte[] decrypted = cipher.doFinal(encrypted);
        return new String(decrypted, "UTF-8");
    }
}
