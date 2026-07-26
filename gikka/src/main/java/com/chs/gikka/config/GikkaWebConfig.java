// [AGENT] @GikkaUserId 리졸버 등록 — WebMvcConfigurer 는 앱 전역 등록이지만
// 리졸버가 @GikkaUserId 붙은 파라미터에만 반응하므로 다른 도메인 영향 0.
package com.chs.gikka.config;

import java.util.List;

import com.chs.gikka.auth.GikkaUserIdArgumentResolver;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class GikkaWebConfig implements WebMvcConfigurer {

    private final GikkaUserIdArgumentResolver resolver;

    public GikkaWebConfig(GikkaUserIdArgumentResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(resolver);
    }
}
