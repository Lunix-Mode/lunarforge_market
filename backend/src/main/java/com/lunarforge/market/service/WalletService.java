package com.lunarforge.market.service;

import com.lunarforge.market.dto.UserDtos.UserProfile;
import com.lunarforge.market.dto.WalletDtos.TransactionResponse;
import com.lunarforge.market.entity.User;
import com.lunarforge.market.entity.WalletTransaction;
import com.lunarforge.market.exception.ApiException;
import com.lunarforge.market.repository.UserRepository;
import com.lunarforge.market.repository.WalletTransactionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

// кошелёк: пополнение, вывод, история операций и общие проверки сумм.
// у юзера два баланса: available (можно тратить/выводить) и frozen (деньги с продаж, ждут разморозки).
// любое изменение денег записываю в WalletTransaction, чтобы была история
@Service
public class WalletService {
    private final UserRepository userRepository;
    private final WalletTransactionRepository transactionRepository;
    private final UserService userService;
    private final com.lunarforge.market.repository.OrderRepository orderRepository;
    private final com.lunarforge.market.repository.WalletReleaseRepository walletReleaseRepository;

    public WalletService(UserRepository userRepository, WalletTransactionRepository transactionRepository,
                          UserService userService,
                         com.lunarforge.market.repository.OrderRepository orderRepository,
                         com.lunarforge.market.repository.WalletReleaseRepository walletReleaseRepository) {
        this.orderRepository = orderRepository;
        this.walletReleaseRepository = walletReleaseRepository;
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
        this.userService = userService;
    }

    // ЗАГЛУШКА. деньги просто появляются, с карты ничего не списывается
    @Transactional
    public UserProfile topUp(Long userId, BigDecimal amount, String cardNumber) {
        amount = validateAmount(amount);
        // сначала лочу строку юзера, иначе два пополнения/вывода одновременно прочитают
        // старый баланс и одно из изменений потеряется
        User user = userRepository.lockById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Пользователь не найден"));

        user.setBalanceAvailable(user.getBalanceAvailable().add(amount));
        userRepository.save(user);

        // короткий log без контрагента и заказа
        log(user, WalletTransaction.Type.TOP_UP, amount,
                "Пополнение с карты" + maskCard(cardNumber));

        return userService.toProfile(user);
    }

