// [AGENT] 역할: GitNexus 심볼 경계로 코드를 메서드/클래스 단위로 청킹하는 Adapter | 연관파일: GitNexusBoundaryProvider.java, CodebaseDocumentChunker.java
package com.chs.springboot.domain.chatbot.service;

import com.chs.springboot.domain.chatbot.config.ChatbotProperties;
import com.chs.springboot.domain.chatbot.service.GitNexusBoundaryProvider.SymbolBoundary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 심볼 인지 청커 (전략 SYMBOL_AWARE 일 때 사용).
 *
 * 핵심 아이디어("리프 우선"):
 *   - GitNexus 가 준 심볼 경계 중 "가장 안쪽(작은) 심볼"을 우선 청크 단위로 채택한다.
 *     → 메서드/함수가 클래스보다 먼저 라인을 점유하므로, 클래스는 메서드가 덮지 않은
 *       빈 줄(패키지/임포트/필드/선언부)에만 'gap 청크'로 남는다. 내용 중복이 없다.
 *   - 너무 작은 청크는 인접끼리 병합하고, 너무 큰 심볼은 토큰 분할로 다시 쪼갠다.
 *   - 인접 청크에 오버랩을 부여해 경계 손실을 줄인다.
 *
 * 안전장치(GitNexus 무관하게 색인이 깨지지 않도록):
 *   - 파일에 경계가 없으면 → 그 파일은 토큰 분할로 폴백.
 *   - 경계의 끝줄이 실제 파일 줄 수를 넘으면(stale 의심) → 그 파일은 토큰 분할로 폴백.
 *   - source 메타데이터가 없으면 → 토큰 분할로 폴백.
 */
@Component
public class SymbolAwareChunker {

    private static final Logger log = LoggerFactory.getLogger(SymbolAwareChunker.class);

    // 토큰 수의 대략 추정(영문/코드 기준 1토큰 ≈ 4자). 병합/오버랩 임계 계산용 근사치.
    private static final int APPROX_CHARS_PER_TOKEN = 4;

    private final ChatbotProperties properties;
    private final GitNexusBoundaryProvider boundaryProvider;

    public SymbolAwareChunker(ChatbotProperties properties, GitNexusBoundaryProvider boundaryProvider) {
        this.properties = properties;
        this.boundaryProvider = boundaryProvider;
    }

    public List<Document> chunk(List<Document> documents) {
        ChatbotProperties.Reindex reindex = properties.getReindex();
        Map<String, List<SymbolBoundary>> boundaries = boundaryProvider.loadBoundaries(reindex.getGitnexusRepo());

        List<Document> result = new ArrayList<>();
        int symbolFiles = 0;
        int fallbackFiles = 0;
        for (Document doc : documents) {
            List<Document> chunks = chunkDocument(doc, boundaries, reindex);
            if (chunks == null) {
                result.addAll(tokenSplit(doc, reindex));
                fallbackFiles++;
            } else {
                result.addAll(chunks);
                symbolFiles++;
            }
        }
        log.info("[색인] 심볼 청킹: 심볼기반 {}개 / 토큰폴백 {}개 파일", symbolFiles, fallbackFiles);
        return result;
    }

    /**
     * 한 문서를 심볼 경계로 청킹한다. 심볼 기반이 불가하면 null 을 반환해 호출자가 토큰 폴백하게 한다.
     */
    private List<Document> chunkDocument(Document doc, Map<String, List<SymbolBoundary>> boundaries,
                                         ChatbotProperties.Reindex reindex) {
        Object source = doc.getMetadata().get("source");
        if (source == null) {
            return null;
        }
        String normalized = source.toString().replace('\\', '/');
        List<SymbolBoundary> fileBoundaries = boundaries.get(normalized);
        if (fileBoundaries == null || fileBoundaries.isEmpty()) {
            return null;
        }

        String content = doc.getText();
        if (content == null || content.isBlank()) {
            return null;
        }
        String[] lines = content.split("\n", -1);
        int lineCount = lines.length;

        // stale 의심: 경계가 실제 파일 길이를 벗어남 → 토큰 폴백.
        for (SymbolBoundary b : fileBoundaries) {
            if (b.endLine() > lineCount) {
                log.debug("[색인] 경계 stale 의심({} > {}줄) → 토큰 폴백: {}", b.endLine(), lineCount, normalized);
                return null;
            }
        }

        List<Segment> segments = buildSegments(fileBoundaries, lineCount);
        List<Chunk> chunks = mergeAndSplit(segments, lines, reindex);
        applyOverlap(chunks, lines, reindex);
        return toDocuments(chunks, doc);
    }

