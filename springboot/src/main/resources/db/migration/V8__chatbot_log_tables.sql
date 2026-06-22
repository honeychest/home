-- [AGENT] 챗봇 로그 분석 스키마 추가: 대화, 질문답변 턴, 검색 근거, 분석 결과 저장
CREATE TABLE `chatbot_conversation` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'PK',
    `session_id` varchar(80) DEFAULT NULL COMMENT '클라이언트 또는 서버가 부여한 대화 세션 식별자',
    `page_id` varchar(40) DEFAULT NULL COMMENT '대화가 시작된 화면 식별자',
    `source_env` varchar(20) NOT NULL DEFAULT 'unknown' COMMENT '로그 발생 환경(local/prod 등)',
    `message_count` int NOT NULL DEFAULT 0 COMMENT '대화에 포함된 질문답변 턴 수',
    `started_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '대화 시작 시각',
    `last_message_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '마지막 메시지 시각',
    PRIMARY KEY (`id`),
    KEY `idx_chatbot_conversation_session_started` (`session_id`, `started_at`),
    KEY `idx_chatbot_conversation_page_last` (`page_id`, `last_message_at`),
    KEY `idx_chatbot_conversation_env_last` (`source_env`, `last_message_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `chatbot_turn` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'PK',
    `conversation_id` bigint NOT NULL COMMENT 'chatbot_conversation FK',
    `request_id` varchar(80) DEFAULT NULL COMMENT '요청 단위 추적 식별자',
    `turn_index` int NOT NULL COMMENT '대화 안에서의 질문답변 순번',
    `page_id` varchar(40) DEFAULT NULL COMMENT '질문 당시 화면 식별자',
    `question` varchar(2000) NOT NULL COMMENT '사용자 질문 원문. 저장 전 2000자 제한',
    `answer` text DEFAULT NULL COMMENT '챗봇 답변. 저장 전 12000자 제한',
    `search_query` varchar(3000) DEFAULT NULL COMMENT '근거 검색에 사용한 보강 질의. 저장 전 3000자 제한',
    `llm_question` varchar(3000) DEFAULT NULL COMMENT 'LLM에 전달한 최종 질문. 저장 전 3000자 제한',
    `page_context` varchar(1000) DEFAULT NULL COMMENT '현재 화면 안내문. 저장 전 1000자 제한',
    `status` varchar(20) NOT NULL DEFAULT 'SUCCESS' COMMENT '턴 처리 상태',
    `issue_type` varchar(40) NOT NULL DEFAULT 'NONE' COMMENT '분석용 문제 유형',
    `latency_ms` int DEFAULT NULL COMMENT '질문 수신부터 답변 생성까지 걸린 시간',
    `evidence_count` int NOT NULL DEFAULT 0 COMMENT '검색된 근거 개수',
    `error_message` text DEFAULT NULL COMMENT '오류 메시지. 저장 전 4000자 제한',
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '질문 수신 시각',
    `completed_at` datetime(6) DEFAULT NULL COMMENT '답변 완료 시각',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uq_chatbot_turn_request` (`request_id`),
    KEY `idx_chatbot_turn_conversation_index` (`conversation_id`, `turn_index`),
    KEY `idx_chatbot_turn_created` (`created_at`),
    KEY `idx_chatbot_turn_page_created` (`page_id`, `created_at`),
    KEY `idx_chatbot_turn_issue_created` (`issue_type`, `created_at`),
    KEY `idx_chatbot_turn_status_created` (`status`, `created_at`),
    KEY `idx_chatbot_turn_latency` (`latency_ms`),
    CONSTRAINT `fk_chatbot_turn_conversation`
        FOREIGN KEY (`conversation_id`) REFERENCES `chatbot_conversation` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `chatbot_retrieved_evidence` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'PK',
    `turn_id` bigint NOT NULL COMMENT 'chatbot_turn FK',
    `rank_no` int NOT NULL COMMENT '검색 결과 순위',
    `source` varchar(500) NOT NULL COMMENT '근거 파일 경로',
    `symbol` varchar(255) DEFAULT NULL COMMENT '심볼 기반 청킹 시 심볼명',
    `line_range` varchar(40) DEFAULT NULL COMMENT '근거 라인 범위',
    `score` decimal(12,8) DEFAULT NULL COMMENT '검색 점수. 제공되지 않으면 NULL',
    `content_preview` varchar(1000) DEFAULT NULL COMMENT '근거 청크 일부 미리보기. 저장 전 1000자 제한',
    `metadata_json` text DEFAULT NULL COMMENT '추가 메타데이터 JSON 문자열. 저장 전 4000자 제한',
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '저장 시각',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uq_chatbot_evidence_turn_rank` (`turn_id`, `rank_no`),
    KEY `idx_chatbot_evidence_source` (`source`(255)),
    CONSTRAINT `fk_chatbot_evidence_turn`
        FOREIGN KEY (`turn_id`) REFERENCES `chatbot_turn` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `chatbot_analysis` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'PK',
    `turn_id` bigint NOT NULL COMMENT 'chatbot_turn FK',
    `issue_type` varchar(40) NOT NULL COMMENT '분석자가 판단한 문제 유형',
    `summary` text DEFAULT NULL COMMENT '문제 요약',
    `suggestion` text DEFAULT NULL COMMENT '보강 제안',
    `confidence` decimal(5,4) DEFAULT NULL COMMENT '자동 분석 신뢰도. 수동 분석이면 NULL',
    `created_by` varchar(20) NOT NULL DEFAULT 'SYSTEM' COMMENT '분석 작성 주체',
    `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '분석 생성 시각',
    PRIMARY KEY (`id`),
    KEY `idx_chatbot_analysis_turn_created` (`turn_id`, `created_at`),
    KEY `idx_chatbot_analysis_issue_created` (`issue_type`, `created_at`),
    CONSTRAINT `fk_chatbot_analysis_turn`
        FOREIGN KEY (`turn_id`) REFERENCES `chatbot_turn` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
