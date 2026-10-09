package com.lunarforge.market.dto;

import java.time.Instant;

// то, что сервер отдаёт приложению по уведомлениям.
// records - чтобы не писать геттеры/конструкторы, jackson их нормально сериализует в json
public class NotificationDtos {
    // одно уведомление. type - вид события (например новый отзыв),
    // refType + refId - на что оно ссылается (заказ, заявка и т.п.), по ним приложение
    // понимает какой экран открыть при нажатии. read - прочитано или нет
    public record NotificationResponse(Long id, String type, String title, String body,
                                       String refType, Long refId, boolean read, Instant createdAt) {}

    // сколько непрочитанных - для красного кружка-счётчика на колокольчике
    public record UnreadCount(long count) {}
}
