// [AGENT] 역할: GitNexus 그래프에서 심볼 라인경계를 조회하는 선택적 Adapter | 연관파일: SymbolAwareChunker.java(예정), CodebaseDocumentChunker.java
package com.chs.springboot.domain.chatbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * GitNexus 심볼 경계 제공자 (선택적 의존).
 *
 * 설계 원칙 — "GitNexus가 없어도 절대 색인을 깨뜨리지 않는다":
 *   - GitNexus 미설치 / CLI 실패 / 타임아웃 / 출력 파싱 실패 / repo 미지정 등
 *     모든 실패 경로에서 예외를 던지지 않고 '빈 맵'을 반환한다.
 *   - 호출자(청커)는 빈 맵이면 해당 파일을 토큰 분할로 폴백한다.
 *   - 따라서 이 클래스를 통째로 제거해도, 청커에서 호출만 끊으면 색인은 정상 동작한다.
 *
 * 동작: `npx gitnexus cypher -r <repo> "<경계 조회>"` 를 1회 실행하고,
 *       CLI가 출력하는 JSON({"markdown": "<파이프 테이블>", ...})을 파싱해
 *       파일경로(구분자 '/'로 정규화) → 심볼 경계 목록 으로 반환한다.
 */
@Component
public class GitNexusBoundaryProvider {

    private static final Logger log = LoggerFactory.getLogger(GitNexusBoundaryProvider.class);

    private static final long TIMEOUT_SECONDS = 60;

    // 파일/폴더 노드는 제외하고, 라인경계가 있는 코드 심볼만 조회. 파싱 안정성을 위해 따옴표 없는 쿼리 유지.
    private static final String BOUNDARY_QUERY =
            "MATCH (n) WHERE n.filePath IS NOT NULL AND n.startLine IS NOT NULL "
          + "AND n.endLine IS NOT NULL AND NOT n:File AND NOT n:Folder "
          + "RETURN n.filePath AS f, n.startLine AS s, n.endLine AS e, n.name AS nm";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 한 심볼의 라인 경계(1-based, GitNexus 기준). */
    public record SymbolBoundary(int startLine, int endLine, String name) {
    }

    /**
     * 심볼 경계를 파일경로 기준으로 반환한다. 어떤 실패에도 예외 없이 빈 맵을 돌려준다.
     *
     * @param repo GitNexus 저장소명(null/blank 이면 즉시 빈 맵)
     * @return key = 정규화된 파일경로('/'), value = 해당 파일의 경계 목록(정렬 안 됨)
     */
    public Map<String, List<SymbolBoundary>> loadBoundaries(String repo) {
        if (repo == null || repo.isBlank()) {
            log.info("[색인] GitNexus repo 미지정 → 경계 조회 생략(토큰 분할로 폴백).");
            return Map.of();
        }
        try {
            String output = runCli(repo);
            if (output == null || output.isBlank()) {
                return Map.of();
            }
            Map<String, List<SymbolBoundary>> boundaries = parse(output);
            log.info("[색인] GitNexus 경계 로드: 파일 {}개", boundaries.size());
            return boundaries;
        } catch (Exception e) {
            // 미설치/권한/네트워크/기타 모든 예외를 흡수. 색인은 계속되어야 한다.
            log.warn("[색인] GitNexus 경계 조회 실패 → 토큰 분할로 폴백. 원인: {}", e.getMessage());
            return Map.of();
        }
    }

    private String runCli(String repo) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(buildCommand(repo));
        // stderr 는 별도로 두고(진행 메시지 등) stdout(JSON)만 읽는다.
        Process process = pb.start();
        byte[] stdout = process.getInputStream().readAllBytes();
        boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            log.warn("[색인] GitNexus 경계 조회 타임아웃({}s) → 폴백.", TIMEOUT_SECONDS);
            return null;
        }
        if (process.exitValue() != 0) {
            log.warn("[색인] GitNexus CLI 비정상 종료(exit={}) → 폴백.", process.exitValue());
            return null;
        }
        return new String(stdout, StandardCharsets.UTF_8);
    }

    private List<String> buildCommand(String repo) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<String> command = new ArrayList<>();
        if (windows) {
            // Windows 에서 npx 는 npx.cmd 라 셸 경유 필요.
            command.add("cmd");
            command.add("/c");
        }
        command.add("npx");
        command.add("gitnexus");
        command.add("cypher");
        command.add("-r");
        command.add(repo);
        command.add(BOUNDARY_QUERY);
        return command;
    }

    /**
     * CLI JSON 출력에서 markdown 파이프 테이블을 파싱한다.
     * 컬럼 순서: f(파일경로), s(시작줄), e(끝줄), nm(심볼명).
     */
    private Map<String, List<SymbolBoundary>> parse(String output) throws Exception {
        JsonNode root = objectMapper.readTree(output);
        JsonNode markdownNode = root.get("markdown");
        if (markdownNode == null || markdownNode.isNull()) {
            return Map.of();
        }
        Map<String, List<SymbolBoundary>> result = new HashMap<>();
        for (String line : markdownNode.asText().split("\n")) {
            if (!line.startsWith("|")) {
                continue; // 테이블 행이 아님
            }
            String[] cols = splitRow(line);
            if (cols.length < 3) {
                continue;
            }
            // 헤더행("f"...) / 구분행("---") 스킵
            if (cols[0].equals("f") || cols[0].startsWith("---")) {
                continue;
            }
            try {
                String filePath = cols[0].replace('\\', '/').trim();
                int start = Integer.parseInt(cols[1]);
                int end = Integer.parseInt(cols[2]);
                String name = cols.length > 3 ? cols[3] : "";
                if (filePath.isEmpty() || end < start) {
                    continue;
                }
                result.computeIfAbsent(filePath, k -> new ArrayList<>())
                      .add(new SymbolBoundary(start, end, name));
            } catch (NumberFormatException ignored) {
                // 숫자가 아닌 행(헤더 잔여 등) → 스킵
            }
        }
        return result;
    }

    private String[] splitRow(String row) {
        String body = row.trim();
        if (body.startsWith("|")) {
            body = body.substring(1);
        }
        if (body.endsWith("|")) {
            body = body.substring(0, body.length() - 1);
        }
        String[] parts = body.split("\\|", -1);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }
}
