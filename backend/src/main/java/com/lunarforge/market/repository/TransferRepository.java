package com.lunarforge.market.repository;

import com.lunarforge.market.entity.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// репозиторий переводов. обычные save/findById даёт JpaRepository, свой тут только один запрос
public interface TransferRepository extends JpaRepository<Transfer, Long> {
    // все переводы где юзер либо отправил, либо получил деньги. сначала новые
    @org.springframework.data.jpa.repository.Query(
        "SELECT t FROM Transfer t WHERE t.sender.id = :userId OR t.receiver.id = :userId ORDER BY t.createdAt DESC")
    List<Transfer> findAllForUser(@org.springframework.data.repository.query.Param("userId") Long userId);
}
