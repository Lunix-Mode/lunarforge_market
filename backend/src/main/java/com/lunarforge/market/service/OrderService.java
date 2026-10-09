package com.lunarforge.market.service;

import com.lunarforge.market.dto.OrderDtos.OrderResponse;
import com.lunarforge.market.entity.Listing;
import com.lunarforge.market.entity.Order;
import com.lunarforge.market.entity.User;
import com.lunarforge.market.entity.WalletRelease;
import com.lunarforge.market.entity.WalletTransaction;
import com.lunarforge.market.exception.ApiException;
import com.lunarforge.market.repository.ListingRepository;
import com.lunarforge.market.repository.OrderRepository;
import com.lunarforge.market.repository.UserRepository;
import com.lunarforge.market.repository.WalletReleaseRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

// эскроу, самое важное место.
// покупка: деньги списываются и висят в заказе, бот пишет в чат
// подтверждение (покупатель): продавцу уходит его доля замороженной на 48ч
// отмена (продавец): деньги назад покупателю, товар назад в наличие
// всё что двигает деньги - через lockById, иначе двойной клик = двойное списание
// споры и возвраты по решению модератора тоже здесь (resolveDispute, refundFromSeller).
// каждый публичный метод под @Transactional - если что-то упало посередине, откатится всё, и деньги не потеряются "наполовину"
@Service
public class OrderService {

    // уведомления в приложении (колокольчик)
    private final com.lunarforge.market.service.NotificationService notifications;
    // сколько часов деньги продавца лежат замороженными после подтверждения
    public static final int WITHDRAWAL_HOLD_HOURS = 48;

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final ListingRepository listingRepository;
    private final WalletReleaseRepository walletReleaseRepository;

    // EntityManager подставляет сам спринг, нужен только для em.refresh с блокировкой
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager em; // перечитать таймер разморозки с блокировкой (см. refundFromSeller)
    private final ChatService chatService;
    private final WalletService walletService;

    public OrderService(OrderRepository orderRepository, UserRepository userRepository,
                         ListingRepository listingRepository, WalletReleaseRepository walletReleaseRepository,
                         ChatService chatService, WalletService walletService, com.lunarforge.market.service.NotificationService notifications) {
        this.notifications = notifications;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.listingRepository = listingRepository;
        this.walletReleaseRepository = walletReleaseRepository;
        this.chatService = chatService;
        this.walletService = walletService;
    }

