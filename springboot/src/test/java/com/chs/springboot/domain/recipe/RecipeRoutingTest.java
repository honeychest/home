// [AGENT] recipe API 경로 가드 (2026-07-25 컨트롤러 3분할과 함께 신설).
//
// 왜 필요한가: 이번 분할의 확정 조건이 "**경로는 하나도 바뀌지 않는다**" 였다(프론트 무수정).
// 그런데 그건 단위 테스트로는 안 잡힌다 — 컨트롤러 메서드를 직접 호출하는 테스트는 @RequestMapping
// 을 아예 안 본다. 게다가 세 컨트롤러가 같은 접두사(/api/recipe/registrations)를 나눠 쓰므로
// **매핑 충돌**(같은 경로를 둘이 주장)은 기동 시점에야 터진다 — 배포하고 나서 아는 종류의 사고다.
// 여기서 실제 핸들러 매핑을 만들어 두 가지를 한꺼번에 잠근다: 충돌 없이 조립되는가, 그리고
// 각 경로가 의도한 컨트롤러로 가는가.
//
// 경로를 바꿔야 할 때(= `gikka/` 분리 시 /api/recipe/dictionary 로 이관) 이 표를 함께 고칠 것.
package com.chs.springboot.domain.recipe;

import java.util.List;

import com.chs.springboot.domain.recipe.auth.GikkaAuthProperties;
import com.chs.springboot.domain.recipe.auth.GikkaOwnerGuard;
import com.chs.springboot.domain.recipe.dictionary.DictionaryController;
import com.chs.springboot.domain.recipe.dictionary.IngredientChangeLogRepository;
import com.chs.springboot.domain.recipe.dictionary.IngredientDictionaryRepository;
import com.chs.springboot.domain.recipe.registration.GeminiRateLimiter;
import com.chs.springboot.domain.recipe.registration.LocalRecipeExtractor;
import com.chs.springboot.domain.recipe.registration.MonitorController;
import com.chs.springboot.domain.recipe.registration.RegistrationController;
import com.chs.springboot.domain.recipe.registration.RegistrationRepository;
import com.chs.springboot.domain.recipe.registration.VideoMetadataClient;
import com.chs.springboot.domain.recipe.registration.VideoRepository;
import com.chs.springboot.domain.recipe.user.GikkaUserRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class RecipeRoutingTest {

    /** 세 컨트롤러를 실제 핸들러 매핑에 등록한다 — 충돌이 있으면 이 조립 자체가 예외로 터진다. */
    private static RequestMappingHandlerMapping mapping() {
        GikkaOwnerGuard owner = new GikkaOwnerGuard(
                new GikkaAuthProperties(), mock(GikkaUserRepository.class));
        RegistrationController registrations = new RegistrationController(
                mock(RegistrationRepository.class), mock(VideoRepository.class),
                mock(VideoMetadataClient.class), owner);
        MonitorController monitor = new MonitorController(
                mock(RegistrationRepository.class), mock(VideoRepository.class),
                mock(GeminiRateLimiter.class), mock(VideoMetadataClient.class), owner,
                mock(LocalRecipeExtractor.class));
        DictionaryController dictionary = new DictionaryController(
                mock(IngredientDictionaryRepository.class), mock(IngredientChangeLogRepository.class), owner);

        StaticMapping mapping = new StaticMapping(List.of(registrations, monitor, dictionary));
        mapping.setApplicationContext(new org.springframework.web.context.support.StaticWebApplicationContext());
        mapping.afterPropertiesSet();
        return mapping;
    }

    /** 스프링 컨텍스트 없이 주어진 인스턴스만 훑도록 한 최소 매핑 (부팅·DB 불필요) */
    private static final class StaticMapping extends RequestMappingHandlerMapping {

        private final List<Object> handlers;

        private StaticMapping(List<Object> handlers) {
            this.handlers = handlers;
        }

        @Override
        protected void initHandlerMethods() {
            handlers.forEach(handler -> detectHandlerMethods(handler));
        }
    }

    private static HandlerMethod handlerFor(String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        Object handler = mapping().getHandler(request) == null
                ? null : mapping().getHandler(request).getHandler();
        return handler instanceof HandlerMethod handlerMethod ? handlerMethod : null;
    }

    @ParameterizedTest(name = "{0} {1} → {2}")
    @DisplayName("분할 전과 똑같은 경로가 의도한 컨트롤러로 간다 (프론트 무수정의 근거)")
    @CsvSource({
        // 보관함 — 일반 사용자
        "GET,    /api/recipe/registrations,                              RegistrationController",
        "POST,   /api/recipe/registrations,                              RegistrationController",
        "GET,    /api/recipe/registrations/search,                       RegistrationController",
        "GET,    /api/recipe/registrations/recent,                       RegistrationController",
        "POST,   /api/recipe/registrations/playlist,                     RegistrationController",
        "POST,   /api/recipe/registrations/by-video/abc,                 RegistrationController",
        "DELETE, /api/recipe/registrations/abc,                          RegistrationController",
        // 운영자 모드 — 오너 전용
        "GET,    /api/recipe/registrations/monitor,                      MonitorController",
        "GET,    /api/recipe/registrations/monitor/abc/analysis,         MonitorController",
        "POST,   /api/recipe/registrations/monitor/abc/reanalyze,        MonitorController",
        "POST,   /api/recipe/registrations/monitor/abc/remove,           MonitorController",
        // 재료 사전 — 경로는 유산(사전은 등록과 무관). gikka/ 분리 때 옮긴다
        "GET,    /api/recipe/registrations/dictionary,                   DictionaryController",
        "GET,    /api/recipe/registrations/dictionary/names,             DictionaryController",
        "GET,    /api/recipe/registrations/dictionary/changes,           DictionaryController",
        "POST,   /api/recipe/registrations/dictionary/classify,          DictionaryController",
        "POST,   /api/recipe/registrations/dictionary/classify-batch,    DictionaryController",
        "POST,   /api/recipe/registrations/dictionary/merge,             DictionaryController",
        "POST,   /api/recipe/registrations/dictionary/merge-batch,       DictionaryController",
    })
    void routesGoToTheIntendedController(String method, String path, String controller) throws Exception {
        HandlerMethod handler = handlerFor(method, path);

        assertNotNull(handler, method + " " + path + " 가 어느 컨트롤러에도 매핑되지 않았다");
        assertEquals(controller, handler.getBeanType().getSimpleName());
    }

    @Test
    @DisplayName("DELETE /{videoId} 는 monitor·dictionary 같은 고정 경로를 삼키지 않는다 — "
            + "세 컨트롤러가 한 접두사를 나눠 쓰므로 경로 변수와 고정 세그먼트의 우선순위가 중요하다")
    void pathVariableDoesNotSwallowFixedSegments() throws Exception {
        assertEquals("MonitorController",
                handlerFor("GET", "/api/recipe/registrations/monitor").getBeanType().getSimpleName());
        assertEquals("DictionaryController",
                handlerFor("GET", "/api/recipe/registrations/dictionary").getBeanType().getSimpleName());
    }
}
