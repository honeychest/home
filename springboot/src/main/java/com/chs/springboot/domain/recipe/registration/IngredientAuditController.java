// [AGENT] 동기 LLM 호출 전용 경로 (/api/recipe/llm/**) — 2026-07-17 신설.
//
// 왜 별도 경로·별도 컨트롤러인가 (지우기 전에 반드시 읽을 것):
//   이 요청은 Gemini 가 재료 사전 전체를 훑는 동안 응답을 붙들고 있어 10~25초가 "정상"이다.
//   그런데 nginx 의 `location /api` 는 proxy_read_timeout 15s 다 — 그 값은 대화형 요청이
//   죽었는지 판정하는 안전장치라 그 부류엔 적절하다. 즉 성격이 다른 두 부류가 한 접두사
//   아래 섞여 있었고, 둘 다에 맞는 타임아웃 값은 존재하지 않는다. 실제로 AI 점검이 계속
//   504 로 끊기고 있었다 (2026-07-17 실측: 사고 ON 24.7초 / OFF 10.9초 vs 한도 15초).
//   그래서 nginx 에 `location ^~ /api/recipe/llm/ { proxy_read_timeout 120s;
//   proxy_next_upstream off; }` 를 두고 이 부류만 분리했다.
//
// 경로를 옮기면 nginx 설정도 함께 옮길 것 (mac-mini `/opt/homebrew/etc/nginx/servers/
// devcontext.conf` + 저장소 백업 사본 `chs/server/nginx/devcontext.conf`). 안 그러면 조용히
// `location /api` 의 15초로 돌아가 같은 버그가 재발한다 — 테스트가 못 잡는 종류다.
//
// 이건 임시 처방이다. 근본 해결은 대기열·워커(pattern-queue-worker)로 비동기화해서 이
// 경로 자체를 없애는 것 — 영상 분석은 이미 그렇게 하고 있고 AI 점검만 그 규율 밖에 있다.
// 승격 조건은 docs/recipe/CONTEXT.md "AI 점검 동기 호출(임시)" 절.
package com.chs.springboot.domain.recipe.registration;

import java.util.List;

import com.chs.springboot.domain.recipe.auth.GikkaAuthProperties;
import com.chs.springboot.domain.recipe.auth.GikkaUserId;
import com.chs.springboot.domain.recipe.user.GikkaUserRepository;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/recipe/llm")
public class IngredientAuditController {

    private final IngredientDictionaryRepository dictionary;
    private final IngredientAuditor auditor;
    private final GikkaAuthProperties authProperties;
    private final GikkaUserRepository users;

    public IngredientAuditController(IngredientDictionaryRepository dictionary, IngredientAuditor auditor,
                                     GikkaAuthProperties authProperties, GikkaUserRepository users) {
        this.dictionary = dictionary;
        this.auditor = auditor;
        this.authProperties = authProperties;
        this.users = users;
    }

    /** AI 일괄 점검 — 아직 판정 안 된(PENDING) 이름을 LLM 이 훑어 양념(SEASONING)·기본양념(BASIC)
        제안만 돌려준다. MAIN 제안은 뺀다 — PENDING 이 이미 주재료 취급이라 바꿀 게 없다(안전 기본값).
        자동 반영 아님 — 오너가 /dictionary/classify(개별)·/dictionary/classify-batch(전체 적용)로 반영.
        온디맨드라 제안은 저장하지 않는다.
        일시적 실패(429·503·타임아웃)는 503 — 프론트가 "잠시 후 다시" 문구로 (에러 계약: 상태 코드만). */
    @PostMapping("/dictionary/audit")
    public List<IngredientAuditor.Proposal> auditDictionary(@GikkaUserId long userId) {
        requireOwner(userId);
        List<String> pending = dictionary.all().stream()
                .filter(e -> IngredientDictionaryRepository.STATUS_PENDING.equals(e.status()))
                .map(IngredientDictionaryRepository.Entry::name)
                .toList();
        try {
            return auditor.audit(pending).stream()
                    .filter(p -> !IngredientAuditor.TIER_MAIN.equals(p.suggestedTier()))
                    .toList();
        } catch (RecipeExtractor.TransientFailureException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Gemini 일시적 실패", e);
        }
    }

    private void requireOwner(long userId) {
        if (!authProperties.isOwner(users.findEmail(userId))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "오너 전용 기능");
        }
    }
}
