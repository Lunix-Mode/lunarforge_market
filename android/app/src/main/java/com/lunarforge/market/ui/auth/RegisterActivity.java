package com.lunarforge.market.ui.auth;

import com.lunarforge.market.util.ApiErrors;
import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.lunarforge.market.R;
import com.lunarforge.market.api.ApiClient;
import com.lunarforge.market.model.AuthModels.AuthResponse;
import com.lunarforge.market.model.AuthModels.RegisterRequest;
import com.lunarforge.market.ui.home.MainActivity;
import com.lunarforge.market.util.SessionManager;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

// экран регистрации. сначала проверяю поля прямо в телефоне (чтобы не гонять запрос зря),
// потом шлю на сервер. если всё ок - сервер сразу отдаёт токен, сохраняю сессию и пускаю на главный экран
public class RegisterActivity extends com.lunarforge.market.util.BaseActivity {
    private EditText nicknameEditText, usernameEditText, emailEditText, passwordEditText;
    private TextView errorText;
    private SessionManager session;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        // SessionManager хранит токен и данные юзера в SharedPreferences
        session = new SessionManager(this);

        nicknameEditText = findViewById(R.id.nicknameEditText);
        usernameEditText = findViewById(R.id.usernameEditText);
        emailEditText = findViewById(R.id.emailEditText);
        passwordEditText = findViewById(R.id.passwordEditText);
        errorText = findViewById(R.id.errorText);

        findViewById(R.id.registerButton).setOnClickListener(v -> doRegister());
        // на регистрацию попадают с экрана входа, поэтому просто закрываю себя и возвращаюсь туда
        findViewById(R.id.goToLoginText).setOnClickListener(v -> finish());
    }

    // собираю поля, проверяю и отправляю запрос регистрации
    private void doRegister() {
        String nickname = nicknameEditText.getText().toString().trim();
        // если человек ввёл @ник - убираю @ в начале, на сервере ник хранится без него
        String username = usernameEditText.getText().toString().trim().replaceFirst("^@", "");
        String email = emailEditText.getText().toString().trim();
        // пароль не trim-аю: пробелы могут быть частью пароля
        String password = passwordEditText.getText().toString();

        if (nickname.isEmpty() || username.isEmpty() || email.isEmpty() || password.isEmpty()) {
            showError("Заполните все поля");
            return;
        }
        // та же регулярка, что и на сервере в RegisterRequest: латиница/цифры/_, и не одни цифры.
        // сервер всё равно проверит сам, тут это только чтобы сразу показать понятную ошибку
        if (!username.matches("^(?=.*[a-zA-Z_])[a-zA-Z0-9_]+$")) {
            showError("Юзернейм: латиница, цифры и _ (не только цифры)");
            return;
        }
        if (password.length() < 6) {
            showError("Пароль должен быть не короче 6 символов");
            return;
        }

        ApiClient.getApiService(this).register(new RegisterRequest(email, nickname, username, password))
                .enqueue(new Callback<AuthResponse>() {
                    @Override
                    public void onResponse(Call<AuthResponse> call, Response<AuthResponse> response) {
                        if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                        if (response.isSuccessful() && response.body() != null) {
                            // сразу логиню: сохраняю токен, чтобы дальше все запросы шли от этого юзера.
                            // finish() - чтобы кнопкой назад не вернуться на регистрацию
                            AuthResponse body = response.body();
                            session.saveSession(body.token, body.userId, body.nickname);
                            startActivity(new Intent(RegisterActivity.this, MainActivity.class));
                            finish();
                        } else {
                            // например почта или ник уже заняты - текст ошибки достаю из ответа сервера
                            showError(ApiErrors.message(response, "Не удалось зарегистрироваться"));
                        }
                    }

                    @Override
                    public void onFailure(Call<AuthResponse> call, Throwable t) {
                        if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                        showError(ApiErrors.network(t));
                    }
                });
    }

    // ошибку показываю и в текстовом поле под формой, и тостом
    private void showError(String message) {
        errorText.setText(message);
        errorText.setVisibility(TextView.VISIBLE);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
