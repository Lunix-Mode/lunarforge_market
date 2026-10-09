package com.lunarforge.market.config;

import com.lunarforge.market.security.JwtAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

// кто куда может ходить. сессий нет, только JWT в заголовке Authorization
// тут настраиваю Spring Security: какие адреса открыты, какие по роли, и что вместо сессий проверяем токен.
// JwtAuthFilter достаёт юзера из токена и кладёт в контекст, дальше контроллеры получают его через @AuthenticationPrincipal
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    // фильтр приходит через конструктор (внедрение зависимостей), сам я его не создаю
    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    // своего AuthenticationProvider нет специально: Spring Security сам соберёт DaoAuthenticationProvider
    // из единственного UserDetailsService (AppUserDetailsService) и этого PasswordEncoder.
    // свой бин дублировал это и давал WARN "Global AuthenticationManager configured with an AuthenticationProvider bean"
    // BCrypt - пароли храню только хэшем с солью, даже я из базы их не прочитаю
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }


    // AuthenticationManager нужен в AuthService при логине (проверить email+пароль), беру готовый у спринга
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    // вход/регистрация/витрина/профили/файлы открыты всем, /api/admin только админу, остальное
    // после логина
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // csrf выключаю: он защищает cookie-сессии в браузере, а у нас токен в заголовке и кук нет
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // STATELESS - сервер ничего не помнит между запросами, каждый запрос сам несёт токен
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // по умолчанию спринг отдаёт 403, а приложению нужен 401 чтобы разлогинить юзера
                .exceptionHandling(e -> e.authenticationEntryPoint((request, response, ex) ->
                        response.sendError(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED, "Требуется вход")))
                // правила проверяются сверху вниз, срабатывает первое подходящее - поэтому конкретные адреса выше, anyRequest в самом конце.
                // hasRole("ADMIN") на деле ищет у юзера ROLE_ADMIN - префикс ROLE_ спринг добавляет сам
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/games/**").permitAll()
                        // файлы чата/аватарки - по прямой ссылке, иначе Glide и плеер на телефоне не смогут их загрузить без заголовка
                        .requestMatchers("/files/**").permitAll()
                        // смотреть объявления, категории, отзывы и профили можно без входа. создавать/менять (POST/PUT/PATCH) - только после логина
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/listings/**").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/categories/**").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/ratings/seller/**").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/users/*/public-profile").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/staff/**").hasAnyRole("ADMIN", "MODERATOR")
                        // новые категории добавляет только админ (GET категорий открыт выше)
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/categories/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                // мой JWT-фильтр ставлю перед стандартным фильтром логина, чтобы юзер был уже известен к моменту проверки прав
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // пока открыто для всех, на бету норм. потом закрыть
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        // CORS нужен только браузеру (например если открыть API со страницы). андроид-приложение CORS вообще не проверяет
        CorsConfiguration configuration = new CorsConfiguration();
        // allowedOriginPatterns, а не allowedOrigins: со звёздочкой и allowCredentials=true обычный allowedOrigins спринг не пропустит
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
