package com.lunarforge.market.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

// nickname - просто отображаемое имя, можно менять, может повторяться.
// username - уникальный @ник, по нему переводы. balanceFrozen = заработал, но ещё 48ч нельзя
// тратить
// таблица users. @Getter/@Setter - это lombok, он сам генерирует геттеры и сеттеры при компиляции, поэтому их тут не видно.
// uniqueConstraints - база сама не даст двух юзеров с одной почтой или одним @username, даже если две регистрации придут одновременно
@Entity
@Table(name = "users", uniqueConstraints = {
        @UniqueConstraint(columnNames = "email"),
        @UniqueConstraint(columnNames = "username")
})
@Getter
@Setter
public class User {
    // IDENTITY - id выдаёт сама база (автоинкремент)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String nickname;

    @Column(nullable = false, unique = true)
    private String username;

    // пароль НЕ храню, только BCrypt-хэш
    @Column(nullable = false)
    private String passwordHash;

    // деньги - BigDecimal и в базе numeric(19,2), ровно 2 знака после запятой.
    // balanceAvailable - можно тратить и выводить прямо сейчас
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balanceAvailable = BigDecimal.ZERO;

    // balanceFrozen - деньги от продаж, которые ещё висят 48ч после подтверждения. потом шедулер переносит их в available
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balanceFrozen = BigDecimal.ZERO;

    // null - аватарки нет, приложение покажет букву ника
    private String avatarUrl;

    // STRING, а не ORDINAL - в базе пишется "ADMIN", а не число. добавлю новую роль в середину - старые записи не поедут
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.USER;

    // заблокированный не может пользоваться площадкой. причину и время храню, чтобы показать их человеку
    @Column(nullable = false)
    private boolean blocked = false;

    @Column(length = 1000)
    private String blockReason;
    private Instant blockedAt;

    // модератора сняли автоматически за низкий рейтинг - админ видит таких в отдельной вкладке
    @Column(nullable = false)
    private boolean demotedAutomatically = false;
    private Instant demotedAt;
    @Column(length = 500)
    private String demotionReason;

    // рейтинг продавца храню суммой и количеством. средний = ratingSum / ratingCount,
    // так не нужно каждый раз пересчитывать все отзывы (обновляется в RatingService)
    @Column(nullable = false)
    private int ratingSum = 0;

    @Column(nullable = false)
    private int ratingCount = 0;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public enum Role {
        USER, MODERATOR, ADMIN // модераторов назначает админ по id
    }
}
