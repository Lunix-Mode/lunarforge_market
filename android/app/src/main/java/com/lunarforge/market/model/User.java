package com.lunarforge.market.model;

// пользователь в том виде, как его отдаёт сервер (свой профиль /me или чужой).
// поля public и без геттеров - это просто контейнер для Gson
public class User {
    public long id;
    // отображаемое имя (можно менять)
    public String nickname;
    // логин
    public String username;
    public String email;
    public String avatarUrl;
    // деньги: доступные можно тратить/выводить, замороженные ждут 48ч после подтверждения заказа.
    // double тут только для показа на экране, вся настоящая денежная математика на сервере в BigDecimal
    public double balanceAvailable;
    public double balanceFrozen;
    // USER / MODERATOR / ADMIN - от этого зависит, какие кнопки показывать
    public String role;
    public double ratingAverage;
    public int ratingCount;
    public String registeredAt;
    public boolean blocked;          // показываем экран блокировки
    public String blockReason;
    public boolean formerModerator;  // можно попросить вернуть должность

    // тело запроса на смену ника
    public static class ChangeNicknameRequest {
        public String nickname;
        public ChangeNicknameRequest(String nickname) { this.nickname = nickname; }
    }

    // тело запроса на смену аватарки (url уже загруженного файла)
    public static class ChangeAvatarRequest {
        public String avatarUrl;
        public ChangeAvatarRequest(String avatarUrl) { this.avatarUrl = avatarUrl; }
    }
}
