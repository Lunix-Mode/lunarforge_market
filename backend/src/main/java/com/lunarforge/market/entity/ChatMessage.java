package com.lunarforge.market.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

// одно сообщение в чате (таблица chat_messages).
// сообщение может быть просто текстом, или с вложением: фото, видео, голосовое или кружок.
// геттеры/сеттеры генерирует lombok, поэтому их тут не видно
@Entity
@Table(name = "chat_messages")
@Getter
@Setter
public class ChatMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // в каком чате сообщение. LAZY - сам чат не грузится из базы, пока я к нему не обращусь,
    // иначе при загрузке сотни сообщений было бы ещё сто лишних запросов
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "thread_id", nullable = false)
    private ChatThread thread;

    // кто отправил
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    // текст может быть пустым, если отправили только вложение
    @Column(length = 4000)
    private String text;

    // ссылка вида /files/uuid.ext, которую вернул FileStorageService
    private String attachmentUrl;

    // храню тип строкой (PHOTO, VOICE...), а не числом - так в базе понятнее
    // и не сломается, если поменять порядок значений в enum
    @Enumerated(EnumType.STRING)
    private AttachmentType attachmentType;

    // длительность голосового/видео в секундах, чтобы показать "0:12" без скачивания файла
    private Integer attachmentDurationSeconds;

    @Column(nullable = false)
    private Instant sentAt = Instant.now();

    // прочитано ли получателем - по этому считаются непрочитанные и галочки
    @Column(nullable = false)
    private boolean read = false;

    // VIDEO_NOTE - это круглое видео-сообщение, как в телеграме
    public enum AttachmentType {
        PHOTO, VIDEO, VOICE, VIDEO_NOTE
    }
}
