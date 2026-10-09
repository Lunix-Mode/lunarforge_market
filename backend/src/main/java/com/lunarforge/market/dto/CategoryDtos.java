package com.lunarforge.market.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// DTO для категорий внутри игры (например "аккаунты", "валюта", "предметы").
// собрал их в один класс-обёртку, чтобы не плодить кучу маленьких файлов.
// record - это неизменяемый класс, геттеры/конструктор/equals java делает сама
public class CategoryDtos {
    // запрос на создание категории: к какой игре и как назвать.
    // @NotNull / @NotBlank сработают при @Valid в контроллере - пустое имя не пройдёт
    public record CreateCategoryRequest(
            @NotNull Long gameId,
            @NotBlank String name
    ) {}

    // то что отдаём клиенту: id категории, id игры и название
    public record CategoryResponse(
            Long id,
            Long gameId,
            String name
    ) {}
}
