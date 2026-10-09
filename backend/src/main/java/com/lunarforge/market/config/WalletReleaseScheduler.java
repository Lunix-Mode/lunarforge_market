package com.lunarforge.market.config;

import com.lunarforge.market.entity.User;
import com.lunarforge.market.entity.WalletRelease;
import com.lunarforge.market.repository.UserRepository;
import com.lunarforge.market.repository.WalletReleaseRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

// раз в минуту размораживает деньги продавцов у которых прошло 48ч.
// у каждого заказа свой таймер, поэтому деньги приходят не все сразу.
// как это устроено: когда покупатель подтверждает заказ, деньги продавцу падают в balanceFrozen
// и создаётся запись WalletRelease с releaseAt = сейчас + 48ч. этот класс просто периодически
// ищет такие записи, у которых время уже вышло, и перекладывает сумму из frozen в available.
// работает сам по себе, без запросов от клиента (нужен @EnableScheduling в конфиге)
@Component
public class WalletReleaseScheduler {

    // чтобы кинуть продавцу уведомление "деньги доступны"
    private final com.lunarforge.market.service.NotificationService notifications;
    private final WalletReleaseRepository releaseRepository;

    // EntityManager нужен только ради em.refresh с блокировкой (в репозитории такого метода нет)
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager em;
    private final UserRepository userRepository;
    // через него пишу строчку в историю операций кошелька
    private final com.lunarforge.market.service.WalletService walletService;

    // зависимости спринг подставляет сам через конструктор
    public WalletReleaseScheduler(WalletReleaseRepository releaseRepository, UserRepository userRepository,
                                   com.lunarforge.market.service.WalletService walletService, com.lunarforge.market.service.NotificationService notifications) {
        this.notifications = notifications;
        this.releaseRepository = releaseRepository;
        this.userRepository = userRepository;
        this.walletService = walletService;
    }

    // fixedDelay = следующий запуск через минуту ПОСЛЕ окончания предыдущего,
    // так что два прохода одновременно не пойдут, даже если один затянулся.
    // всё в одной транзакции: если что-то упадёт посередине, откатится весь проход
    // и на следующей минуте попробуем снова (released ещё false)
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void releaseMaturedFunds() {
        // берём только не размороженные и те, у кого releaseAt уже в прошлом
        List<WalletRelease> matured = releaseRepository.findByReleasedFalseAndReleaseAtBefore(Instant.now());
        for (WalletRelease release : matured) {
            // лочим юзера, а то параллельная покупка/перевод может перезаписать баланс
            User user = userRepository.lockById(release.getUser().getId()).orElse(null);
            // юзера нет (на всякий случай) - просто пропускаю эту запись
            if (user == null) continue;
            // список читали ДО блокировки: модератор мог за это время забрать часть денег (возврат за счёт продавца).
            // перечитываем таймер из базы с блокировкой и работаем с актуальной суммой
            em.refresh(release, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            // уже разморожено кем-то другим или сумму целиком забрали возвратом - тут делать нечего
            if (release.isReleased() || release.getAmount().signum() <= 0) continue;

            // сама разморозка: минус из замороженных, плюс в доступные. общая сумма у продавца не меняется
            user.setBalanceFrozen(user.getBalanceFrozen().subtract(release.getAmount()));
            user.setBalanceAvailable(user.getBalanceAvailable().add(release.getAmount()));
            userRepository.save(user);

            // запись в историю кошелька, чтобы продавец видел откуда взялись деньги.
            // покупателя передаю как вторую сторону операции, id заказа - чтобы можно было перейти к нему
            walletService.log(user, com.lunarforge.market.entity.WalletTransaction.Type.SALE_RELEASED, release.getAmount(),
                    "Заморозка снята: заказ #" + release.getOrder().getId(),
                    release.getOrder().getBuyer(), release.getOrder().getId(), null);
            // уведомление в колокольчик, по нажатию ведёт на заказ (refType ORDER)
            notifications.notify(user, "MONEY_RELEASED", "💰 " + release.getAmount() + " ₽ доступны",
                    "Заморозка по заказу #" + release.getOrder().getId() + " снята - деньги можно тратить и выводить.",
                    "ORDER", release.getOrder().getId());

            // помечаю таймер отработавшим, иначе через минуту разморозили бы второй раз
            release.setReleased(true);
            releaseRepository.save(release);
        }
    }
}
