package com.lunarforge.market.controller;

import com.lunarforge.market.dto.ListingDtos.CreateListingRequest;
import com.lunarforge.market.dto.ListingDtos.ListingResponse;
import com.lunarforge.market.security.AppUserDetails;
import com.lunarforge.market.service.ListingService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// rest-контроллер объявлений (товаров). сам ничего не считает, всё отдаёт в ListingService.
// principal - это залогиненный юзер, его подставляет spring security по токену
@RestController
@RequestMapping("/api/listings")
public class ListingController {
    private final ListingService listingService;

    public ListingController(ListingService listingService) {
        this.listingService = listingService;
    }

    // создать объявление. @Valid проверяет поля запроса (название, цена и т.д.) по аннотациям в dto,
    // если что-то не так - 400 через GlobalExceptionHandler. продавцом всегда ставлю текущего юзера,
    // чтобы нельзя было выложить товар от чужого имени
    @PostMapping
    public ListingResponse create(@AuthenticationPrincipal AppUserDetails principal,
                                   @Valid @RequestBody CreateListingRequest request) {
        return listingService.create(principal.getUser(), request);
    }

    // одно объявление по id, если нет - сервис кинет 404
    @GetMapping("/{id}")
    public ListingResponse get(@PathVariable Long id) {
        return listingService.getOrThrow(id);
    }

    // все объявления игры (старый список без страниц)
    @GetMapping("/game/{gameId}")
    public List<ListingResponse> byGame(@PathVariable Long gameId) {
        return listingService.byGame(gameId);
    }

    // объявления игры в конкретной подкатегории
    @GetMapping("/game/{gameId}/category/{categoryId}")
    public List<ListingResponse> byGameAndCategory(@PathVariable Long gameId, @PathVariable(required = false) Long categoryId) {
        return listingService.byGameAndCategory(gameId, categoryId);
    }

    // "Другое" - товары игры без подкатегории, поэтому categoryId = null
    @GetMapping("/game/{gameId}/other")
    public List<ListingResponse> byGameOther(@PathVariable Long gameId) {
        return listingService.byGameAndCategory(gameId, null);
    }

    // мои объявления (для профиля продавца), тут и скрытые тоже видно
    @GetMapping("/mine")
    public List<ListingResponse> mine(@AuthenticationPrincipal AppUserDetails principal) {
        return listingService.bySeller(principal.getUser().getId());
    }

    // публичный список товаров продавца - для чужого профиля
    @GetMapping("/seller/{sellerId}")
    public List<ListingResponse> bySeller(@PathVariable Long sellerId) {
        return listingService.byPublicSeller(sellerId);
    }

    // витрина порциями: игра / подкатегория / "Другое" / поиск + тип + сортировка (new, cheap, expensive)
    @GetMapping("/browse")
    public com.lunarforge.market.dto.PageResponse<ListingResponse> browse(
            @RequestParam(required = false) Long gameId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "false") boolean other,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "new") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        return listingService.browse(gameId, categoryId, other, q, type, sort, page, size);
    }

    // простой поиск по тексту, без страниц
    @GetMapping("/search")
    public List<ListingResponse> search(@RequestParam String q) {
        return listingService.search(q);
    }

    // продавец включает/выключает объявление (снять с продажи и вернуть).
    // что объявление именно его - проверяется в сервисе
    @PatchMapping("/{id}/active")
    public void setActive(@AuthenticationPrincipal AppUserDetails principal,
                           @PathVariable Long id, @RequestParam boolean active) {
        listingService.setActive(principal.getUser(), id, active);
    }
}
