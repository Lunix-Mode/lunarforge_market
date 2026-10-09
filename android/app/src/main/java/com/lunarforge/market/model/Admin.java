package com.lunarforge.market.model;

import java.util.List;

// модели для админской статистики, gson раскладывает в них json от /api/admin/revenue.
// имена полей должны совпадать с сервером один в один, иначе поле останется пустым
public class Admin {
    // доход за месяц: month строкой "ГГГГ-ММ" и сумма комиссии за этот месяц
    public static class MonthlyRevenue {
        public String month;
        public double commissionRevenue;
    }

    // общий доход с комиссии + список по месяцам
    public static class Revenue {
        public double totalCommissionRevenue;
        public List<MonthlyRevenue> byMonth;
    }
}
