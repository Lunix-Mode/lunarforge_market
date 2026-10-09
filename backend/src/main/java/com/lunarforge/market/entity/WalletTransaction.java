package com.lunarforge.market.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

// одна запись в истории кошелька (таблица wallet_transactions).
// сам баланс хранится в User (balanceAvailable / balanceFrozen), а тут - лог каждой операции: пополнение, покупка, перевод и т.д.
// по этим записям строится экран "история операций" в приложении, и по ним можно проверить, откуда взялся баланс
@Entity
@Table(name = "wallet_transactions")
@Getter
@Setter
public class WalletTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // чей это кошелёк
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    // сумма операции. BigDecimal, а не double - с деньгами double даёт ошибки вроде 0.1+0.2=0.30000000000000004
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    // текст для пользователя, например "Покупка: ..."
    @Column(nullable = false)
    private String description;

    // просто id без связи @ManyToOne: нужно только чтобы из операции открыть заказ или перевод
    private Long relatedOrderId;
    private Long relatedTransferId;

    // для красивого экрана операции: с кем (перевод / покупатель / продавец) и сообщение к переводу
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "counterparty_id")
    private User counterparty;

    @Column(length = 500)
    private String message;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public enum Type {
        TOP_UP,             // пополнение
        WITHDRAWAL,         // вывод
        PURCHASE,           // покупка: деньги списаны у покупателя и ждут в сделке, пока он не подтвердит
        SALE_FROZEN,        // покупатель подтвердил - продавцу начислено, но 48ч заморожено
        SALE_RELEASED,      // заморозка снята, деньги стали доступны продавцу
        REFUND,             // возврат покупателю
        TRANSFER_OUT,       // перевод другому пользователю (у отправителя)
        TRANSFER_IN,        // перевод от другого пользователя (у получателя)
        COMPENSATION,       // компенсация от площадки
        CLAWBACK            // списание у продавца: возврат покупателю по решению модератора
    }
}
