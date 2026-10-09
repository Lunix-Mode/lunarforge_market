package com.lunarforge.market.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

// оценка решения по тикету: лайк или дизлайк от участника. из них складывается рейтинг модератора.
// unique по (ticket_id, user_id) - один человек может оценить тикет только один раз, это база сама не даст нарушить
@Entity
@Table(name = "ticket_feedback", uniqueConstraints = @UniqueConstraint(columnNames = {"ticket_id", "user_id"}))
@Getter
@Setter
public class TicketFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Ticket ticket;

    // кто оценил (участник тикета)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // кому ставим: модератор, который вынес решение (на момент оценки)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "staff_id", nullable = false)
    private User staff;

    // true - решение понравилось, false - нет
    @Column(nullable = false)
    private boolean positive;

    // необязательный комментарий к оценке
    @Column(length = 1000)
    private String comment;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
