package com.lunarforge.market.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

// перевод денег между пользователями (с кошелька на кошелёк по нику).
// запись нужна для истории: кто, кому, сколько и с каким сообщением.
// сами балансы меняются в WalletService, тут только лог
@Entity
@Table(name = "transfers")
@Getter
@Setter
public class Transfer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // отправитель, LAZY чтобы не грузить юзера лишний раз
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    // получатель
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receiver_id", nullable = false)
    private User receiver;

    // сумма. деньги храню в BigDecimal с 2 знаками после запятой, double для денег нельзя - копейки поплывут
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    // необязательный комментарий к переводу
    private String message;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
