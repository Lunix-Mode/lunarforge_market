package com.lunarforge.market.dto;

import java.math.BigDecimal;

// DTO для всего, что связано с юзером: мой профиль, чужой профиль, пополнение/вывод, статистика.
// специально не отдаю наружу сущность User целиком - там хэш пароля и служебные поля, их в JSON отдавать нельзя
public class UserDtos {
    // мой собственный профиль - тут есть почта и баланс, поэтому отдаётся только самому владельцу
    public record UserProfile(
            Long id,
            String nickname,
            String username,
            String email,
            String avatarUrl,
            // balanceAvailable - деньги, которые можно тратить или выводить прямо сейчас
            BigDecimal balanceAvailable,
            // balanceFrozen - деньги в безопасных сделках: заморожены, пока покупатель не подтвердит, и ещё 48 часов холда после.
            // считаю всё в BigDecimal, а не double, чтобы копейки не терялись при округлении
            BigDecimal balanceFrozen,
            String role,
            // средняя оценка по отзывам и сколько отзывов всего
            double ratingAverage,
            int ratingCount,
            java.time.Instant registeredAt,
            boolean blocked,       // приложение показывает экран блокировки
            String blockReason,
            boolean formerModerator // был модератором - может попросить вернуть должность
    ) {}

    // чужой профиль (продавца/покупателя) - без почты и без баланса, это никому кроме владельца знать не надо
    public record PublicProfile(
            Long id,
            String nickname,
            String username,
            String avatarUrl,
            double ratingAverage,
            int ratingCount,
            java.time.Instant registeredAt,
            String role,          // MODERATOR / ADMIN - в профиле рисуется значок
            boolean blocked       // видно всем: покупатели не будут писать заблокированному продавцу
    ) {}

    // пополнение кошелька. оплата учебная, реального эквайринга нет: сервер зачисляет сумму,
    // а номер карты только маскирует и пишет в историю операций
    public record TopUpRequest(
            BigDecimal amount,
            String cardNumber
    ) {}

    // вывод денег на карту - сервер проверяет номер карты (минимум 12 цифр) и хватает ли доступного баланса
    public record WithdrawRequest(
            BigDecimal amount,
            String cardNumber
    ) {}

    // цифры для профиля: сколько потратил, сколько заработал, сколько сделок завершено.
    // считает это UserService через запросы в OrderRepository
    public record UserStats(
            BigDecimal totalSpent,
            BigDecimal totalEarned,
            long purchasesCompleted,
            long salesCompleted
    ) {}
}