    // ЗАГЛУШКА. выводить можно только available, замороженные нельзя
    @Transactional
    public UserProfile withdraw(Long userId, BigDecimal amount, String cardNumber) {
        amount = validateAmount(amount);
        // тот же лок что и в topUp - иначе два вывода одновременно оба увидят что денег хватает и спишут дважды
        User user = userRepository.lockById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Пользователь не найден"));

        // проверяю только available, frozen тут вообще не участвует
        if (user.getBalanceAvailable().compareTo(amount) < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Недостаточно доступных средств. " +
                    "Замороженные после продажи деньги станут доступны по истечении срока заморозки.");
        }
        // номер карты тоже не настоящий, просто минимальная проверка что там что-то похожее (пробелы убираю)
        if (cardNumber == null || cardNumber.replaceAll("\\s", "").length() < 12) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Укажите корректный номер карты");
        }

        user.setBalanceAvailable(user.getBalanceAvailable().subtract(amount));
        userRepository.save(user);

        log(user, WalletTransaction.Type.WITHDRAWAL, amount.negate(),
                "Вывод на карту" + maskCard(cardNumber));

        return userService.toProfile(user);
    }

    // история постранично (для экрана кошелька), pageOf общий хелпер из OrderService
    @Transactional(readOnly = true)
    public List<TransactionResponse> history(Long userId, int page, int size) {
        return transactionRepository.findByUserIdOrderByCreatedAtDesc(userId, OrderService.pageOf(page, size)).stream()
                .map(this::toResponse).toList();
    }

    // старая версия без страниц
    public List<TransactionResponse> history(Long userId) {
        return transactionRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toResponse).toList();
    }

    // потолок одной операции
    public static final BigDecimal MAX_OPERATION = new BigDecimal("1000000");

    // общие правила для всех сумм: > 0, максимум 2 знака после запятой, не больше 1млн за раз
    public static BigDecimal validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Сумма должна быть больше нуля");
        }
        // stripTrailingZeros - чтобы нули в конце (10.500) не считались лишними знаками, а вот 10.505 уже не пройдёт
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Сумма может содержать не больше 2 знаков после запятой");
        }
        if (amount.compareTo(MAX_OPERATION) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Максимальная сумма за одну операцию - 1 000 000 ₽");
        }
        // приводим к 2 знакам. UNNECESSARY - округления тут быть не может, мы выше уже проверили
        return amount.setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    // log не @Transactional сам по себе - его всегда зовут изнутри других транзакционных методов,
    // поэтому запись в историю сохраняется или откатывается вместе с самим движением денег
    public void log(User user, WalletTransaction.Type type, BigDecimal signedAmount, String description) {
        log(user, type, signedAmount, description, null, null, null);
    }

    // полная версия: с кем операция, по какому заказу и сообщение (для экрана подробностей)
    // signedAmount со знаком: плюс - пришло, минус - ушло. так в истории сразу видно направление
    public WalletTransaction log(User user, WalletTransaction.Type type, BigDecimal signedAmount, String description,
                                 User counterparty, Long orderId, String message) {
        WalletTransaction tx = new WalletTransaction();
        tx.setUser(user);
        tx.setType(type);
        tx.setAmount(signedAmount);
        tx.setDescription(description);
        tx.setCounterparty(counterparty);
        tx.setRelatedOrderId(orderId);
        // пустое сообщение не храню, ставлю null
        tx.setMessage(message == null || message.isBlank() ? null : message.trim());
        return transactionRepository.save(tx);
    }

    // подробности одной операции, только своей
    @Transactional(readOnly = true)
    public TransactionResponse detail(Long userId, Long txId) {
        WalletTransaction t = transactionRepository.findById(txId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Операция не найдена"));
        if (!t.getUser().getId().equals(userId)) throw new ApiException(HttpStatus.FORBIDDEN, "Не ваша операция");
        return toResponse(t);
    }

    // человеческое название операции для приложения по типу.
    // switch по enum без default - если добавлю новый тип и забуду сюда, не скомпилируется
    static String title(WalletTransaction.Type type) {
        return switch (type) {
            case TOP_UP -> "Пополнение баланса";
            case WITHDRAWAL -> "Вывод на карту";
            case PURCHASE -> "Покупка";
            case SALE_FROZEN -> "Продажа";
            case SALE_RELEASED -> "Деньги разморожены";
            case REFUND -> "Возврат средств";
            case TRANSFER_OUT -> "Перевод";
            case TRANSFER_IN -> "Входящий перевод";
            case COMPENSATION -> "Компенсация от площадки";
            case CLAWBACK -> "Возврат покупателю";
        };
    }

    // от номера карты показываю только последние 4 цифры, полный номер в историю не пишу
    private String maskCard(String cardNumber) {
        if (cardNumber == null) return "";
        String digits = cardNumber.replaceAll("\\s", "");
        if (digits.length() < 4) return "";
        return " •••• " + digits.substring(digits.length() - 4);
    }

    // сущность -> dto. если операция связана с заказом, подтягиваю название товара
    private TransactionResponse toResponse(WalletTransaction t) {
        User c = t.getCounterparty();
        String orderTitle = null;
        java.time.Instant releaseAt = null;
        Boolean released = null;
        if (t.getRelatedOrderId() != null) {
            com.lunarforge.market.entity.Order o = orderRepository.findById(t.getRelatedOrderId()).orElse(null);
            if (o != null) orderTitle = o.getListing().getTitle();
            // для замороженной продажи ищу запись разморозки, чтобы приложение показало когда деньги станут
            // доступны (через 48 часов после подтверждения) и разморозились ли уже
            if (t.getType() == WalletTransaction.Type.SALE_FROZEN) {
                com.lunarforge.market.entity.WalletRelease r =
                        walletReleaseRepository.findFirstByOrderIdAndUserId(t.getRelatedOrderId(), t.getUser().getId()).orElse(null);
                if (r != null) {
                    releaseAt = r.getReleaseAt();
                    released = r.isReleased();
                }
            }
        }
        return new TransactionResponse(t.getId(), t.getType().name(), t.getAmount(), t.getDescription(), t.getCreatedAt(),
                title(t.getType()),
                c != null ? c.getId() : null, c != null ? c.getNickname() : null,
                c != null ? c.getUsername() : null, c != null ? c.getAvatarUrl() : null,
                t.getRelatedOrderId(), orderTitle, t.getMessage(), releaseAt, released);
    }
}
