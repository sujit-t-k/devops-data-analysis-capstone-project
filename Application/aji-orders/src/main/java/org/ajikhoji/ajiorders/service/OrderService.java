package org.ajikhoji.ajiorders.service;

import org.ajikhoji.ajiorders.dto.request.CreateOrderRequest;
import org.ajikhoji.ajiorders.dto.request.UpdateOrderStatusRequest;
import org.ajikhoji.ajiorders.dto.response.OrderResponse;
import org.ajikhoji.ajiorders.dto.response.PagedResponse;
import org.springframework.data.domain.Pageable;

public interface OrderService {

    OrderResponse createOrder(CreateOrderRequest request);

    PagedResponse<OrderResponse> getAllOrders(Pageable pageable);

    OrderResponse getOrderById(Long orderId);

    OrderResponse getOrderWithItems(Long orderId);

    OrderResponse updateOrderStatus(Long orderId, UpdateOrderStatusRequest request);
}
