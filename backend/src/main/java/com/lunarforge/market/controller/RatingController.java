package com.lunarforge.market.controller;

import com.lunarforge.market.dto.RatingDtos.CreateRatingRequest;
import com.lunarforge.market.dto.RatingDtos.RatingResponse;
import com.lunarforge.market.dto.RatingDtos.UpdateRatingRequest;
import com.lunarforge.market.security.AppUserDetails;
import com.lunarforge.market.service.RatingService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// отзывы о продавцах. оставить отзыв можно по своему заказу, потом его можно отредактировать.
// вся проверка (что заказ мой, что он завершён и т.д.) живёт в RatingService
@RestController
@RequestMapping("/api/ratings")
public class RatingController {
    private final RatingService ratingService;

    public RatingController(RatingService ratingService) {
        this.ratingService = ratingService;
    }

    // новый отзыв. в запросе id заказа, оценка и текст
    @PostMapping
    public RatingResponse create(@AuthenticationPrincipal AppUserDetails principal, @RequestBody CreateRatingRequest request) {
        return ratingService.create(principal.getUser(), request);
    }

    // редактирование уже оставленного отзыва по его id (свой отзыв, это проверяет сервис)
    @PutMapping("/{id}")
    public RatingResponse update(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long id,
                                  @RequestBody UpdateRatingRequest request) {
        return ratingService.update(principal.getUser(), id, request);
    }

    // principal может быть null если смотрит гость - тогда автора отзыва не показываем.
    // id текущего юзера передаю в сервис, чтобы он мог пометить какие отзывы мои
    @GetMapping("/seller/{sellerId}")
    public List<RatingResponse> forSeller(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long sellerId) {
        return ratingService.forSeller(sellerId, principal != null ? principal.getUser().getId() : null);
    }

    // 204 если отзыва ещё нет. раньше тут было пустое тело с 200 - ретрофит на нём падал в onFailure,
    // и приложение не знало что отзыв уже есть (пыталось создать второй вместо редактирования)
    @GetMapping("/order/{orderId}/mine")
    public org.springframework.http.ResponseEntity<RatingResponse> mineForOrder(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long orderId) {
        // null приходит если по этому заказу я ещё ничего не писал
        RatingResponse r = ratingService.mineForOrder(principal.getUser(), orderId);
        return r == null ? org.springframework.http.ResponseEntity.noContent().build() : org.springframework.http.ResponseEntity.ok(r);
    }
}
