// [AGENT] 동기 LLM 호출 전용 경로 (/api/recipe/llm/**) — 2026-07-17 신설.
//
// 왜 별도 경로·별도 컨트롤러인가 (지우기 전에 반드시 읽을 것):
//   이 요청은 재료 사전 전체를 훑는 동안 응답을 붙들고 있어 10~25초가 "정상"이다.
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
// (2026-07-26 패키지만 registration → dictionary 로 옮겼고 <b>경로는 그대로다</b> —
//  RecipeRoutingTest 가 그 사실을 잠근다. nginx 는 무관.)
//
// 이건 임시 처방이다. 근본 해결은 대기열·워커(pattern-queue-worker)로 비동기화해서 이
// 경로 자체를 없애는 것 — 영상 분석은 이미 그렇게 하고 있고 AI 점검만 그 규율 밖에 있다.
// 승격 조건은 docs/recipe/CONTEXT.md "AI 점검 동기 호출(임시)" 절.
//
// 판정 자체(대상 선정·채널 라우팅·제안 검증)는 DictionaryJudge 가 한다 (2026-07-25 점검).
// 여기 남은 것은 이 경로만의 계약 둘 — 오너 게이트(403)와 일시적 실패의 503 매핑이다.
package com.chs.springboot.domain.recipe.dictionary;

import java.util.List;

import com.chs.springboot.domain.recipe.auth.GikkaOwnerGuard;
import com.chs.springboot.domain.recipe.auth.GikkaUserId;
import com.chs.springboot.domain.recipe.external.TransientFailureException;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/recipe/llm")
public class IngredientAuditController {

    private final DictionaryJudge judge;
    private final GikkaOwnerGuard owner;

    public IngredientAuditController(DictionaryJudge judge, GikkaOwnerGuard owner) {
        this.judge = judge;
        this.owner = owner;
    }

    /**
     * AI 일괄 점검 — 사전을 LLM 이 한 번에 훑어 두 종류를 "제안"한다(자동 반영 아님. 오너가
     * /dictionary/classify·classify-batch·merge·merge-batch 로 반영. 온디맨드라 제안은 저장 안 함).
     *   · 분류 제안: 아직 판정 안 된(PENDING) 이름의 양념(SEASONING)·기본양념(BASIC) 여부.
     *   · 묶기 제안: "계란 2개 → 계란" 같은 변형 흡수 (2026-07-17 슬라이스2).
     *
     * <p>판정 대상 선정과 제안 검증의 규칙은 DictionaryJudge 가 소유한다 — PENDING 대표만 판정하고,
     * 모델이 지어낸 이름·대표 자격 없는 대상은 사전 사실과 대조해 거른다.
     *
     * <p>채널 순서는 <b>Gemini 우선</b>이다 — 오너가 지금 최고 품질을 원해서 누른 온디맨드 경로이므로
     * (상시 경로인 IngredientAutoJudge 는 한도를 아끼려 반대 순서를 쓴다). Gemini 가 일시적으로
     * 실패하면(429·503·타임아웃) gikka-local 로 폴백하고, 로컬도 안 되면 그때 503 —
     * 프론트가 "잠시 후 다시" 문구로 (에러 계약: 상태 코드만).
     */
    @PostMapping("/dictionary/audit")
    public List<IngredientJudge.Proposal> auditDictionary(@GikkaUserId long userId) {
        owner.require(userId);
        try {
            return judge.propose(DictionaryJudge.Order.GEMINI_FIRST);
        } catch (TransientFailureException e) {
            // 두 채널이 다 막힌 경우에만 여기 온다 (DictionaryJudge 가 1차 예외를 그대로 던진다).
            // 감사기는 워커와 달리 재시도 정책이 없어, 안 막으면 500 으로 새어 프론트가 "잠시 후
            // 다시"를 못 띄운다 (2026-07-17).
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "일시적 실패", e);
        }
    }
}
