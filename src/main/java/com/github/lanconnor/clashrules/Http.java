package com.github.lanconnor.clashrules;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 简单的 HTTP 下载工具。
 *
 * @author Connor.Wang
 */
final class Http {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private Http() {
    }

    /**
     * 下载远程文本，UTF-8 解码（非法字节替换为 U+FFFD）。
     *
     * @param url 远程地址
     * @return 文本内容
     * @throws IOException          下载失败或状态码异常
     * @throws InterruptedException 线程被中断
     */
    static String fetch(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .timeout(TIMEOUT)
                .GET()
                .build();
        HttpResponse<byte[]> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }
        return new String(response.body(), StandardCharsets.UTF_8);
    }
}
