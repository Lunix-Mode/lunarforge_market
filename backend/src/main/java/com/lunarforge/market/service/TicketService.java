package com.lunarforge.market.service;

import com.lunarforge.market.dto.TicketDtos.*;
import com.lunarforge.market.entity.*;
import com.lunarforge.market.exception.ApiException;
import com.lunarforge.market.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

// заявки: возвраты, проблемы с заказом, жалобы, аккаунт, разблокировка, обжалование решений.
// одна заявка = один ответственный. молчит 8ч после ответа людей - можно перехватить (админ - всегда).
// модератор не разбирает апелляцию на свою блокировку и не пересматривает своё же решение.
// сюда приходят вызовы из TicketController (пользователь) и StaffController (модераторы).
// все изменения в @Transactional - если посреди решения что-то упадёт (например не хватит денег), откатится и заявка, и деньги
@Service
public class TicketService {

    // сколько часов модератор может молчать, прежде чем заявку перехватят
    public static final long TAKEOVER_HOURS = 8;
    // сколько дней после решения его можно обжаловать
    public static final long APPEAL_DAYS = 7;
    // "живые" статусы - по ним проверяю, нет ли уже открытой заявки такого же типа
    private static final List<Ticket.Status> ACTIVE = List.of(Ticket.Status.OPEN, Ticket.Status.IN_PROGRESS);

    private final TicketRepository ticketRepository;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final ChatService chatService;
    private final StaffService staffService;
    private final TicketClaimRepository claimRepository;
    private final TicketFeedbackRepository feedbackRepository;
    private final WalletService walletService;
    private final NotificationService notifications;

    // все зависимости через конструктор - так спринг их подставит сам, и в тестах легко подменить
    public TicketService(TicketRepository ticketRepository, UserRepository userRepository, OrderRepository orderRepository,
                         OrderService orderService, ChatService chatService, StaffService staffService,
                         TicketClaimRepository claimRepository, TicketFeedbackRepository feedbackRepository,
                         WalletService walletService, NotificationService notifications) {
        this.ticketRepository = ticketRepository;
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.chatService = chatService;
        this.staffService = staffService;
        this.claimRepository = claimRepository;
        this.feedbackRepository = feedbackRepository;
        this.walletService = walletService;
        this.notifications = notifications;
    }

    // ============================== создание ==============================

    // возврат: заказ замораживается (DISPUTED) до решения
    @Transactional
    public TicketResponse createRefund(User buyer, CreateRefundRequest req) {
        // markDisputed внутри сам лочит заказ, проверяет что это покупатель и что заказ ещё можно оспорить,
        // и переводит его в DISPUTED - пока спор идёт, деньги не уйдут продавцу
        Order order = orderService.markDisputed(buyer, req.orderId());
        // заявка привязывается к чату покупателя с продавцом по этому товару - модератор пишет прямо туда
        ChatThread thread = chatService.threadBetween(order.getBuyer(), order.getSeller(), order.getListing().getId());
        Ticket t = newTicket(Ticket.Type.REFUND, buyer, req.reason(), thread);
        t.setOrder(order);
        t.setReporterRole("BUYER");
        ticketRepository.save(t);
        // сообщение от бота в тот же чат, чтобы обе стороны сразу видели, что начался спор
        chatService.postBotMessage(order.getBuyer(), order.getSeller(), order.getListing().getId(),
                "⚖️ Покупатель запросил возврат по заказу #" + order.getId() + " (заявка #" + t.getId() + ").\n"
                        + "Причина: " + t.getReason() + "\n"
                        + "Заказ заморожен до решения модератора. Модератор подключится прямо к этому чату.");
        // продавцу ещё и пуш-уведомление, а то он может не заходить в чат
        notifications.notify(order.getSeller(), "TICKET_OPENED", "⚖️ Запрос возврата по заказу #" + order.getId(),
                "Заказ заморожен до решения модератора. Ответьте на его вопросы в чате.", "TICKET", t.getId());
        return toResponse(t, buyer);
    }

