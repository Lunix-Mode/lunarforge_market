package com.lunarforge.market.model;

// модели для регистрации и входа, их retrofit превращает в json и обратно (gson по именам полей,
// поэтому названия полей должны совпадать с бэкендом)
public class AuthModels {
    // что отправляю на /auth/register
    public static class RegisterRequest {
        public String email;
        // nickname - отображаемое имя, username - уникальный логин (по нему например делают переводы)
        public String nickname;
        public String username;
        public String password;

        public RegisterRequest(String email, String nickname, String username, String password) {
            this.email = email;
            this.nickname = nickname;
            this.username = username;
            this.password = password;
        }
    }

    // вход по email и паролю
    public static class LoginRequest {
        public String email;
        public String password;

        public LoginRequest(String email, String password) {
            this.email = email;
            this.password = password;
        }
    }

    // ответ сервера после входа/регистрации
    public static class AuthResponse {
        // JWT токен, сохраняю его и потом добавляю в заголовок Authorization ко всем запросам
        public String token;
        // id текущего юзера, нужен чтобы например понимать какие сообщения в чате мои
        public long userId;
        public String nickname;
        public String email;
    }
}
