package org.ajikhoji.ajiorders.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.ajikhoji.ajiorders.dto.request.CreateOrderRequest;
import org.ajikhoji.ajiorders.dto.request.UpdateOrderStatusRequest;
import org.ajikhoji.ajiorders.dto.response.OrderResponse;
import org.ajikhoji.ajiorders.dto.response.PagedResponse;
import org.ajikhoji.ajiorders.service.OrderService;
import org.ajikhoji.ajiorders.service.SimulationService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final SimulationService simulationService;

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        OrderResponse response = orderService.createOrder(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<PagedResponse<OrderResponse>> getAllOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("orderId").descending());
        return ResponseEntity.ok(orderService.getAllOrders(pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getOrderById(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.getOrderWithItems(id));
    }

    @PatchMapping("/{orderId}/status")
    public ResponseEntity<OrderResponse> updateOrderStatus(
            @PathVariable Long orderId,
            @Valid @RequestBody UpdateOrderStatusRequest request) {
        return ResponseEntity.ok(orderService.updateOrderStatus(orderId, request));
    }

    @PostMapping("/{orderId}/pay")
    public ResponseEntity<Void> simulatePaymentFailure(@PathVariable Long orderId) {
        simulationService.simulatePaymentFailure(orderId);
        return ResponseEntity.ok().build(); // Won't reach here — exception is thrown
    }

    @PostMapping("/new/no-customer-name")
    public ResponseEntity<Void> simulateDatabaseFailure() {
        simulationService.simulateDatabaseFailure();
        return ResponseEntity.ok().build(); // Won't reach here — exception is thrown
    }
}
