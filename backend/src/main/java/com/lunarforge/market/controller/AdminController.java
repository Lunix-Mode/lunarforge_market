package com.lunarforge.market.controller;

import com.lunarforge.market.dto.AdminDtos.MonthlyRevenue;
import com.lunarforge.market.dto.AdminDtos.RevenueResponse;
import com.lunarforge.market.repository.OrderRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

// эндпоинты админки: список модераторов, назначение/снятие, их статистика и доход площадки.
// сам контроллер роль не проверяет - в SecurityConfig стоит /api/admin/** -> hasRole("ADMIN"),
// так что обычный юзер или модератор сюда даже не дойдёт, получит 403 раньше
@RestController
@RequestMapping("/api/admin")
public class AdminController {
    // репозиторий заказов нужен только для подсчёта комиссии (дохода)
    private final OrderRepository orderRepository;
    // вся логика с модераторами (назначить, снять, рейтинг) лежит в StaffService
    private final com.lunarforge.market.service.StaffService staffService;
    // а подробная статистика по заявкам - в TicketService
    private final com.lunarforge.market.service.TicketService ticketService;

    // зависимости через конструктор, спринг сам их подставит
    public AdminController(OrderRepository orderRepository, com.lunarforge.market.service.StaffService staffService,
                           com.lunarforge.market.service.TicketService ticketService) {
        this.ticketService = ticketService;
        this.orderRepository = orderRepository;
        this.staffService = staffService;
    }

    // действующие модераторы и админ с рейтингом и цифрами
    @GetMapping("/moderators")
    public List<com.lunarforge.market.dto.TicketDtos.ModeratorResponse> moderators() {
        return staffService.activeStaff();
    }

    // сняты автоматически за низкий рейтинг
    @GetMapping("/moderators/demoted")
    public List<com.lunarforge.market.dto.TicketDtos.ModeratorResponse> demoted() {
        return staffService.demoted();
    }

    // подробно: цифры + все заявки, которые он брал или решал, + все его блокировки
    // p - это текущий админ, его передаю дальше, чтобы заявки собрались так, как их видит админ
    @GetMapping("/moderators/{userId}/stats")
    public com.lunarforge.market.dto.TicketDtos.ModeratorStatsResponse stats(
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.lunarforge.market.security.AppUserDetails p,
            @org.springframework.web.bind.annotation.PathVariable Long userId) {
        return ticketService.moderatorStats(p.getUser(), userId);
    }

    // назначить пользователя модератором (POST на его id)
    @org.springframework.web.bind.annotation.PostMapping("/moderators/{userId}")
    public com.lunarforge.market.dto.TicketDtos.ModeratorResponse appoint(@org.springframework.web.bind.annotation.PathVariable Long userId) {
        return staffService.appoint(userId);
    }

    // снять с модерации вручную (DELETE на тот же адрес)
    @org.springframework.web.bind.annotation.DeleteMapping("/moderators/{userId}")
    public void dismiss(@org.springframework.web.bind.annotation.PathVariable Long userId) {
        staffService.dismiss(userId);
    }

    // доход площадки = сумма комиссий (5%) по завершённым заказам.
    // total - за всё время, byMonth - разбивка по месяцам (новые месяцы сверху)
    @GetMapping("/revenue")
    public RevenueResponse revenue() {
        BigDecimal total = orderRepository.totalCommissionRevenue();
        // запрос native и возвращает сырые строки Object[]: [0] - месяц строкой "YYYY-MM",
        // [1] - сумма. поэтому тут руками привожу типы и собираю в нормальный dto
        List<MonthlyRevenue> byMonth = orderRepository.monthlyCommissionRevenue().stream()
                .map(row -> new MonthlyRevenue((String) row[0], (BigDecimal) row[1]))
                .toList();
        return new RevenueResponse(total, byMonth);
    }
}
