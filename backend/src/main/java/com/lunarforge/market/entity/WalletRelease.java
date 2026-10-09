package com.lunarforge.market.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

// создаётся когда продавец получает деньги в замороженный баланс (подтверждение заказа или решение спора).
// шедулер раз в минуту берёт те, у которых releaseAt уже прошёл, и переносит amount из frozen в available
// таймер разморозки для одной продажи. обрабатывает WalletReleaseScheduler
@Entity
@Table(name = "wallet_releases")
// lombok генерирует геттеры/сеттеры сам, поэтому их тут нет
@Getter
@Setter
public class WalletRelease {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // чей это баланс (продавец). LAZY - юзер подгрузится из базы только если к нему обратиться
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // за какой заказ эти деньги - по нему находим таймер при возврате за счёт продавца
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    // сколько разморозить. может уменьшиться, если часть вернули покупателю
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    // когда разморозить (время подтверждения + 48ч)
    private Instant releaseAt;

    @Column(nullable = false)
    // true - уже разморожено, второй раз шедулер его не возьмёт
    private boolean released = false;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
