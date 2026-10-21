package com.freshlab.freshdoctor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freshlab.freshdoctor.domain.Item;
import com.freshlab.freshdoctor.domain.NewsArticle;
import com.freshlab.freshdoctor.dto.NewsCollectResult;
import com.freshlab.freshdoctor.dto.NewsResponse;
import com.freshlab.freshdoctor.repository.NewsArticleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NaverNewsService {

    private static final String SOURCE = "NAVER";

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final NewsArticleRepository newsArticleRepository;
    private final ItemService itemService;
    private final NewsArticleFilter newsArticleFilter;

    @Value("${naver.news.url:https://openapi.naver.com/v1/search/news.json}")
    private String newsUrl;

    @Value("${naver.news.client-id:}")
    private String clientId;

    @Value("${naver.news.client-secret:}")
    private String clientSecret;

    @Transactional
    public NewsCollectResult collectNews(String itemCode, String query, int display) {
        Item item = itemService.getItem(itemCode);
        String resolvedQuery = resolveQuery(item, query);
        try {
            int resolvedDisplay = Math.min(Math.max(display, 1), 100);
            JsonNode root = requestNews(resolvedQuery, resolvedDisplay);
            JsonNode items = root.path("items");
            if (!items.isArray()) {
                return new NewsCollectResult(itemCode, resolvedQuery, 0, 0, "Naver news response has no items.");
            }

            int fetchedCount = 0;
            int savedCount = 0;
            for (JsonNode newsItem : items) {
                fetchedCount++;
                if (saveRelevantArticle(item, resolvedQuery, newsItem)) {
                    savedCount++;
                }
            }
            refreshRepresentativeRiskArticle(itemCode);

            return new NewsCollectResult(
                    itemCode,
                    resolvedQuery,
                    fetchedCount,
                    savedCount,
                    "Naver news collection completed. irrelevant articles were filtered out."
            );
        } catch (Exception ex) {
            return new NewsCollectResult(itemCode, resolvedQuery, 0, 0, "Naver news collection failed: " + ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<NewsResponse> getNews(String itemCode) {
        itemService.getItem(itemCode);
        return newsArticleRepository.findByItemCodeAndRepresentativeRiskFalseOrderByPublishedAtDescCreatedAtDesc(itemCode)
                .stream()
                .map(NewsResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public NewsResponse getRepresentativeRiskNews(String itemCode) {
        itemService.getItem(itemCode);
        return newsArticleRepository
                .findFirstByItemCodeAndRepresentativeRiskTrueOrderByNewsRiskScoreDescPublishedAtDescCreatedAtDesc(itemCode)
                .map(NewsResponse::from)
                .orElse(null);
    }

    private JsonNode requestNews(String query, int display) throws Exception {
        if (isBlank(clientId) || isBlank(clientSecret)) {
            throw new IllegalStateException("Naver news API credentials are empty.");
        }

        URI uri = UriComponentsBuilder.fromUriString(newsUrl)
                .queryParam("query", query)
                .queryParam("display", display)
                .queryParam("sort", "date")
                .build()
                .encode()
                .toUri();

        String body = webClientBuilder.build()
                .get()
                .uri(uri)
                .header("X-Naver-Client-Id", clientId)
                .header("X-Naver-Client-Secret", clientSecret)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        if (isBlank(body)) {
            throw new IllegalStateException("Empty Naver news response.");
        }
        return objectMapper.readTree(body);
    }

    private boolean saveRelevantArticle(Item item, String query, JsonNode node) throws Exception {
        String rawTitle = node.path("title").asText("");
        String rawDescription = node.path("description").asText("");
        String link = node.path("originallink").asText(node.path("link").asText(""));
        if (isBlank(link)) {
            link = node.path("link").asText("");
        }

        NewsArticleFilter.FilterResult filterResult = newsArticleFilter.filter(
                item,
                query,
                rawTitle,
                rawDescription,
                link
        );
        if (!filterResult.relevant()) {
            return false;
        }

        String linkHash = sha256(filterResult.normalizedLink());
        NewsArticle newsArticle = newsArticleRepository
                .findByItemCodeAndLinkHash(item.getItemCode(), linkHash)
                .orElseGet(NewsArticle::new);

        newsArticle.setItemCode(item.getItemCode());
        newsArticle.setQueryText(query);
        newsArticle.setTitle(filterResult.title());
        newsArticle.setLink(filterResult.normalizedLink());
        newsArticle.setLinkHash(linkHash);
        newsArticle.setDescription(filterResult.description());
        newsArticle.setMatchedKeywords(filterResult.matchedKeywords());
        newsArticle.setPublishedAt(parsePublishedAt(node.path("pubDate").asText(null)));
        newsArticle.setNewsRiskScore(filterResult.riskScore());
        newsArticle.setRepresentativeRisk(false);
        newsArticle.setSource(SOURCE);

        newsArticleRepository.save(newsArticle);
        return true;
    }

    private void refreshRepresentativeRiskArticle(String itemCode) {
        List<NewsArticle> articles = newsArticleRepository.findTop20ByItemCodeOrderByPublishedAtDescCreatedAtDesc(itemCode);
        articles.forEach(article -> article.setRepresentativeRisk(false));

        articles.stream()
                .max(Comparator
                        .comparing(NewsArticle::getNewsRiskScore, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(NewsArticle::getPublishedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(NewsArticle::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .ifPresent(article -> article.setRepresentativeRisk(true));
    }

    private String resolveQuery(Item item, String query) {
        if (!isBlank(query)) {
            return query;
        }

        if (!isBlank(item.getNewsKeyword())) {
            return item.getNewsKeyword();
        }
        return item.getItemName() + " 가격";
    }

    private LocalDateTime parsePublishedAt(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDateTime();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
