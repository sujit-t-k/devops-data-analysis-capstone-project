package org.ajikhoji.ajiorders.service.impl;

import lombok.RequiredArgsConstructor;
import org.ajikhoji.ajiorders.dto.response.PagedResponse;
import org.ajikhoji.ajiorders.dto.response.ProductResponse;
import org.ajikhoji.ajiorders.entity.Product;
import org.ajikhoji.ajiorders.exception.ResourceNotFoundException;
import org.ajikhoji.ajiorders.repository.ProductRepository;
import org.ajikhoji.ajiorders.service.ProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductServiceImpl implements ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductServiceImpl.class);

    private final ProductRepository productRepository;

    @Override
    public PagedResponse<ProductResponse> getAllProducts(Pageable pageable) {
        MDC.put("operation", "GET_ALL_PRODUCTS");
        try {
            log.info("Retrieving all products, page={}, size={}", pageable.getPageNumber(), pageable.getPageSize());

            Page<Product> productPage = productRepository.findAll(pageable);
            List<ProductResponse> products = productPage.getContent().stream()
                    .map(this::toProductResponse)
                    .toList();

            log.info("Retrieved {} products (page {} of {})", products.size(),
                    pageable.getPageNumber(), productPage.getTotalPages());

            return PagedResponse.<ProductResponse>builder()
                    .content(products)
                    .page(productPage.getNumber())
                    .size(productPage.getSize())
                    .totalElements(productPage.getTotalElements())
                    .totalPages(productPage.getTotalPages())
                    .last(productPage.isLast())
                    .build();
        } finally {
            MDC.remove("operation");
        }
    }

    @Override
    public ProductResponse getProductById(Long productId) {
        MDC.put("operation", "GET_PRODUCT_BY_ID");
        MDC.put("product_id", String.valueOf(productId));
        try {
            log.info("Retrieving product with id={}", productId);

            Product product = productRepository.findById(productId)
                    .orElseThrow(() -> {
                        log.warn("Product not found with id={}", productId);
                        return new ResourceNotFoundException("Product not found with id: " + productId);
                    });

            log.info("Successfully retrieved product with id={}", productId);
            return toProductResponse(product);
        } finally {
            MDC.remove("operation");
            MDC.remove("product_id");
        }
    }

    private ProductResponse toProductResponse(Product product) {
        return ProductResponse.builder()
                .productId(product.getProductId())
                .productName(product.getProductName())
                .price(product.getPrice())
                .productPurchasable(product.getProductPurchasable())
                .createdAt(product.getCreatedAt())
                .updatedAt(product.getUpdatedAt())
                .build();
    }
}
