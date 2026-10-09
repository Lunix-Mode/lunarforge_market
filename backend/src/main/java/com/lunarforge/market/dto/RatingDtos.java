package com.lunarforge.market.dto;

import java.time.Instant;

// dto для отзывов. record - короткая запись неизменяемого класса с полями, геттерами и конструктором сразу
public class RatingDtos {
    // оставить отзыв по заказу. score - звёзды 1..5, comment необязательный
    public record CreateRatingRequest(Long orderId, int score, String comment) {}

    // отредактировать свой отзыв
    public record UpdateRatingRequest(int score, String comment) {}

    // roundedAmount - сумма заказа, округлённая до 10р, чтобы по ней нельзя было опознать конкретную покупку
    // поля автора заполняются только когда смотрит сам продавец, остальным приходят null
    public record RatingResponse(
            Long id, Long orderId, int score, String comment, long roundedAmount,
            Instant createdAt, Instant updatedAt,
            Long raterId, String raterNickname, String raterUsername
    ) {}
}
