package com.lunarforge.market.repository;

import com.lunarforge.market.entity.WalletTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// история операций кошелька (пополнения, покупки, продажи, разморозки, возвраты)
public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {
    // вся история юзера сразу, новые сверху
    List<WalletTransaction> findByUserIdOrderByCreatedAtDesc(Long userId);

    // то же самое, но постранично: Pageable добавляет LIMIT/OFFSET.
    // приложение грузит историю кусками при прокрутке, а не тянет тысячи строк за раз
    List<WalletTransaction> findByUserIdOrderByCreatedAtDesc(Long userId, org.springframework.data.domain.Pageable pageable);
}
