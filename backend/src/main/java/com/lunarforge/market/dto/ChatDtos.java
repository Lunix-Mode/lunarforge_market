package com.lunarforge.market.dto;

import java.time.Instant;

// dto для чата. record-ы потому что это просто данные туда-сюда, геттеры и конструктор java делает сама
public class ChatDtos {
    // открыть чат с продавцом. listingId - с какого товара пришли, чтобы было видно о чём речь (может быть null)
    public record StartThreadRequest(Long sellerId, Long listingId) {}

    // отправка сообщения. может быть только текст, только вложение или и то и то
    public record SendMessageRequest(
            String text,
            // attachmentUrl - ссылка, которую до этого вернул /api/files/upload
            String attachmentUrl,
            // тип вложения: фото / видео / голос / кружок, по нему приложение решает как рисовать
            String attachmentType,
            // длительность для голосовых и кружков, чтобы показать "0:15" не скачивая файл
            Integer attachmentDurationSeconds
    ) {}

    // чат в списке чатов. отдаю сразу ники и аватарки обоих участников,
    // чтобы приложение само выбрало "собеседника" и не делало лишних запросов
    public record ThreadResponse(
            Long id,
            Long buyerId,
            String buyerNickname,
            String buyerAvatarUrl,
            Long sellerId,
            String sellerNickname,
            String sellerAvatarUrl,
            Long listingId,
            String listingTitle,
            // последнее сообщение для превью в списке и его время (по нему сортируем)
            String lastMessagePreview,
            Instant lastMessageAt
    ) {}

    // одно сообщение в чате
    public record MessageResponse(
            Long id,
            Long threadId,
            Long senderId,
            String senderNickname,
            // по username приложение узнаёт бота. по нику нельзя - ники не уникальные, кто угодно
            // может назваться ботом
            String senderUsername,
            String senderRole,     // USER / MODERATOR / ADMIN - сообщения модераторов приложение помечает
            String text,
            String attachmentUrl,
            String attachmentType,
            Integer attachmentDurationSeconds,
            Instant sentAt,
            String senderAvatarUrl    // аватар отправителя - в чате над чужими сообщениями
    ) {}
}
