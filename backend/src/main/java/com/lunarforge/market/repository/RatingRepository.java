package com.lunarforge.market.repository;

import com.lunarforge.market.entity.Rating;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

// работа с отзывами/оценками продавцов. SQL руками не пишу - spring data сам строит запрос по имени метода
public interface RatingRepository extends JpaRepository<Rating, Long> {
    // отзыв по конкретному заказу. на один заказ - один отзыв, этим проверяю что второй раз оценить нельзя
    Optional<Rating> findByOrderId(Long orderId);
    // все отзывы о продавце, новые сверху - для его публичного профиля
    List<Rating> findBySellerIdOrderByCreatedAtDesc(Long sellerId);
}
