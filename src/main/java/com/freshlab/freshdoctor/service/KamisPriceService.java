package com.freshlab.freshdoctor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freshlab.freshdoctor.domain.PriceHistory;
import com.freshlab.freshdoctor.domain.Item;
import com.freshlab.freshdoctor.dto.CurrentPriceResponse;
import com.freshlab.freshdoctor.dto.KamisPriceCollectResult;
import com.freshlab.freshdoctor.dto.PriceChangeResponse;
import com.freshlab.freshdoctor.dto.PricePointResponse;
import com.freshlab.freshdoctor.dto.PriceResponse;
import com.freshlab.freshdoctor.dto.PriceTrendResponse;
import com.freshlab.freshdoctor.exception.InvalidPricePeriodException;
import com.freshlab.freshdoctor.repository.PriceHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class KamisPriceService {

    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter KAMIS_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final PriceHistoryRepository priceHistoryRepository;
    private final ItemService itemService;
    private final PriceValueNormalizer priceValueNormalizer;
    private final PriceDateRangeResolver priceDateRangeResolver;
    private final PriceIncreaseRateCalculator priceIncreaseRateCalculator;

    @Value("${kamis.api.base-url:https://www.kamis.or.kr/service/price/xml.do}")
    private String baseUrl;

    @Value("${kamis.api.cert-key}")
    private String certKey;

    @Value("${kamis.api.cert-id}")
    private String certId;

    public KamisPriceCollectResult collectDailyPrice(String itemCode) {
        return collectDailyPrice(itemCode, null);
    }

    public KamisPriceCollectResult collectDailyPrice(String itemCode, LocalDate regDate) {
        return collectDailyPrice(itemCode, regDate, null, null, null, null, null, null, null);
    }

    public KamisPriceCollectResult collectDailyPrice(
            String itemCode,
            LocalDate regDate,
            String productClsCode,
            String itemCategoryCode,
            String kamisItemCode,
            String kindCode,
            String productRankCode,
            String countryCode,
            String convertKgYn
    ) {
        if (isBlank(itemCode)) {
            return new KamisPriceCollectResult(itemCode, 0, 0, "itemCode is required.");
        }

        Item item = itemService.getItem(itemCode);
        try {
            KamisRequest request = resolveRequest(
                    item,
                    regDate,
                    productClsCode,
                    itemCategoryCode,
                    kamisItemCode,
                    kindCode,
                    productRankCode,
                    countryCode,
                    convertKgYn
            );
            KamisResponse response = requestPeriodPrices(request);
            List<KamisPriceRow> rawRows = new ArrayList<>();
            collectPeriodRows(response.root(), request, rawRows);
            List<KamisPriceRow> rows = aggregateDailyPrices(rawRows);

            NormalYearPrice normalYearPrice;
            try {
                normalYearPrice = requestNormalYearPrice(request);
            } catch (Exception ignored) {
                normalYearPrice = null;
            }
            if (normalYearPrice != null && !rows.isEmpty()) {
                String sourceUnit = normalYearPrice.unit();
                rows = rows.stream()
                        .map(row -> convertRowUnit(row, sourceUnit, request.unit()))
                        .filter(Objects::nonNull)
                        .toList();
                Integer convertedNormalYearPrice = convertPriceUnit(
                        normalYearPrice.price(), sourceUnit, request.unit()
                );
                rows = new ArrayList<>(rows);
                int latestIndex = rows.size() - 1;
                rows.set(latestIndex, rows.get(latestIndex).withNormalYearPrice(convertedNormalYearPrice));
            }

            if (rows.isEmpty()) {
                return new KamisPriceCollectResult(
                        itemCode,
                        0,
                        0,
                        "KAMIS price collection returned no parsable rows. responsePreview=" + response.preview()
                );
            }

            int savedCount = 0;
            for (KamisPriceRow row : rows) {
                saveOrUpdate(row);
                savedCount++;
            }

            return new KamisPriceCollectResult(itemCode, rows.size(), savedCount, "KAMIS price collection completed.");
        } catch (Exception ex) {
            return new KamisPriceCollectResult(itemCode, 0, 0, "KAMIS price collection failed: " + ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<PriceResponse> getPrices(String itemCode, LocalDate startDate, LocalDate endDate) {
        Item item = itemService.getItem(itemCode);
        PriceDateRangeResolver.DateRange dateRange = priceDateRangeResolver.resolve(startDate, endDate);

        return priceHistoryRepository
                .findByItemCodeAndMarketTypeAndKamisRankCodeAndUnitAndPriceDateBetweenOrderByPriceDateAsc(
                        itemCode,
                        item.getDefaultMarketType(),
                        item.getDefaultRankCode(),
                        item.getDefaultUnit(),
                        dateRange.startDate(),
                        dateRange.endDate()
                )
                .stream()
                .map(PriceResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public PriceTrendResponse getPriceTrend(String itemCode, int days) {
        if (days != 7 && days != 14 && days != 30) {
            throw new InvalidPricePeriodException();
        }

        Item item = itemService.getItem(itemCode);
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days - 1L);

        List<PriceHistory> prices = priceHistoryRepository
                .findByItemCodeAndMarketTypeAndKamisRankCodeAndUnitAndPriceDateBetweenOrderByPriceDateAsc(
                        itemCode,
                        item.getDefaultMarketType(),
                        item.getDefaultRankCode(),
                        item.getDefaultUnit(),
                        startDate,
                        endDate
                );

        Map<LocalDate, PriceHistory> pricesByDate = new LinkedHashMap<>();
        for (PriceHistory price : prices) {
            pricesByDate.put(price.getPriceDate(), price);
        }

        PriceHistory seedPrice = priceHistoryRepository
                .findTopByItemCodeAndMarketTypeAndKamisRankCodeAndUnitAndPriceDateLessThanEqualOrderByPriceDateDesc(
                        itemCode,
                        item.getDefaultMarketType(),
                        item.getDefaultRankCode(),
                        item.getDefaultUnit(),
                        startDate
                )
                .orElse(null);
        PriceHistory lastActualPrice = seedPrice;

        List<PricePointResponse> trend = new ArrayList<>();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            PriceHistory actualPrice = pricesByDate.get(date);
            if (actualPrice != null) {
                lastActualPrice = actualPrice;
                trend.add(toTrendPoint(date, actualPrice, false));
            } else if (lastActualPrice != null) {
                trend.add(toTrendPoint(date, lastActualPrice, true));
            }
        }
        PriceHistory latestActualPrice = lastActualPrice;

        CurrentPriceResponse current = null;
        if (!trend.isEmpty()) {
            PricePointResponse latestPoint = trend.get(trend.size() - 1);
            current = new CurrentPriceResponse(
                    latestPoint.price(),
                    latestActualPrice.getUnit(),
                    endDate,
                    latestPoint.actualPriceDate(),
                    latestPoint.carriedForward()
            );
        }

        List<PriceHistory> relevantActualPrices = new ArrayList<>(prices);
        if (seedPrice != null) {
            relevantActualPrices.add(seedPrice);
        }

        Integer normalPrice = relevantActualPrices.stream()
                .filter(price -> price.getNormalYearPrice() != null)
                .max(Comparator.comparing(PriceHistory::getPriceDate))
                .map(PriceHistory::getNormalYearPrice)
                .orElse(null);

        PriceChangeResponse priceChange = getRecentSevenPriceChange(item);

        LocalDateTime lastUpdatedAt = relevantActualPrices.stream()
                .map(PriceHistory::getUpdatedAt)
                .filter(java.util.Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);

        return new PriceTrendResponse(
                item.getItemCode(),
                item.getItemName(),
                current,
                normalPrice,
                priceChange,
                lastUpdatedAt,
                trend
        );
    }

    private PriceChangeResponse getRecentSevenPriceChange(Item item) {
        List<PriceHistory> recentPrices = priceHistoryRepository
                .findTop7ByItemCodeAndMarketTypeAndKamisRankCodeAndUnitOrderByPriceDateDesc(
                        item.getItemCode(),
                        item.getDefaultMarketType(),
                        item.getDefaultRankCode(),
                        item.getDefaultUnit()
                );

        if (recentPrices.size() < 7) {
            return null;
        }

        PriceHistory latest = recentPrices.get(0);
        PriceHistory previous = recentPrices.get(recentPrices.size() - 1);
        if (previous.getPrice() == null || previous.getPrice() <= 0
                || latest.getPrice() == null || latest.getPrice() <= 0) {
            return null;
        }
        return priceIncreaseRateCalculator.calculate(
                previous.getPrice(),
                previous.getPriceDate(),
                latest.getPrice(),
                latest.getPriceDate()
        );
    }

    private PricePointResponse toTrendPoint(
            LocalDate displayDate,
            PriceHistory actualPrice,
            boolean carriedForward
    ) {
        return new PricePointResponse(
                displayDate,
                actualPrice.getPrice(),
                actualPrice.getPriceDate(),
                carriedForward
        );
    }

    @Transactional(readOnly = true)
    public List<PriceResponse> getPricesByItemName(String itemName, LocalDate startDate, LocalDate endDate) {
        PriceDateRangeResolver.DateRange dateRange = priceDateRangeResolver.resolve(startDate, endDate);

        return priceHistoryRepository
                .findByItemNameContainingAndPriceDateBetweenOrderByPriceDateAsc(
                        itemName,
                        dateRange.startDate(),
                        dateRange.endDate()
                )
                .stream()
                .map(PriceResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PriceResponse> getPriceTrendByItemName(String itemName, int days) {
        int resolvedDays = Math.max(days, 1);
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(resolvedDays);

        List<PriceHistory> prices = priceHistoryRepository
                .findByItemNameContainingAndPriceDateBetweenOrderByPriceDateAsc(itemName, startDate, endDate);

        if (prices.isEmpty()) {
            prices = priceHistoryRepository.findTop60ByItemNameContainingOrderByPriceDateDesc(itemName)
                    .stream()
                    .sorted(Comparator.comparing(PriceHistory::getPriceDate))
                    .toList();
        }

        return prices.stream()
                .map(PriceResponse::from)
                .toList();
    }

    private KamisResponse requestPeriodPrices(KamisRequest request) throws Exception {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString(baseUrl)
                .queryParam("action", "periodProductList")
                .queryParam("p_cert_key", certKey)
                .queryParam("p_cert_id", certId)
                .queryParam("p_returntype", "json")
                .queryParam("p_productclscode", request.productClsCode())
                .queryParam("p_startday", request.regDate().minusDays(29).format(KAMIS_DATE))
                .queryParam("p_endday", request.regDate().format(KAMIS_DATE))
                .queryParam("p_itemcategorycode", request.itemCategoryCode())
                .queryParam("p_countrycode", request.countryCode())
                .queryParam("p_convert_kg_yn", "N");

        addQueryParamIfPresent(uriBuilder, "p_itemcode", request.kamisItemCode());
        addQueryParamIfPresent(uriBuilder, "p_kindcode", request.kindCode());
        addQueryParamIfPresent(uriBuilder, "p_productrankcode", request.productRankCode());

        return request(uriBuilder);
    }

    private NormalYearPrice requestNormalYearPrice(KamisRequest request) throws Exception {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString(baseUrl)
                .queryParam("action", "dailyPriceByCategoryList")
                .queryParam("p_cert_key", certKey)
                .queryParam("p_cert_id", certId)
                .queryParam("p_returntype", "json")
                .queryParam("p_product_cls_code", request.productClsCode())
                .queryParam("p_item_category_code", request.itemCategoryCode())
                .queryParam("p_country_code", request.countryCode())
                .queryParam("p_regday", request.regDate().format(KAMIS_DATE))
                .queryParam("p_convert_kg_yn", "N");

        KamisResponse response = request(uriBuilder);
        return findNormalYearPriceRow(
                response.root(),
                request.kamisItemCode(),
                request.kindCode(),
                request.productRankCode()
        );
    }

    private KamisResponse request(UriComponentsBuilder uriBuilder) throws Exception {
        URI uri = uriBuilder.build(true).toUri();
        String body = webClientBuilder.build()
                .get()
                .uri(uri)
                .exchangeToMono(response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(responseBody -> {
                            if (response.statusCode().isError()) {
                                throw new IllegalStateException(
                                        "KAMIS HTTP " + response.statusCode().value()
                                                + " responsePreview=" + preview(responseBody)
                                );
                            }
                            return responseBody;
                        }))
                .block();

        if (isBlank(body)) {
            throw new IllegalStateException("Empty KAMIS response.");
        }

        String trimmed = body.trim();
        if (trimmed.startsWith("<")) {
            throw new IllegalStateException("KAMIS returned XML/HTML instead of JSON. responsePreview=" + preview(body));
        }

        return new KamisResponse(objectMapper.readTree(body), preview(body));
    }

    private void saveOrUpdate(KamisPriceRow row) {
        String unit = defaultIfBlank(row.unit(), "UNKNOWN");
        String marketType = defaultIfBlank(row.marketType(), "UNKNOWN");
        String rankCode = defaultIfBlank(row.kamisRankCode(), "UNKNOWN");

        List<PriceHistory> existingRows = priceHistoryRepository
                .findByItemCodeAndPriceDateOrderByIdAsc(row.itemCode(), row.priceDate());
        PriceHistory history = existingRows.isEmpty() ? new PriceHistory() : existingRows.get(0);
        if (existingRows.size() > 1) {
            priceHistoryRepository.deleteAll(existingRows.subList(1, existingRows.size()));
        }
        history.setItemCode(row.itemCode());
        history.setItemName(row.itemName());
        history.setKamisItemCode(row.kamisItemCode());
        history.setKamisKindCode(row.kamisKindCode());
        history.setKamisRankCode(rankCode);
        history.setPriceDate(row.priceDate());
        history.setPrice(row.price());
        if (row.normalYearPrice() != null) {
            history.setNormalYearPrice(row.normalYearPrice());
        }
        history.setUnit(unit);
        history.setMarketType(marketType);
        history.setSource("KAMIS");
        // Do not keep a single transaction open for the complete KAMIS response.
        // If one malformed row violates a database constraint, saveAndFlush throws
        // here and the caller can return a failed result without leaving its
        // transaction rollback-only (which previously turned the whole request into 500).
        priceHistoryRepository.saveAndFlush(history);
    }

    private void collectPeriodRows(JsonNode node, KamisRequest request, List<KamisPriceRow> rows) {
        if (node == null || node.isNull()) {
            return;
        }

        if (node.isTextual()) {
            JsonNode parsedTextNode = parseJsonTextNode(node.asText());
            if (parsedTextNode != null) {
                collectPeriodRows(parsedTextNode, request, rows);
            }
            return;
        }

        if (node.isObject()) {
            KamisPriceRow row = toPeriodRow(node, request);
            if (row != null) {
                rows.add(row);
            }

            Iterator<JsonNode> children = node.elements();
            while (children.hasNext()) {
                collectPeriodRows(children.next(), request, rows);
            }
            return;
        }

        if (node.isArray()) {
            for (JsonNode child : node) {
                collectPeriodRows(child, request, rows);
            }
        }
    }

    private KamisPriceRow toPeriodRow(JsonNode node, KamisRequest request) {
        String responseItemName = firstText(node, "itemname");
        Integer price = priceValueNormalizer.normalize(firstText(node, "price"));
        LocalDate priceDate = parsePeriodDate(firstText(node, "yyyy"), firstText(node, "regday"));
        if (price == null) {
            return null;
        }
        if (isBlank(responseItemName) || priceDate == null) {
            return null;
        }
        String itemName = defaultIfBlank(request.internalItemName(), responseItemName);

        return new KamisPriceRow(
                request.internalItemCode(),
                itemName,
                request.kamisItemCode(),
                request.kindCode(),
                request.productRankCode(),
                priceDate,
                price,
                null,
                request.unit(),
                request.marketType()
        );
    }

    private List<KamisPriceRow> aggregateDailyPrices(List<KamisPriceRow> rawRows) {
        Map<LocalDate, List<KamisPriceRow>> rowsByDate = new LinkedHashMap<>();
        rawRows.stream()
                .sorted(Comparator.comparing(KamisPriceRow::priceDate))
                .forEach(row -> rowsByDate.computeIfAbsent(row.priceDate(), ignored -> new ArrayList<>()).add(row));

        List<KamisPriceRow> result = new ArrayList<>();
        for (List<KamisPriceRow> dailyRows : rowsByDate.values()) {
            List<Integer> prices = dailyRows.stream()
                    .map(KamisPriceRow::price)
                    .filter(Objects::nonNull)
                    .sorted()
                    .toList();
            if (prices.isEmpty()) {
                continue;
            }
            int middle = prices.size() / 2;
            int representativePrice = prices.size() % 2 == 1
                    ? prices.get(middle)
                    : (int) Math.round((prices.get(middle - 1).longValue() + prices.get(middle).longValue()) / 2.0);
            result.add(dailyRows.get(0).withPrice(representativePrice));
        }
        return result;
    }

    Integer findNormalYearPrice(JsonNode node, String requestedItemCode, String requestedKindCode, String requestedRankCode) {
        NormalYearPrice row = findNormalYearPriceRow(
                node, requestedItemCode, requestedKindCode, requestedRankCode
        );
        return row == null ? null : row.price();
    }

    private NormalYearPrice findNormalYearPriceRow(
            JsonNode node,
            String requestedItemCode,
            String requestedKindCode,
            String requestedRankCode
    ) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            String itemCode = firstText(node, "itemcode", "item_code");
            String kindCode = firstText(node, "kindcode", "kind_code");
            String rankCode = firstText(node, "rankcode", "rank_code");
            boolean itemMatches = isBlank(requestedItemCode) || requestedItemCode.equals(itemCode);
            boolean kindMatches = isBlank(requestedKindCode) || isBlank(kindCode) || requestedKindCode.equals(kindCode);
            boolean rankMatches = isBlank(requestedRankCode)
                    || isBlank(rankCode)
                    || requestedRankCode.equals(rankCode);
            if (itemMatches && kindMatches && rankMatches) {
                Integer normalYearPrice = priceValueNormalizer.normalize(firstText(node, "dpr7"));
                if (normalYearPrice != null) {
                    return new NormalYearPrice(normalYearPrice, firstText(node, "unit"));
                }
            }
            Iterator<JsonNode> children = node.elements();
            while (children.hasNext()) {
                NormalYearPrice found = findNormalYearPriceRow(
                        children.next(), requestedItemCode, requestedKindCode, requestedRankCode
                );
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                NormalYearPrice found = findNormalYearPriceRow(
                        child, requestedItemCode, requestedKindCode, requestedRankCode
                );
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    Integer convertPriceUnit(Integer price, String sourceUnit, String targetUnit) {
        if (price == null) {
            return null;
        }
        if (Objects.equals(sourceUnit, targetUnit)) {
            return price;
        }
        if (isBlank(sourceUnit) || isBlank(targetUnit)) {
            return null;
        }

        Integer sourceGrams = weightInGrams(sourceUnit);
        Integer targetGrams = weightInGrams(targetUnit);
        if (sourceGrams == null || targetGrams == null) {
            return null;
        }
        return BigDecimal.valueOf(price)
                .multiply(BigDecimal.valueOf(targetGrams))
                .divide(BigDecimal.valueOf(sourceGrams), 0, RoundingMode.HALF_UP)
                .intValueExact();
    }

    private KamisPriceRow convertRowUnit(KamisPriceRow row, String sourceUnit, String targetUnit) {
        Integer convertedPrice = convertPriceUnit(row.price(), sourceUnit, targetUnit);
        return convertedPrice == null ? null : row.withPrice(convertedPrice);
    }

    private Integer weightInGrams(String unit) {
        String normalized = unit.trim().toLowerCase().replace(" ", "");
        if (normalized.matches("\\d+kg")) {
            return Integer.parseInt(normalized.substring(0, normalized.length() - 2)) * 1_000;
        }
        if (normalized.matches("\\d+g")) {
            return Integer.parseInt(normalized.substring(0, normalized.length() - 1));
        }
        return null;
    }

    private LocalDate parsePeriodDate(String year, String regday) {
        LocalDate direct = parseDate(regday);
        if (direct != null && !isBlank(regday) && regday.trim().matches("\\d{4}.*")) {
            return direct;
        }
        if (isBlank(year) || isBlank(regday)) {
            return direct;
        }
        String normalizedDay = regday.trim().replace('.', '-').replace('/', '-');
        return parseDate(year.trim() + "-" + normalizedDay);
    }

    private JsonNode parseJsonTextNode(String value) {
        if (isBlank(value)) {
            return null;
        }

        String trimmed = value.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return null;
        }

        try {
            return objectMapper.readTree(trimmed);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String firstText(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private LocalDate parseDate(String value) {
        if (isBlank(value)) {
            return null;
        }

        String normalized = value.trim().replace(".", "-").replace("/", "-");
        try {
            if (normalized.matches("\\d{8}")) {
                return LocalDate.parse(normalized, BASIC_DATE);
            }
            if (normalized.matches("\\d{1,2}-\\d{1,2}")) {
                String[] parts = normalized.split("-");
                return LocalDate.of(LocalDate.now().getYear(), Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            }
            if (normalized.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) {
                String[] parts = normalized.split("-");
                return LocalDate.of(
                        Integer.parseInt(parts[0]),
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2])
                );
            }
            return LocalDate.parse(normalized);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void addQueryParamIfPresent(UriComponentsBuilder uriBuilder, String name, String value) {
        if (!isBlank(value)) {
            uriBuilder.queryParam(name, value);
        }
    }

    private KamisRequest resolveRequest(
            Item item,
            LocalDate regDate,
            String productClsCode,
            String itemCategoryCode,
            String kamisItemCode,
            String kindCode,
            String productRankCode,
            String countryCode,
            String convertKgYn
    ) {
        LocalDate resolvedRegDate = regDate == null ? LocalDate.now() : regDate;
        return new KamisRequest(
                item.getItemCode(),
                item.getItemName(),
                resolvedRegDate,
                defaultIfBlank(productClsCode, "01"),
                defaultIfBlank(itemCategoryCode, item.getKamisCategoryCode()),
                defaultIfBlank(kamisItemCode, item.getKamisItemCode()),
                defaultIfBlank(kindCode, item.getKamisKindCode()),
                defaultIfBlank(productRankCode, item.getDefaultRankCode()),
                defaultIfBlank(countryCode, "1101"),
                defaultIfBlank(convertKgYn, "Y"),
                defaultIfBlank(item.getDefaultUnit(), "UNKNOWN"),
                defaultIfBlank(item.getDefaultMarketType(), "UNKNOWN")
        );
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value;
    }

    private String preview(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.substring(0, Math.min(normalized.length(), 500));
    }

    private record KamisResponse(JsonNode root, String preview) {
    }

    private record NormalYearPrice(Integer price, String unit) {
    }

    private record KamisRequest(
            String internalItemCode,
            String internalItemName,
            LocalDate regDate,
            String productClsCode,
            String itemCategoryCode,
            String kamisItemCode,
            String kindCode,
            String productRankCode,
            String countryCode,
            String convertKgYn,
            String unit,
            String marketType
    ) {
    }

    private record KamisPriceRow(
            String itemCode,
            String itemName,
            String kamisItemCode,
            String kamisKindCode,
            String kamisRankCode,
            LocalDate priceDate,
            Integer price,
            Integer normalYearPrice,
            String unit,
            String marketType
    ) {
        KamisPriceRow withPrice(int value) {
            return new KamisPriceRow(itemCode, itemName, kamisItemCode, kamisKindCode, kamisRankCode,
                    priceDate, value, normalYearPrice, unit, marketType);
        }

        KamisPriceRow withNormalYearPrice(int value) {
            return new KamisPriceRow(itemCode, itemName, kamisItemCode, kamisKindCode, kamisRankCode,
                    priceDate, price, value, unit, marketType);
        }
    }
}
