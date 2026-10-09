package com.lunarforge.market.model;

// заявка: возврат по заказу или жалоба. поля как в TicketResponse на сервере
// gson заполняет поля по именам из json, поэтому они public и называются как на сервере.
// обёртки Long/Double там где сервер может прислать null (например у жалобы нет заказа)
public class Ticket {
    public long id;
    public String type;        // REFUND / COMPLAINT
    public String status;      // OPEN / IN_PROGRESS / RESOLVED
    // у каждой заявки свой чат, где общаются заявитель и модератор
    public long threadId;
    public Long orderId;
    public String orderTitle;
    public Double orderAmount;
    public Long buyerId;
    public String buyerNickname;
    public Long sellerId;
    public String sellerNickname;
    public long reporterId;
    public String reporterNickname;
    public Long reportedUserId;
    public String reportedUserNickname;
    public String reason;
    // кто из модераторов взял заявку в работу (null - пока никто)
    public Long assigneeId;
    public String assigneeNickname;
    public String assigneeRole; // MODERATOR / ADMIN
    // даты приходят строкой ISO, парсю их уже при показе
    public String claimedAt;
    // canClaim/canAct считает сервер: можно ли мне взять заявку и можно ли по ней принимать решение.
    // приложение по ним просто показывает/прячет кнопки, само права не вычисляет
    public boolean canClaim;
    public boolean canAct;
    // когда другой модератор сможет перехватить заявку (сервер считает: через 8 часов после того как её взяли)
    public String takeoverAvailableAt;
    public String resolution;  // FULL_REFUND / PARTIAL_REFUND / NO_REFUND / CLOSED
    // сколько вернули при частичном возврате
    public Double refundAmount;
    public String resolutionNote;
    public String createdAt;
    public String resolvedAt;
    public String reporterRole;   // BUYER / SELLER - кем заявитель был в заказе
    public String orderStatus;
    public Long appealOfId;       // обжалование: какую заявку обжалуют
    public boolean canRate;       // могу поставить 👍/👎
    public boolean canAppeal;     // могу обжаловать решение
    public Boolean myFeedback;    // что я поставил: true 👍 / false 👎 / null
    public String blockReason;    // разблокировка: за что заблокировали
    public String blockedByLabel; // кто заблокировал (видят только модераторы)
    public Double orderCommission; // комиссия заказа - для точного расклада частичного возврата

    // тело запроса на возврат по заказу
    public static class RefundRequest {
        public long orderId;
        public String reason;
        public RefundRequest(long orderId, String reason) { this.orderId = orderId; this.reason = reason; }
    }

    // жалоба на пользователя
    public static class ComplaintRequest {
        public long userId;
        public String reason;
        public ComplaintRequest(long userId, String reason) { this.userId = userId; this.reason = reason; }
    }

    // решение модератора по заявке: тип решения, сумма (для частичного возврата) и комментарий
    public static class ResolveRequest {
        public String resolution;
        public Double refundAmount;
        public String note;
        public ResolveRequest(String resolution, Double refundAmount, String note) {
            this.resolution = resolution; this.refundAmount = refundAmount; this.note = note;
        }
    }

    // модератор + рейтинг и цифры (для админа)
    public static class Moderator {
        public long id;
        public String nickname;
        public String username;
        public String role;
        public int rating;               // 0..100
        public long resolvedCount;
        public long claimedCount;
        public long inProgressCount;
        public long likes;
        public long dislikes;
        public long overturned;          // сколько его решений отменили по обжалованию
        public long blocksIssued;
        public long blocksReversed;      // сколько его блокировок сняли по апелляции
        public boolean demotedAutomatically;
        public String demotedAt;
        public String demotionReason;
        public boolean blocked;
    }

    // проблема с заказом (отдельная заявка от покупателя/продавца по заказу)
    public static class OrderProblemRequest {
        public long orderId;
        public String reason;
        public OrderProblemRequest(long orderId, String reason) { this.orderId = orderId; this.reason = reason; }
    }

    // аккаунт / разблокировка / обжалование / возврат на должность - достаточно текста
    public static class TextRequest {
        public String reason;
        public TextRequest(String reason) { this.reason = reason; }
    }

    // оценка решения модератора: понравилось или нет + комментарий
    public static class FeedbackRequest {
        public boolean positive;
        public String comment;
        public FeedbackRequest(boolean positive, String comment) { this.positive = positive; this.comment = comment; }
    }

    // модератор блокирует юзера, причина обязательна
    public static class BlockRequest {
        public String reason;
        public BlockRequest(String reason) { this.reason = reason; }
    }

    // одна блокировка в истории: когда, за что, сняли ли и почему
    public static class Block {
        public long id;
        public long userId;
        public String userNickname;
        public String reason;
        public String createdAt;
        public String liftedAt;
        public String liftReason;
        public boolean reversedByAppeal;
        public String blockedByLabel;
    }

    // вся статистика модератора для админа: сам модератор, его заявки и его блокировки
    public static class ModeratorStats {
        public Moderator moderator;
        public java.util.List<Ticket> tickets;
        public java.util.List<Block> blocks;
    }
}
