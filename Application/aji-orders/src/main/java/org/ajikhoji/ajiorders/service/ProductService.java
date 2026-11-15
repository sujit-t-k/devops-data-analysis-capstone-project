package org.ajikhoji.ajiorders.service;

import org.ajikhoji.ajiorders.dto.response.PagedResponse;
import org.ajikhoji.ajiorders.dto.response.ProductResponse;
import org.springframework.data.domain.Pageable;

public interface ProductService {

    PagedResponse<ProductResponse> getAllProducts(Pageable pageable);

    ProductResponse getProductById(Long productId);
}
