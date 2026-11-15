package org.ajikhoji.ajiorders.service;

import org.ajikhoji.ajiorders.dto.AccountInfoUpdateDto;

public interface SimulationService {

    void simulatePaymentFailure(Long orderId);

    void simulateDatabaseFailure();

    void updateAccountInfo(AccountInfoUpdateDto accountInfo);

}
