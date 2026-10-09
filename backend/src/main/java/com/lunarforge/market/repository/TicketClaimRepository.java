package com.lunarforge.market.repository;

import com.lunarforge.market.entity.TicketClaim;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

// записи "модератор взял заявку в работу". одна и та же заявка может быть взята несколько раз
// (например отпустили и взяли снова), поэтому это отдельная таблица, а не поле в заявке
public interface TicketClaimRepository extends JpaRepository<TicketClaim, Long> {
    // сколько раз модератор вообще брал заявки - идёт в его статистику.
    // запрос спринг строит сам по имени метода
    long countByStaffId(Long staffId);

    // все заявки, которые модератор когда-либо брал (без повторов)
    // DISTINCT убирает повторы, если одну заявку брал несколько раз.
    // сортировки тут нет - заявки по этим id потом достаются в TicketService уже с сортировкой по дате, новые сверху
    @Query("SELECT DISTINCT c.ticket.id FROM TicketClaim c WHERE c.staff.id = :staffId")
    List<Long> claimedTicketIds(@Param("staffId") Long staffId);
}
