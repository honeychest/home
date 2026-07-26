// [AGENT] mac-mini 호스트 서비스 불가 (2026-07-26 중립 지대로 이관 — 이전엔 LocalRecipeExtractor 의
// 중첩 클래스라, 재료 사전의 로컬 어댑터가 "영상 추출 어댑터"를 이름 하나 때문에 import 했다).
package com.chs.gikka.external;

/**
 * 호스트 서비스(gikka-extractor/server.py) 자체가 지금 안 되는 상황 — 서비스 미기동·네트워크
 * 오류·설정으로 꺼둠. 부르던 쪽이 다른 채널로 넘어가야 한다는 뜻이다
 * (추출은 HybridRecipeExtractor 가 Gemini 로, 사전 판정은 DictionaryJudge 가 반대 채널로).
 *
 * <p><b>{@link TransientFailureException} 과 합치지 말 것</b> — 저쪽 주석의 이유와 같다.
 */
public class LocalUnavailableException extends RuntimeException {

    public LocalUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
