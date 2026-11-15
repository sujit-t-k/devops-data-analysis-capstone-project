package org.ajikhoji.ajiorders.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
public class ProductResponse {

    private Long productId;
    private String productName;
    private BigDecimal price;
    private Boolean productPurchasable;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