    /**
     * 리프 우선 + gap-fill: 파일의 모든 줄을 겹치지 않는 세그먼트(심볼 or 빈영역)로 덮는다.
     */
    private List<Segment> buildSegments(List<SymbolBoundary> fileBoundaries, int lineCount) {
        boolean[] claimed = new boolean[lineCount + 1]; // 1-based, index 0 미사용
        List<Segment> picked = new ArrayList<>();

        // 작은(안쪽) 심볼 우선 → 동률이면 시작줄 빠른 순.
        List<SymbolBoundary> sorted = new ArrayList<>(fileBoundaries);
        sorted.sort(Comparator
                .comparingInt((SymbolBoundary b) -> b.endLine() - b.startLine())
                .thenComparingInt(SymbolBoundary::startLine));

        for (SymbolBoundary b : sorted) {
            int start = clamp(b.startLine(), 1, lineCount);
            int end = clamp(b.endLine(), 1, lineCount);
            if (start > end) {
                continue;
            }
            boolean overlaps = false;
            for (int i = start; i <= end; i++) {
                if (claimed[i]) {
                    overlaps = true;
                    break;
                }
            }
            if (overlaps) {
                continue; // 더 안쪽 심볼이 이미 점유 → 상위 컨테이너는 통째로 스킵
            }
            for (int i = start; i <= end; i++) {
                claimed[i] = true;
            }
            picked.add(new Segment(start, end, b.name()));
        }

        // gap-fill: 어떤 심볼에도 안 덮인 줄들을 연속 구간으로 묶는다.
        int i = 1;
        while (i <= lineCount) {
            if (!claimed[i]) {
                int j = i;
                while (j <= lineCount && !claimed[j]) {
                    j++;
                }
                picked.add(new Segment(i, j - 1, null));
                i = j;
            } else {
                i++;
            }
        }

        picked.sort(Comparator.comparingInt(s -> s.start));
        return picked;
    }

    /**
     * 세그먼트를 순서대로 병합/분할해 청크를 만든다.
     *   - 큰 세그먼트(charBudget 초과)는 토큰 분할.
     *   - 작은 세그먼트는 인접끼리 charBudget 한도 내에서 병합(최소 크기 미만이면 계속 병합).
     */
    private List<Chunk> mergeAndSplit(List<Segment> segments, String[] lines, ChatbotProperties.Reindex reindex) {
        int charBudget = reindex.getChunkSize() * APPROX_CHARS_PER_TOKEN;
        int minChars = reindex.getMinChunkSizeChars();

        List<Chunk> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int bufStart = -1;
        int bufEnd = -1;
        List<String> bufSymbols = new ArrayList<>();

        for (Segment seg : segments) {
            String segText = extractText(lines, seg.start, seg.end);
            if (segText.isBlank()) {
                continue;
            }

            // 큰 세그먼트: 현재 버퍼를 먼저 비우고, 토큰 분할로 쪼갠다.
            if (segText.length() > charBudget) {
                if (buf.length() > 0) {
                    out.add(new Chunk(buf.toString(), bufStart, bufEnd, joinSymbols(bufSymbols)));
                    buf.setLength(0);
                    bufSymbols.clear();
                    bufStart = -1;
                }
                for (String piece : tokenSplitText(segText, reindex)) {
                    out.add(new Chunk(piece, seg.start, seg.end, seg.name));
                }
                continue;
            }

            if (buf.length() == 0) {
                buf.append(segText);
                bufStart = seg.start;
                bufEnd = seg.end;
                addSymbol(bufSymbols, seg.name);
            } else if (buf.length() + 1 + segText.length() <= charBudget || buf.length() < minChars) {
                buf.append("\n").append(segText);
                bufEnd = seg.end;
                addSymbol(bufSymbols, seg.name);
            } else {
                out.add(new Chunk(buf.toString(), bufStart, bufEnd, joinSymbols(bufSymbols)));
                buf.setLength(0);
                bufSymbols.clear();
                buf.append(segText);
                bufStart = seg.start;
                bufEnd = seg.end;
                addSymbol(bufSymbols, seg.name);
            }
        }
        if (buf.length() > 0) {
            out.add(new Chunk(buf.toString(), bufStart, bufEnd, joinSymbols(bufSymbols)));
        }
        return out;
    }

