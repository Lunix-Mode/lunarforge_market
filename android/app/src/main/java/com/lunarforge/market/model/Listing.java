package com.lunarforge.market.model;

// модель объявления (лота), как её присылает сервер. Gson сам раскладывает JSON по этим полям,
// поэтому имена полей должны совпадать с именами в JSON на бэке
public class Listing {
    public long id;
    public long gameId;
    public String gameName;
    // Long, а не long - категории может не быть (null), примитив null хранить не умеет
    public Long categoryId;
    public String categoryName;
    // тип лота (ITEM и т.п.), на бэке это enum, сюда приходит строкой
    public String type;
    public long sellerId;
    public String sellerNickname;
    public String sellerAvatarUrl;
    public String title;
    public String description;
    // price - сколько получит продавец, buyerPrice - сколько заплатит покупатель (цена + 5% комиссии площадки).
    // покупателю в приложении показываю buyerPrice
    public double price;
    public double buyerPrice;
    // сколько штук осталось; если unlimited = true, количество не уменьшается при покупке
    public int quantity;
    public boolean unlimited;
    // как продавец передаёт товар - просто текст от продавца
    public String deliveryMethod;
    // главная картинка (обложка) и дополнительные фото
    public String imageUrl;
    public java.util.List<String> extraImageUrls;
    // false = лот снят с продажи
    public boolean active;
    // дата приходит строкой в формате ISO, разбираю её через Ui.parseIso
    public String createdAt;

    // то, что отправляю на сервер при создании/редактировании лота.
    // id, продавца и buyerPrice не шлю - их сервер ставит сам, чтобы нельзя было подделать
    public static class CreateRequest {
        public long gameId;
        public Long categoryId;
        public String type;
        public String title;
        public String description;
        public double price;
        // Integer, чтобы для бесконечного товара можно было не передавать количество (null)
        public Integer quantity;
        public boolean unlimited;
        public String deliveryMethod;
        public String imageUrl;
    public java.util.List<String> extraImageUrls;
        public boolean active;
    }
}