    // проблема с заказом: кто ты в заказе (покупатель/продавец) сервер определяет сам, заказ не замораживается
    @Transactional
    public TicketResponse createOrderProblem(User user, CreateOrderProblemRequest req) {
        Order order = orderRepository.findById(req.orderId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Заказ не найден"));
        // смотрю, кто подаёт: покупатель, продавец или вообще посторонний (тогда null и 403)
        String role = order.getBuyer().getId().equals(user.getId()) ? "BUYER"
                : order.getSeller().getId().equals(user.getId()) ? "SELLER" : null;
        if (role == null) throw new ApiException(HttpStatus.FORBIDDEN, "Это не ваш заказ");
        // не даю наспамить одинаковыми заявками по одному заказу, пока старая не закрыта
        if (ticketRepository.existsByReporterIdAndOrderIdAndTypeAndStatusIn(user.getId(), order.getId(), Ticket.Type.ORDER_PROBLEM, ACTIVE)) {
            throw new ApiException(HttpStatus.CONFLICT, "По этому заказу у вас уже есть открытая заявка");
        }
        ChatThread thread = chatService.threadBetween(order.getBuyer(), order.getSeller(), order.getListing().getId());
        Ticket t = newTicket(Ticket.Type.ORDER_PROBLEM, user, req.reason(), thread);
        t.setOrder(order);
        t.setReporterRole(role);
        ticketRepository.save(t);
        chatService.postBotMessage(order.getBuyer(), order.getSeller(), order.getListing().getId(),
                "🧾 " + ("BUYER".equals(role) ? "Покупатель" : "Продавец") + " сообщил о проблеме с заказом #"
                        + order.getId() + " (заявка #" + t.getId() + ").\nОписание: " + t.getReason()
                        + "\nМодератор подключится к этому чату.");
        // уведомляю вторую сторону заказа (не того, кто подал)
        User other = "BUYER".equals(role) ? order.getSeller() : order.getBuyer();
        notifications.notify(other, "TICKET_OPENED", "🧾 Заявка по заказу #" + order.getId(),
                "Вторая сторона сообщила о проблеме. Модератор может задать вопросы в чате.", "TICKET", t.getId());
        return toResponse(t, user);
    }

    // жалоба на человека. обсуждается в чате заявителя с ботом поддержки
    @Transactional
    public TicketResponse createComplaint(User reporter, CreateComplaintRequest req) {
        // проверки по порядку: не на себя, юзер существует, не на бота, не на админа
        if (req.userId().equals(reporter.getId())) throw new ApiException(HttpStatus.BAD_REQUEST, "Нельзя пожаловаться на себя");
        User target = userRepository.findById(req.userId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Пользователь не найден"));
        User bot = requireBot();
        if (target.getId().equals(bot.getId())) throw new ApiException(HttpStatus.BAD_REQUEST, "На бота жаловаться нельзя");
        if (target.getRole() == User.Role.ADMIN) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "На создателя площадки пожаловаться нельзя");
        }
        // у жалобы нет заказа, поэтому чат - между заявителем и ботом поддержки (listing = null)
        Ticket t = newTicket(Ticket.Type.COMPLAINT, reporter, req.reason(), chatService.threadBetween(reporter, bot, null));
        // на кого жалуются - нужно потом, чтобы заблокировать его одним решением
        t.setReportedUser(target);
        ticketRepository.save(t);
        chatService.postBotMessage(reporter, bot, null, "📝 Жалоба на @" + target.getUsername() + " принята (заявка #"
                + t.getId() + ").\nМодератор ответит здесь, в этом чате.");
        return toResponse(t, reporter);
    }

    // проблема с аккаунтом: просто текст, отвечают в чате с ботом поддержки. одна открытая заявка за раз
    @Transactional
    public TicketResponse createAccountProblem(User user, TextRequest req) {
        if (ticketRepository.existsByReporterIdAndTypeAndStatusIn(user.getId(), Ticket.Type.ACCOUNT_PROBLEM, ACTIVE)) {
            throw new ApiException(HttpStatus.CONFLICT, "У вас уже есть открытая заявка по аккаунту - ответ придёт в чат поддержки");
        }
        User bot = requireBot();
        Ticket t = newTicket(Ticket.Type.ACCOUNT_PROBLEM, user, req.reason(), chatService.threadBetween(user, bot, null));
        ticketRepository.save(t);
        chatService.postBotMessage(user, bot, null, "🛟 Заявка по аккаунту #" + t.getId() + " принята. Модератор ответит здесь.");
        return toResponse(t, user);
    }

    // разблокировку разбирает тот, кто НЕ блокировал. если её одобрят - блокировка считается
    // необоснованной и бьёт по рейтингу заблокировавшего
    @Transactional
    public TicketResponse createUnblockAppeal(User user, TextRequest req) {
        if (!user.isBlocked()) throw new ApiException(HttpStatus.BAD_REQUEST, "Ваш аккаунт не заблокирован");
        if (ticketRepository.existsByReporterIdAndTypeAndStatusIn(user.getId(), Ticket.Type.UNBLOCK_APPEAL, ACTIVE)) {
            throw new ApiException(HttpStatus.CONFLICT, "Заявка на разблокировку уже рассматривается");
        }
        User bot = requireBot();
        Ticket t = newTicket(Ticket.Type.UNBLOCK_APPEAL, user, req.reason(), chatService.threadBetween(user, bot, null));
        // запоминаю, какая именно блокировка обжалуется - по ней потом видно, кто блокировал (ему эту заявку брать нельзя)
        t.setBlockRecord(staffService.activeBlock(user.getId()));
        ticketRepository.save(t);
        chatService.postBotMessage(user, bot, null, "🔓 Заявка на разблокировку #" + t.getId()
                + " принята. Её рассмотрит модератор, который вас не блокировал. Ответ придёт сюда.");
        return toResponse(t, user);
    }

