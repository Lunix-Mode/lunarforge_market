package com.lunarforge.market.security;

import com.lunarforge.market.entity.User;
import com.lunarforge.market.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

// через этот сервис находится юзер: при входе (spring security) и на каждом запросе в JwtAuthFilter по email из токена. "username" у нас это email,
// так что метод хоть и называется loadUserByUsername, ищет по почте
@Service
public class AppUserDetailsService implements UserDetailsService {
    private final UserRepository userRepository;

    public AppUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    // не нашли - кидаю стандартное исключение, spring security превратит его в ошибку входа
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
        // оборачиваю свою сущность User в UserDetails, который понимает spring security
        return new AppUserDetails(user);
    }
}
