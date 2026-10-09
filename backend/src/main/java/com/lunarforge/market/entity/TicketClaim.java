package com.lunarforge.market.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

// кто и когда брал заявку. assignee в самой заявке хранит только последнего,
// а для статистики модератора нужны и заявки, которые у него потом перехватили.
// то есть это просто журнал: каждый раз когда модератор берёт заявку - новая строчка
@Entity
@Table(name = "ticket_claims")
@Getter
@Setter
public class TicketClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // какую заявку взяли
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Ticket ticket;

    // какой модератор/админ взял
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "staff_id", nullable = false)
    private User staff;

    // время взятия, ставится само при создании объекта
    @Column(nullable = false)
    private Instant claimedAt = Instant.now();
}
