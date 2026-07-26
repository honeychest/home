// [AGENT] 외부 채널 일시적 실패 (2026-07-26 중립 지대로 이관 — 이전엔 RecipeExtractor 의 중첩 클래스).
package com.chs.springboot.domain.recipe.external;

/**
 * 밖에 있는 상대(지금은 Gemini) 사정으로 지금 당장은 안 되지만 곧 풀릴 상황 (2026-07-13 확정,
 * 실측: 429 무료 한도 + 503 "high demand" 과부하 + 타임아웃 전부 이 성격으로 관찰됨).
 * 영상·재료 자체의 문제가 아니므로 워커가 시도 횟수 안 깎고 대기 후 자동 재개한다.
 *
 * <p><b>{@link LocalUnavailableException} 과 합치지 말 것</b> — 이름은 비슷하지만 처리가 다르다.
 * 이쪽은 "기다리면 풀린다"라 워커가 60초 백오프 후 재개하고, 저쪽은 "이 채널은 지금 없다"라
 * 즉시 다른 채널로 넘어간다. 합치면 로컬이 꺼져 있을 때도 60초씩 쉬게 된다.
 *
 * <p>왜 추출(RecipeExtractor)에서 여기로 나왔나 (2026-07-26): 재료 사전 판정(DictionaryJudge·
 * 두 어댑터)도 이 예외를 던지고 받는데, 사전은 영상 추출과 아무 관계가 없다. 추출 시임의 중첩
 * 클래스로 두면 사전이 "일시적 실패"를 말하려고 추출을 import 해야 했고, 그것 때문에 판정
 * 6개가 registration 패키지를 못 떠났다.
 */
public class TransientFailureException extends RuntimeException {

    public TransientFailureException(String message) {
        super(message);
    }
}
