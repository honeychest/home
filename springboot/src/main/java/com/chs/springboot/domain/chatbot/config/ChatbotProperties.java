// [AGENT] 역할: 코드베이스 RAG 챗봇 운영 설정 seam | 연관파일: AsyncReindexRunner.java, ChatbotService.java
package com.chs.springboot.domain.chatbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "chs.chatbot")
public class ChatbotProperties {

    // 색인 대상 루트(여러 개). 소스 폴더만 직접 지정해 node_modules/build 등을 아예 밟지 않는다.
    private List<String> indexRoots = new ArrayList<>();
    // source 메타데이터를 이 경로 기준으로 상대화(예: "frontend/src/..."). 미설정 시 각 루트 기준.
    private String sourceBase;
    private int topK = 6;
    private Reindex reindex = new Reindex();

    public List<String> getIndexRoots() {
        return indexRoots;
    }

    public void setIndexRoots(List<String> indexRoots) {
        this.indexRoots = indexRoots;
    }

    public String getSourceBase() {
        return sourceBase;
    }

    public void setSourceBase(String sourceBase) {
        this.sourceBase = sourceBase;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public Reindex getReindex() {
        return reindex;
    }

    public void setReindex(Reindex reindex) {
        this.reindex = reindex;
    }

    // 청킹 전략. TOKEN = 기존 TokenTextSplitter(맹목 토큰 분할),
    // SYMBOL_AWARE = GitNexus 심볼 경계로 메서드/클래스 단위 분할(폴백 내장).
    public enum ChunkStrategy {
        TOKEN,
        SYMBOL_AWARE
    }

    public static class Reindex {
        private List<String> includeExtensions = new ArrayList<>(List.of(
                ".java", ".html", ".md", ".tsx", ".jsx", ".ts", ".js"));
        // application.properties 와 동기화된 기본값(1단계 튜닝 반영).
        private int chunkSize = 512;
        private int minChunkSizeChars = 350;
        private int minChunkLengthToEmbed = 5;
        private int maxNumChunks = 10000;
        private boolean keepSeparator = true;
        private int batchSize = 8;
        // 2단계: 청킹 전략 토글. 기본 TOKEN(안전), SYMBOL_AWARE 는 4번 구현 후 전환.
        private ChunkStrategy chunkStrategy = ChunkStrategy.TOKEN;
        // 인접 청크 간 오버랩 토큰 수(경계 손실 방지). 0 이면 오버랩 없음.
        private int overlapTokens = 64;
        // 심볼 경계를 조회할 GitNexus 저장소명.
        private String gitnexusRepo = "lab";

        public List<String> getIncludeExtensions() {
            return includeExtensions;
        }

        public void setIncludeExtensions(List<String> includeExtensions) {
            this.includeExtensions = includeExtensions;
        }

        public int getChunkSize() {
            return chunkSize;
        }

        public void setChunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
        }

        public int getMinChunkSizeChars() {
            return minChunkSizeChars;
        }

        public void setMinChunkSizeChars(int minChunkSizeChars) {
            this.minChunkSizeChars = minChunkSizeChars;
        }

        public int getMinChunkLengthToEmbed() {
            return minChunkLengthToEmbed;
        }

        public void setMinChunkLengthToEmbed(int minChunkLengthToEmbed) {
            this.minChunkLengthToEmbed = minChunkLengthToEmbed;
        }

        public int getMaxNumChunks() {
            return maxNumChunks;
        }

        public void setMaxNumChunks(int maxNumChunks) {
            this.maxNumChunks = maxNumChunks;
        }

        public boolean isKeepSeparator() {
            return keepSeparator;
        }

        public void setKeepSeparator(boolean keepSeparator) {
            this.keepSeparator = keepSeparator;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public ChunkStrategy getChunkStrategy() {
            return chunkStrategy;
        }

        public void setChunkStrategy(ChunkStrategy chunkStrategy) {
            this.chunkStrategy = chunkStrategy;
        }

        public int getOverlapTokens() {
            return overlapTokens;
        }

        public void setOverlapTokens(int overlapTokens) {
            this.overlapTokens = overlapTokens;
        }

        public String getGitnexusRepo() {
            return gitnexusRepo;
        }

        public void setGitnexusRepo(String gitnexusRepo) {
            this.gitnexusRepo = gitnexusRepo;
        }
    }
}
