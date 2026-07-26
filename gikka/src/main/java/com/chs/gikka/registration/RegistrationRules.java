// [AGENT] 등록 판정 — DB·HTTP 없이 검증하는 순수 모듈 (rankFrequent 패턴, PLAYBOOK 관례 3)
// 컨트롤러는 이 판정의 결과만 사용한다. 규칙 변경·테스트는 이 파일과 RegistrationRulesTest 에서만.
package com.chs.gikka.registration;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

final class RegistrationRules {

    static final String STATUS_WAITING = "WAITING";
    static final String STATUS_TOO_LONG = "TOO_LONG";

    /** 이 이하 글자는 "의미 있는 음성 인식 없음"으로 취급(whisper 잡음·감탄사 수준) — 2026-07-14 확정 */
    static final int MIN_TRANSCRIPT_CHARS = 10;

    private RegistrationRules() {
    }

    /**
     * 이 분석에 실제로 쓸 수 있었던 원시 신호 목록 (2026-07-14 확정, pattern-raw-signal —
     * springboot/AGENTS.md). 특정 증상 하나를 위한 좁은 컬럼 대신 "무엇이 있었나"를 그대로
     * 저장하고, 경고 문구는 이 목록을 보는 별도 계층(프론트, 에러 계약상 문구는 프론트 소유)이
     * 도출한다 — 여기는 사실 나열만, "무슨 문구를 보여줄지"는 모른다.
     * FRAMES 는 추출이 성공한 이상 항상 있음(현재는 실패하면 예외로 빠지므로 무조건 포함).
     */
    static List<String> analysisSignals(String description, Integer transcriptChars) {
        List<String> signals = new java.util.ArrayList<>();
        signals.add("FRAMES");
        if (description != null && !description.isBlank()) {
            signals.add("DESCRIPTION");
        }
        if (transcriptChars != null && transcriptChars >= MIN_TRANSCRIPT_CHARS) {
            signals.add("TRANSCRIPT");
        }
        return signals;
    }

    /**
     * 길이 컷 (CONTEXT.md 2026-07-12 확정 = 7분): 초과 영상은 Gemini 호출 없이 TOO_LONG.
     * 길이를 모르면(메타 실패, null) 컷하지 않고 분석을 시도한다 — 막는 것보다 낫다.
     */
    static String initialStatus(Integer durationSeconds, int maxVideoMinutes) {
        boolean tooLong = durationSeconds != null && durationSeconds > maxVideoMinutes * 60;
        return tooLong ? STATUS_TOO_LONG : STATUS_WAITING;
    }

    /**
     * 재료 이름에서 수량·단위·괄호 보충 설명을 뗀 대표 후보 ("계란 2개" → "계란", 2026-07-18 확정).
     * 기계적으로 확실한 변형만 다룬다 — 오타·동의어("고웃 고춧가루" 등) 판단은 AI 점검+오너 확정
     * 경로가 담당한다(안전 비대칭 규칙: 묶기를 틀리면 없는 재료를 있다고 하게 됨).
     * 뗄 게 없거나(원형 그대로) 떼고 나면 아무것도 안 남으면(이름이 수량으로 시작하는 상품명 등)
     * null — 병합 후보 아님.
     */
    static String representativeCandidate(String name) {
        if (name == null) {
            return null;
        }
        String stripped = name
                .replaceAll("\\([^)]*\\)", " ")
                // 꼬리의 "숫자(+분수·범위) + 짧은 단위" — 2개, 1/2모, 2~3개, 300g, 1.5큰술
                .replaceAll("\\s*\\d[\\d./~\\-]*\\s*[가-힣a-zA-Z]{0,3}\\s*$", " ")
                .trim().replaceAll("\\s+", " ");
        if (stripped.isBlank() || stripped.equals(name.trim())) {
            return null;
        }
        return stripped;
    }

    /** 재생목록 일괄 등록용: 메타 목록을 영상 ID 로 색인 (중복 ID 는 첫 항목 우선) */
    static Map<String, VideoMetadataClient.VideoMetadata> metadataById(
            List<VideoMetadataClient.VideoMetadata> metas) {
        return metas.stream().collect(Collectors.toMap(
                VideoMetadataClient.VideoMetadata::videoId, Function.identity(), (first, dup) -> first));
    }
}