    /**
     * 인접 청크에 앞 청크의 꼬리(overlapTokens 근사) 를 줄 단위로 붙여 경계 손실을 줄인다.
     */
    private void applyOverlap(List<Chunk> chunks, String[] lines, ChatbotProperties.Reindex reindex) {
        int overlapChars = reindex.getOverlapTokens() * APPROX_CHARS_PER_TOKEN;
        if (overlapChars <= 0 || chunks.size() < 2) {
            return;
        }
        for (int k = 1; k < chunks.size(); k++) {
            Chunk prev = chunks.get(k - 1);
            String tail = trailingByLines(prev.text, overlapChars);
            if (!tail.isBlank()) {
                Chunk cur = chunks.get(k);
                chunks.set(k, new Chunk(tail + "\n" + cur.text, cur.startLine, cur.endLine, cur.symbol));
            }
        }
    }

    /** 텍스트의 마지막 줄들을 약 overlapChars 분량만큼(줄 경계 유지) 잘라 반환. */
    private String trailingByLines(String text, int overlapChars) {
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = lines.length - 1; i >= 0; i--) {
            if (sb.length() > 0 && sb.length() + lines[i].length() + 1 > overlapChars) {
                break;
            }
            sb.insert(0, sb.length() == 0 ? lines[i] : lines[i] + "\n");
            if (sb.length() >= overlapChars) {
                break;
            }
        }
        return sb.toString();
    }

    private List<Document> toDocuments(List<Chunk> chunks, Document source) {
        List<Document> docs = new ArrayList<>();
        for (Chunk c : chunks) {
            if (c.text.isBlank()) {
                continue;
            }
            Map<String, Object> metadata = new HashMap<>(source.getMetadata());
            metadata.put("chunkStrategy", "SYMBOL_AWARE");
            metadata.put("lines", c.startLine + "-" + c.endLine);
            if (c.symbol != null && !c.symbol.isBlank()) {
                metadata.put("symbol", c.symbol);
            }
            docs.add(new Document(c.text, metadata));
        }
        return docs;
    }

    /** 큰 텍스트 한 덩이를 토큰 분할해 문자열 청크 목록으로 반환. */
    private List<String> tokenSplitText(String text, ChatbotProperties.Reindex reindex) {
        List<Document> split = newSplitter(reindex).apply(List.of(new Document(text)));
        List<String> pieces = new ArrayList<>();
        for (Document d : split) {
            pieces.add(d.getText());
        }
        return pieces.isEmpty() ? List.of(text) : pieces;
    }

    /** 파일 전체를 토큰 분할로 폴백(메타데이터 유지). */
    private List<Document> tokenSplit(Document doc, ChatbotProperties.Reindex reindex) {
        return newSplitter(reindex).apply(List.of(doc));
    }

    private TokenTextSplitter newSplitter(ChatbotProperties.Reindex reindex) {
        return new TokenTextSplitter(
                reindex.getChunkSize(),
                reindex.getMinChunkSizeChars(),
                reindex.getMinChunkLengthToEmbed(),
                reindex.getMaxNumChunks(),
                reindex.isKeepSeparator());
    }

    /** 1-based inclusive 라인 범위 텍스트 추출(클램프 적용). */
    private String extractText(String[] lines, int start, int end) {
        int s = clamp(start, 1, lines.length);
        int e = clamp(end, 1, lines.length);
        if (s > e) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = s; i <= e; i++) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(lines[i - 1]);
        }
        return sb.toString();
    }

    private int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void addSymbol(List<String> symbols, String name) {
        if (name != null && !name.isBlank() && !symbols.contains(name)) {
            symbols.add(name);
        }
    }

    private String joinSymbols(List<String> symbols) {
        return symbols.isEmpty() ? null : String.join(",", symbols);
    }

    /** 겹치지 않는 라인 구간(심볼이면 name, gap이면 name=null). */
    private record Segment(int start, int end, String name) {
    }

    /** 최종 청크 후보(텍스트 + 라인범위 + 심볼명). */
    private static final class Chunk {
        final String text;
        final int startLine;
        final int endLine;
        final String symbol;

        Chunk(String text, int startLine, int endLine, String symbol) {
            this.text = text;
            this.startLine = startLine;
            this.endLine = endLine;
            this.symbol = symbol;
        }
    }
}
