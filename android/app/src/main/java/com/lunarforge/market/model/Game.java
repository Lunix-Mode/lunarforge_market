package com.lunarforge.market.model;

// игра как её присылает сервер (GET /api/games). Gson раскладывает json прямо в эти поля по именам,
// поэтому они public и называются так же как в ответе сервера
public class Game {
    public long id;
    public String name;
    // путь к иконке, может быть null - тогда в списке рисуется буква
    public String iconUrl;
    // категория игры, по ней можно фильтровать список
    public String category;
}
