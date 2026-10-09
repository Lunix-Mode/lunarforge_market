package com.lunarforge.market.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.function.Function;

// токены живут 7 дней, внутри почта и id юзера
// сервис для работы с JWT: выдать токен при входе и потом проверять его на каждом запросе (это делает JwtAuthFilter).
// токен подписан секретным ключом, поэтому подделать его без ключа нельзя, а на сервере ничего хранить не надо
@Service
public class JwtService {
    private final SecretKey key;
    private final long expirationMs;

    // секрет и срок жизни берутся из application.properties, чтобы не хардкодить ключ в коде.
    // hmacShaKeyFor делает из строки ключ для HMAC-SHA; строка должна быть длинной (минимум 32 байта), иначе jjwt ругается
    public JwtService(@Value("${app.jwt.secret}") String secret,
                       @Value("${app.jwt.expiration-ms}") long expirationMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    // собираю токен: subject = почта, отдельно кладу userId, время выдачи и время истечения, и подписываю ключом.
    // compact() превращает всё это в строку вида xxx.yyy.zzz, которую приложение шлёт в заголовке Authorization
    public String generateToken(Long userId, String email) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);
        return Jwts.builder()
                .subject(email)
                .claim("userId", userId)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    // достаю почту из токена (она лежит в subject)
    public String extractEmail(String token) {
        return extractClaim(token, io.jsonwebtoken.Claims::getSubject);
    }

    // достаю id юзера из токена, чтобы лишний раз не искать юзера по почте
    public Long extractUserId(String token) {
        return extractClaim(token, claims -> {
            Object v = claims.get("userId");
            // jjwt может вернуть Integer вместо Long, поэтому через Number
            return v instanceof Number ? ((Number) v).longValue() : null;
        });
    }

    // токен годится, если почта в нём совпадает с юзером и срок не вышел.
    // подпись проверяется ещё раньше, в extractClaim - если подпись битая, там вылетит исключение
    public boolean isTokenValid(String token, String expectedEmail) {
        String email = extractEmail(token);
        return email.equals(expectedEmail) && !isExpired(token);
    }

    // истёк ли токен - сравниваю дату expiration с текущим временем
    private boolean isExpired(String token) {
        return extractClaim(token, io.jsonwebtoken.Claims::getExpiration).before(new Date());
    }

    // общий метод: проверяю подпись ключом, разбираю токен и достаю нужное поле через переданную функцию.
    // если токен подделан или просрочен - parseSignedClaims кинет исключение, и фильтр просто не пустит запрос
    private <T> T extractClaim(String token, Function<io.jsonwebtoken.Claims, T> resolver) {
        io.jsonwebtoken.Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return resolver.apply(claims);
    }
}
