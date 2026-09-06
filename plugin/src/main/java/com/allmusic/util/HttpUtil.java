package com.allmusic.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * HTTP 工具类
 */
public class HttpUtil {
    private static final Logger logger = LoggerFactory.getLogger("MygoMusic-Http");
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

    private static final Gson gson = new Gson();

    /**
     * 使用LENIENT模式解析JSON（兼容不规范的JSON）
     * 直接使用JsonReader避免assertFullConsumption检查
     */
    public static <T> T fromJsonLenient(String json, Class<T> classOfT) {
        JsonReader reader = new JsonReader(new StringReader(json));
        reader.setStrictness(Strictness.LENIENT);
        return gson.fromJson(reader, classOfT);
    }

    /**
     * GET 请求
     */
    public static String get(String url) throws IOException {
        return get(url, null);
    }

    /**
     * GET 请求 (带自定义Header)
     */
    public static String get(String url, Map<String, String> headers) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .get();

        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                builder.addHeader(entry.getKey(), entry.getValue());
            }
        }

        try (Response response = client.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP GET failed: " + response.code() + " " + response.message());
            }
            ResponseBody body = response.body();
            return body != null ? body.string() : "";
        }
    }

    /**
     * 带响应头的 GET 请求（用于获取 Set-Cookie）
     */
    public static HttpResponse getWithHeaders(String url, Map<String, String> headers) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .get();

        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                builder.addHeader(entry.getKey(), entry.getValue());
            }
        }

        try (Response response = client.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP GET failed: " + response.code() + " " + response.message());
            }
            ResponseBody body = response.body();
            String bodyStr = body != null ? body.string() : "";
            String setCookie = response.header("Set-Cookie");
            return new HttpResponse(bodyStr, setCookie);
        }
    }

    /**
     * HTTP 响应（body + Set-Cookie）
     */
    public static class HttpResponse {
        public final String body;
        public final String setCookie;

        public HttpResponse(String body, String setCookie) {
            this.body = body;
            this.setCookie = setCookie;
        }
    }

    /**
     * POST 请求 (JSON)
     */
    public static String postJson(String url, String json) throws IOException {
        return postJson(url, json, null);
    }

    /**
     * POST 请求 (JSON + 自定义Header)
     */
    public static String postJson(String url, String json, Map<String, String> headers) throws IOException {
        RequestBody body = RequestBody.create(json, MediaType.parse("application/json; charset=utf-8"));

        Request.Builder builder = new Request.Builder()
                .url(url)
                .post(body);

        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                builder.addHeader(entry.getKey(), entry.getValue());
            }
        }

        try (Response response = client.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP POST failed: " + response.code() + " " + response.message());
            }
            ResponseBody responseBody = response.body();
            return responseBody != null ? responseBody.string() : "";
        }
    }

    /**
     * POST 请求 (表单)
     */
    public static String postForm(String url, Map<String, String> formData) throws IOException {
        FormBody.Builder formBuilder = new FormBody.Builder();
        if (formData != null) {
            for (Map.Entry<String, String> entry : formData.entrySet()) {
                formBuilder.add(entry.getKey(), entry.getValue());
            }
        }

        Request request = new Request.Builder()
                .url(url)
                .post(formBuilder.build())
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP POST failed: " + response.code() + " " + response.message());
            }
            ResponseBody body = response.body();
            return body != null ? body.string() : "";
        }
    }

    /**
     * 带 Cookie 的 GET 请求
     */
    public static String getWithCookie(String url, String cookie) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Cookie", cookie)
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP GET failed: " + response.code() + " " + response.message());
            }
            ResponseBody body = response.body();
            return body != null ? body.string() : "";
        }
    }

    public static OkHttpClient getClient() {
        return client;
    }
}
