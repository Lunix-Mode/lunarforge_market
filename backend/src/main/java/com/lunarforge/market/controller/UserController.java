package com.lunarforge.market.controller;

import com.lunarforge.market.dto.UserDtos.*;
import com.lunarforge.market.entity.User;
import com.lunarforge.market.exception.ApiException;
import com.lunarforge.market.repository.UserRepository;
import com.lunarforge.market.security.AppUserDetails;
import com.lunarforge.market.service.UserService;
import com.lunarforge.market.service.WalletService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

// всё про пользователя: свой профиль, аватар, ник, статистика, чужие публичные профили,
// пополнение/вывод и история операций кошелька.
// текущего юзера беру через @AuthenticationPrincipal - его туда кладёт JwtAuthFilter из токена,
// поэтому id из запроса для "me" не принимаю, иначе можно было бы подставить чужой
@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserService userService;
    private final WalletService walletService;
    private final UserRepository userRepository;

    public UserController(UserService userService, WalletService walletService, UserRepository userRepository) {
        this.userService = userService;
        this.walletService = walletService;
        this.userRepository = userRepository;
    }

    // мой профиль целиком (баланс, роль, блокировка и т.д.). это же приложение дергает при старте
    @GetMapping("/me")
    public UserProfile me(@AuthenticationPrincipal AppUserDetails principal) {
        return userService.toProfile(principal.getUser());
    }

    // смена аватарки. сама картинка уже загружена отдельно, сюда приходит только её ссылка
    // в теле {"avatarUrl": "..."}, отдельный dto под одно поле не стал делать - взял Map
    @PatchMapping("/me/avatar")
    public UserProfile changeAvatar(@AuthenticationPrincipal AppUserDetails principal,
                                    @RequestBody java.util.Map<String, String> body) {
        return userService.changeAvatar(principal.getUser().getId(), body.get("avatarUrl"));
    }

    // ник может повторяться, а @username менять нельзя - он для переводов
    @PatchMapping("/me/nickname")
    public UserProfile changeNickname(@AuthenticationPrincipal AppUserDetails principal,
                                      @RequestBody java.util.Map<String, String> body) {
        return userService.changeNickname(principal.getUser().getId(), body.get("nickname"));
    }

    // мои цифры для экрана профиля: сколько потратил/заработал, сколько завершённых покупок и продаж
    @GetMapping("/me/stats")
    public UserStats stats(@AuthenticationPrincipal AppUserDetails principal) {
        return userService.stats(principal.getUser().getId());
    }

    // поиск человека по @username (например перед переводом денег).
    // нормализую ввод: обрезаю пробелы, в нижний регистр и убираю @ в начале,
    // чтобы "@Vasya " и "vasya" находили одного и того же
    @GetMapping("/by-username/{username}")
    public PublicProfile byUsername(@PathVariable String username) {
        User user = userRepository.findByUsername(username.trim().toLowerCase().replaceFirst("^@", ""))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Пользователь не найден"));
        return userService.toPublicProfile(user);
    }

    // публичный профиль по id - отдаю урезанную версию (без баланса и почты)
    @GetMapping("/{id}/public-profile")
    public PublicProfile publicProfile(@PathVariable Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Пользователь не найден"));
        return userService.toPublicProfile(user);
    }

    // заглушка, реальной оплаты нет
    // номер карты сервис только маскирует и пишет в историю, сумма зачисляется на баланс сразу
    @PostMapping("/me/topup")
    public UserProfile topUp(@AuthenticationPrincipal AppUserDetails principal, @RequestBody TopUpRequest request) {
        return walletService.topUp(principal.getUser().getId(), request.amount(), request.cardNumber());
    }

    // тоже заглушка
    // проверка что хватает денег - внутри WalletService
    @PostMapping("/me/withdraw")
    public UserProfile withdraw(@AuthenticationPrincipal AppUserDetails principal, @RequestBody WithdrawRequest request) {
        return walletService.withdraw(principal.getUser().getId(), request.amount(), request.cardNumber());
    }

    // одна операция кошелька подробно. id юзера передаю в сервис,
    // чтобы нельзя было открыть чужую транзакцию, просто перебирая id
    @GetMapping("/me/transactions/{id}")
    public com.lunarforge.market.dto.WalletDtos.TransactionResponse transaction(
            @AuthenticationPrincipal AppUserDetails principal, @PathVariable Long id) {
        return walletService.detail(principal.getUser().getId(), id);
    }

    // история операций. если page передали - отдаю постранично (по size штук, по умолчанию 30),
    // это для бесконечной прокрутки в приложении. без page - старый вариант, весь список сразу
    @GetMapping("/me/transactions")
    public java.util.List<com.lunarforge.market.dto.WalletDtos.TransactionResponse> transactions(
            @AuthenticationPrincipal AppUserDetails principal,
            @RequestParam(required = false) Integer page,
            @RequestParam(defaultValue = "30") int size) {
        if (page != null) return walletService.history(principal.getUser().getId(), page, size);
        return walletService.history(principal.getUser().getId());
    }
}
