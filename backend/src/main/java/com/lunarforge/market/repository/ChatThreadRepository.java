package com.lunarforge.market.repository;

import com.lunarforge.market.entity.ChatThread;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

// репозиторий чатов. обычные методы (save, findById) даёт JpaRepository, свои запросы ниже на JPQL
public interface ChatThreadRepository extends JpaRepository<ChatThread, Long> {
    // ищем в обе стороны, кто там buyer а кто seller неважно
    // ORDER BY id - если вдруг каким-то образом создалось два чата между людьми, берём самый старый
    @org.springframework.data.jpa.repository.Query(
        "SELECT t FROM ChatThread t WHERE (t.buyer.id = :a AND t.seller.id = :b) " +
        "OR (t.buyer.id = :b AND t.seller.id = :a) ORDER BY t.id ASC")
    List<ChatThread> findBetween(@org.springframework.data.repository.query.Param("a") Long a,
                                 @org.springframework.data.repository.query.Param("b") Long b);

    // удобная обёртка: один чат между двумя людьми или пусто. default-метод, потому что
    // в интерфейсе репозитория нельзя просто так написать логику без default
    default Optional<ChatThread> findOneBetween(Long a, Long b) {
        return findBetween(a, b).stream().findFirst();
    }

    // все чаты, где юзер участвует с любой стороны, свежие сверху
    @org.springframework.data.jpa.repository.Query(
        "SELECT t FROM ChatThread t WHERE t.buyer.id = :userId OR t.seller.id = :userId " +
        "ORDER BY t.lastMessageAt DESC")
    List<ChatThread> findAllForUser(@org.springframework.data.repository.query.Param("userId") Long userId);
}
