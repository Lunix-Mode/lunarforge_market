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
import com.lunarforge.market.model.AuthModels.LoginRequest;
import com.lunarforge.market.ui.home.MainActivity;
import com.lunarforge.market.util.SessionManager;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

// экран входа. если токен уже сохранён - сразу пускаю на главный экран, иначе показываю форму почта + пароль.
// наследуюсь от своего BaseActivity, а не напрямую от AppCompatActivity, там общая для всех экранов настройка
public class LoginActivity extends com.lunarforge.market.util.BaseActivity {
    private EditText emailEditText, passwordEditText;
    private TextView errorText;
    private SessionManager session;

    // setContentView вызываю до проверки сессии, а потом если уже залогинен - сразу ухожу на главный.
    // return нужен, чтобы дальше не искать вьюшки и не вешать обработчики на экран, который всё равно закрывается
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        session = new SessionManager(this);
        if (session.isLoggedIn()) {
            goToMain();
            return;
        }

        emailEditText = findViewById(R.id.emailEditText);
        passwordEditText = findViewById(R.id.passwordEditText);
        errorText = findViewById(R.id.errorText);

        // кнопка "Войти" и ссылка на регистрацию
        findViewById(R.id.loginButton).setOnClickListener(v -> doLogin());
        findViewById(R.id.goToRegisterText).setOnClickListener(v ->
                startActivity(new Intent(this, RegisterActivity.class)));
    }

    // вход: проверяю, что поля не пустые, и шлю запрос на /api/auth/login.
    // enqueue - запрос идёт в фоне, а ответ Retrofit отдаёт уже в главном потоке, поэтому тут можно трогать UI
    private void doLogin() {
        String email = emailEditText.getText().toString().trim();
        String password = passwordEditText.getText().toString();

        if (email.isEmpty() || password.isEmpty()) {
            showError("Заполните все поля");
            return;
        }

        ApiClient.getApiService(this).login(new LoginRequest(email, password))
                .enqueue(new Callback<AuthResponse>() {
                    @Override
                    public void onResponse(Call<AuthResponse> call, Response<AuthResponse> response) {
                        if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                        if (response.isSuccessful() && response.body() != null) {
                            AuthResponse body = response.body();
                            // сохраняю токен, id и ник - дальше ApiClient подставляет токен в каждый запрос
                            session.saveSession(body.token, body.userId, body.nickname);
                            goToMain();
                        } else {
                            // ApiErrors достаёт текст ошибки из ответа сервера, если его нет - показываю свой текст по умолчанию
                            showError(ApiErrors.message(response, "Неверный email или пароль"));
                        }
                    }

                    @Override
                    public void onFailure(Call<AuthResponse> call, Throwable t) {
                        if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                        // сюда попадаем, если сервер вообще не ответил (нет интернета, сервер выключен)
                        showError(ApiErrors.network(t));
                    }
                });
    }

    // ошибку показываю и текстом под полями, и тостом
    private void showError(String message) {
        errorText.setText(message);
        errorText.setVisibility(TextView.VISIBLE);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    // finish() закрывает экран входа, чтобы по кнопке "назад" с главного экрана не вернуться обратно на логин
    private void goToMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
