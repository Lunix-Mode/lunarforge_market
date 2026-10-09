package com.lunarforge.market.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// все объекты для входа и регистрации собраны в одном файле, чтобы не плодить мелкие классы.
// record - это короткий неизменяемый класс: поля, конструктор, геттеры и equals делаются сами.
// аннотации валидации срабатывают, когда в контроллере стоит @Valid - если что-то не так,
// спринг сам вернёт 400 ещё до того, как запрос дойдёт до сервиса
public class AuthDtos {
    // что присылает приложение при регистрации
    // email - логин, nickname - отображаемое имя (можно любое),
    // username - уникальный @ник: только латиница/цифры/_ и хотя бы одна буква или _,
    // чтобы ник не был похож на число (id). пароль минимум 6 символов
    public record RegisterRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 3, max = 32) String nickname,
            @NotBlank @Size(min = 3, max = 32) @jakarta.validation.constraints.Pattern(
                    regexp = "^(?=.*[a-zA-Z_])[a-zA-Z0-9_]+$", message = "латиница, цифры и _, не только цифры") String username,
            @NotBlank @Size(min = 6, max = 100) String password
    ) {}

    // вход по почте и паролю
    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password
    ) {}

    // ответ после успешного входа/регистрации. token - jwt, приложение сохраняет его
    // и дальше шлёт в заголовке Authorization в каждом запросе
    public record AuthResponse(
            String token,
            Long userId,
            String nickname,
            String email
    ) {}
}
