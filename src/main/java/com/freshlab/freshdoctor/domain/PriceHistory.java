package com.freshlab.freshdoctor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "price_history",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_price_item_date",
                columnNames = {"item_code", "price_date"}
        )
)
@Getter
@Setter
@NoArgsConstructor
public class PriceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "item_code", nullable = false)
    private String itemCode;

    @Column(name = "item_name", nullable = false)
    private String itemName;

    @Column(name = "kamis_item_code")
    private String kamisItemCode;

    @Column(name = "kamis_kind_code")
    private String kamisKindCode;

    @Column(name = "kamis_rank_code", nullable = false)
    private String kamisRankCode = "UNKNOWN";

    @Column(name = "price_date", nullable = false)
    private LocalDate priceDate;

    @Column(name = "price", nullable = false)
    private Integer price;

    @Column(name = "normal_year_price")
    private Integer normalYearPrice;

    @Column(name = "unit", nullable = false)
    private String unit = "UNKNOWN";

    @Column(name = "market_type", nullable = false)
    private String marketType = "UNKNOWN";

    @Column(name = "source", nullable = false)
    private String source = "KAMIS";

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