    // обжалование решения: разбирает другой модератор. если решение признают ошибочным -
    // площадка может выплатить компенсацию, а тому модератору это минус в рейтинг
    @Transactional
    public TicketResponse createDecisionAppeal(User user, Long ticketId, TextRequest req) {
        Ticket original = find(ticketId);
        // все условия (кто, сколько дней прошло, не обжаловали ли уже) - в canAppealBy внизу
        if (!canAppealBy(original, user)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Это решение нельзя обжаловать: обжаловать можно один раз, "
                    + "в течение " + APPEAL_DAYS + " дней, и только участнику заявки");
        }
        User bot = requireBot();
        Ticket t = newTicket(Ticket.Type.DECISION_APPEAL, user, req.reason(), chatService.threadBetween(user, bot, null));
        // ссылка на исходную заявку - по ней другой модератор видит, что пересматривает
        t.setAppealOf(original);
        t.setOrder(original.getOrder());
        // роль (покупатель/продавец) берём из исходного заказа
        t.setReporterRole(roleIn(original, user));
        ticketRepository.save(t);
        chatService.postBotMessage(user, bot, null, "📣 Обжалование решения по заявке #" + original.getId()
                + " принято (заявка #" + t.getId() + "). Его рассмотрит другой модератор.");
        return toResponse(t, user);
    }

    // бывший модератор просит вернуть должность. видит и решает только админ
    @Transactional
    public TicketResponse createReinstatementRequest(User user, TextRequest req) {
        // должность могли вернуть только тому, кого раньше сняли: сейчас USER и есть дата снятия
        if (user.getRole() != User.Role.USER || user.getDemotedAt() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Запросить возврат на должность может только бывший модератор");
        }
        if (user.isBlocked()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Сначала подайте заявку на разблокировку");
        }
        if (ticketRepository.existsByReporterIdAndTypeAndStatusIn(user.getId(), Ticket.Type.MODERATOR_REINSTATEMENT, ACTIVE)) {
            throw new ApiException(HttpStatus.CONFLICT, "Ваша заявка уже у администратора");
        }
        User bot = requireBot();
        Ticket t = newTicket(Ticket.Type.MODERATOR_REINSTATEMENT, user, req.reason(), chatService.threadBetween(user, bot, null));
        ticketRepository.save(t);
        chatService.postBotMessage(user, bot, null, "🛡️ Заявка на возврат в модераторы #" + t.getId()
                + " отправлена администратору. Он ответит здесь.");
        return toResponse(t, user);
    }

    // общая заготовка заявки, остальные поля заполняет каждый create* сам
    private Ticket newTicket(Ticket.Type type, User reporter, String reason, ChatThread thread) {
        Ticket t = new Ticket();
        t.setType(type);
        t.setReporter(reporter);
        // trim - убираю пробелы и пустые строки по краям
        t.setReason(reason.trim());
        t.setThread(thread);
        return t;
    }

    // бот поддержки - отдельный служебный юзер. если его в базе нет - заявки в чат поддержки создавать некуда, отдаю 503
    private User requireBot() {
        User bot = chatService.botUser();
        if (bot == null) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Поддержка сейчас недоступна");
        return bot;
    }

    // ============================== списки ==============================

    // мои заявки: которые подал я + которые касаются меня (запрос findVisibleTo в репозитории)
    @Transactional(readOnly = true)
    public List<TicketResponse> mine(User user) {
        return ticketRepository.findVisibleTo(user.getId()).stream().map(t -> toResponse(t, user)).toList();
    }

    // последняя заявка по заказу - для экрана заказа. смотреть могут только стороны и персонал
    @Transactional(readOnly = true)
    public TicketResponse forOrder(User user, Long orderId) {
        Ticket t = ticketRepository.findFirstByOrderIdOrderByCreatedAtDesc(orderId).orElse(null);
        if (t == null) return null;
        if (!isParty(t, user) && !StaffService.isStaff(user)) throw new ApiException(HttpStatus.FORBIDDEN, "Нет доступа");
        return toResponse(t, user);
    }

    // filter: new (свободные + можно перехватить), mine, in_progress, resolved, all
    // type: REFUND / ORDER_PROBLEM / ... (пусто = все), party: BUYER / SELLER (кто подал по заказу)
    @Transactional(readOnly = true)
    public List<TicketResponse> staffList(User staff, String filter, String type, String party) {
        // requireStaff кидает 403, если это обычный юзер
        StaffService.requireStaff(staff);
        String f = filter == null ? "new" : filter;
        // беру все заявки и фильтрую в памяти стримом. для беты заявок мало - норм,
        // если их станет тысячи, надо переносить фильтры в SQL
        Stream<Ticket> s = ticketRepository.findAllByOrderByCreatedAtDesc().stream();
        // switch-выражение (java 14+): каждая ветка возвращает отфильтрованный стрим
        s = switch (f) {
            case "mine" -> s.filter(t -> t.getStatus() == Ticket.Status.IN_PROGRESS && t.getAssignee() != null
                    && t.getAssignee().getId().equals(staff.getId()));
            case "in_progress" -> s.filter(t -> t.getStatus() == Ticket.Status.IN_PROGRESS);
            case "resolved" -> s.filter(t -> t.getStatus() == Ticket.Status.RESOLVED);
            case "all" -> s;
            // default = "new": свободные + те, что можно перехватить у пропавшего модератора
            default -> s.filter(t -> t.getStatus() == Ticket.Status.OPEN || canTakeOver(t, staff));
        };
        // заявки на возврат в модераторы обычные модераторы вообще не видят
        if (staff.getRole() != User.Role.ADMIN) s = s.filter(t -> t.getType() != Ticket.Type.MODERATOR_REINSTATEMENT);
        // type/party пустые или ALL - фильтр не применяю
        if (type != null && !type.isBlank() && !"ALL".equals(type)) s = s.filter(t -> t.getType().name().equals(type));
        if (party != null && !party.isBlank() && !"ALL".equals(party)) s = s.filter(t -> party.equals(t.getReporterRole()));
        // в работе - сначала самые старые (кто дольше ждёт), в истории - сначала новые
        if (!"resolved".equals(f) && !"all".equals(f)) s = s.sorted(Comparator.comparing(Ticket::getCreatedAt));
        return s.map(t -> toResponse(t, staff)).toList();
    }

    @Transactional(readOnly = true)
    public TicketResponse staffGet(User staff, Long id) {
        StaffService.requireStaff(staff);
        Ticket t = find(id);
        if (t.getType() == Ticket.Type.MODERATOR_REINSTATEMENT && staff.getRole() != User.Role.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Эти заявки видит только администратор");
        }
        return toResponse(t, staff);
    }

    // одна заявка для её участника (или для персонала)
    @Transactional(readOnly = true)
    public TicketResponse partyGet(User user, Long id) {
        Ticket t = find(id);
        if (!isParty(t, user) && !StaffService.isStaff(user)) throw new ApiException(HttpStatus.FORBIDDEN, "Нет доступа");
        return toResponse(t, user);
    }

    // ============================== взять / перехватить ==============================

    // взять заявку себе. lockById - SELECT ... FOR UPDATE: строка заявки блокируется до конца транзакции.
    // без этого два модератора, нажавшие "взять" одновременно, оба стали бы ответственными
    @Transactional
    public TicketResponse claim(User staff, Long id) {
        StaffService.requireStaff(staff);
        Ticket t = ticketRepository.lockById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Заявка не найдена"));
        if (t.getStatus() == Ticket.Status.RESOLVED) throw new ApiException(HttpStatus.CONFLICT, "Заявка уже закрыта");
        // нельзя разбирать заявку про самого себя
        if (isParty(t, staff)) throw new ApiException(HttpStatus.FORBIDDEN, "Нельзя разбирать заявку, в которой вы сами участник");
        String conflict = conflictOfInterest(t, staff);
        if (conflict != null) throw new ApiException(HttpStatus.FORBIDDEN, conflict);
        User previous = t.getAssignee();
        // уже моя - просто отдаю, ничего не меняю
        if (previous != null && previous.getId().equals(staff.getId())) return toResponse(t, staff);
        // заявку ведёт другой и перехватывать ещё рано - объясняю, до какого времени ждать
        if (previous != null && !canTakeOver(t, staff)) {
            Instant at = takeoverAvailableAt(t);
            throw new ApiException(HttpStatus.CONFLICT, "Заявку ведёт " + staffLabel(previous)
                    + (at != null ? ". Перехватить можно будет после " + at : ". Сейчас он ждёт ответа от участников"));
        }
        t.setAssignee(staff);
        t.setClaimedAt(Instant.now());
        t.setLastStaffActivityAt(null); // пошёл 8-часовой таймер нового ответственного
        t.setStatus(Ticket.Status.IN_PROGRESS);
        ticketRepository.save(t);

        // пишу историю, кто когда брал заявку - потом админ видит это в статистике модератора
        TicketClaim c = new TicketClaim();
        c.setTicket(t);
        c.setStaff(staff);
        claimRepository.save(c);

        // в чат заявки пишется, кто подключился, а заявителю летит уведомление
        postToTicketChat(t, staffLabel(staff) + " подключился к заявке #" + t.getId()
                + (previous != null ? " (вместо " + staffLabel(previous) + ")" : "") + ".");
        notifications.notify(t.getReporter(), "TICKET_CLAIMED", "🛡️ Заявку #" + t.getId() + " взяли в работу",
                staffLabel(staff) + " разбирается. Отвечайте ему в чате.", "TICKET", t.getId());
        return toResponse(t, staff);
    }

    // null = конфликта нет. админу можно всё (он один и последняя инстанция)
    private String conflictOfInterest(Ticket t, User staff) {
        if (staff.getRole() == User.Role.ADMIN) return null;
        if (t.getType() == Ticket.Type.MODERATOR_REINSTATEMENT) return "Эти заявки рассматривает только администратор";
        if (t.getType() == Ticket.Type.UNBLOCK_APPEAL && t.getBlockRecord() != null
                && t.getBlockRecord().getBlockedBy().getId().equals(staff.getId())) {
            return "Нельзя разбирать апелляцию на собственную блокировку";
        }
        if (t.getType() == Ticket.Type.DECISION_APPEAL && t.getAppealOf() != null && t.getAppealOf().getAssignee() != null
                && t.getAppealOf().getAssignee().getId().equals(staff.getId())) {
            return "Нельзя пересматривать собственное решение";
        }
        return null;
    }

    // ============================== решение ==============================

    // вынести решение. опять лочу заявку - чтобы одну и ту же не закрыли дважды двумя запросами (и дважды не вернули деньги)
    @Transactional
    public TicketResponse resolve(User staff, Long id, ResolveRequest req) {
        StaffService.requireStaff(staff);
        Ticket t = ticketRepository.lockById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Заявка не найдена"));
        // решать может только тот, кто сейчас ответственный и заявка в работе
        if (t.getStatus() != Ticket.Status.IN_PROGRESS || t.getAssignee() == null || !t.getAssignee().getId().equals(staff.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Решение выносит только ответственный по заявке. Сначала возьмите её");
        }
        // решение приходит строкой - пробую превратить в enum, не вышло - значит прислали ерунду, 400
        Ticket.Resolution resolution;
        try {
            resolution = Ticket.Resolution.valueOf(req.resolution());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Неизвестное решение");
        }
        // пустой комментарий храню как null, а не как пустую строку
        String note = req.note() == null || req.note().isBlank() ? null : req.note().trim();
        String text;

        // дальше по типу заявки - у каждого типа свой набор допустимых решений. text - что напишет бот в чат.
        // неподходящее решение для типа -> 400, и благодаря @Transactional ничего не меняется
        switch (t.getType()) {
            // возврат: деньги ещё заморожены у площадки - делим их между покупателем и продавцом
            case REFUND -> text = settleOrderMoney(t, resolution, req);
            case ORDER_PROBLEM -> {
                Order order = t.getOrder();
                switch (resolution) {
                    // заказ ещё не подтверждён (или в споре) - деньги у площадки, решаем как спор
                    case FULL_REFUND, PARTIAL_REFUND, NO_REFUND -> {
                        if (order.getStatus() != Order.OrderStatus.PENDING_CONFIRMATION
                                && order.getStatus() != Order.OrderStatus.DISPUTED) {
                            throw new ApiException(HttpStatus.CONFLICT, "Заказ уже завершён или отменён - "
                                    + "для завершённого: возврат за счёт продавца или компенсация от площадки");
                        }
                        text = settleOrderMoney(t, resolution, req);
                    }
                    // заказ завершён - забираем у продавца (сначала из замороженных по этому заказу)
                    case SELLER_REFUND -> {
                        BigDecimal r = WalletService.validateAmount(req.refundAmount());
                        orderService.refundFromSeller(order.getId(), r);
                        t.setRefundAmount(r);
                        text = "⚖️ По заказу #" + order.getId() + " покупателю возвращено " + r + " ₽ за счёт продавца.";
                    }
                    // платит площадка - у продавца ничего не забираем
                    case COMPENSATED -> {
                        BigDecimal r = WalletService.validateAmount(req.refundAmount());
                        payCompensationTo(t.getReporter(), r, "Компенсация по заявке #" + t.getId(), order.getId());
                        t.setRefundAmount(r);
                        text = "🎁 По заявке #" + t.getId() + " площадка выплатила компенсацию " + r + " ₽ ("
                                + t.getReporter().getNickname() + ").";
                    }
                    case CLOSED -> text = "✅ Заявка #" + t.getId() + " закрыта.";
                    default -> throw new ApiException(HttpStatus.BAD_REQUEST,
                            "Проблема с заказом: возврат, возврат за счёт продавца, компенсация или закрыть");
                }
            }
            case COMPLAINT -> {
                // блокировка по жалобе: true = через заявку (только так можно заблокировать модератора)
                if (resolution == Ticket.Resolution.BLOCKED_USER) {
                    staffService.block(staff, t.getReportedUser().getId(),
                            note != null ? note : "Нарушение правил (жалоба #" + t.getId() + ")", true);
                    text = "⛔ По жалобе #" + t.getId() + " пользователь @" + t.getReportedUser().getUsername() + " заблокирован.";
                } else if (resolution == Ticket.Resolution.CLOSED) {
                    text = "✅ Жалоба #" + t.getId() + " рассмотрена.";
                } else {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "Жалобу можно закрыть или заблокировать нарушителя");
                }
            }
            case UNBLOCK_APPEAL -> {
                if (resolution == Ticket.Resolution.UNBLOCKED) {
                    // true = разблокировали по апелляции, значит блокировка была необоснованной - минус тому, кто блокировал
                    staffService.unblock(staff, t.getReporter().getId(), note, true);
                    text = "🔓 Заявка #" + t.getId() + ": аккаунт разблокирован.";
                } else if (resolution == Ticket.Resolution.CLOSED) {
                    text = "🔒 Заявка #" + t.getId() + ": блокировка оставлена в силе.";
                } else {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "Разблокировать или оставить блокировку");
                }
            }
            case DECISION_APPEAL -> {
                if (resolution == Ticket.Resolution.OVERTURNED) {
                    // компенсация необязательная: 0 или пусто = просто признали ошибку, без денег
                    BigDecimal comp = req.refundAmount() == null || req.refundAmount().signum() == 0
                            ? BigDecimal.ZERO : WalletService.validateAmount(req.refundAmount());
                    if (comp.signum() > 0) payCompensation(t, comp);
                    t.setRefundAmount(comp.signum() > 0 ? comp : null);
                    text = "📣 Обжалование #" + t.getId() + ": решение по заявке #" + t.getAppealOf().getId() + " признано ошибочным."
                            + (comp.signum() > 0 ? " Площадка выплатила компенсацию " + comp + " ₽." : "");
                } else if (resolution == Ticket.Resolution.UPHELD) {
                    text = "📣 Обжалование #" + t.getId() + ": решение по заявке #" + t.getAppealOf().getId() + " оставлено в силе.";
                } else {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "Обжалование: оставить решение или признать ошибочным");
                }
            }
            case MODERATOR_REINSTATEMENT -> {
                if (resolution == Ticket.Resolution.REINSTATED) {
                    // назначаю обратно модератором
                    staffService.appoint(t.getReporter().getId());
                    text = "🛡️ Заявка #" + t.getId() + ": вы снова модератор.";
                } else if (resolution == Ticket.Resolution.CLOSED) {
                    text = "Заявка #" + t.getId() + ": в возврате на должность отказано.";
                } else {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "Вернуть на должность или отказать");
                }
            }
            default -> { // ORDER_PROBLEM, ACCOUNT_PROBLEM
                if (resolution != Ticket.Resolution.CLOSED) throw new ApiException(HttpStatus.BAD_REQUEST, "Эту заявку можно только закрыть с комментарием");
                text = "✅ Заявка #" + t.getId() + " закрыта.";
            }
        }
        // комментарий модератора дописываю в конец сообщения бота
        if (note != null) text += "\nКомментарий модератора: " + note;

        // закрываю заявку и запоминаю время - от него считаются 7 дней на обжалование
        t.setResolution(resolution);
        t.setResolutionNote(note);
        t.setStatus(Ticket.Status.RESOLVED);
        t.setResolvedAt(Instant.now());
        t.setLastStaffActivityAt(Instant.now());
        ticketRepository.save(t);
        postToTicketChat(t, text);

        // уведомляю всех участников (без дублей, см. partiesToNotify)
        for (User u : partiesToNotify(t)) {
            notifications.notify(u, "TICKET_RESOLVED", "Решение по заявке #" + t.getId(), text, "TICKET", t.getId());
        }
        // отменили решение другого модератора - проверяем, не пора ли его снять (после save - чтобы засчиталось)
        if (resolution == Ticket.Resolution.OVERTURNED && t.getAppealOf().getAssignee() != null) {
            staffService.checkAutoDemotion(t.getAppealOf().getAssignee());
        }
        return toResponse(t, staff);
    }

    // полный / частичный возврат / в пользу продавца - для заявки на возврат и для проблемы с неподтверждённым заказом
    // общий код денег по неподтверждённому заказу. сами деньги двигает OrderService.resolveDispute (он лочит заказ)
    private String settleOrderMoney(Ticket t, Ticket.Resolution resolution, ResolveRequest req) {
        Order order = t.getOrder();
        BigDecimal amount = order.getAmount();
        // refund - сколько вернуть покупателю. остальное (минус комиссия) получает продавец
        BigDecimal refund = switch (resolution) {
            case FULL_REFUND -> amount;
            case NO_REFUND -> BigDecimal.ZERO;
            case PARTIAL_REFUND -> {
                BigDecimal r = WalletService.validateAmount(req.refundAmount());
                // частичный должен быть строго меньше суммы, иначе это полный возврат
                if (r.compareTo(amount) >= 0) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "Частичный возврат должен быть меньше суммы заказа (" + amount + " ₽)");
                }
                // yield - так из блока {} в switch-выражении возвращается значение
                yield r;
            }
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "Для возврата: полный, частичный или в пользу продавца");
        };
        // done - заказ после решения, из него беру, сколько в итоге получил продавец
        Order done = orderService.resolveDispute(order.getId(), refund);
        t.setRefundAmount(refund);
        // текст для чата тоже собираю switch-выражением прямо в конкатенации
        return "⚖️ Решение по заказу #" + order.getId() + ": " + switch (resolution) {
            case FULL_REFUND -> "полный возврат. " + refund + " ₽ вернулись покупателю.";
            case PARTIAL_REFUND -> "частичный возврат. Покупателю " + refund + " ₽, продавцу " + done.getSellerAmount()
                    + " ₽ (будут доступны через " + OrderService.WITHDRAWAL_HOLD_HOURS + " ч).";
            default -> "в пользу продавца. Продавец получит " + done.getSellerAmount() + " ₽ (будут доступны через "
                    + OrderService.WITHDRAWAL_HOLD_HOURS + " ч).";
        };
    }

    // деньги площадки, а не продавца - решение уже исполнено, забирать у людей ничего не будем
    private void payCompensation(Ticket t, BigDecimal amount) {
        payCompensationTo(t.getReporter(), amount, "Компенсация по обжалованию заявки #" + t.getAppealOf().getId(),
                t.getOrder() != null ? t.getOrder().getId() : null);
    }

    // площадка платит из своих денег (решение уже исполнено - забирать у людей ничего не будем)
    private void payCompensationTo(User to, BigDecimal amount, String description, Long orderId) {
        // лочу строку юзера (FOR UPDATE), прежде чем прибавить деньги: если параллельно идёт покупка или вывод,
        // без блокировки один из запросов перезаписал бы баланс старым значением и деньги потерялись бы
        User u = userRepository.lockById(to.getId()).orElseThrow();
        // BigDecimal неизменяемый - add возвращает новое число, поэтому результат сразу в сеттер
        u.setBalanceAvailable(u.getBalanceAvailable().add(amount));
        userRepository.save(u);
        // пишу в историю кошелька, чтобы юзер видел, откуда пришли деньги
        walletService.log(u, WalletTransaction.Type.COMPENSATION, amount, description, null, orderId, null);
    }

    // ============================== оценка модератора ==============================

    // участник ставит 👍/👎 решению. оценка пишется на того модератора, который решал
    @Transactional
    public TicketResponse feedback(User user, Long id, FeedbackRequest req) {
        Ticket t = find(id);
        if (!canRateBy(t, user)) throw new ApiException(HttpStatus.BAD_REQUEST, "Оценить эту заявку нельзя");
        if (feedbackRepository.findByTicketIdAndUserId(t.getId(), user.getId()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "Вы уже оценили эту заявку");
        }
        TicketFeedback f = new TicketFeedback();
        f.setTicket(t);
        f.setUser(user);
        f.setStaff(t.getAssignee());
        f.setPositive(req.positive());
        f.setComment(req.comment() == null || req.comment().isBlank() ? null : req.comment().trim());
        feedbackRepository.save(f);
        // после 👎 проверяю, не упал ли рейтинг модератора ниже порога - тогда его снимут автоматически
        if (!req.positive()) staffService.checkAutoDemotion(t.getAssignee());
        return toResponse(t, user);
    }

    // ============================== правила ==============================

    private Ticket find(Long id) {
        return ticketRepository.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Заявка не найдена"));
    }

    // участник = заявитель, тот на кого жаловались, или покупатель/продавец заказа
    private boolean isParty(Ticket t, User u) {
        Long id = u.getId();
        if (t.getReporter().getId().equals(id)) return true;
        if (t.getReportedUser() != null && t.getReportedUser().getId().equals(id)) return true;
        return t.getOrder() != null && (t.getOrder().getBuyer().getId().equals(id) || t.getOrder().getSeller().getId().equals(id));
    }

    // оценивать и обжаловать могут заявитель и стороны заказа. тот, на кого жаловались, - нет:
    // иначе нарушители топили бы рейтинг честным модераторам
    private boolean isRater(Ticket t, User u) {
        Long id = u.getId();
        if (t.getReporter().getId().equals(id)) return true;
        return t.getOrder() != null && (t.getOrder().getBuyer().getId().equals(id) || t.getOrder().getSeller().getId().equals(id));
    }

    // оценить можно только закрытую заявку, не свою (модератор не оценивает сам себя) и только один раз (проверка в feedback)
    private boolean canRateBy(Ticket t, User u) {
        return t.getStatus() == Ticket.Status.RESOLVED && t.getAssignee() != null
                && !t.getAssignee().getId().equals(u.getId()) && isRater(t, u);
    }

    // обжаловать: заявка закрыта, это не апелляция на апелляцию, прошло меньше 7 дней и ещё не обжаловали
    private boolean canAppealBy(Ticket t, User u) {
        return t.getStatus() == Ticket.Status.RESOLVED && t.getAssignee() != null
                && t.getType() != Ticket.Type.DECISION_APPEAL && t.getType() != Ticket.Type.UNBLOCK_APPEAL
                && t.getType() != Ticket.Type.MODERATOR_REINSTATEMENT // решение админа - последняя инстанция
                && isRater(t, u) && !t.getAssignee().getId().equals(u.getId())
                && t.getResolvedAt() != null && t.getResolvedAt().isAfter(Instant.now().minus(APPEAL_DAYS, ChronoUnit.DAYS))
                && !ticketRepository.existsByAppealOfId(t.getId());
    }

    // кем был юзер в заказе исходной заявки
    private String roleIn(Ticket t, User u) {
        if (t.getOrder() == null) return null;
        if (t.getOrder().getBuyer().getId().equals(u.getId())) return "BUYER";
        if (t.getOrder().getSeller().getId().equals(u.getId())) return "SELLER";
        return null;
    }

    private List<User> partiesToNotify(Ticket t) {
        // LinkedHashMap по id - чтобы один человек (заявитель и он же покупатель) не получил уведомление дважды
        java.util.LinkedHashMap<Long, User> m = new java.util.LinkedHashMap<>();
        m.put(t.getReporter().getId(), t.getReporter());
        if (t.getOrder() != null) {
            m.putIfAbsent(t.getOrder().getBuyer().getId(), t.getOrder().getBuyer());
            m.putIfAbsent(t.getOrder().getSeller().getId(), t.getOrder().getSeller());
        }
        return List.copyOf(m.values());
    }

    // когда можно перехватить: взял и молчит 8ч, или люди ответили, а он 8ч молчит
    private Instant takeoverAvailableAt(Ticket t) {
        if (t.getStatus() != Ticket.Status.IN_PROGRESS || t.getAssignee() == null) return null;
        // взял, но ни разу не ответил - считаю 8ч от момента, когда взял
        if (t.getLastStaffActivityAt() == null) {
            return t.getClaimedAt() == null ? null : t.getClaimedAt().plus(TAKEOVER_HOURS, ChronoUnit.HOURS);
        }
        // люди написали позже последнего ответа модератора - 8ч от их сообщения
        if (t.getLastPartyMessageAt() != null && t.getLastPartyMessageAt().isAfter(t.getLastStaffActivityAt())) {
            return t.getLastPartyMessageAt().plus(TAKEOVER_HOURS, ChronoUnit.HOURS);
        }
        return null; // модератор спросил и ждёт ответа - он не "пропал"
    }

    // можно ли этому сотруднику перехватить заявку прямо сейчас. админу можно всегда (кроме конфликта интересов)
    private boolean canTakeOver(Ticket t, User viewer) {
        if (t.getStatus() != Ticket.Status.IN_PROGRESS || t.getAssignee() == null) return false;
        if (t.getAssignee().getId().equals(viewer.getId())) return false;
        if (conflictOfInterest(t, viewer) != null) return false;
        if (viewer.getRole() == User.Role.ADMIN) return true;
        Instant at = takeoverAvailableAt(t);
        // !isBefore = время уже наступило (или ровно сейчас)
        return at != null && !Instant.now().isBefore(at);
    }

    // бот пишет в чат, к которому привязана заявка (чат заказа или чат поддержки)
    private void postToTicketChat(Ticket t, String text) {
        ChatThread th = t.getThread();
        chatService.postBotMessage(th.getBuyer(), th.getSeller(), th.getListing() != null ? th.getListing().getId() : null, text);
    }

    // как подписываем сотрудника: админа с короной, модератора со щитом
    static String staffLabel(User u) {
        return (u.getRole() == User.Role.ADMIN ? "👑 Создатель " : "🛡️ Модератор ") + u.getNickname();
    }

    // собираю DTO для приложения. для каждого зрителя свои флаги:
    // canClaim - показывать ли кнопку "взять", canAct - кнопки решения, можно ли оценить/обжаловать
    TicketResponse toResponse(Ticket t, User viewer) {
        boolean staffViewer = StaffService.isStaff(viewer);
        boolean canClaim = staffViewer && t.getStatus() != Ticket.Status.RESOLVED && !isParty(t, viewer)
                && conflictOfInterest(t, viewer) == null && (t.getAssignee() == null || canTakeOver(t, viewer));
        boolean canAct = t.getStatus() == Ticket.Status.IN_PROGRESS && t.getAssignee() != null
                && t.getAssignee().getId().equals(viewer.getId());
        Order o = t.getOrder();
        User a = t.getAssignee();
        // уже оценивал? null - нет, иначе true/false
        Boolean myFeedback = feedbackRepository.findByTicketIdAndUserId(t.getId(), viewer.getId())
                .map(TicketFeedback::isPositive).orElse(null);
        BlockRecord br = t.getBlockRecord();
        // порядок аргументов строго как в record TicketResponse.
        // кто заблокировал (предпоследнее поле) видит только персонал - обычному юзеру там null
        return new TicketResponse(
                t.getId(), t.getType().name(), t.getStatus().name(), t.getThread().getId(),
                o != null ? o.getId() : null, o != null ? o.getListing().getTitle() : null,
                o != null ? o.getAmount() : null, o != null ? o.getStatus().name() : null,
                o != null ? o.getBuyer().getId() : null, o != null ? o.getBuyer().getNickname() : null,
                o != null ? o.getSeller().getId() : null, o != null ? o.getSeller().getNickname() : null,
                t.getReporter().getId(), t.getReporter().getNickname(), t.getReporterRole(),
                t.getReportedUser() != null ? t.getReportedUser().getId() : null,
                t.getReportedUser() != null ? t.getReportedUser().getNickname() : null,
                t.getReason(),
                a != null ? a.getId() : null, a != null ? a.getNickname() : null, a != null ? a.getRole().name() : null,
                t.getClaimedAt(), canClaim, canAct, takeoverAvailableAt(t),
                t.getResolution() != null ? t.getResolution().name() : null, t.getRefundAmount(), t.getResolutionNote(),
                t.getCreatedAt(), t.getResolvedAt(),
                t.getAppealOf() != null ? t.getAppealOf().getId() : null,
                canRateBy(t, viewer) && myFeedback == null,
                canAppealBy(t, viewer),
                myFeedback,
                br != null ? br.getReason() : null,
                br != null && staffViewer ? staffLabel(br.getBlockedBy()) : null,
                o != null ? o.getCommissionAmount() : null
        );
    }

    // для админа: цифры модератора + все заявки, которые он когда-либо брал или решал, + его блокировки
    @Transactional(readOnly = true)
    public ModeratorStatsResponse moderatorStats(User admin, Long staffId) {
        // id заявок из истории взятий - в Set, чтобы одна заявка не повторялась, если её брали несколько раз
        User u = userRepository.findById(staffId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Пользователь не найден"));
        java.util.Set<Long> ids = new java.util.LinkedHashSet<>(claimRepository.claimedTicketIds(staffId));
        // пустой список в запрос IN () не передаю - сразу пустой ответ
        List<TicketResponse> tickets = ids.isEmpty() ? List.of()
                : ticketRepository.findByIdInOrderByCreatedAtDesc(ids).stream().map(t -> toResponse(t, admin)).toList();
        return new ModeratorStatsResponse(staffService.describe(u), tickets, staffService.blocksBy(staffId));
    }
}
