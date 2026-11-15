package org.ajikhoji.ajiorders.service.impl;

import lombok.RequiredArgsConstructor;
import org.ajikhoji.ajiorders.dto.AccountInfoUpdateDto;
import org.ajikhoji.ajiorders.entity.Order;
import org.ajikhoji.ajiorders.exception.DatabaseSimulationException;
import org.ajikhoji.ajiorders.exception.PaymentFailureException;
import org.ajikhoji.ajiorders.exception.ResourceNotFoundException;
import org.ajikhoji.ajiorders.exception.SimulationDisabledException;
import org.ajikhoji.ajiorders.repository.OrderRepository;
import org.ajikhoji.ajiorders.service.SimulationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SimulationServiceImpl implements SimulationService {

    private static final Logger log = LoggerFactory.getLogger(SimulationServiceImpl.class);

    private final OrderRepository orderRepository;

    @Value("${app.simulation.enabled:false}")
    private boolean simulationEnabled;

    @Override
    public void simulatePaymentFailure(Long orderId) {
        checkSimulationEnabled();

        MDC.put("operation", "SIMULATE_PAYMENT");
        MDC.put("order_id", String.valueOf(orderId));
        MDC.put("error_code", "PAYMENT_GATEWAY_TIMEOUT");
        try {
            log.info("Processing payment for order id={}", orderId);

            // Verify the order exists
            orderRepository.findById(orderId)
                    .orElseThrow(() -> {
                        log.warn("Order not found with id={}", orderId);
                        return new ResourceNotFoundException("Order not found with id: " + orderId);
                    });

            // Simulate deterministic payment failure
            MDC.put("exception_type", "PaymentFailureException");
            log.error("Payment processing failed for order id={}. Error code: PAYMENT_GATEWAY_TIMEOUT. " +
                    "Reason: Payment gateway did not respond within the configured timeout period", orderId);

            throw new PaymentFailureException(
                    "Payment gateway timeout: unable to process payment for order " + orderId,
                    "PAYMENT_GATEWAY_TIMEOUT"
            );
        } finally {
            MDC.remove("operation");
            MDC.remove("order_id");
            MDC.remove("error_code");
            MDC.remove("exception_type");
        }
    }

    @Override
    @Transactional
    public void simulateDatabaseFailure() {
        checkSimulationEnabled();

        MDC.put("operation", "ATTEMPTING_TO_INSERT_ORDER_WITH_NO_NAME");
        try {
            log.info("Simulating database failure");

            // Create an order with a deliberately invalid state to cause a constraint violation.
            // The @Transactional annotation ensures rollback.
            Order invalidOrder = new Order();
            invalidOrder.setCustomerName(null); // Violates NOT NULL constraint

            try {
                orderRepository.saveAndFlush(invalidOrder);
            } catch (Exception dbException) {
                MDC.put("error_code", "DB_CONSTRAINT_VIOLATION");
                MDC.put("exception_type", dbException.getClass().getSimpleName());
                log.error("Database constraint violation during simulated failure: {}", dbException.getMessage());

                throw new DatabaseSimulationException(
                        "Database failure: constraint violation", dbException);
            }
        } finally {
            MDC.remove("operation");
            MDC.remove("error_code");
            MDC.remove("exception_type");
        }
    }

    @Override
    public void updateAccountInfo(AccountInfoUpdateDto accountInfo) {
        checkSimulationEnabled();

        MDC.put("operation", "UPDATING_ACCOUNT_INFORMATION");

        log.info("Account with user-id {} logged in with json-login-token {} attempts to update their account info", accountInfo.getUserId(), "****************");
        log.info("Updating address to {} and account password to {}", accountInfo.getUpdatedAddress(), "****************");

        MDC.remove("operation");
    }

    private void checkSimulationEnabled() {
        if (!simulationEnabled) {
            log.warn("Simulation endpoint called but simulations are disabled");
            throw new SimulationDisabledException(
                    "Simulation endpoints are disabled in the current environment");
        }
    }
}
