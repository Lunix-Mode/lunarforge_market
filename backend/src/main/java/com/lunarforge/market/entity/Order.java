package com.lunarforge.market.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

// пока PENDING_CONFIRMATION деньги покупателя висят в заказе.
// после подтверждения sellerAmount уходит продавцу (замороженным), commissionAmount остаётся
// площадке
@Entity
@Table(name = "orders")
@Getter
@Setter
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // lazy - лот не грузится из базы, пока я к нему не обращусь, так меньше запросов
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "listing_id", nullable = false)
    private Listing listing;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "buyer_id", nullable = false)
    private User buyer;

    // продавца храню прямо в заказе, а не только через listing - так проще искать мои продажи
    // и понятно кто продавец, даже если лот потом поменяют
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_id", nullable = false)
    private User seller;

    // полная сумма, которую заплатил покупатель. scale = 2 - копейки, деньги только в BigDecimal,
    // double на деньгах даёт ошибки округления
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    // комиссия площадки (5%)
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal commissionAmount = BigDecimal.ZERO;

    // сколько получит продавец после вычета комиссии
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal sellerAmount = BigDecimal.ZERO;

    @Column(nullable = false)
    private Integer quantity = 1;

    // сколько вернули покупателю по спору. null/0 - возврата не было.
    // колонка nullable специально: иначе постгрес не даст добавить её в таблицу где уже есть заказы
    @Column(precision = 19, scale = 2)
    private BigDecimal refundedAmount;

    // статус храню строкой (EnumType.STRING), а не числом - если поменять порядок в enum, старые записи не поедут
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status = OrderStatus.PENDING_CONFIRMATION;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    // когда покупатель подтвердил получение (или спор закрыли). сами 48ч холда считаются не отсюда,
    // а по отдельной записи с releaseAt в сервисе
    private Instant confirmedAt;
    private Instant cancelledAt;

    public enum OrderStatus {
        PENDING_CONFIRMATION,
        DISPUTED,   // покупатель запросил возврат, решает модератор
        COMPLETED,
        CANCELLED
    }
}
