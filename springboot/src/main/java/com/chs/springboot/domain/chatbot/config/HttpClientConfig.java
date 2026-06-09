// [AGENT] 역할: 외부 HTTP 호출을 HTTP/1.1 로 고정 | 연관파일: AsyncReindexRunner, ChatbotService (Spring AI OpenAI/LM Studio 호출)
package com.chs.springboot.domain.chatbot.config;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * RestClient HTTP 버전 고정.
 *
 * 왜 필요한가:
 *   - JDK HttpClient(Spring 의 JdkClientHttpRequest)는 기본적으로 HTTP/2 를 먼저 시도한다.
 *   - LM Studio 의 임베딩/채팅 서버는 평문 HTTP/2 를 제대로 처리하지 못해, 요청이
 *     응답 없이 무한 대기(hang)했다(실측: curl HTTP/1.1 1초 vs 앱 HTTP/2 무한).
 *   - 따라서 클라이언트를 HTTP/1.1 로 고정해 hang 을 회피한다.
 *
 * 적용 범위: RestClientCustomizer 는 auto-config 된 RestClient.Builder 전체에 적용된다.
 *   Spring AI OpenAI 클라이언트가 이 빌더를 사용하므로 LM Studio 호출이 HTTP/1.1 이 된다.
 *   다른 RestClient 호출도 HTTP/1.1 로 동작하지만, HTTP/1.1 은 보편 호환이라 부작용이 없다.
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public RestClientCustomizer http11RestClientCustomizer() {
        return builder -> {
            HttpClient httpClient = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
            factory.setReadTimeout(Duration.ofSeconds(300));
            builder.requestFactory(factory);
        };
    }
}
