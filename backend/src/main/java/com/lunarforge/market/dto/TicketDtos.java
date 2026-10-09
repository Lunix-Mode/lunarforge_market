package com.lunarforge.market.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

// все запросы/ответы для заявок в поддержку (возвраты, жалобы, проблемы с заказом, разблокировка, обжалования)
// собрал в один класс, чтобы не плодить кучу файлов. record - это неизменяемый класс, Jackson сам переводит его в JSON и обратно.
// аннотации @NotNull/@NotBlank/@Size работают вместе с @Valid в контроллере: кривой запрос отвалится с 400 сам
public class TicketDtos {

    // заявка на возврат денег по заказу: номер заказа + причина (до 2000 символов, чтобы не прислали роман на мегабайт)
    public record CreateRefundRequest(@NotNull Long orderId, @NotBlank @Size(max = 2000) String reason) {}

    // жалоба на пользователя: на кого жалуемся и за что
    public record CreateComplaintRequest(@NotNull Long userId, @NotBlank @Size(max = 2000) String reason) {}

    // проблема с заказом: роль (покупатель/продавец) сервер определит сам по заказу
    public record CreateOrderProblemRequest(@NotNull Long orderId, @NotBlank @Size(max = 2000) String reason) {}

    // проблема с аккаунтом / разблокировка / обжалование - достаточно текста
    public record TextRequest(@NotBlank @Size(max = 2000) String reason) {}

    // оценка решения модератора: positive = true это лайк, false дизлайк, комментарий необязательный.
    // от этих оценок потом считается рейтинг модератора
    public record FeedbackRequest(boolean positive, @Size(max = 1000) String comment) {}

    // решение модератора по заявке. resolution - что решили (например вернуть деньги или отказать),
    // refundAmount нужен только при частичном возврате, поэтому без @NotNull
    public record ResolveRequest(@NotBlank String resolution, BigDecimal refundAmount, @Size(max = 1000) String note) {}

    // блокировка пользователя модератором - причину писать обязательно, она потом показывается заблокированному
    public record BlockRequest(@NotBlank @Size(max = 1000) String reason) {}

    // одна заявка целиком, как её видит приложение. полей много, потому что один и тот же ответ
    // используется и в списке заявок, и на экране заявки, и у модератора, и у участника.
    // поля can* сервер считает сам под конкретного юзера, приложение просто прячет/показывает кнопки по ним
    public record TicketResponse(
            Long id,
            String type,
            String status,
            // чат заявки (там переписка участников с модератором)
            Long threadId,
            Long orderId,
            String orderTitle,
            BigDecimal orderAmount,
            String orderStatus,
            Long buyerId,
            String buyerNickname,
            Long sellerId,
            String sellerNickname,
            Long reporterId,
            String reporterNickname,
            String reporterRole,      // BUYER / SELLER для проблем с заказом
            Long reportedUserId,
            String reportedUserNickname,
            String reason,
            Long assigneeId,
            String assigneeNickname,
            String assigneeRole,      // MODERATOR / ADMIN - приложение рисует 🛡️ или 👑
            // когда модератор взял заявку себе
            Instant claimedAt,
            // можно ли мне взять заявку (она свободна и я модератор)
            boolean canClaim,
            // могу ли я по ней что-то делать (заявка моя или я админ)
            boolean canAct,
            // с какого момента другой модератор может перехватить зависшую заявку
            Instant takeoverAvailableAt,
            String resolution,
            BigDecimal refundAmount,
            String resolutionNote,
            Instant createdAt,
            Instant resolvedAt,
            Long appealOfId,          // для обжалования - номер обжалуемой заявки
            boolean canRate,          // участник может поставить 👍/👎 решению
            boolean canAppeal,        // участник может обжаловать решение
            Boolean myFeedback,       // что я уже поставил: true 👍, false 👎, null - ещё нет
            String blockReason,       // для разблокировки - за что заблокировали
            String blockedByLabel,    // кто заблокировал (видят только модераторы)
            BigDecimal orderCommission   // комиссия заказа - приложение показывает точный расклад частичного возврата
    ) {}

    // модератор + его рейтинг и цифры
    // поле rating считается на сервере из лайков/дизлайков и отменённых решений
    public record ModeratorResponse(
            Long id,
            String nickname,
            String username,
            String role,
            int rating,               // 0..100, ниже 40 при 5+ решённых - снимается автоматически
            long resolvedCount,
            long claimedCount,
            long inProgressCount,
            long likes,
            long dislikes,
            long overturned,          // сколько его решений отменили по обжалованию
            long blocksIssued,
            long blocksReversed,      // сколько его блокировок сняли по апелляции
            boolean demotedAutomatically,
            Instant demotedAt,
            String demotionReason,
            boolean blocked           // админ сразу видит, что бывшего модератора сначала надо разблокировать
    ) {}

    // одна блокировка пользователя: кто, за что, когда, и сняли ли её потом
    public record BlockResponse(
            Long id,
            Long userId,
            String userNickname,
            String reason,
            Instant createdAt,
            // liftedAt = null значит блокировка ещё действует
            Instant liftedAt,
            String liftReason,
            // true если блокировку сняли потому что юзер обжаловал и выиграл - это бьёт по рейтингу модератора
            boolean reversedByAppeal,
            String blockedByLabel     // "👑 Создатель Lunix" / "🛡️ Модератор ник"
    ) {}

    // подробная статистика модератора для админа: цифры, все заявки которые он брал/решал, блокировки
    public record ModeratorStatsResponse(ModeratorResponse moderator, List<TicketResponse> tickets, List<BlockResponse> blocks) {}
}
