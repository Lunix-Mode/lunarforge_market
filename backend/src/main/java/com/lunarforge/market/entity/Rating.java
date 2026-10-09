package com.lunarforge.market.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

// отзыв покупателя о продавце после сделки. геттеры/сеттеры генерит lombok
@Entity
@Table(name = "ratings")
@Getter
@Setter
public class Rating {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // отзыв привязан к заказу, unique = true - на один заказ можно оставить только один отзыв,
    // это защита ещё и на уровне базы, а не только проверкой в сервисе
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    // кто оценил (покупатель)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rater_id", nullable = false)
    private User rater;

    // кого оценили. храню отдельно, хотя можно достать через order, - так проще искать все отзывы продавца
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_id", nullable = false)
    private User seller;

    // звёзды, диапазон проверяется в RatingService (validateScore)
    @Column(nullable = false)
    private int score;

    // текст отзыва, необязательный
    @Column(length = 1000)
    private String comment;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    // время последней правки. RatingService не даёт редактировать чаще раза в 24 часа, считает от этого поля
    @Column(nullable = false)
    private Instant lastEditAt = Instant.now();
}
