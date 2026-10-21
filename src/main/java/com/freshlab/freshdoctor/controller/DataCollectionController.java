package com.freshlab.freshdoctor.controller;

import com.freshlab.freshdoctor.dto.PriceBackfillResult;
import com.freshlab.freshdoctor.domain.SchedulerRun;
import com.freshlab.freshdoctor.service.DataCollectionJobService;
import com.freshlab.freshdoctor.service.PriceHistoryBackfillService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;

@RestController
@RequestMapping("/api/data-collection")
@RequiredArgsConstructor
public class DataCollectionController {
    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");

    private final PriceHistoryBackfillService priceHistoryBackfillService;
    private final DataCollectionJobService dataCollectionJobService;

    @PostMapping("/prices/backfill")
    public PriceBackfillResult backfillPrices(
            @RequestParam(name = "startDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "endDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        return priceHistoryBackfillService.backfill(startDate, endDate);
    }

    @PostMapping("/current")
    public SchedulerRun collectCurrentData() {
        SchedulerRun run = dataCollectionJobService.run(LocalDate.now(SEOUL_ZONE));
        if (run == null) {
            throw new IllegalStateException("Today's data collection has already run.");
        }
        return run;
    }
}
