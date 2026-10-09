package com.lunarforge.market.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

// заявка для модерации: возврат по заказу или жалоба на пользователя.
// общение идёт в обычном чате (thread): для возврата это чат покупателя с продавцом,
// для жалобы - чат автора жалобы с ботом поддержки. модератор пишет прямо туда.
// жизненный цикл: OPEN (никто не взял) -> IN_PROGRESS (модератор взял себе) -> RESOLVED (вынесено решение).
// все enum храню строками (EnumType.STRING), чтобы в базе было читаемо и порядок значений можно было менять
@Entity
@Table(name = "tickets")
@Getter
@Setter
public class Ticket {

    public enum Type {
        REFUND,          // возврат по заказу (заказ замораживается)
        ORDER_PROBLEM,   // проблема с заказом от покупателя или продавца (заказ не замораживается)
        ACCOUNT_PROBLEM, // проблема с аккаунтом
        COMPLAINT,       // жалоба на пользователя
        UNBLOCK_APPEAL,  // просьба разблокировать
        DECISION_APPEAL, // обжалование решения другого модератора
        MODERATOR_REINSTATEMENT // бывший модератор просит вернуть его на должность (видит и решает только админ)
    }

    public enum Status { OPEN, IN_PROGRESS, RESOLVED }

    public enum Resolution {
        FULL_REFUND,     // всё назад покупателю
        PARTIAL_REFUND,  // часть покупателю, остальное продавцу
        NO_REFUND,       // в пользу продавца
        CLOSED,          // рассмотрено, без денег
        BLOCKED_USER,    // по жалобе: нарушитель заблокирован
        UNBLOCKED,       // разблокировка одобрена
        UPHELD,          // обжалование: решение модератора оставлено
        OVERTURNED,      // обжалование: решение признано ошибочным (минус в рейтинг тому модератору)
        REINSTATED,      // админ вернул модератора на должность
        SELLER_REFUND,   // проблема с завершённым заказом: покупателю вернули за счёт продавца
        COMPENSATED      // площадка выплатила компенсацию из своих денег
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // тип заявки, от него зависит какие решения модератор может выбрать
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.OPEN;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;                 // только для возврата

    // чат, в котором идёт разбирательство (обязателен у любой заявки)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "thread_id", nullable = false)
    private ChatThread thread;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter;               // кто подал

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reported_user_id")
    private User reportedUser;           // на кого жалоба

    @Column(nullable = false, length = 2000)
    private String reason;

    @Column(length = 10)
    private String reporterRole;         // BUYER / SELLER - кем заявитель был в заказе (для проблем с заказом)

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appeal_of_id")
    private Ticket appealOf;             // для обжалования: какую заявку обжалуют

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "block_record_id")
    private BlockRecord blockRecord;     // для разблокировки: какую блокировку оспаривают

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private User assignee;               // ответственный модератор, максимум один

    // когда модератор взял заявку себе
    private Instant claimedAt;
    // две даты ниже нужны, чтобы видеть зависшие заявки: модератор взял и пропал, или стороны давно молчат
    private Instant lastStaffActivityAt; // когда ответственный последний раз что-то написал/сделал
    private Instant lastPartyMessageAt;  // когда стороны последний раз написали в чат

    // итог, заполняется только при закрытии (до этого null)
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Resolution resolution;

    // сколько вернули покупателю при частичном возврате/компенсации.
    // деньги только в BigDecimal с 2 знаками - double терял бы копейки при округлении
    @Column(precision = 19, scale = 2)
    private BigDecimal refundAmount;

    // пояснение модератора к решению, его видят стороны
    @Column(length = 1000)
    private String resolutionNote;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private Instant resolvedAt;
}
