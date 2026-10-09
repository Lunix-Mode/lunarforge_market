package com.lunarforge.market.repository;

import com.lunarforge.market.entity.TicketFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

// оценки 👍/👎 решений модераторов. запросы Spring Data генерирует по имени метода
public interface TicketFeedbackRepository extends JpaRepository<TicketFeedback, Long> {
    // уже оценивал эту заявку? (один юзер - одна оценка на заявку, в таблице ещё и unique стоит)
    Optional<TicketFeedback> findByTicketIdAndUserId(Long ticketId, Long userId);
    // сколько 👍 и 👎 у модератора - по ним считается его рейтинг и решается, не снять ли его автоматически
    long countByStaffIdAndPositiveTrue(Long staffId);
    long countByStaffIdAndPositiveFalse(Long staffId);
}
