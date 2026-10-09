package com.lunarforge.market.dto;

// что отдаём приложению про игру. record - неизменяемый класс, геттеры/конструктор/equals java генерирует сама
public class GameDtos {
    // iconUrl - путь к картинке (/files/...), category - раздел на главной (по умолчанию "games"), по нему фильтрует GET /api/games?category=...
    public record GameResponse(Long id, String name, String iconUrl, String category) {}
}
