package com.lunarforge.market.repository;

import com.lunarforge.market.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

// уведомления пользователя (колокольчик в приложении)
public interface NotificationRepository extends JpaRepository<Notification, Long> {
    // последние 100 уведомлений, новые сверху. ограничиваю 100, чтобы не тащить всю историю
    List<Notification> findTop100ByUserIdOrderByCreatedAtDesc(Long userId);
    // сколько непрочитанных - для цифры на иконке
    long countByUserIdAndReadFalse(Long userId);

    // пометить все прочитанными одним UPDATE, а не грузить каждое и сохранять по одному.
    // @Modifying обязателен для запросов, которые меняют данные; возвращает сколько строк обновилось
    @Modifying
    @Query("UPDATE Notification n SET n.read = true WHERE n.user.id = :userId AND n.read = false")
    int markAllRead(@Param("userId") Long userId);
}
