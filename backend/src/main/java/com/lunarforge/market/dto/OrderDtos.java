package com.lunarforge.market.dto;

import com.lunarforge.market.entity.Order;

import java.math.BigDecimal;
import java.time.Instant;

// dto для заказов. отдельные классы для ответа нужны, чтобы не светить entity наружу
// (там lazy связи и лишние поля, jackson бы на них упал или выдал лишнее)
public class OrderDtos {
    // запрос на покупку: какой лот и сколько штук
    public record CreateOrderRequest(Long listingId, Integer quantity) {}

    // заказ в том виде, как его видит приложение
    public record OrderResponse(
            Long id,
            Long listingId,
            String listingTitle,
            Long buyerId,
            String buyerNickname,
            Long sellerId,
            String sellerNickname,
            // amount - сколько всего заплатил покупатель
            BigDecimal amount,
            // commissionAmount - комиссия площадки, sellerAmount - что достанется продавцу
            BigDecimal commissionAmount,
            BigDecimal sellerAmount,
            Integer quantity,
            Order.OrderStatus status,
            Instant createdAt,
            // когда покупатель подтвердил получение, null если ещё не подтвердил
            Instant confirmedAt,
            // сколько вернули покупателю по спору, null если возврата не было
            BigDecimal refundedAmount
    ) {}
}
