package com.lunarforge.market.repository;

import com.lunarforge.market.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// репозиторий сообщений чата. запросы не пишу руками - Spring Data сам строит SQL по названию метода
// (findBy... + поле + OrderBy... ), так меньше шансов накосячить
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    // вся переписка в чате по порядку отправки (старые сверху)
    List<ChatMessage> findByThreadIdOrderBySentAtAsc(Long threadId);
    // только сообщения, пришедшие после указанного времени - для опроса новых сообщений, чтобы не тянуть весь чат заново
    List<ChatMessage> findByThreadIdAndSentAtAfterOrderBySentAtAsc(Long threadId, java.time.Instant after);

    // последнее сообщение в чате - нужно для превью в списке чатов
    java.util.Optional<ChatMessage> findTopByThreadIdOrderBySentAtDesc(Long threadId);

    // для подгрузки порциями: самые новые (или новые из тех, что старее beforeId) - по id, он всегда растёт
    // Pageable задаёт размер порции (например 30 штук). сортирую по убыванию id, чтобы взять самые свежие,
    // а приложение потом само разворачивает их в нормальный порядок.
    // второй метод - для прокрутки вверх: беру сообщения с id меньше самого старого, которое уже загружено
    List<ChatMessage> findByThreadIdOrderByIdDesc(Long threadId, org.springframework.data.domain.Pageable pageable);
    List<ChatMessage> findByThreadIdAndIdLessThanOrderByIdDesc(Long threadId, Long beforeId, org.springframework.data.domain.Pageable pageable);
}
