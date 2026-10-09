package com.lunarforge.market.controller;

import com.lunarforge.market.dto.TicketDtos.*;
import com.lunarforge.market.security.AppUserDetails;
import com.lunarforge.market.service.TicketService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// заявки глазами обычного пользователя.
// тут создаются все виды заявок (возврат, проблема с заказом, жалоба, аккаунт, разблокировка...)
// и можно посмотреть свои. модераторская часть (взять, решить) живёт в другом контроллере.
// везде p - это текущий юзер из JWT, его подставляет spring security через @AuthenticationPrincipal,
// так что подделать "от чьего имени" заявка нельзя. @Valid проверяет поля DTO (пустой текст и т.п.)
// ещё до того как мы зайдём в сервис - иначе отдаётся 400
@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    // покупатель просит вернуть деньги по заказу
    @PostMapping("/refund")
    public TicketResponse refund(@AuthenticationPrincipal AppUserDetails p, @Valid @RequestBody CreateRefundRequest req) {
        return ticketService.createRefund(p.getUser(), req);
    }

    // проблема с заказом (не выдали товар, что-то не так), без требования возврата
    @PostMapping("/order-problem")
    public TicketResponse orderProblem(@AuthenticationPrincipal AppUserDetails p, @Valid @RequestBody CreateOrderProblemRequest req) {
        return ticketService.createOrderProblem(p.getUser(), req);
    }

    // жалоба на другого пользователя
    @PostMapping("/complaint")
    public TicketResponse complaint(@AuthenticationPrincipal AppUserDetails p, @Valid @RequestBody CreateComplaintRequest req) {
        return ticketService.createComplaint(p.getUser(), req);
    }

    // проблема с собственным аккаунтом, просто текст
    @PostMapping("/account")
    public TicketResponse account(@AuthenticationPrincipal AppUserDetails p, @Valid @RequestBody TextRequest req) {
        return ticketService.createAccountProblem(p.getUser(), req);
    }

    // доступно и заблокированным (см. JwtAuthFilter) - иначе как им просить разблокировку
    @PostMapping("/unblock")
    public TicketResponse unblock(@AuthenticationPrincipal AppUserDetails p, @Valid @RequestBody TextRequest req) {
        return ticketService.createUnblockAppeal(p.getUser(), req);
    }

    // бывший модератор просит вернуть должность (решает только админ)
    @PostMapping("/reinstatement")
    public TicketResponse reinstatement(@AuthenticationPrincipal AppUserDetails p, @Valid @RequestBody TextRequest req) {
        return ticketService.createReinstatementRequest(p.getUser(), req);
    }

    // обжалование решения модератора по заявке id. проверки (можно ли, не обжаловали ли уже) в сервисе
    @PostMapping("/{id}/appeal")
    public TicketResponse appeal(@AuthenticationPrincipal AppUserDetails p, @PathVariable Long id, @Valid @RequestBody TextRequest req) {
        return ticketService.createDecisionAppeal(p.getUser(), id, req);
    }

    // отзыв о работе модератора после закрытия заявки
    @PostMapping("/{id}/feedback")
    public TicketResponse feedback(@AuthenticationPrincipal AppUserDetails p, @PathVariable Long id, @Valid @RequestBody FeedbackRequest req) {
        return ticketService.feedback(p.getUser(), id, req);
    }

    // мои заявки: и те что я подал, и те что касаются моих заказов (см. findVisibleTo)
    @GetMapping("/mine")
    public List<TicketResponse> mine(@AuthenticationPrincipal AppUserDetails p) {
        return ticketService.mine(p.getUser());
    }

    // одна заявка. partyGet внутри проверяет что я участник, чужую не покажет
    @GetMapping("/{id}")
    public TicketResponse get(@AuthenticationPrincipal AppUserDetails p, @PathVariable Long id) {
        return ticketService.partyGet(p.getUser(), id);
    }

    // 204 если заявок по заказу нет - ретрофит тогда спокойно отдаёт body = null
    // (если бы отдавал 404, на клиенте это выглядело бы как ошибка)
    @GetMapping("/order/{orderId}")
    public ResponseEntity<TicketResponse> forOrder(@AuthenticationPrincipal AppUserDetails p, @PathVariable Long orderId) {
        TicketResponse t = ticketService.forOrder(p.getUser(), orderId);
        return t == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(t);
    }
}
