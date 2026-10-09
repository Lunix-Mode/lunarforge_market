package com.lunarforge.market.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

// buyer/seller тут по сути просто участник 1 и участник 2.
// с одним человеком всегда один чат, неважно кто у кого покупал
@Entity
@Table(name = "chat_threads")
@Getter
@Setter
public class ChatThread {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    // ключ, база сама увеличивает (IDENTITY)
    private Long id;

    // LAZY - юзера из базы подтягиваю только когда реально к нему обращаюсь, а не при каждой загрузке чата
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "buyer_id", nullable = false)
    private User buyer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_id", nullable = false)
    private User seller;

    // товар, с которого начали переписку. необязательный, поэтому без optional=false
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "listing_id")
    private Listing listing;

    // когда создали чат
    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    // время последнего сообщения - обновляю при каждой отправке, по нему список чатов сортируется (свежие сверху)
    @Column(nullable = false)
    private Instant lastMessageAt = Instant.now();
}
