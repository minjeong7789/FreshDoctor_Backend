package com.freshlab.freshdoctor.repository;

import com.freshlab.freshdoctor.domain.NewsArticle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface NewsArticleRepository extends JpaRepository<NewsArticle, Long> {

    List<NewsArticle> findTop20ByItemCodeOrderByPublishedAtDescCreatedAtDesc(String itemCode);

    List<NewsArticle> findTop50ByItemCodeOrderByPublishedAtDescCreatedAtDesc(String itemCode);

    List<NewsArticle> findByItemCodeAndRepresentativeRiskFalseOrderByPublishedAtDescCreatedAtDesc(String itemCode);

    Optional<NewsArticle> findFirstByItemCodeAndRepresentativeRiskTrueOrderByNewsRiskScoreDescPublishedAtDescCreatedAtDesc(
            String itemCode
    );

    List<NewsArticle> findByItemCodeAndRepresentativeRiskTrue(String itemCode);

    List<NewsArticle> findByItemCodeAndPublishedAtAfterOrderByPublishedAtDesc(
            String itemCode,
            LocalDateTime publishedAfter
    );

    Optional<NewsArticle> findByItemCodeAndLinkHash(String itemCode, String linkHash);
}
