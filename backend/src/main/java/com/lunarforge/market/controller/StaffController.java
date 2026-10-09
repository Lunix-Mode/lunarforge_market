package com.lunarforge.market.controller;

import com.lunarforge.market.dto.TicketDtos.*;
import com.lunarforge.market.security.AppUserDetails;
import com.lunarforge.market.service.StaffService;
import com.lunarforge.market.service.TicketService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

// модераторы и админ (доступ ограничен в SecurityConfig: /api/staff/** только MODERATOR/ADMIN)
// всё, что делают модераторы: список заявок, взять заявку, вынести решение, блокировки.
// сама логика и проверки (конфликт интересов, кто может решать) - в TicketService и StaffService, тут только адреса
@RestController
@RequestMapping("/api/staff")
public class StaffController {

    private final TicketService ticketService;
    private final StaffService staffService;

    public StaffController(TicketService ticketService, StaffService staffService) {
        this.ticketService = ticketService;
        this.staffService = staffService;
    }

    // filter: new / mine / in_progress / resolved / all; type и party - необязательные
    // p - текущий юзер из JWT. сервис сам ещё раз проверяет, что он модератор/админ
    @GetMapping("/tickets")
    public List<TicketResponse> tickets(@AuthenticationPrincipal AppUserDetails p,
                                        @RequestParam(required = false) String filter,
                                        @RequestParam(required = false) String type,
                                        @RequestParam(required = false) String party) {
        return ticketService.staffList(p.getUser(), filter, type, party);
    }

    // одна заявка подробно
    @GetMapping("/tickets/{id}")
    public TicketResponse ticket(@AuthenticationPrincipal AppUserDetails p, @PathVariable Long id) {
        return ticketService.staffGet(p.getUser(), id);
    }

    // взять заявку себе. если её уже ведёт другой и он не пропал на 8ч - сервер вернёт 409
    @PostMapping("/tickets/{id}/claim")
    public TicketResponse claim(@AuthenticationPrincipal AppUserDetails p, @PathVariable Long id) {
        return ticketService.claim(p.getUser(), id);
    }

    // вынести решение (возврат, блокировка и т.д.) - только тот модератор, который заявку взял
    @PostMapping("/tickets/{id}/resolve")
    public TicketResponse resolve(@AuthenticationPrincipal AppUserDetails p, @PathVariable Long id, @Valid @RequestBody ResolveRequest req) {
        return ticketService.resolve(p.getUser(), id, req);
    }

    // блокировка вручную, без заявки. req.reason() обязателен - его потом видит заблокированный
    @PostMapping("/users/{userId}/block")
    public BlockResponse block(@AuthenticationPrincipal AppUserDetails p, @PathVariable Long userId, @Valid @RequestBody BlockRequest req) {
        return staffService.block(p.getUser(), userId, req.reason());
    }

    // ручная разблокировка (не по апелляции) - на рейтинг заблокировавшего не влияет
    // тело необязательное: причину можно не писать, тогда body = null и передаю null
    @PostMapping("/users/{userId}/unblock")
    public void unblock(@AuthenticationPrincipal AppUserDetails p, @PathVariable Long userId, @RequestBody(required = false) Map<String, String> body) {
        staffService.unblock(p.getUser(), userId, body != null ? body.get("reason") : null, false);
    }

    // история блокировок юзера. тут проверка прав прямо в контроллере, потому что blocksOf сам права не проверяет
    @GetMapping("/users/{userId}/blocks")
    public List<BlockResponse> blocks(@AuthenticationPrincipal AppUserDetails p, @PathVariable Long userId) {
        StaffService.requireStaff(p.getUser());
        return staffService.blocksOf(userId);
    }
}
