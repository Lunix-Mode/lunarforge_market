package com.lunarforge.market.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// каждый запрос: достаём токен, проверяем, кладём юзера в контекст.
// нет токена или кривой - идём дальше гостем, закрытые урлы сами ответят 401
// OncePerRequestFilter - чтобы фильтр гарантированно отработал один раз на запрос.
// в цепочку его ставит SecurityConfig
@Component
public class JwtAuthFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final AppUserDetailsService userDetailsService;

    public JwtAuthFilter(JwtService jwtService, AppUserDetailsService userDetailsService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        // токен приходит в заголовке вида "Authorization: Bearer <jwt>"
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        // отрезаю "Bearer " (7 символов), остаётся сам токен
        String token = authHeader.substring(7);
        try {
            String email = jwtService.extractEmail(token);
            // если в контексте уже кто-то есть - второй раз не авторизую
            if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                // юзера беру свежего из базы, а не из токена - так видно актуальную роль и блокировку
                UserDetails userDetails = userDetailsService.loadUserByUsername(email);
                // сверяем ещё и id, а не только почту. был баг: после пересоздания базы старый
                // токен
                // продолжал работать для нового акка с той же почтой, а id в приложении был уже
                // чужой
                Long tokenUserId = jwtService.extractUserId(token);
                Long realUserId = ((AppUserDetails) userDetails).getUser().getId();
                // isTokenValid сверяет почту и что срок не истёк (подпись проверилась ещё при разборе токена)
                if (jwtService.isTokenValid(token, userDetails.getUsername()) && realUserId.equals(tokenUserId)) {
                    // пароль null - он уже не нужен, юзер доказал кто он токеном
                    UsernamePasswordAuthenticationToken authToken =
                            new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    // после этой строки запрос считается авторизованным, и @AuthenticationPrincipal
                    // в контроллерах вернёт этого юзера
                    SecurityContextHolder.getContext().setAuthentication(authToken);

                    // заблокированному можно только: свой профиль (увидеть причину), заявки (подать на разблокировку),
                    // уведомления и чат. в чате пускаем только в чат его апелляции - это проверяет ChatService
                    com.lunarforge.market.entity.User u = ((AppUserDetails) userDetails).getUser();
                    if (u.isBlocked() && !allowedWhileBlocked(request)) {
                        // сразу отвечаю 403 и дальше по цепочке не пускаю (return без doFilter).
                        // флаг blocked:true приложение ловит и показывает экран блокировки
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        response.setContentType("application/json;charset=UTF-8");
                        response.getWriter().write("{\"status\":403,\"error\":\"Forbidden\",\"blocked\":true,"
                                + "\"message\":\"Аккаунт заблокирован. Можно подать заявку на разблокировку\"}");
                        return;
                    }
                }
            }
        // любая проблема с токеном = просто не авторизован
        // (истёк, подделан, юзера удалили) - не роняю запрос, пусть идёт гостем
        } catch (Exception ex) {
        }

        filterChain.doFilter(request, response);
    }

    // белый список урлов для заблокированного юзера
    private static boolean allowedWhileBlocked(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        // профиль только на чтение, менять ник/аватар заблокированному нельзя
        if (path.equals("/api/users/me") && "GET".equals(method)) return true;
        if (path.startsWith("/api/tickets")) return true;
        if (path.startsWith("/api/notifications")) return true;
        // только сообщения конкретного чата, в какой именно чат можно - решает ChatService
        if (path.startsWith("/api/chat/threads/") && path.endsWith("/messages")) return true;
        // файлы (картинки, вложения), иначе в чате апелляции не откроются фото
        return path.startsWith("/files/");
    }
}
