package com.lunarforge.market.repository;

import com.lunarforge.market.entity.WalletRelease;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

// таймеры разморозки денег
public interface WalletReleaseRepository extends JpaRepository<WalletRelease, Long> {
    // для шедулера: ещё не размороженные, у которых время уже наступило
    List<WalletRelease> findByReleasedFalseAndReleaseAtBefore(Instant now);
    // таймеры юзера, новые сверху
    List<WalletRelease> findByUserIdOrderByCreatedAtDesc(Long userId);

    // таймер конкретного заказа у конкретного продавца - нужен для возврата за счёт продавца и для подробностей операции
    java.util.Optional<WalletRelease> findFirstByOrderIdAndUserId(Long orderId, Long userId);
}
