package com.lunarforge.market.service;

import com.lunarforge.market.dto.RatingDtos.CreateRatingRequest;
import com.lunarforge.market.dto.RatingDtos.RatingResponse;
import com.lunarforge.market.dto.RatingDtos.UpdateRatingRequest;
import com.lunarforge.market.entity.Order;
import com.lunarforge.market.entity.Rating;
import com.lunarforge.market.entity.User;
import com.lunarforge.market.exception.ApiException;
import com.lunarforge.market.repository.OrderRepository;
import com.lunarforge.market.repository.RatingRepository;
import com.lunarforge.market.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

// правила отзывов: только после завершённого заказа, один на заказ, править раз в сутки,
// автора видит только продавец, сумма округляется до 10р
@Service
public class RatingService {

    private final com.lunarforge.market.service.NotificationService notifications;
    // кулдаун на редактирование - чтобы продавца не дёргали бесконечными правками отзыва
    private static final int EDIT_COOLDOWN_HOURS = 24;

    private final RatingRepository ratingRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;

    public RatingService(RatingRepository ratingRepository, OrderRepository orderRepository, UserRepository userRepository, com.lunarforge.market.service.NotificationService notifications) {
        this.notifications = notifications;
        this.ratingRepository = ratingRepository;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
    }

    // оставить отзыв. @Transactional: сохранение отзыва и обновление рейтинга продавца проходят вместе -
    // если что-то упадёт посередине, откатится всё, и рейтинг не разъедется с отзывами
    @Transactional
    public RatingResponse create(User rater, CreateRatingRequest req) {
        // проверяю оценку до похода в базу - нет смысла искать заказ, если оценка кривая
        validateScore(req.score());
        Order order = orderRepository.findById(req.orderId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Заказ не найден"));
        // отзыв может оставить только покупатель этого заказа, не продавец и не посторонний
        if (!order.getBuyer().getId().equals(rater.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Оставить отзыв может только покупатель");
        }
        if (order.getStatus() != Order.OrderStatus.COMPLETED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Отзыв можно оставить только после завершения заказа");
        }
        // один заказ = один отзыв. второй раз - 409, его надо именно изменить
        if (ratingRepository.findByOrderId(order.getId()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "Отзыв на этот заказ уже есть - его можно изменить");
        }

        Rating r = new Rating();
        r.setOrder(order);
        r.setRater(rater);
        r.setSeller(order.getSeller());
        r.setScore(req.score());
        r.setComment(req.comment());
        ratingRepository.save(r);

        // перечитываю продавца из базы свежим, а не беру order.getSeller() - так работаю с актуальными суммами
        User seller = userRepository.findById(order.getSeller().getId()).orElseThrow();
        // храним сумму и кол-во, средний = sum/count, не надо пересчитывать все отзывы
        seller.setRatingSum(seller.getRatingSum() + req.score());
        seller.setRatingCount(seller.getRatingCount() + 1);
        userRepository.save(seller);
        // уведомление продавцу: звёздочки строкой (★ повторить score раз) + текст отзыва
        notifications.notify(seller, "NEW_REVIEW", "⭐ Новый отзыв: " + "★".repeat(req.score()),
                r.getComment() != null && !r.getComment().isBlank() ? r.getComment() : "Без комментария", "USER", seller.getId());

        // false - в ответе автору не раскрываем данные о самом себе, это нужно только продавцу
        return toResponse(r, false);
    }

    // изменить отзыв. первое изменение тоже только через 24ч после создания - lastEditAt ставится при создании
    @Transactional
    public RatingResponse update(User rater, Long ratingId, UpdateRatingRequest req) {
        validateScore(req.score());
        Rating r = ratingRepository.findById(ratingId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Отзыв не найден"));
        if (!r.getRater().getId().equals(rater.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Можно менять только свой отзыв");
        }
        // lastEditAt + 24 часа ещё не наступило - рано, 429
        if (Instant.now().isBefore(r.getLastEditAt().plus(EDIT_COOLDOWN_HOURS, ChronoUnit.HOURS))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Отзыв можно менять не чаще раза в 24 часа");
        }
        // старую оценку запоминаю ДО изменения - нужна, чтобы поправить сумму у продавца
        int oldScore = r.getScore();
        r.setScore(req.score());
        r.setComment(req.comment());
        r.setLastEditAt(Instant.now());
        ratingRepository.save(r);

        User seller = userRepository.findById(r.getSeller().getId()).orElseThrow();
        // в сумме меняю только разницу (было 3, стало 5 -> +2). количество отзывов не меняется
        seller.setRatingSum(seller.getRatingSum() + (req.score() - oldScore));
        userRepository.save(seller);

        return toResponse(r, false);
    }

    // отзывы о продавце для его профиля. viewerId может быть null - смотрят без логина.
    // кто написал отзыв, видит только сам продавец - остальным автор скрыт
    public List<RatingResponse> forSeller(Long sellerId, Long viewerId) {
        boolean viewerIsSeller = viewerId != null && viewerId.equals(sellerId);
        return ratingRepository.findBySellerIdOrderByCreatedAtDesc(sellerId).stream()
                .map(r -> toResponse(r, viewerIsSeller)).toList();
    }

    // мой отзыв на заказ, если есть. чужой не отдаю - filter отсекает, и вернётся null
    public RatingResponse mineForOrder(User rater, Long orderId) {
        return ratingRepository.findByOrderId(orderId)
                .filter(r -> r.getRater().getId().equals(rater.getId()))
                .map(r -> toResponse(r, false))
                .orElse(null);
    }

    private void validateScore(int score) {
        if (score < 1 || score > 5) throw new ApiException(HttpStatus.BAD_REQUEST, "Оценка должна быть от 1 до 5");
    }

    // 16 -> 20. чтобы было понятно примерно сколько купили, но заказ не вычислить
    private long roundToTen(BigDecimal amount) {
        // делю на 10 с округлением до целого (16/10 = 1.6 -> 2) и умножаю обратно на 10.
        // HALF_UP - обычное школьное округление, 15 -> 20
        return amount.divide(BigDecimal.TEN, 0, RoundingMode.HALF_UP).longValue() * 10;
    }

    // собираю DTO. revealRater решает, отдавать ли id/ник автора или null
    private RatingResponse toResponse(Rating r, boolean revealRater) {
        return new RatingResponse(
                r.getId(), r.getOrder().getId(), r.getScore(), r.getComment(),
                roundToTen(r.getOrder().getAmount()), r.getCreatedAt(), r.getLastEditAt(),
                revealRater ? r.getRater().getId() : null,
                revealRater ? r.getRater().getNickname() : null,
                revealRater ? r.getRater().getUsername() : null);
    }
}