    @Transactional
    // покупка товара. quantityReq может не прийти - тогда покупаю 1 шт.
    public OrderResponse purchase(User buyer, Long listingId, Integer quantityReq) {
        // порядок блокировок везде один: сначала товар, потом юзер. иначе можно словить дедлок
        // lockById у товара = SELECT FOR UPDATE: два покупателя последнего товара не купят его оба, второй подождёт и увидит что кончилось
        Listing listing = listingRepository.lockById(listingId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Товар не найден"));

        // товар сняли с продажи или закончился
        if (!listing.isActive()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Товар недоступен для покупки");
        }
        if (listing.getSeller().getId().equals(buyer.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Нельзя купить собственный товар");
        }

        int quantity = quantityReq == null ? 1 : quantityReq;
        // ограничиваю количество, чтобы не прислали минус (тогда покупатель бы "получил" деньги) или огромное число
        if (quantity <= 0 || quantity > 999) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Некорректное количество");
        }
        // unlimited - товар без ограничения количества (например услуга), остаток не проверяю
        if (!listing.isUnlimited() && quantity > listing.getQuantity()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Недостаточно товара в наличии (осталось " + listing.getQuantity() + " шт.)");
        }

        // считаю всё в BigDecimal, double для денег нельзя - копейки поплывут. округление до копеек
        // amount - что платит покупатель (цена с 5% комиссией), sellerAmount - что получит продавец
        BigDecimal amount = listing.getBuyerPrice().multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal sellerAmount = listing.getPrice().multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
        // комиссия = что заплатил покупатель минус что получит продавец
        BigDecimal commissionAmount = amount.subtract(sellerAmount);

        // лочу покупателя уже после товара (см. порядок выше). теперь баланс никто другой не поменяет пока я тут
        User lockedBuyer = userRepository.lockById(buyer.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Пользователь не найден"));
        if (lockedBuyer.isBlocked()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Ваш аккаунт заблокирован");
        }
        // compareTo потому что equals у BigDecimal учитывает scale (10.0 != 10.00)
        if (lockedBuyer.getBalanceAvailable().compareTo(amount) < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Недостаточно средств: нужно " + amount
                    + " ₽, доступно " + lockedBuyer.getBalanceAvailable() + " ₽");
        }

        // деньги просто списываю с покупателя - они "висят" в заказе, пока он не подтвердит или продавец не отменит
        // продавец пока ничего не получает
        lockedBuyer.setBalanceAvailable(lockedBuyer.getBalanceAvailable().subtract(amount));
        userRepository.save(lockedBuyer);

        // уменьшаю остаток, а если всё раскупили - прячу товар с витрины
        if (!listing.isUnlimited()) {
            listing.setQuantity(listing.getQuantity() - quantity);
            if (listing.getQuantity() <= 0) {
                listing.setActive(false);
            }
            listingRepository.save(listing);
        }

        // создаю заказ в статусе "ждёт подтверждения" - это и есть безопасная сделка
        Order order = new Order();
        order.setListing(listing);
        order.setBuyer(lockedBuyer);
        order.setSeller(listing.getSeller());
        order.setAmount(amount);
        order.setCommissionAmount(commissionAmount);
        order.setSellerAmount(sellerAmount);
        order.setQuantity(quantity);
        order.setStatus(Order.OrderStatus.PENDING_CONFIRMATION);
        orderRepository.save(order);
        // операцию пишем после сохранения заказа - чтобы в ней был номер заказа
        walletService.log(lockedBuyer, WalletTransaction.Type.PURCHASE, amount.negate(),
                "Покупка: " + listing.getTitle() + " × " + quantity, listing.getSeller(), order.getId(), null);
        // продавцу уведомление что пришёл заказ
        notifications.notify(listing.getSeller(), "ORDER_NEW", "🛒 Новый заказ #" + order.getId(),
                "«" + listing.getTitle() + "» × " + quantity + " на " + amount + " ₽. Выполните заказ и напишите покупателю.",
                "ORDER", order.getId());

        // бот пишет в чат покупателя с продавцом, чтобы обоим было понятно что дальше делать
        chatService.postBotMessage(lockedBuyer, listing.getSeller(), listing.getId(),
                "🛒 Куплен заказ #" + order.getId() + ": «" + listing.getTitle() + "» × " + quantity
                        + " на сумму " + amount + " ₽.\n"
                        + "Заказ ожидает выполнения продавцом.\n"
                        + "Покупатель: после получения нажмите «Подтвердить получение» в заказе - только тогда "
                        + "деньги поступят продавцу (станут доступны через " + WITHDRAWAL_HOLD_HOURS + " ч).\n"
                        + "Продавец: если не можете выполнить заказ - отмените его, деньги вернутся покупателю.");

        return toResponse(order);
    }

    @Transactional
    // покупатель подтверждает что получил товар - только после этого продавец получает деньги
    public OrderResponse confirmReceipt(User buyer, Long orderId) {
        // лочу заказ, иначе двойной тап по кнопке начислил бы продавцу два раза (второй запрос ждёт и потом видит COMPLETED)
        Order order = orderRepository.lockById(orderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Заказ не найден"));

        // подтвердить может только сам покупатель этого заказа
        if (!order.getBuyer().getId().equals(buyer.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Подтвердить получение может только покупатель");
        }
        if (order.getStatus() == Order.OrderStatus.DISPUTED) {
            throw new ApiException(HttpStatus.CONFLICT, "По заказу идёт спор, решение примет модератор");
        }
        // подтверждать можно только заказ который ещё ждёт
        if (order.getStatus() != Order.OrderStatus.PENDING_CONFIRMATION) {
            throw new ApiException(HttpStatus.CONFLICT, "Заказ уже завершён или отменён");
        }

        // лочу продавца перед изменением его баланса
        User seller = userRepository.lockById(order.getSeller().getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Продавец не найден"));

        // в frozen, а не в available - 48ч их нельзя тратить
        seller.setBalanceFrozen(seller.getBalanceFrozen().add(order.getSellerAmount()));
        userRepository.save(seller);
        walletService.log(seller, WalletTransaction.Type.SALE_FROZEN, order.getSellerAmount(),
                "Продажа: " + order.getListing().getTitle() + " (заморожено на " + WITHDRAWAL_HOLD_HOURS + " ч)",
                order.getBuyer(), order.getId(), null);
        notifications.notify(seller, "ORDER_CONFIRMED", "✅ Заказ #" + order.getId() + " подтверждён",
                order.getSellerAmount() + " ₽ поступили на баланс и станут доступны через " + WITHDRAWAL_HOLD_HOURS + " ч.",
                "ORDER", order.getId());

        order.setStatus(Order.OrderStatus.COMPLETED);
        order.setConfirmedAt(Instant.now());
        orderRepository.save(order);

        // таймер разморозки: WalletReleaseScheduler раз в минуту ищет просроченные таймеры и переносит деньги из frozen в available
        WalletRelease release = new WalletRelease();
        release.setUser(seller);
        release.setOrder(order);
        release.setAmount(order.getSellerAmount());
        release.setReleaseAt(Instant.now().plus(WITHDRAWAL_HOLD_HOURS, ChronoUnit.HOURS));
        walletReleaseRepository.save(release);

        chatService.postBotMessage(order.getBuyer(), seller, order.getListing().getId(),
                "✅ Заказ #" + order.getId() + " подтверждён покупателем. Продавец получит "
                        + order.getSellerAmount() + " ₽ - они станут доступны через " + WITHDRAWAL_HOLD_HOURS
                        + " ч. Покупатель может оставить отзыв в заказе.");

        return toResponse(order);
    }

    @Transactional
    // отмена заказа продавцом (не может выполнить). деньги полностью назад покупателю
    public OrderResponse cancel(User requester, Long orderId) {
        Order order = orderRepository.lockById(orderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Заказ не найден"));

        // только продавец! покупатель если не получил товар - просто не подтверждает
        if (order.getStatus() == Order.OrderStatus.DISPUTED) {
            throw new ApiException(HttpStatus.CONFLICT, "По заказу идёт спор, решение примет модератор");
        }
        if (!order.getSeller().getId().equals(requester.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Отменить заказ может только продавец. Если товар не получен - не подтверждайте заказ и напишите продавцу.");
        }
        if (order.getStatus() != Order.OrderStatus.PENDING_CONFIRMATION) {
            throw new ApiException(HttpStatus.CONFLICT, "Заказ уже завершён или отменён");
        }

        // лочу товар, возвращаю количество на склад. orElse(null) - товар могли удалить, тогда просто пропускаю
        Listing listing = listingRepository.lockById(order.getListing().getId()).orElse(null);
        if (listing != null && !listing.isUnlimited()) {
            // если товар был скрыт только потому что кончился - возвращаем на витрину
            boolean wasSoldOut = listing.getQuantity() <= 0;
            listing.setQuantity(listing.getQuantity() + order.getQuantity());
            if (wasSoldOut) {
                listing.setActive(true);
            }
            listingRepository.save(listing);
        }

        // возвращаю покупателю всю сумму вместе с комиссией - площадка ничего не берёт с отменённого заказа
        User buyer = userRepository.lockById(order.getBuyer().getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Покупатель не найден"));
        buyer.setBalanceAvailable(buyer.getBalanceAvailable().add(order.getAmount()));
        userRepository.save(buyer);
        walletService.log(buyer, WalletTransaction.Type.REFUND, order.getAmount(),
                "Возврат: " + order.getListing().getTitle(), order.getSeller(), order.getId(), null);
        notifications.notify(buyer, "ORDER_CANCELLED", "↩️ Заказ #" + order.getId() + " отменён продавцом",
                order.getAmount() + " ₽ вернулись на ваш баланс.", "ORDER", order.getId());

        order.setStatus(Order.OrderStatus.CANCELLED);
        order.setCancelledAt(Instant.now());
        orderRepository.save(order);

        chatService.postBotMessage(buyer, order.getSeller(), order.getListing().getId(),
                "↩️ Продавец отменил заказ #" + order.getId() + ". " + order.getAmount()
                        + " ₽ возвращены на баланс покупателя.");

        return toResponse(order);
    }

    // покупатель открыл спор: заказ замораживается, подтвердить/отменить нельзя пока модератор не решит
    @Transactional
    // вызывается когда покупатель создаёт заявку на возврат
    public Order markDisputed(User buyer, Long orderId) {
        Order order = orderRepository.lockById(orderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Заказ не найден"));
        if (!order.getBuyer().getId().equals(buyer.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Запросить возврат может только покупатель");
        }
        // спор можно открыть только пока заказ не подтверждён - потом деньги уже у продавца
        if (order.getStatus() != Order.OrderStatus.PENDING_CONFIRMATION) {
            throw new ApiException(HttpStatus.CONFLICT, order.getStatus() == Order.OrderStatus.DISPUTED
                    ? "Спор по этому заказу уже открыт"
                    : "Возврат можно запросить только до подтверждения получения");
        }
        order.setStatus(Order.OrderStatus.DISPUTED);
        return orderRepository.save(order);
    }

    // решение модератора по спору. refund = сколько вернуть покупателю (0..amount).
    // остаток уходит продавцу (замороженным на 48ч), комиссия берётся только с остатка пропорционально.
    // порядок локов как в cancel: заказ -> товар -> юзеры
    @Transactional
    public Order resolveDispute(Long orderId, BigDecimal refund) {
        Order order = orderRepository.lockById(orderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Заказ не найден"));
        // спор (возврат) или ещё не подтверждённый заказ (проблема с заказом) - деньги ещё у площадки
        if (order.getStatus() != Order.OrderStatus.DISPUTED && order.getStatus() != Order.OrderStatus.PENDING_CONFIRMATION) {
            throw new ApiException(HttpStatus.CONFLICT, "Заказ уже завершён или отменён");
        }
        BigDecimal amount = order.getAmount();
        // округляю до копеек и проверяю что возврат в пределах суммы заказа
        refund = refund.setScale(2, RoundingMode.HALF_UP);
        if (refund.signum() < 0 || refund.compareTo(amount) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Сумма возврата должна быть от 0 до " + amount + " ₽");
        }
        // remainder - что остаётся продавцу+площадке. если 0 - это полный возврат
        BigDecimal remainder = amount.subtract(refund);
        boolean fullRefund = remainder.signum() == 0;

        if (fullRefund) {
            // всё вернули - значит товар покупатель не получил, возвращаем в наличие
            Listing listing = listingRepository.lockById(order.getListing().getId()).orElse(null);
            if (listing != null && !listing.isUnlimited()) {
                boolean wasSoldOut = listing.getQuantity() <= 0;
                listing.setQuantity(listing.getQuantity() + order.getQuantity());
                if (wasSoldOut) listing.setActive(true);
                listingRepository.save(listing);
            }
        }

        // покупателю его часть возврата сразу в доступные
        if (refund.signum() > 0) {
            User buyer = userRepository.lockById(order.getBuyer().getId()).orElseThrow();
            buyer.setBalanceAvailable(buyer.getBalanceAvailable().add(refund));
            userRepository.save(buyer);
            walletService.log(buyer, WalletTransaction.Type.REFUND, refund,
                    "Возврат по спору: " + order.getListing().getTitle(), order.getSeller(), order.getId(), null);
        }

        // комиссию делю пропорционально: если продавцу досталась половина суммы, площадка берёт половину комиссии.
        // например заказ 105р (комиссия 5), вернули 52.50 -> остаток 52.50, комиссия 2.50, продавцу 50
        BigDecimal commissionPart = BigDecimal.ZERO;
        BigDecimal sellerPart = BigDecimal.ZERO;
        if (remainder.signum() > 0) {
            commissionPart = order.getCommissionAmount().multiply(remainder)
                    .divide(amount, 2, RoundingMode.HALF_UP);
            sellerPart = remainder.subtract(commissionPart);
            // продавцу - в замороженные и с таким же таймером на 48ч, как при обычном подтверждении
            User seller = userRepository.lockById(order.getSeller().getId()).orElseThrow();
            seller.setBalanceFrozen(seller.getBalanceFrozen().add(sellerPart));
            userRepository.save(seller);
            walletService.log(seller, WalletTransaction.Type.SALE_FROZEN, sellerPart,
                    "Продажа по решению спора: " + order.getListing().getTitle()
                            + " (заморожено на " + WITHDRAWAL_HOLD_HOURS + " ч)", order.getBuyer(), order.getId(), null);
            WalletRelease release = new WalletRelease();
            release.setUser(seller);
            release.setOrder(order);
            release.setAmount(sellerPart);
            release.setReleaseAt(Instant.now().plus(WITHDRAWAL_HOLD_HOURS, ChronoUnit.HOURS));
            walletReleaseRepository.save(release);
        }

        // перезаписываю суммы в заказе фактическими, чтобы статистика заработка и комиссии считалась честно
        order.setRefundedAmount(refund);
        order.setCommissionAmount(commissionPart);
        order.setSellerAmount(sellerPart);
        // полный возврат = заказ отменён, иначе считается завершённым
        if (fullRefund) {
            order.setStatus(Order.OrderStatus.CANCELLED);
            order.setCancelledAt(Instant.now());
        } else {
            order.setStatus(Order.OrderStatus.COMPLETED);
            order.setConfirmedAt(Instant.now());
        }
        return orderRepository.save(order);
    }

    // readOnly - только чтение, hibernate не будет проверять изменения сущностей
    @Transactional(readOnly = true)
    // один заказ - увидеть его могут только покупатель или продавец
    public OrderResponse getForParty(User requester, Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Заказ не найден"));
        boolean isParty = order.getBuyer().getId().equals(requester.getId())
                || order.getSeller().getId().equals(requester.getId());
        if (!isParty) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Это не ваш заказ");
        }
        return toResponse(order);
    }

    // все покупки/продажи целиком, новые сверху
    @Transactional(readOnly = true)
    public List<OrderResponse> myPurchases(Long buyerId) {
        return orderRepository.findByBuyerIdOrderByCreatedAtDesc(buyerId).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> mySales(Long sellerId) {
        return orderRepository.findBySellerIdOrderByCreatedAtDesc(sellerId).stream().map(this::toResponse).toList();
    }

    // порция списка. без page - весь список (нужен, например, при выборе заказа для заявки)
    @Transactional(readOnly = true)
    public List<OrderResponse> myPurchases(Long buyerId, int page, int size) {
        return orderRepository.findByBuyerIdOrderByCreatedAtDesc(buyerId, pageOf(page, size)).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> mySales(Long sellerId, int page, int size) {
        return orderRepository.findBySellerIdOrderByCreatedAtDesc(sellerId, pageOf(page, size)).stream().map(this::toResponse).toList();
    }

    // защита от кривых параметров: страница не меньше 0, размер от 1 до 100
    static org.springframework.data.domain.PageRequest pageOf(int page, int size) {
        return org.springframework.data.domain.PageRequest.of(Math.max(0, page), Math.max(1, Math.min(100, size)));
    }

    // entity -> dto, наружу отдаю только нужные поля (без паролей юзеров и т.п.)
    private OrderResponse toResponse(Order o) {
        return new OrderResponse(
                o.getId(),
                o.getListing().getId(),
                o.getListing().getTitle(),
                o.getBuyer().getId(),
                o.getBuyer().getNickname(),
                o.getSeller().getId(),
                o.getSeller().getNickname(),
                o.getAmount(),
                o.getCommissionAmount(),
                o.getSellerAmount(),
                o.getQuantity(),
                o.getStatus(),
                o.getCreatedAt(),
                o.getConfirmedAt(),
                o.getRefundedAmount()
        );
    }

    // завершённый заказ, но модератор решил вернуть покупателю часть/всё за счёт продавца.
    // берём сначала из замороженных денег ЭТОГО заказа (продавец их ещё не мог потратить), потом из доступных.
    // не хватает - ничего не делаем и говорим модератору, сколько можно (остальное - компенсацией от площадки).
    // комиссия площадки не возвращается: продавец отдаёт только из своей доли
    @Transactional
    public Order refundFromSeller(Long orderId, BigDecimal amount) {
        Order order = orderRepository.lockById(orderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Заказ не найден"));
        if (order.getStatus() != Order.OrderStatus.COMPLETED) {
            throw new ApiException(HttpStatus.CONFLICT, "Возврат за счёт продавца - только для завершённого заказа");
        }
        // сумму округляю и проверяю, что не больше того что продавец получил с этого заказа
        amount = amount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal alreadyRefunded = order.getRefundedAmount() == null ? BigDecimal.ZERO : order.getRefundedAmount();
        if (amount.compareTo(order.getSellerAmount()) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "За счёт продавца можно вернуть не больше того, что он получил: "
                    + order.getSellerAmount() + " ₽. Остальное - компенсацией от площадки");
        }
        // блокируем обоих по возрастанию id - как в переводах, чтобы встречные операции не ждали друг друга вечно
        Long buyerId = order.getBuyer().getId(), sellerId = order.getSeller().getId();
        User first = userRepository.lockById(Math.min(buyerId, sellerId)).orElseThrow();
        User second = userRepository.lockById(Math.max(buyerId, sellerId)).orElseThrow();
        User buyer = first.getId().equals(buyerId) ? first : second;
        User seller = first.getId().equals(sellerId) ? first : second;

        // таймер разморозки перечитываем ИЗ БАЗЫ с блокировкой: фоновая разморозка могла его уже изменить
        // ищу таймер разморозки этой продажи
        com.lunarforge.market.entity.WalletRelease release = walletReleaseRepository
                .findFirstByOrderIdAndUserId(orderId, sellerId).orElse(null);
        BigDecimal frozenForOrder = BigDecimal.ZERO;
        if (release != null) {
            // refresh перечитывает строку из базы и сразу лочит её (FOR UPDATE). без этого шедулер мог в тот же момент разморозить
            // эти деньги, и я бы снял их из frozen второй раз
            em.refresh(release, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            // если уже разморожено - из frozen этого заказа взять нечего
            if (!release.isReleased()) frozenForOrder = release.getAmount();
        }
        // сначала беру из замороженных этого заказа, недостающее - из доступных продавца
        BigDecimal fromFrozen = frozenForOrder.min(amount);
        BigDecimal fromAvailable = amount.subtract(fromFrozen);
        // если доступных не хватает - ничего не трогаю и кидаю ошибку с максимумом, который реально можно вернуть
        if (seller.getBalanceAvailable().compareTo(fromAvailable) < 0) {
            BigDecimal max = fromFrozen.add(seller.getBalanceAvailable());
            throw new ApiException(HttpStatus.CONFLICT, "У продавца не хватает денег: за его счёт можно вернуть не больше "
                    + max + " ₽. Остальное - компенсацией от площадки");
        }
        // всё проверили - теперь двигаем деньги
        if (fromFrozen.signum() > 0) {
            // уменьшаю таймер, чтобы шедулер потом не разморозил уже возвращённые деньги
            release.setAmount(release.getAmount().subtract(fromFrozen));
            if (release.getAmount().signum() == 0) release.setReleased(true); // размораживать больше нечего
            walletReleaseRepository.save(release);
            seller.setBalanceFrozen(seller.getBalanceFrozen().subtract(fromFrozen));
        }
        seller.setBalanceAvailable(seller.getBalanceAvailable().subtract(fromAvailable));
        buyer.setBalanceAvailable(buyer.getBalanceAvailable().add(amount));
        userRepository.save(seller);
        userRepository.save(buyer);

        order.setRefundedAmount(alreadyRefunded.add(amount));
        order.setSellerAmount(order.getSellerAmount().subtract(amount)); // в статистике "заработано" - честная цифра
        orderRepository.save(order);

        String title = order.getListing().getTitle();
        // две записи в истории: покупателю плюс (REFUND), продавцу минус (CLAWBACK)
        walletService.log(buyer, WalletTransaction.Type.REFUND, amount,
                "Возврат по решению модератора: " + title, seller, orderId, null);
        walletService.log(seller, WalletTransaction.Type.CLAWBACK, amount.negate(),
                "Возврат покупателю по решению модератора: " + title, buyer, orderId, null);
        notifications.notify(buyer, "ORDER_REFUND", "↩️ Возврат по заказу #" + orderId,
                amount + " ₽ вернулись на баланс по решению модератора.", "ORDER", orderId);
        notifications.notify(seller, "ORDER_CLAWBACK", "⚖️ Списание по заказу #" + orderId,
                amount + " ₽ возвращены покупателю по решению модератора.", "ORDER", orderId);
        return order;
    }
}
