package com.lunarforge.market.model;

// категория лотов внутри игры (например "аккаунты", "валюта"). поля такие же как в json от сервера, gson заполняет их сам
public class Category {
    public long id;
    // к какой игре относится категория
    public long gameId;
    public String name;

    // тело запроса на создание новой категории
    public static class CreateRequest {
        public long gameId;
        public String name;
    }
}
