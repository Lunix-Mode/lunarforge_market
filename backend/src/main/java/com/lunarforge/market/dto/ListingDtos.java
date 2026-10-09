package com.lunarforge.market.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;

// dto для лотов (объявлений). record - это неизменяемый класс, геттеры и конструктор java делает сама.
// аннотации @NotNull/@Size проверяются валидацией spring, если поле кривое - сразу 400 и до сервиса не доходит
public class ListingDtos {
    // то, что приходит от приложения при создании/редактировании лота
    public record CreateListingRequest(
            @NotNull Long gameId,
            // категория внутри игры (аккаунты, валюта и т.п.), может быть не указана
            Long categoryId,
            // тип лота строкой, разбирается в сервисе
            String type,
            @NotBlank @jakarta.validation.constraints.Size(max = 100) String title,
            @jakarta.validation.constraints.Size(max = 2000) String description,
            @NotNull @Positive BigDecimal price,
            // количество товара. если unlimited = true, количество не важно
            Integer quantity,
            Boolean unlimited,
            @jakarta.validation.constraints.Size(max = 100) String deliveryMethod,
            @jakarta.validation.constraints.Size(max = 255) String imageUrl,
            // дополнительные фото к главной картинке, больше 5 не даю
            @jakarta.validation.constraints.Size(max = 5, message = "не больше 5 дополнительных фото") java.util.List<String> extraImageUrls,
            // активен ли лот (виден в каталоге или скрыт продавцом)
            Boolean active
    ) {}

    // то, что сервер отдаёт приложению по лоту. имя игры, категории и продавца кладу сразу сюда,
    // чтобы приложению не делать лишние запросы
    public record ListingResponse(
            Long id,
            Long gameId,
            String gameName,
            Long categoryId,
            String categoryName,
            String type,
            Long sellerId,
            String sellerNickname,
            // аватарка продавца, показываю в карточке лота
            String sellerAvatarUrl,
            String title,
            String description,
            // price - цена которую поставил продавец, buyerPrice - сколько реально заплатит покупатель (с комиссией)
            BigDecimal price,
            BigDecimal buyerPrice,
            Integer quantity,
            boolean unlimited,
            String deliveryMethod,
            String imageUrl,
            java.util.List<String> extraImageUrls,
            boolean active,
            Instant createdAt
    ) {}
}
