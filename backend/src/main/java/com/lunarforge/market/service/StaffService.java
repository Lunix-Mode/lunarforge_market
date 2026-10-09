package com.lunarforge.market.service;

import com.lunarforge.market.dto.TicketDtos.BlockResponse;
import com.lunarforge.market.dto.TicketDtos.ModeratorResponse;
import com.lunarforge.market.entity.BlockRecord;
import com.lunarforge.market.entity.Ticket;
import com.lunarforge.market.entity.User;
import com.lunarforge.market.exception.ApiException;
import com.lunarforge.market.repository.BlockRecordRepository;
import com.lunarforge.market.repository.TicketClaimRepository;
import com.lunarforge.market.repository.TicketFeedbackRepository;
import com.lunarforge.market.repository.TicketRepository;
import com.lunarforge.market.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

// блокировки, рейтинг модераторов и автоматическое снятие. этим сервисом пользуются контроллеры
// модерации и TicketService, когда по жалобе нужно кого-то заблокировать.
//
// рейтинг = 100 + лайки - 3*дизлайки - 15*(отменённые по обжалованию решения) - 15*(снятые по апелляции блокировки), 0..100.
// если у модератора 10+ решённых заявок (MIN_RESOLVED_FOR_DEMOTION) и рейтинг < 40 - снимаем автоматически,
// админ видит таких в отдельной вкладке.
// лайки только гасят штрафы (выше 100 не бывает), чтобы рейтинг нельзя было "накрутить" лайками
@Service
public class StaffService {

    // сервис уведомлений - через него шлю пуш/уведомление в приложение о блокировке, снятии и т.д.
    private final com.lunarforge.market.service.NotificationService notifications;

    // порог рейтинга: ниже 40 модератора снимаем автоматически
    public static final int DEMOTE_BELOW = 40;
    public static final int MIN_RESOLVED_FOR_DEMOTION = 10; // меньше 10 решённых - не снимаем, мало данных

    private final UserRepository userRepository;
    private final BlockRecordRepository blockRecordRepository;
    private final TicketRepository ticketRepository;
    private final TicketClaimRepository ticketClaimRepository;
    private final TicketFeedbackRepository feedbackRepository;
    private final ChatService chatService;

    public StaffService(UserRepository userRepository, BlockRecordRepository blockRecordRepository,
                        TicketRepository ticketRepository, TicketClaimRepository ticketClaimRepository,
                        TicketFeedbackRepository feedbackRepository, ChatService chatService, com.lunarforge.market.service.NotificationService notifications) {
        this.notifications = notifications;
        this.userRepository = userRepository;
        this.blockRecordRepository = blockRecordRepository;
        this.ticketRepository = ticketRepository;
        this.ticketClaimRepository = ticketClaimRepository;
        this.feedbackRepository = feedbackRepository;
        this.chatService = chatService;
    }

    // модератор или админ - оба считаются "персоналом"
    public static boolean isStaff(User u) {
        return u.getRole() == User.Role.ADMIN || u.getRole() == User.Role.MODERATOR;
    }

    // вызываю в начале любых модераторских действий, обычный юзер получит 403
    public static void requireStaff(User u) {
        if (!isStaff(u)) throw new ApiException(HttpStatus.FORBIDDEN, "Только для модераторов");
    }

    // ---------- блокировки ----------

    // короткий вариант для обычной блокировки из админки (не по жалобе)
    @Transactional
    public BlockResponse block(User staff, Long userId, String reason) {
        return block(staff, userId, reason, false);
    }

