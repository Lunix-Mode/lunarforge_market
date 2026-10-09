package com.lunarforge.market.model;

// чужой профиль (продавца или покупателя) - без почты и баланса, только то, что можно видеть всем
public class PublicProfile {
    public long id;
    public String nickname;
    public String username;
    public String avatarUrl;
    // средний рейтинг по отзывам и количество отзывов
    public double ratingAverage;
    public int ratingCount;
    public String registeredAt;
    public String role; // MODERATOR / ADMIN - значок в профиле
    // заблокирован ли юзер - показываю это в профиле, чтобы покупатели не писали заблокированному продавцу
    public boolean blocked;
}
