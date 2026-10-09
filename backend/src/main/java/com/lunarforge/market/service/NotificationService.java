package com.lunarforge.market.service;

import com.lunarforge.market.dto.NotificationDtos.NotificationResponse;
import com.lunarforge.market.entity.Notification;
import com.lunarforge.market.entity.User;
import com.lunarforge.market.exception.ApiException;
import com.lunarforge.market.repository.NotificationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// уведомления. notify() зовут из других сервисов в их же транзакции:
// откатилась покупка - откатилось и уведомление о ней
@Service
public class NotificationService {

    private final NotificationRepository repository;

    public NotificationService(NotificationRepository repository) {
        this.repository = repository;
    }

    // создать уведомление юзеру. type - вид события (например TRANSFER_IN), refType/refId - на что
    // ссылается (заказ, операция), чтобы по тапу в приложении открыть нужный экран.
    // REQUIRED - если уже есть транзакция снаружи, работаю в ней, а не в отдельной
    @Transactional(propagation = Propagation.REQUIRED)
    public void notify(User user, String type, String title, String body, String refType, Long refId) {
        // на всякий случай, чтобы не падать если юзера не передали
        if (user == null) return;
        Notification n = new Notification();
        n.setUser(user);
        n.setType(type);
        // обрезаю длинный текст под размер колонок в базе, иначе save упадёт
        n.setTitle(title.length() > 200 ? title.substring(0, 200) : title);
        n.setBody(body != null && body.length() > 1000 ? body.substring(0, 1000) : body);
        n.setRefType(refType);
        n.setRefId(refId);
        repository.save(n);
    }

    // последние 100 уведомлений, свежие сверху. больше в приложении всё равно никто не листает
    @Transactional(readOnly = true)
    public List<NotificationResponse> mine(Long userId) {
        return repository.findTop100ByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(n -> new NotificationResponse(n.getId(), n.getType(), n.getTitle(), n.getBody(),
                        n.getRefType(), n.getRefId(), n.isRead(), n.getCreatedAt()))
                .toList();
    }

    // количество непрочитанных - для красного кружка на колокольчике
    public long unread(Long userId) {
        return repository.countByUserIdAndReadFalse(userId);
    }

    // отметить всё прочитанным одним update-запросом, а не по одному
    @Transactional
    public void markAllRead(Long userId) {
        repository.markAllRead(userId);
    }

    // отметить одно. проверяю что уведомление моё, иначе можно было бы трогать чужие по id
    @Transactional
    public void markRead(Long userId, Long id) {
        Notification n = repository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Уведомление не найдено"));
        if (!n.getUser().getId().equals(userId)) throw new ApiException(HttpStatus.FORBIDDEN, "Не ваше уведомление");
        n.setRead(true);
        repository.save(n);
    }
}
