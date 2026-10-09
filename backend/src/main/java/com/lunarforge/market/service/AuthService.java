package com.lunarforge.market.service;

import com.lunarforge.market.dto.AuthDtos.AuthResponse;
import com.lunarforge.market.dto.AuthDtos.LoginRequest;
import com.lunarforge.market.dto.AuthDtos.RegisterRequest;
import com.lunarforge.market.entity.User;
import com.lunarforge.market.exception.ApiException;
import com.lunarforge.market.repository.UserRepository;
import com.lunarforge.market.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

// логика регистрации и входа. тут проверяю занятость почты/юзернейма, хэширую пароль и выдаю JWT
@Service
public class AuthService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    // всё нужное получаю через конструктор, Spring сам подставит бины
    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                        JwtService jwtService, AuthenticationManager authenticationManager) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authenticationManager = authenticationManager;
    }

    // почту сразу в нижний регистр, ДО проверки на занятость (иначе Ivan@ и ivan@ падали с 500)
    // шаги: нормализую почту/ник -> проверяю что почта свободна -> проверяю ник -> проверяю юзернейм ->
    // сохраняю юзера с хэшем пароля -> сразу выдаю токен
    public AuthResponse register(RegisterRequest req) {
        String email = req.email().trim().toLowerCase();
        String nickname = req.nickname().trim();
        if (userRepository.existsByEmail(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "Email уже используется");
        }
        // проверка ника общая с UserService (длина 2..32 и нельзя назваться LunarBot), чтобы правила были одинаковые и при смене ника
        UserService.validateNickname(nickname);
        // юзернейм (@имя) тоже в нижний регистр, иначе @Ivan и @ivan считались бы разными людьми
        String username = req.username().trim().toLowerCase();
        if (userRepository.existsByUsername(username)) {
            throw new ApiException(HttpStatus.CONFLICT, "Юзернейм @" + username + " уже занят");
        }

        User user = new User();
        user.setEmail(email);
        user.setNickname(nickname);
        user.setUsername(username);
        // пароль в открытом виде никогда не храню, только хэш через PasswordEncoder (в SecurityConfig это BCrypt).
        // даже если базу утащат, пароли так просто не достать
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        // после save у юзера появляется id из базы, он нужен для токена ниже
        userRepository.save(user);

        String token = jwtService.generateToken(user.getId(), user.getEmail());
        return new AuthResponse(token, user.getId(), user.getNickname(), user.getEmail());
    }

    // вход: проверку пароля делает сам Spring Security через AuthenticationManager -
    // он найдёт юзера по почте и сравнит хэш пароля
    public AuthResponse login(LoginRequest req) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.email().trim().toLowerCase(), req.password()));
        // если пароль неверный - отдаю 401 с одинаковым текстом и для "нет такой почты", и для "неверный пароль",
        // чтобы по ответу нельзя было узнать, зарегистрирована ли почта
        } catch (BadCredentialsException ex) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Неверный email или пароль");
        }

        // раз authenticate прошёл, юзер точно есть, но достаю его из базы, чтобы взять id и ник для ответа
        User user = userRepository.findByEmail(req.email().trim().toLowerCase())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Неверный email или пароль"));

        String token = jwtService.generateToken(user.getId(), user.getEmail());
        return new AuthResponse(token, user.getId(), user.getNickname(), user.getEmail());
    }
}
