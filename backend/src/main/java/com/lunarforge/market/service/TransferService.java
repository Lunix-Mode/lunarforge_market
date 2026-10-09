package com.lunarforge.market.service;

import com.lunarforge.market.dto.TransferDtos.CreateTransferRequest;
import com.lunarforge.market.dto.TransferDtos.TransferResponse;
import com.lunarforge.market.entity.Transfer;
import com.lunarforge.market.entity.User;
import com.lunarforge.market.entity.WalletTransaction;
import com.lunarforge.market.exception.ApiException;
import com.lunarforge.market.repository.TransferRepository;
import com.lunarforge.market.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

// переводы денег между пользователями. деньги идут только с доступного баланса (не с замороженного),
// сразу пишется запись Transfer, операции в историю кошелька обоим и уведомление получателю
@Service
public class TransferService {

    private final com.lunarforge.market.service.NotificationService notifications;
    private final TransferRepository transferRepository;
    private final UserRepository userRepository;
    private final WalletService walletService;

    public TransferService(TransferRepository transferRepository, UserRepository userRepository,
                            WalletService walletService, com.lunarforge.market.service.NotificationService notifications) {
        this.notifications = notifications;
        this.transferRepository = transferRepository;
        this.userRepository = userRepository;
        this.walletService = walletService;
    }

    // получатель по id (цифры) или по @username
    // всё в одной транзакции: если где-то ошибка, деньги не спишутся у одного без зачисления другому
    @Transactional
    public TransferResponse send(User sender, CreateTransferRequest req) {
        BigDecimal amount = WalletService.validateAmount(req.amount());
        if (req.toUsername() == null || req.toUsername().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Укажите получателя");
        }

        // приводим к нижнему регистру и убираем @ в начале, юзернеймы хранятся без него
        String target = req.toUsername().trim().toLowerCase().replaceFirst("^@", "");
        // юзернейм не может быть из одних цифр (проверка при регистрации), поэтому путаницы с id
        // нет
        // если одни цифры - ищу по id, иначе по username
        User receiver = (target.matches("\\d{1,18}")
                ? userRepository.findById(Long.parseLong(target))
                : userRepository.findByUsername(target))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Пользователь " + target + " не найден"));

        if (receiver.getId().equals(sender.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Нельзя перевести деньги самому себе");
        }

        // лочим по возрастанию id - если А->Б и Б->А одновременно, не будет дедлока
        Long firstId = Math.min(sender.getId(), receiver.getId());
        Long secondId = Math.max(sender.getId(), receiver.getId());
        // lockById = SELECT ... FOR UPDATE. пока моя транзакция не закончится, другой запрос
        // с этими юзерами ждёт. иначе два перевода одновременно прочитали бы один и тот же баланс
        // и оба прошли, хотя денег хватало только на один
        User first = userRepository.lockById(firstId).orElseThrow();
        User second = userRepository.lockById(secondId).orElseThrow();
        // после лока беру свежие объекты из базы, а не те что пришли в метод - там баланс мог устареть
        User freshSender = first.getId().equals(sender.getId()) ? first : second;
        receiver = first.getId().equals(sender.getId()) ? second : first;

        // блокировку проверяю уже на свежих данных
        if (freshSender.isBlocked()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Ваш аккаунт заблокирован");
        }
        // хватает ли денег. compareTo, потому что equals у BigDecimal учитывает scale (10.0 != 10.00)
        if (freshSender.getBalanceAvailable().compareTo(amount) < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Недостаточно средств на балансе");
        }

        // само движение денег: у отправителя минус, у получателя плюс
        freshSender.setBalanceAvailable(freshSender.getBalanceAvailable().subtract(amount));
        receiver.setBalanceAvailable(receiver.getBalanceAvailable().add(amount));
        userRepository.save(freshSender);
        userRepository.save(receiver);

        Transfer transfer = new Transfer();
        transfer.setSender(freshSender);
        transfer.setReceiver(receiver);
        transfer.setAmount(amount);
        transfer.setMessage(req.message());
        transferRepository.save(transfer);

        // сообщение храним отдельным полем - приложение показывает его в своей карточке, а не через тире в описании
        walletService.log(freshSender, WalletTransaction.Type.TRANSFER_OUT, amount.negate(),
                "Перевод для @" + receiver.getUsername(), receiver, null, req.message());
        // у получателя запоминаю операцию, чтобы уведомление вело прямо на неё (refId)
        com.lunarforge.market.entity.WalletTransaction in = walletService.log(receiver, WalletTransaction.Type.TRANSFER_IN, amount,
                "Перевод от @" + freshSender.getUsername(), freshSender, null, req.message());
        notifications.notify(receiver, "TRANSFER_IN", "💸 Перевод " + amount + " ₽",
                "От " + freshSender.getNickname() + " (@" + freshSender.getUsername() + ")"
                        + (req.message() != null && !req.message().isBlank() ? ": " + req.message().trim() : ""),
                "TRANSACTION", in.getId());

        return toResponse(transfer);
    }

    // история переводов где я отправитель или получатель
    public List<TransferResponse> history(Long userId) {
        return transferRepository.findAllForUser(userId).stream().map(this::toResponse).toList();
    }

    private TransferResponse toResponse(Transfer t) {
        return new TransferResponse(
                t.getId(), t.getSender().getId(), t.getSender().getUsername(),
                t.getReceiver().getId(), t.getReceiver().getUsername(),
                t.getAmount(), t.getMessage(), t.getCreatedAt()
        );
    }
}
