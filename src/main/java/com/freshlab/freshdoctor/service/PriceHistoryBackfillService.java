package com.freshlab.freshdoctor.service;

import com.freshlab.freshdoctor.domain.Item;
import com.freshlab.freshdoctor.dto.KamisPriceCollectResult;
import com.freshlab.freshdoctor.dto.PriceBackfillResult;
import com.freshlab.freshdoctor.repository.ItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Restores only historical KAMIS price data. Weather and news are deliberately
 * excluded because their APIs provide a current forecast/search result, not a
 * reliable historical snapshot.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceHistoryBackfillService {
    private static final int KAMIS_PERIOD_DAYS = 30;

    private final ItemRepository itemRepository;
    private final KamisPriceService kamisPriceService;

    public PriceBackfillResult backfill(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("startDate and endDate are required.");
        }
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must not be before startDate.");
        }
        if (endDate.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("endDate must not be in the future.");
        }

        List<Item> items = itemRepository.findByActiveTrueOrderByItemNameAsc();
        List<String> failures = new ArrayList<>();
        int requestCount = 0;
        int savedCount = 0;

        // KAMIS periodProductList returns the 30 days ending on requestDate.
        // Advancing by 30 days covers the requested range without daily duplicate calls.
        for (Item item : items) {
            LocalDate requestDate = firstRequestDate(startDate, endDate);
            while (true) {
                requestCount++;
                KamisPriceCollectResult result = kamisPriceService.collectDailyPrice(item.getItemCode(), requestDate);
                savedCount += result.savedCount();
                if (isFailure(result)) {
                    String failure = item.getItemCode() + "@" + requestDate + ": " + result.message();
                    failures.add(failure);
                    log.warn("Price history backfill failed. {}", failure);
                }
                if (requestDate.equals(endDate)) {
                    break;
                }
                LocalDate nextRequestDate = requestDate.plusDays(KAMIS_PERIOD_DAYS);
                requestDate = nextRequestDate.isAfter(endDate) ? endDate : nextRequestDate;
            }
        }
        return new PriceBackfillResult(startDate, endDate, items.size(), requestCount, savedCount, List.copyOf(failures));
    }

    private LocalDate firstRequestDate(LocalDate startDate, LocalDate endDate) {
        LocalDate firstWindowEnd = startDate.plusDays(KAMIS_PERIOD_DAYS - 1L);
        return firstWindowEnd.isAfter(endDate) ? endDate : firstWindowEnd;
    }

    private boolean isFailure(KamisPriceCollectResult result) {
        return result.message() != null && result.message().toLowerCase().contains("failed");
    }
}
