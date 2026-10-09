package com.lunarforge.market.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

// история блокировок. по ней видно кто кого и за что блокировал,
// и сколько блокировок модератора потом сняли по апелляции (это бьёт по его рейтингу)
// одна запись = одна блокировка. когда снимают - запись не удаляю, а заполняю lifted*,
// чтобы история осталась
@Entity
@Table(name = "block_records")
@Getter
@Setter
public class BlockRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // кого заблокировали. LAZY - чтобы при загрузке списка не тянуть сразу всех юзеров из базы
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // кто заблокировал (модератор или админ)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blocked_by_id", nullable = false)
    private User blockedBy;

    // причина обязательна, её потом видит заблокированный
    @Column(nullable = false, length = 1000)
    private String reason;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    // когда сняли блокировку. null = блокировка ещё действует
    private Instant liftedAt;

    // кто снял и почему (тоже null, пока не сняли)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lifted_by_id")
    private User liftedBy;

    @Column(length = 1000)
    private String liftReason;

    // сняли по заявке на разблокировку другим модератором = блокировка была необоснованной
    @Column(nullable = false)
    private boolean reversedByAppeal = false;
}
