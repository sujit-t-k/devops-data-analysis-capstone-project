package org.ajikhoji.ajiorders.repository;

import org.ajikhoji.ajiorders.entity.OrderItem;
import org.ajikhoji.ajiorders.entity.OrderItemId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, OrderItemId> {
}
