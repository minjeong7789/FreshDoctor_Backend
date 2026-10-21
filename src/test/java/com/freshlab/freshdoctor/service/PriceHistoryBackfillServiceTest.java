package com.freshlab.freshdoctor.service;

import com.freshlab.freshdoctor.domain.Item;
import com.freshlab.freshdoctor.dto.KamisPriceCollectResult;
import com.freshlab.freshdoctor.dto.PriceBackfillResult;
import com.freshlab.freshdoctor.repository.ItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PriceHistoryBackfillServiceTest {
    @Mock ItemRepository itemRepository;
    @Mock KamisPriceService kamisPriceService;

    @Test
    void coversRangeInThirtyDayKamisWindows() {
        Item item = new Item();
        item.setItemCode("1001");
        when(itemRepository.findByActiveTrueOrderByItemNameAsc()).thenReturn(List.of(item));
        when(kamisPriceService.collectDailyPrice(eq("1001"), any()))
                .thenReturn(new KamisPriceCollectResult("1001", 30, 30, "completed"));

        PriceBackfillResult result = new PriceHistoryBackfillService(itemRepository, kamisPriceService)
                .backfill(LocalDate.of(2026, 7, 25), LocalDate.of(2026, 10, 21));

        ArgumentCaptor<LocalDate> dates = ArgumentCaptor.forClass(LocalDate.class);
        verify(kamisPriceService, times(3)).collectDailyPrice(eq("1001"), dates.capture());
        assertThat(dates.getAllValues()).containsExactly(
                LocalDate.of(2026, 8, 23), LocalDate.of(2026, 9, 22), LocalDate.of(2026, 10, 21));
        assertThat(result.requestCount()).isEqualTo(3);
        assertThat(result.savedCount()).isEqualTo(90);
        assertThat(result.failures()).isEmpty();
    }

    @Test
    void rejectsFutureEndDate() {
        PriceHistoryBackfillService service = new PriceHistoryBackfillService(itemRepository, kamisPriceService);

        assertThatThrownBy(() -> service.backfill(LocalDate.now(), LocalDate.now().plusDays(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("future");
    }
}
