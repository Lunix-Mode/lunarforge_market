package com.lunarforge.market.dto;

import java.math.BigDecimal;
import java.time.Instant;

// dto для переводов денег между пользователями по @username
public class TransferDtos {
    // запрос на перевод: кому (username, а не id - его человек знает и вводит руками),
    // сколько и необязательная подпись. сумма в BigDecimal, чтобы не было ошибок округления как с double
    public record CreateTransferRequest(
            String toUsername,
            BigDecimal amount,
            String message
    ) {}

    // готовый перевод для истории: кто отправил, кто получил, сумма, сообщение и когда
    public record TransferResponse(
            Long id,
            Long senderId,
            String senderUsername,
            Long receiverId,
            String receiverUsername,
            BigDecimal amount,
            String message,
            Instant createdAt
    ) {}
}
