package com.lunarforge.market.security;

import com.lunarforge.market.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

// обёртка над моей сущностью User, чтобы spring security понимал её.
// именно этот объект потом приходит в контроллеры через @AuthenticationPrincipal,
// и оттуда я достаю настоящего юзера через getUser()
public class AppUserDetails implements UserDetails {
    private final User user;

    public AppUserDetails(User user) {
        this.user = user;
    }

    public User getUser() {
        return user;
    }

    // роль юзера превращаю в "ROLE_USER" / "ROLE_ADMIN" и т.д.
    // префикс ROLE_ обязателен: hasRole("ADMIN") в SecurityConfig ищет именно "ROLE_ADMIN"
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    }

    // хэш пароля (bcrypt), сам пароль нигде не хранится
    @Override
    public String getPassword() {
        return user.getPasswordHash();
    }

    // для spring security "username" - это то, по чему логинимся. у меня это почта,
    // а не @username из профиля
    @Override
    public String getUsername() {
        return user.getEmail();
    }

    // эти флаги всегда true: истечения аккаунтов у меня нет, а блокировку
    // я проверяю сам в JwtAuthFilter (заблокированному часть запросов всё-таки разрешена,
    // а isAccountNonLocked = false закрыл бы ему вообще всё)
    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() { return true; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }

    @Override
    public boolean isEnabled() { return true; }
}
