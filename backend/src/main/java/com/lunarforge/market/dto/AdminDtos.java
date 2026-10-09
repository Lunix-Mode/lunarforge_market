package com.lunarforge.market.dto;

import java.math.BigDecimal;
import java.util.List;

// dto для админской статистики по доходу (комиссия 5% с каждой сделки)
public class AdminDtos {
    // доход за один месяц, month строкой вида "2026-05"
    public record MonthlyRevenue(String month, BigDecimal commissionRevenue) {}

    // ответ целиком: сколько всего заработали на комиссии + разбивка по месяцам для графика
    public record RevenueResponse(BigDecimal totalCommissionRevenue, List<MonthlyRevenue> byMonth) {}
}
