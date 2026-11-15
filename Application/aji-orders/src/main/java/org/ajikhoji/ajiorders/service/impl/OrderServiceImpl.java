package org.ajikhoji.ajiorders.service.impl;

import lombok.RequiredArgsConstructor;
import org.ajikhoji.ajiorders.dto.request.CreateOrderRequest;
import org.ajikhoji.ajiorders.dto.request.OrderItemRequest;
import org.ajikhoji.ajiorders.dto.request.UpdateOrderStatusRequest;
import org.ajikhoji.ajiorders.dto.response.OrderItemResponse;
import org.ajikhoji.ajiorders.dto.response.OrderResponse;
import org.ajikhoji.ajiorders.dto.response.PagedResponse;
import org.ajikhoji.ajiorders.entity.*;
import org.ajikhoji.ajiorders.exception.InvalidOrderException;
import org.ajikhoji.ajiorders.exception.InvalidStatusTransitionException;
import org.ajikhoji.ajiorders.exception.ResourceNotFoundException;
import org.ajikhoji.ajiorders.repository.OrderRepository;
import org.ajikhoji.ajiorders.repository.ProductRepository;
import org.ajikhoji.ajiorders.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

    private static final Map<OrderStatus, Set<OrderStatus>> VALID_TRANSITIONS;

    static {
        Map<OrderStatus, Set<OrderStatus>> transitions = new EnumMap<>(OrderStatus.class);
        transitions.put(OrderStatus.CREATED, EnumSet.of(OrderStatus.PROCESSING, OrderStatus.CANCELLED));
        transitions.put(OrderStatus.PROCESSING, EnumSet.of(OrderStatus.PROCESSED, OrderStatus.CANCELLED));
        transitions.put(OrderStatus.PROCESSED, EnumSet.noneOf(OrderStatus.class));
        transitions.put(OrderStatus.CANCELLED, EnumSet.noneOf(OrderStatus.class));
        VALID_TRANSITIONS = Collections.unmodifiableMap(transitions);
    }

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    @Override
    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
        MDC.put("operation", "CREATE_ORDER");
        try {
            log.info("Creating order for customer={}", request.getCustomerName());

            // Collect all requested product IDs
            List<Long> productIds = request.getItems().stream()
                    .map(OrderItemRequest::getProductId)
                    .toList();

            // Fetch all products in one query to prevent N+1
            List<Product> products = productRepository.findAllByProductIdIn(productIds);
            Map<Long, Product> productMap = products.stream()
                    .collect(Collectors.toMap(Product::getProductId, Function.identity()));

            // Validate all products exist and are purchasable
            for (OrderItemRequest item : request.getItems()) {
                Product product = productMap.get(item.getProductId());
                if (product == null) {
                    throw new InvalidOrderException("Product not found with id: " + item.getProductId());
                }
                if (!product.getProductPurchasable()) {
                    throw new InvalidOrderException("Product is not purchasable: " + product.getProductName()
                            + " (id: " + product.getProductId() + ")");
                }
            }

            // Create order
            Order order = new Order();
            order.setCustomerName(request.getCustomerName());
            order.setOrderStatus(OrderStatus.CREATED);

            // Create order items with price snapshot
            for (OrderItemRequest itemRequest : request.getItems()) {
                Product product = productMap.get(itemRequest.getProductId());

                OrderItem orderItem = new OrderItem();
                orderItem.setOrder(order);
                orderItem.setProduct(product);
                orderItem.setQuantity(itemRequest.getQuantity());
                orderItem.setUnitPrice(product.getPrice()); // Snapshot current price

                order.getOrderItems().add(orderItem);
            }

            Order savedOrder = orderRepository.save(order);
            MDC.put("order_id", String.valueOf(savedOrder.getOrderId()));
            log.info("Order created successfully with id={}, items={}", savedOrder.getOrderId(),
                    savedOrder.getOrderItems().size());

            return toOrderResponseWithItems(savedOrder);
        } finally {
            MDC.remove("operation");
            MDC.remove("order_id");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<OrderResponse> getAllOrders(Pageable pageable) {
        MDC.put("operation", "GET_ALL_ORDERS");
        try {
            log.info("Retrieving all orders, page={}, size={}", pageable.getPageNumber(), pageable.getPageSize());

            Page<Order> orderPage = orderRepository.findAll(pageable);
            List<OrderResponse> orders = orderPage.getContent().stream()
                    .map(this::toOrderResponse)
                    .toList();

            log.info("Retrieved {} orders (page {} of {})", orders.size(),
                    pageable.getPageNumber(), orderPage.getTotalPages());

            return PagedResponse.<OrderResponse>builder()
                    .content(orders)
                    .page(orderPage.getNumber())
                    .size(orderPage.getSize())
                    .totalElements(orderPage.getTotalElements())
                    .totalPages(orderPage.getTotalPages())
                    .last(orderPage.isLast())
                    .build();
        } finally {
            MDC.remove("operation");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse getOrderById(Long orderId) {
        MDC.put("operation", "GET_ORDER_BY_ID");
        MDC.put("order_id", String.valueOf(orderId));
        try {
            log.info("Retrieving order with id={}", orderId);

            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> {
                        log.warn("Order not found with id={}", orderId);
                        return new ResourceNotFoundException("Order not found with id: " + orderId);
                    });

            log.info("Successfully retrieved order with id={}", orderId);
            return toOrderResponse(order);
        } finally {
            MDC.remove("operation");
            MDC.remove("order_id");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse getOrderWithItems(Long orderId) {
        MDC.put("operation", "GET_ORDER_WITH_ITEMS");
        MDC.put("order_id", String.valueOf(orderId));
        try {
            log.info("Retrieving order with items, id={}", orderId);

            Order order = orderRepository.findByIdWithItems(orderId)
                    .orElseThrow(() -> {
                        log.warn("Order not found with id={}", orderId);
                        return new ResourceNotFoundException("Order not found with id: " + orderId);
                    });

            log.info("Successfully retrieved order with id={} and {} items", orderId,
                    order.getOrderItems().size());
            return toOrderResponseWithItems(order);
        } finally {
            MDC.remove("operation");
            MDC.remove("order_id");
        }
    }

    @Override
    @Transactional
    public OrderResponse updateOrderStatus(Long orderId, UpdateOrderStatusRequest request) {
        MDC.put("operation", "UPDATE_ORDER_STATUS");
        MDC.put("order_id", String.valueOf(orderId));
        try {
            log.info("Updating status for order id={}", orderId);

            // Parse and validate the requested status
            OrderStatus newStatus;
            try {
                newStatus = OrderStatus.valueOf(request.getStatus().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new InvalidStatusTransitionException("Invalid order status: " + request.getStatus());
            }

            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> {
                        log.warn("Order not found with id={}", orderId);
                        return new ResourceNotFoundException("Order not found with id: " + orderId);
                    });

            OrderStatus oldStatus = order.getOrderStatus();
            MDC.put("old_status", oldStatus.name());
            MDC.put("new_status", newStatus.name());

            // Validate transition
            Set<OrderStatus> allowedTransitions = VALID_TRANSITIONS.getOrDefault(oldStatus, EnumSet.noneOf(OrderStatus.class));
            if (!allowedTransitions.contains(newStatus)) {
                log.warn("Invalid status transition from {} to {} for order id={}",
                        oldStatus, newStatus, orderId);
                throw new InvalidStatusTransitionException(
                        "Cannot transition from " + oldStatus + " to " + newStatus);
            }

            order.setOrderStatus(newStatus);
            Order updatedOrder = orderRepository.save(order);

            log.info("Order id={} status updated from {} to {}", orderId, oldStatus, newStatus);

            return toOrderResponse(updatedOrder);
        } finally {
            MDC.remove("operation");
            MDC.remove("order_id");
            MDC.remove("old_status");
            MDC.remove("new_status");
        }
    }

    private OrderResponse toOrderResponse(Order order) {
        return OrderResponse.builder()
                .orderId(order.getOrderId())
                .customerName(order.getCustomerName())
                .orderStatus(order.getOrderStatus().name())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .build();
    }

    private OrderResponse toOrderResponseWithItems(Order order) {
        List<OrderItemResponse> itemResponses = order.getOrderItems().stream()
                .map(item -> OrderItemResponse.builder()
                        .productId(item.getProduct().getProductId())
                        .productName(item.getProduct().getProductName())
                        .quantity(item.getQuantity())
                        .unitPrice(item.getUnitPrice())
                        .build())
                .toList();

        return OrderResponse.builder()
                .orderId(order.getOrderId())
                .customerName(order.getCustomerName())
                .orderStatus(order.getOrderStatus().name())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .items(itemResponses)
                .build();
    }
}
