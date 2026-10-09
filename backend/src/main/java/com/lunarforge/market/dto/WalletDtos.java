package com.lunarforge.market.dto;

import java.math.BigDecimal;
import java.time.Instant;

// ответы по кошельку. одна операция = одна строка в истории + экран подробностей
public class WalletDtos {
    // record с кучей полей - так один запрос отдаёт всё для экрана подробностей, второй запрос с телефона не нужен
    public record TransactionResponse(
            Long id,
            // тип операции (пополнение, вывод, покупка, продажа, перевод, компенсация...) строкой, приложение по нему рисует иконку
            String type,
            // BigDecimal, не double - с деньгами double теряет копейки (0.1 + 0.2 != 0.3)
            BigDecimal amount,
            String description,
            Instant createdAt,
            String title,                 // "Входящий перевод", "Продажа", ...
            // counterparty - вторая сторона: кому перевёл / от кого пришло / кто купил. может быть null (пополнение с карты)
            Long counterpartyId,
            String counterpartyNickname,
            String counterpartyUsername,
            String counterpartyAvatarUrl,
            // если операция по заказу - id и название товара, чтобы с экрана операции перейти в заказ
            Long orderId,
            String orderTitle,
            String message,               // сообщение к переводу - отдельно, а не через тире в описании
            Instant releaseAt,            // для продажи: когда разморозится
            Boolean released              // для продажи: уже разморожено или нет
    ) {}
}
