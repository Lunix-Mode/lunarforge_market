package com.lunarforge.market.repository;

import com.lunarforge.market.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

// заказы. большинство методов spring data строит сам по названию (findBy...OrderBy...)
public interface OrderRepository extends JpaRepository<Order, Long> {
    // без пагинации - весь список, новые сверху
    List<Order> findByBuyerIdOrderByCreatedAtDesc(Long buyerId);
    List<Order> findBySellerIdOrderByCreatedAtDesc(Long sellerId);

    // порциями - для экранов "Мои покупки/продажи"
    List<Order> findByBuyerIdOrderByCreatedAtDesc(Long buyerId, org.springframework.data.domain.Pageable pageable);
    List<Order> findBySellerIdOrderByCreatedAtDesc(Long sellerId, org.springframework.data.domain.Pageable pageable);

    // сколько юзер потратил: только завершённые заказы, минус то что ему вернули. COALESCE - чтобы вместо null был 0
    @Query("SELECT COALESCE(SUM(o.amount - COALESCE(o.refundedAmount, 0)), 0) FROM Order o WHERE o.buyer.id = :userId AND o.status = 'COMPLETED'")
    BigDecimal totalSpent(@Param("userId") Long userId);

    // сколько продавец заработал - его доля по завершённым заказам
    @Query("SELECT COALESCE(SUM(o.sellerAmount), 0) FROM Order o WHERE o.seller.id = :userId AND o.status = 'COMPLETED'")
    BigDecimal totalEarned(@Param("userId") Long userId);

    // счётчики заказов по статусу, для профиля/статистики
    long countByBuyerIdAndStatus(Long buyerId, Order.OrderStatus status);
    long countBySellerIdAndStatus(Long sellerId, Order.OrderStatus status);

    // тут нативный sql, to_char есть только в постгресе
    // доход площадки по месяцам: группирую комиссию по месяцу подтверждения, для админки
    @Query(value = "SELECT to_char(confirmed_at, 'YYYY-MM') AS ym, COALESCE(SUM(commission_amount), 0) AS total " +
            "FROM orders WHERE status = 'COMPLETED' AND confirmed_at IS NOT NULL " +
            "GROUP BY ym ORDER BY ym DESC", nativeQuery = true)
    List<Object[]> monthlyCommissionRevenue();

    // вся комиссия за всё время
    @Query("SELECT COALESCE(SUM(o.commissionAmount), 0) FROM Order o WHERE o.status = 'COMPLETED'")
    BigDecimal totalCommissionRevenue();

    // SELECT ... FOR UPDATE. второй запрос к этому заказу будет ждать пока первый не закончит.
    // без этого двойной тап по "подтвердить" платил продавцу два раза
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Order e WHERE e.id = :id")
    Optional<Order> lockById(@Param("id") Long id);
}
