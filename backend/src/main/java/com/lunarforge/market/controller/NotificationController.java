package com.lunarforge.market.controller;

import com.lunarforge.market.dto.NotificationDtos.NotificationResponse;
import com.lunarforge.market.dto.NotificationDtos.UnreadCount;
import com.lunarforge.market.security.AppUserDetails;
import com.lunarforge.market.service.NotificationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// уведомления в приложении (колокольчик). все методы работают только с уведомлениями текущего юзера:
// id беру из токена через @AuthenticationPrincipal, а не из запроса - иначе можно было бы читать чужие
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    // список моих уведомлений
    @GetMapping
    public List<NotificationResponse> mine(@AuthenticationPrincipal AppUserDetails principal) {
        return service.mine(principal.getUser().getId());
    }

    // приложение дёргает это раз в ~30 сек, чтобы показать цифру на колокольчике
    @GetMapping("/unread-count")
    public UnreadCount unread(@AuthenticationPrincipal AppUserDetails principal) {
        return new UnreadCount(service.unread(principal.getUser().getId()));
    }

    // открыл экран уведомлений - все помечаются прочитанными
    @PostMapping("/read-all")
    public void readAll(@AuthenticationPrincipal AppUserDetails principal) {
        service.markAllRead(principal.getUser().getId());
    }

    // пометить одно. userId тоже передаю - сервис проверит, что уведомление моё
    @PostMapping("/{id}/read")
    public void read(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long id) {
        service.markRead(principal.getUser().getId(), id);
    }
}
