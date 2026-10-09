package com.lunarforge.market.controller;

import com.lunarforge.market.dto.OrderDtos.CreateOrderRequest;
import com.lunarforge.market.dto.OrderDtos.OrderResponse;
import com.lunarforge.market.security.AppUserDetails;
import com.lunarforge.market.service.OrderService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// REST-контроллер заказов (сделок). сам ничего не считает, всю логику с деньгами отдаю в OrderService,
// тут только достаю текущего юзера из токена и передаю дальше.
// principal - это залогиненный пользователь, его подставляет spring security по jwt
@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    // покупка лота: POST /api/orders с id лота и количеством.
    // в сервисе с покупателя списываются деньги и замораживаются в заказе, пока он не подтвердит получение
    @PostMapping
    public OrderResponse purchase(@AuthenticationPrincipal AppUserDetails principal,
                                   @RequestBody CreateOrderRequest request) {
        return orderService.purchase(principal.getUser(), request.listingId(), request.quantity());
    }

    // подтверждает только покупатель, после этого деньги уходят продавцу (замороженными).
    // проверка что это именно покупатель сделана в сервисе, а не тут
    @PostMapping("/{id}/confirm")
    public OrderResponse confirm(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long id) {
        return orderService.confirmReceipt(principal.getUser(), id);
    }

    // отменяет только продавец - иначе покупатель мог бы получить товар и вернуть деньги.
    // при отмене деньги возвращаются покупателю
    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long id) {
        return orderService.cancel(principal.getUser(), id);
    }

    // один заказ по id. getForParty - значит отдаю только участнику сделки (покупателю или продавцу),
    // чужой заказ посмотреть нельзя
    @GetMapping("/{id}")
    public OrderResponse get(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long id) {
        return orderService.getForParty(principal.getUser(), id);
    }

    // мои покупки. если page не передали - отдаю весь список (так работали старые экраны),
    // если передали - постранично по size штук, чтобы не тянуть сотни заказов разом
    @GetMapping("/purchases")
    public List<OrderResponse> myPurchases(@AuthenticationPrincipal AppUserDetails principal,
                                           @RequestParam(required = false) Integer page,
                                           @RequestParam(defaultValue = "30") int size) {
        if (page != null) return orderService.myPurchases(principal.getUser().getId(), page, size);
        return orderService.myPurchases(principal.getUser().getId());
    }

    // мои продажи, то же самое что покупки, только я тут в роли продавца
    @GetMapping("/sales")
    public List<OrderResponse> mySales(@AuthenticationPrincipal AppUserDetails principal,
                                       @RequestParam(required = false) Integer page,
                                       @RequestParam(defaultValue = "30") int size) {
        if (page != null) return orderService.mySales(principal.getUser().getId(), page, size);
        return orderService.mySales(principal.getUser().getId());
    }
}
