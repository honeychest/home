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

    public static class Reindex {
        private List<String> includeExtensions = new ArrayList<>(List.of(
                ".java", ".html", ".md", ".tsx", ".jsx", ".ts", ".js"));
        private int chunkSize = 256;
        private int minChunkSizeChars = 200;
        private int minChunkLengthToEmbed = 5;
        private int maxNumChunks = 10000;
        private boolean keepSeparator = true;
        private int batchSize = 50;

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
    }
}
