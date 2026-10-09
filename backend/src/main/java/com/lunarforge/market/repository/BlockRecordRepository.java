package com.lunarforge.market.repository;

import com.lunarforge.market.entity.BlockRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

// история блокировок. методы без тела - Spring Data сам строит SQL по имени метода
// (findBy... = WHERE, And = AND, OrderBy...Desc = сортировка, count = SELECT COUNT)
public interface BlockRecordRepository extends JpaRepository<BlockRecord, Long> {
    // текущая активная блокировка юзера: ещё не снята (liftedAt пустой), самая свежая
    Optional<BlockRecord> findFirstByUserIdAndLiftedAtIsNullOrderByCreatedAtDesc(Long userId);
    // все блокировки, которые выдал этот модератор - для админа в статистике
    List<BlockRecord> findByBlockedByIdOrderByCreatedAtDesc(Long staffId);
    // история блокировок конкретного юзера
    List<BlockRecord> findByUserIdOrderByCreatedAtDesc(Long userId);
    // сколько всего блокировок выдал модератор и сколько из них потом отменили по апелляции - из этого считается его рейтинг
    long countByBlockedById(Long staffId);
    long countByBlockedByIdAndReversedByAppealTrue(Long staffId);
}
