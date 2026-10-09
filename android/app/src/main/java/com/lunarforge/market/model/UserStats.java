package com.lunarforge.market.model;

// статистика для экрана профиля: сколько потратил и заработал, и сколько сделок завершено как покупатель и как продавец.
// сервер считает в BigDecimal, а сюда для показа хватает double
public class UserStats {
    public double totalSpent;
    public double totalEarned;
    public long purchasesCompleted;
    public long salesCompleted;
}
