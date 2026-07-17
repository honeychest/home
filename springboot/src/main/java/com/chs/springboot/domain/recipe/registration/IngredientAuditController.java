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
import java.util.Map;
import java.util.stream.Collectors;

import com.chs.springboot.domain.recipe.auth.GikkaAuthProperties;
import com.chs.springboot.domain.recipe.auth.GikkaUserId;
import com.chs.springboot.domain.recipe.user.GikkaUserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/recipe/llm")
public class IngredientAuditController {

    private static final Logger log = LoggerFactory.getLogger(IngredientAuditController.class);

    private final IngredientDictionaryRepository dictionary;
    private final IngredientAuditor auditor;
    private final LocalIngredientAuditor localAuditor;
    private final GikkaAuthProperties authProperties;
    private final GikkaUserRepository users;

    public IngredientAuditController(IngredientDictionaryRepository dictionary, IngredientAuditor auditor,
                                     LocalIngredientAuditor localAuditor, GikkaAuthProperties authProperties,
                                     GikkaUserRepository users) {
        this.dictionary = dictionary;
        this.auditor = auditor;
        this.localAuditor = localAuditor;
        this.authProperties = authProperties;
        this.users = users;
    }

    /**
     * AI 일괄 점검 — 사전을 LLM 이 한 번에 훑어 두 종류를 "제안"한다(자동 반영 아님. 오너가
     * /dictionary/classify·classify-batch·merge·merge-batch 로 반영. 온디맨드라 제안은 저장 안 함).
     *   · 분류 제안: 아직 판정 안 된(PENDING) 이름의 양념(SEASONING)·기본양념(BASIC) 여부.
     *     MAIN 제안은 뺀다 — PENDING 이 이미 주재료 취급이라 바꿀 게 없다(안전 기본값).
     *   · 묶기 제안: "계란 2개 → 계란" 같은 변형 흡수 (2026-07-17 슬라이스2).
     *
     * <p>판정 대상은 <b>PENDING 대표만</b>이다(2026-07-18 확정, 이미 묶인 멤버는 성격이 대표에서
     * 나오므로 애초에 대표가 아니다). 전체 대표 목록(확정 포함)은 LLM 에 "이 중에서만 mergeInto
     * 를 골라라"는 참고 자료로만 넘어간다 — 판정 대상이 아니므로 응답에 안 나온다. 이렇게 하면
     * 응답 크기가 사전 전체 크기가 아니라 신규(PENDING) 개수에만 비례한다(예전엔 대표 전체를
     * 판정 대상으로 보내 사전이 커질수록 출력이 자라다가 gikka-local max_tokens 를 넘겨 503 이
     * 재발했다). 트레이드오프: 이미 CONFIRMED 인 대표끼리 뒤늦게 묶자는 제안은 더 이상 안 나온다
     * (그 경우도 "매칭이 덜 될 뿐"인 안전한 실패 모드).
     *
     * <p>LLM 이 낸 제안을 사전 사실과 대조해 여기서 거른다(모델은 사전을 모르고, 없는 이름을
     * 지어낼 수 있다): 실재하지 않는 이름, 대표 자격이 없는 이름(이미 남에게 묶인 멤버 — 대표로
     * 삼으면 A→B→C 체인이 된다), 이미 그 그룹인 것은 제안이 아니다.
     *
     * <p>일시적 실패(429·503·타임아웃)는 gikka-local(mac-mini LM Studio) 로 폴백한다(2026-07-18
     * 확정). 로컬도 안 되면(서비스 다운 등) 그때 503 — 프론트가 "잠시 후 다시" 문구로
     * (에러 계약: 상태 코드만).
     */
    @PostMapping("/dictionary/audit")
    public List<IngredientAuditor.Proposal> auditDictionary(@GikkaUserId long userId) {
        requireOwner(userId);
        List<IngredientDictionaryRepository.Entry> all = dictionary.all();
        Map<String, IngredientDictionaryRepository.Entry> byName = all.stream()
                .collect(Collectors.toMap(IngredientDictionaryRepository.Entry::name, e -> e));
        List<String> representatives = all.stream()
                .filter(IngredientAuditController::isRepresentative)
                .map(IngredientDictionaryRepository.Entry::name)
                .toList();
        List<String> pendingRepresentatives = all.stream()
                .filter(IngredientAuditController::isRepresentative)
                .filter(e -> IngredientDictionaryRepository.STATUS_PENDING.equals(e.status()))
                .map(IngredientDictionaryRepository.Entry::name)
                .toList();
        if (pendingRepresentatives.isEmpty()) {
            return List.of(); // 신규 없음 — LLM 호출 자체를 생략(한도 절약)
        }
        try {
            return auditor.audit(pendingRepresentatives, representatives).stream()
                    .filter(p -> isUseful(p, byName))
                    .toList();
        } catch (RecipeExtractor.TransientFailureException e) {
            log.warn("Gemini 일시적 실패: {} - {}", "일시적 실패(원인불명)", e.getMessage());
            try {
                return localAuditor.audit(pendingRepresentatives, representatives).stream()
                        .filter(p -> isUseful(p, byName))
                        .toList();
            } catch (LocalRecipeExtractor.LocalUnavailableException le) {
                log.warn("[gikka] 로컬 감사도 불가 — 503: {}", le.getMessage());
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Gemini 일시적 실패", e);
            }
        }
    }

    /** 대표 = 아무에게도 안 묶인 행(자기 이름이 자기 매칭 키). 멤버는 성격을 대표에서 물려받는다. */
    private static boolean isRepresentative(IngredientDictionaryRepository.Entry entry) {
        return entry.name().equals(entry.matchKey());
    }

    /** 오너에게 보여줄 가치가 있는 제안인가 — 사전에 실재하고, 지금 상태를 실제로 바꾸는 것만. */
    private static boolean isUseful(IngredientAuditor.Proposal proposal,
                                    Map<String, IngredientDictionaryRepository.Entry> byName) {
        IngredientDictionaryRepository.Entry entry = byName.get(proposal.name());
        if (entry == null || !isRepresentative(entry)) {
            return false; // 모델이 지어낸 이름이거나, 이미 묶인 멤버
        }
        if (proposal.mergeInto() != null) {
            IngredientDictionaryRepository.Entry target = byName.get(proposal.mergeInto());
            return target != null && isRepresentative(target); // 없는 대표·체인 방지
        }
        // 분류 제안은 아직 안 정한 것만 — 오너가 이미 정한 건 덮어쓸 후보로 올리지 않는다(사람 판정 우선)
        return IngredientDictionaryRepository.STATUS_PENDING.equals(entry.status())
                && !IngredientAuditor.TIER_MAIN.equals(proposal.suggestedTier());
    }

    private void requireOwner(long userId) {
        if (!authProperties.isOwner(users.findEmail(userId))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "오너 전용 기능");
        }
    }
}
