package com.lunarforge.market.controller;

import com.lunarforge.market.dto.AuthDtos.AuthResponse;
import com.lunarforge.market.dto.AuthDtos.LoginRequest;
import com.lunarforge.market.dto.AuthDtos.RegisterRequest;
import com.lunarforge.market.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

// контроллер для входа и регистрации. эти два адреса открыты без токена (в SecurityConfig /api/auth/** разрешён всем),
// иначе новый юзер вообще не смог бы получить свой первый токен.
// сам контроллер ничего не решает, всю логику отдаю в AuthService, тут только приём запроса и отдача ответа
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    // сервис прилетает через конструктор (Spring сам его подставляет), поле final чтобы никто случайно не перезаписал
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    // регистрация: POST /api/auth/register.
    // @Valid включает проверки из RegisterRequest (пустые поля, формат почты, длина пароля),
    // если что-то не так - Spring сам вернёт 400 ещё до того, как я дойду до сервиса.
    // в ответ сразу отдаю токен, чтобы после регистрации не надо было отдельно логиниться
    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    // вход: POST /api/auth/login. тоже возвращает токен + id/ник/почту, приложение сохраняет это в SessionManager
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }
}
