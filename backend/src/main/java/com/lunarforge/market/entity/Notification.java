package com.lunarforge.market.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

// уведомление в колокольчике. refType + refId - куда вести по нажатию (заказ, заявка, операция...)
// индекс по (user_id, createdAt) - потому что почти всегда выбираем "уведомления этого юзера,
// свежие сверху", без индекса база бы перебирала всю таблицу
@Entity
@Table(name = "notifications", indexes = @Index(name = "idx_notifications_user", columnList = "user_id, createdAt"))
@Getter
@Setter
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // кому уведомление
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 40)
    private String type;     // ORDER_NEW, ORDER_CONFIRMED, MONEY_RELEASED, TICKET_RESOLVED ...

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 1000)
    private String body;

    @Column(length = 20)
    private String refType;  // ORDER / TICKET / TRANSACTION / USER

    // id того объекта, на который указывает refType
    private Long refId;

    // колонку назвал is_read, потому что read может оказаться служебным словом в некоторых БД
    @Column(name = "is_read", nullable = false)
    private boolean read = false;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
