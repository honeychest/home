// [AGENT] 역할: 챗봇 질문답변 턴 로그 조회 Repository | 연관파일: ChatbotTurn.java
package com.chs.springboot.domain.chatbot.repository;

import com.chs.springboot.domain.chatbot.model.ChatbotIssueType;
import com.chs.springboot.domain.chatbot.model.ChatbotTurn;
import com.chs.springboot.domain.chatbot.model.ChatbotTurnStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface ChatbotTurnRepository extends JpaRepository<ChatbotTurn, Long> {

    Optional<ChatbotTurn> findByRequestId(String requestId);

    @Query("""
            select t
            from ChatbotTurn t
            join t.conversation c
            where (:from is null or t.createdAt >= :from)
              and (:to is null or t.createdAt <= :to)
              and (:sourceEnv is null or c.sourceEnv = :sourceEnv)
              and (:pageId is null or t.pageId = :pageId)
              and (:issueType is null or t.issueType = :issueType)
              and (:status is null or t.status = :status)
              and (:minLatencyMs is null or t.latencyMs >= :minLatencyMs)
              and (
                    :keyword is null
                    or t.question like concat('%', :keyword, '%')
                    or t.answer like concat('%', :keyword, '%')
                    or t.searchQuery like concat('%', :keyword, '%')
              )
            order by t.createdAt desc
            """)
    Page<ChatbotTurn> findByFilters(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("sourceEnv") String sourceEnv,
            @Param("pageId") String pageId,
            @Param("issueType") ChatbotIssueType issueType,
            @Param("status") ChatbotTurnStatus status,
            @Param("minLatencyMs") Integer minLatencyMs,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    @Query("""
            select count(t)
            from ChatbotTurn t
            join t.conversation c
            where (:from is null or t.createdAt >= :from)
              and (:to is null or t.createdAt <= :to)
              and (:sourceEnv is null or c.sourceEnv = :sourceEnv)
              and (:pageId is null or t.pageId = :pageId)
              and (:issueType is null or t.issueType = :issueType)
              and (:status is null or t.status = :status)
              and (:minLatencyMs is null or t.latencyMs >= :minLatencyMs)
              and (
                    :keyword is null
                    or t.question like concat('%', :keyword, '%')
                    or t.answer like concat('%', :keyword, '%')
                    or t.searchQuery like concat('%', :keyword, '%')
              )
            """)
    long countByFilters(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("sourceEnv") String sourceEnv,
            @Param("pageId") String pageId,
            @Param("issueType") ChatbotIssueType issueType,
            @Param("status") ChatbotTurnStatus status,
            @Param("minLatencyMs") Integer minLatencyMs,
            @Param("keyword") String keyword
    );

    @Query("""
            select count(t)
            from ChatbotTurn t
            join t.conversation c
            where (:from is null or t.createdAt >= :from)
              and (:to is null or t.createdAt <= :to)
              and (:sourceEnv is null or c.sourceEnv = :sourceEnv)
              and (:pageId is null or t.pageId = :pageId)
              and (:issueType is null or t.issueType = :issueType)
              and (:status is null or t.status = :status)
              and (:minLatencyMs is null or t.latencyMs >= :minLatencyMs)
              and (
                    :keyword is null
                    or t.question like concat('%', :keyword, '%')
                    or t.answer like concat('%', :keyword, '%')
                    or t.searchQuery like concat('%', :keyword, '%')
              )
              and (t.status = com.chs.springboot.domain.chatbot.model.ChatbotTurnStatus.ERROR
                   or t.issueType <> com.chs.springboot.domain.chatbot.model.ChatbotIssueType.NONE)
            """)
    long countSuspectedByFilters(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("sourceEnv") String sourceEnv,
            @Param("pageId") String pageId,
            @Param("issueType") ChatbotIssueType issueType,
            @Param("status") ChatbotTurnStatus status,
            @Param("minLatencyMs") Integer minLatencyMs,
            @Param("keyword") String keyword
    );

    @Query("""
            select avg(t.latencyMs)
            from ChatbotTurn t
            join t.conversation c
            where (:from is null or t.createdAt >= :from)
              and (:to is null or t.createdAt <= :to)
              and (:sourceEnv is null or c.sourceEnv = :sourceEnv)
              and (:pageId is null or t.pageId = :pageId)
              and (:issueType is null or t.issueType = :issueType)
              and (:status is null or t.status = :status)
              and (:minLatencyMs is null or t.latencyMs >= :minLatencyMs)
              and (
                    :keyword is null
                    or t.question like concat('%', :keyword, '%')
                    or t.answer like concat('%', :keyword, '%')
                    or t.searchQuery like concat('%', :keyword, '%')
              )
              and t.latencyMs is not null
            """)
    Double averageLatencyByFilters(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("sourceEnv") String sourceEnv,
            @Param("pageId") String pageId,
            @Param("issueType") ChatbotIssueType issueType,
            @Param("status") ChatbotTurnStatus status,
            @Param("minLatencyMs") Integer minLatencyMs,
            @Param("keyword") String keyword
    );

    @Query("""
            select count(t)
            from ChatbotTurn t
            join t.conversation c
            where (:from is null or t.createdAt >= :from)
              and (:to is null or t.createdAt <= :to)
              and (:sourceEnv is null or c.sourceEnv = :sourceEnv)
              and (:pageId is null or t.pageId = :pageId)
              and (:issueType is null or t.issueType = :issueType)
              and (:status is null or t.status = :status)
              and (:minLatencyMs is null or t.latencyMs >= :minLatencyMs)
              and (
                    :keyword is null
                    or t.question like concat('%', :keyword, '%')
                    or t.answer like concat('%', :keyword, '%')
                    or t.searchQuery like concat('%', :keyword, '%')
              )
              and t.latencyMs >= :thresholdMs
            """)
    long countSlowByFilters(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("sourceEnv") String sourceEnv,
            @Param("pageId") String pageId,
            @Param("issueType") ChatbotIssueType issueType,
            @Param("status") ChatbotTurnStatus status,
            @Param("minLatencyMs") Integer minLatencyMs,
            @Param("keyword") String keyword,
            @Param("thresholdMs") int thresholdMs
    );
}
