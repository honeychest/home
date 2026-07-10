// [AGENT] @GikkaUserId 파라미터 리졸버 — 인증 판정을 이 한 곳에 집중 (컨트롤러는 userId 만 받음)
package com.chs.springboot.domain.recipe.auth;

import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Component
public class GikkaUserIdArgumentResolver implements HandlerMethodArgumentResolver {

    private final CurrentUser currentUser;

    public GikkaUserIdArgumentResolver(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(GikkaUserId.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                  NativeWebRequest request, WebDataBinderFactory binderFactory) {
        return currentUser.currentUserId(); // 미로그인이면 여기서 401
    }
}