    // viaTicket = блокируем по решению жалобы. модератора другой модератор может заблокировать ТОЛЬКО так
    @Transactional
    public BlockResponse block(User staff, Long userId, String reason, boolean viaTicket) {
        // по шагам: проверяю права и причину, лочу юзера, проверяю кого блокируем, пишу запись в историю,
        // ставлю флаг blocked, если это модератор - снимаю с должности, в конце уведомление и сообщение от бота
        requireStaff(staff);
        // без причины не блокирую - потом её показываю человеку и по ней разбирают апелляцию
        if (reason == null || reason.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "Укажите причину блокировки");
        if (userId.equals(staff.getId())) throw new ApiException(HttpStatus.BAD_REQUEST, "Себя заблокировать нельзя");
        // lockById берёт строку юзера с блокировкой (PESSIMISTIC_WRITE) до конца транзакции.
        // если два модератора жмут "заблокировать" одновременно, второй подождёт первого и увидит что юзер уже заблокирован
        User target = userRepository.lockById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Пользователь не найден"));
        // бот - системный пользователь, от него приходят сообщения в чат. его блокировать бессмысленно
        User bot = chatService.botUser();
        if (bot != null && bot.getId().equals(target.getId())) throw new ApiException(HttpStatus.BAD_REQUEST, "Бота блокировать нельзя");
        if (target.getRole() == User.Role.ADMIN) throw new ApiException(HttpStatus.FORBIDDEN, "Создателя площадки заблокировать нельзя");
        if (target.getRole() == User.Role.MODERATOR && staff.getRole() != User.Role.ADMIN && !viaTicket) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Модератора можно заблокировать только по жалобе на него");
        }
        // повторно не блокирую, иначе будет две активные записи в истории
        if (target.isBlocked()) throw new ApiException(HttpStatus.CONFLICT, "Пользователь уже заблокирован");

        // запись в историю блокировок: кто, кого, за что. по ней потом считается рейтинг и идёт апелляция
        BlockRecord r = new BlockRecord();
        r.setUser(target);
        r.setBlockedBy(staff);
        r.setReason(reason.trim());
        blockRecordRepository.save(r);

        // а это сам флаг на юзере, по нему остальной код понимает что юзеру ничего нельзя
        target.setBlocked(true);
        target.setBlockReason(reason.trim());
        target.setBlockedAt(Instant.now());
        userRepository.save(target);
        if (target.getRole() == User.Role.MODERATOR) {
            // заблокированный модератор теряет должность и попадает в "снятые" - админ может вернуть
            releaseTickets(target);
            target.setRole(User.Role.USER);
            target.setDemotedAutomatically(false);
            target.setDemotedAt(Instant.now());
            target.setDemotionReason("Заблокирован: " + r.getReason());
            userRepository.save(target);
        }
        // уведомление + сообщение от бота в чат, чтобы человек точно увидел причину и знал что можно обжаловать
        notifications.notify(target, "ACCOUNT_BLOCKED", "⛔ Аккаунт заблокирован", "Причина: " + r.getReason()
                + ". Можно подать заявку на разблокировку.", "USER", target.getId());

