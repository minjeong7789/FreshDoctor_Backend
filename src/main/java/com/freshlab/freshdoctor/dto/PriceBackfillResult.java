package com.freshlab.freshdoctor.dto;

import java.time.LocalDate;
import java.util.List;

public record PriceBackfillResult(
        LocalDate startDate,
        LocalDate endDate,
        int itemCount,
        int requestCount,
        int savedCount,
        List<String> failures
) {
}
