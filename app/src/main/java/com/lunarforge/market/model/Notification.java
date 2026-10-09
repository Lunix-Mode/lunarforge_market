package com.lunarforge.market.model;

// уведомление из колокольчика. refType + refId - куда вести по нажатию
// (например refType = ORDER и refId = 15 - открыть заказ 15)
public class Notification {
    public long id;
    // тип события строкой, например ACCOUNT_BLOCKED
    public String type;
    public String title;
    public String body;
    public String refType;   // ORDER / TICKET / TRANSACTION / USER
    public Long refId;
    // прочитано или нет - от этого зависит выделение в списке и счётчик
    public boolean read;
    // дата строкой как пришла с сервера, форматирую уже при показе
    public String createdAt;

    // ответ на запрос количества непрочитанных - для цифры на колокольчике
    public static class UnreadCount {
        public long count;
    }
}