        if (bot != null) {
            chatService.postBotMessage(target, bot, null, "⛔ Ваш аккаунт заблокирован.\nПричина: " + r.getReason()
                    + "\nЕсли вы не согласны - подайте заявку на разблокировку в приложении. Её рассмотрит другой модератор.");
        }
        return toBlockResponse(r);
    }

    // byAppeal = снимаем по заявке на разблокировку. если снимает не тот, кто блокировал -
    // блокировка считается необоснованной и бьёт по рейтингу заблокировавшего
    @Transactional
    public void unblock(User staff, Long userId, String reason, boolean byAppeal) {
        // снимать блокировку тоже может только персонал
        requireStaff(staff);
        // тоже лочу строку юзера, чтобы два снятия одновременно не испортили данные
        User target = userRepository.lockById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Пользователь не найден"));
        if (!target.isBlocked()) throw new ApiException(HttpStatus.CONFLICT, "Пользователь не заблокирован");
        if (target.getRole() == User.Role.MODERATOR && staff.getRole() != User.Role.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Модератора может разблокировать только администратор");
        }
        // ищу последнюю активную (не снятую) запись блокировки. может не быть, если блокировали ещё до истории
        BlockRecord r = blockRecordRepository.findFirstByUserIdAndLiftedAtIsNullOrderByCreatedAtDesc(userId).orElse(null);
        User blocker = null;
        if (r != null) {
            r.setLiftedAt(Instant.now());
            r.setLiftedBy(staff);
            r.setLiftReason(reason == null || reason.isBlank() ? null : reason.trim());
            // если сняли по апелляции и это сделал другой модератор - значит блокировка была необоснованной,
            // это минус в рейтинг тому, кто блокировал
            r.setReversedByAppeal(byAppeal && !r.getBlockedBy().getId().equals(staff.getId()));
            blockRecordRepository.save(r);
            if (r.isReversedByAppeal()) blocker = r.getBlockedBy();
        }
        // снимаю флаг и чищу причину на самом юзере
        target.setBlocked(false);
        target.setBlockReason(null);
        target.setBlockedAt(null);
        userRepository.save(target);

        User bot = chatService.botUser();
        if (bot != null) chatService.postBotMessage(target, bot, null, "✅ Ваш аккаунт разблокирован.");
        notifications.notify(target, "ACCOUNT_UNBLOCKED", "✅ Аккаунт разблокирован", "Снова можно покупать, продавать и общаться.", "USER", target.getId());
        // у заблокировавшего упал рейтинг - проверяю, не пора ли его снимать
        if (blocker != null) checkAutoDemotion(blocker);
    }

    // все блокировки, которые выдал конкретный модератор
    @Transactional(readOnly = true)
    public List<BlockResponse> blocksBy(Long staffId) {
        return blockRecordRepository.findByBlockedByIdOrderByCreatedAtDesc(staffId).stream().map(this::toBlockResponse).toList();
    }

    // история блокировок конкретного человека (кто, когда, за что, сняли ли)
    @Transactional(readOnly = true)
    public List<BlockResponse> blocksOf(Long userId) {
        return blockRecordRepository.findByUserIdOrderByCreatedAtDesc(userId).stream().map(this::toBlockResponse).toList();
    }

    // текущая активная блокировка юзера или null
    public BlockRecord activeBlock(Long userId) {
        return blockRecordRepository.findFirstByUserIdAndLiftedAtIsNullOrderByCreatedAtDesc(userId).orElse(null);
    }

    // ---------- рейтинг ----------

    // собираю всю статистику модератора для админки: лайки/дизлайки, отменённые решения, блокировки,
    // сколько заявок решил и сколько сейчас в работе. отсюда же считается рейтинг
    @Transactional(readOnly = true)
    public ModeratorResponse describe(User u) {
        long likes = feedbackRepository.countByStaffIdAndPositiveTrue(u.getId());
        long dislikes = feedbackRepository.countByStaffIdAndPositiveFalse(u.getId());
        long overturned = ticketRepository.countOverturnedDecisions(u.getId());
        long blocksIssued = blockRecordRepository.countByBlockedById(u.getId());
        long blocksReversed = blockRecordRepository.countByBlockedByIdAndReversedByAppealTrue(u.getId());
        long resolved = ticketRepository.countByAssigneeIdAndStatus(u.getId(), Ticket.Status.RESOLVED);
        long inProgress = ticketRepository.countByAssigneeIdAndStatus(u.getId(), Ticket.Status.IN_PROGRESS);
        long claimed = ticketClaimRepository.countByStaffId(u.getId());
        // сам рейтинг считаю на лету из счётчиков, в базе он не хранится - поэтому всегда актуальный
        int rating = rating(likes, dislikes, overturned, blocksReversed);
        return new ModeratorResponse(u.getId(), u.getNickname(), u.getUsername(), u.getRole().name(), rating,
                resolved, claimed, inProgress, likes, dislikes, overturned, blocksIssued, blocksReversed,
                u.isDemotedAutomatically(), u.getDemotedAt(), u.getDemotionReason(), u.isBlocked());
    }

    // формула рейтинга. Math.max/min обрезают результат в диапазон 0..100,
    // поэтому лайками нельзя уйти выше 100, они только компенсируют штрафы
    static int rating(long likes, long dislikes, long overturned, long blocksReversed) {
        long r = 100 + likes - 3 * dislikes - 15 * overturned - 15 * blocksReversed;
        return (int) Math.max(0, Math.min(100, r));
    }

    // вызываем после каждого "минуса": дизлайк, отменённое решение, снятая по апелляции блокировка
    @Transactional
    public void checkAutoDemotion(User staff) {
        // перечитываю юзера из базы, а не беру переданный объект - роль могла уже поменяться
        User u = userRepository.findById(staff.getId()).orElse(null);
        if (u == null || u.getRole() != User.Role.MODERATOR) return; // админа не снимаем
        ModeratorResponse m = describe(u);
        // мало решённых заявок или рейтинг нормальный - ничего не делаю
        if (m.resolvedCount() < MIN_RESOLVED_FOR_DEMOTION || m.rating() >= DEMOTE_BELOW) return;

        // снимаю: роль обратно USER, отметка что автоматически, причина с цифрами чтобы админ понял за что
        u.setRole(User.Role.USER);
        u.setDemotedAutomatically(true);
        u.setDemotedAt(Instant.now());
        u.setDemotionReason("Рейтинг " + m.rating() + "/100 при " + m.resolvedCount() + " решённых заявках: 👎 "
                + m.dislikes() + ", отменено решений " + m.overturned() + ", снято блокировок " + m.blocksReversed());
        userRepository.save(u);
        // его заявки в работе возвращаю в общую очередь, иначе они зависнут
        releaseTickets(u);
        notifications.notify(u, "MODERATOR_DEMOTED", "Вы сняты с модераторов",
                "Автоматически, из-за низкого рейтинга. " + u.getDemotionReason(), "USER", u.getId());
    }

    // ---------- состав ----------

    // текущий состав: все модераторы и админы со статистикой
    @Transactional(readOnly = true)
    public List<ModeratorResponse> activeStaff() {
        return userRepository.findByRoleIn(List.of(User.Role.MODERATOR, User.Role.ADMIN)).stream().map(this::describe).toList();
    }

    // снятые модераторы (у них есть demotedAt, а роль уже USER) - отдельная вкладка у админа
    @Transactional(readOnly = true)
    public List<ModeratorResponse> demoted() {
        return userRepository.findByDemotedAtIsNotNullAndRoleOrderByDemotedAtDesc(User.Role.USER).stream().map(this::describe).toList();
    }

    // назначить модератором по id (это делает админ). бота, админа и заблокированного назначить нельзя
    @Transactional
    public ModeratorResponse appoint(Long userId) {
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Пользователь с ID " + userId + " не найден"));
        User bot = chatService.botUser();
        if (bot != null && bot.getId().equals(u.getId())) throw new ApiException(HttpStatus.BAD_REQUEST, "Бота назначить нельзя");
        if (u.getRole() == User.Role.ADMIN) throw new ApiException(HttpStatus.BAD_REQUEST, "Это администратор");
        if (u.isBlocked()) throw new ApiException(HttpStatus.BAD_REQUEST, "Пользователь заблокирован");
        u.setRole(User.Role.MODERATOR);
        u.setDemotedAutomatically(false); // вернули вручную - снимаем отметку
        userRepository.save(u);
        notifications.notify(u, "MODERATOR_APPOINTED", "🛡️ Вы назначены модератором",
                "В меню появился раздел «Заявки». Разбирайте честно - участники оценивают решения.", "USER", u.getId());
        return describe(u);
    }

    // ручное снятие модератора админом
    @Transactional
    public void dismiss(Long userId) {
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Пользователь не найден"));
        if (u.getRole() != User.Role.MODERATOR) throw new ApiException(HttpStatus.BAD_REQUEST, "Этот пользователь не модератор");
        u.setRole(User.Role.USER);
        u.setDemotedAutomatically(false);
        u.setDemotedAt(Instant.now());
        u.setDemotionReason("Снят администратором");
        userRepository.save(u);
        releaseTickets(u);
    }

    // все заявки модератора в статусе IN_PROGRESS возвращаю в OPEN без исполнителя,
    // чтобы их мог взять другой модератор
    private void releaseTickets(User u) {
        for (Ticket t : ticketRepository.findByAssigneeIdAndStatusOrderByCreatedAtAsc(u.getId(), Ticket.Status.IN_PROGRESS)) {
            t.setAssignee(null);
            t.setClaimedAt(null);
            t.setStatus(Ticket.Status.OPEN);
            ticketRepository.save(t);
        }
    }

    // entity -> dto для ответа. staffLabel - чтобы в приложении было видно, админ блокировал или модератор
    private BlockResponse toBlockResponse(BlockRecord r) {
        return new BlockResponse(r.getId(), r.getUser().getId(), r.getUser().getNickname(), r.getReason(),
                r.getCreatedAt(), r.getLiftedAt(), r.getLiftReason(), r.isReversedByAppeal(),
                staffLabel(r.getBlockedBy()));
    }

    // подпись для персонала: создатель или модератор + ник
    static String staffLabel(User u) {
        return (u.getRole() == User.Role.ADMIN ? "👑 Создатель " : "🛡️ Модератор ") + u.getNickname();
    }
}
