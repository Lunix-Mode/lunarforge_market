package com.lunarforge.market.ui.admin;

import com.lunarforge.market.util.ApiErrors;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.lunarforge.market.R;
import com.lunarforge.market.api.ApiClient;
import com.lunarforge.market.model.Admin;
import com.lunarforge.market.util.BaseActivity;

import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

// экран админа "доход площадки": сколько всего заработали на комиссии 5% и разбивка по месяцам.
// данные берём с /api/admin/revenue, сервер пустит туда только ADMIN
public class AdminRevenueActivity extends BaseActivity {
    // запоминаю итог, чтобы подставить его в окно вывода как сумму по умолчанию
    private double totalRevenue = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_admin_revenue);
        findViewById(R.id.backButton).setOnClickListener(v -> finish());

        TextView totalText = findViewById(R.id.totalRevenueText);
        findViewById(R.id.withdrawRevenueButton).setOnClickListener(v -> showWithdrawDialog());
        LinearLayout monthsContainer = findViewById(R.id.monthsContainer);

        // запрос идёт в фоне, ответ Retrofit возвращает в главный поток - поэтому внутри можно трогать вьюшки.
        // totalText и monthsContainer - локальные переменные, лямбда/анонимный класс их видит, т.к. они effectively final
        ApiClient.getApiService(this).adminRevenue().enqueue(new Callback<Admin.Revenue>() {
            @Override
            public void onResponse(Call<Admin.Revenue> call, Response<Admin.Revenue> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                // 403 (не админ) или 500 - body будет null, дальше идти нельзя, иначе NullPointerException
                if (!response.isSuccessful() || response.body() == null) {
                    Toast.makeText(AdminRevenueActivity.this, "Нет доступа или ошибка сервера", Toast.LENGTH_SHORT).show();
                    return;
                }
                Admin.Revenue r = response.body();
                totalRevenue = r.totalCommissionRevenue;
                // Locale.getDefault() - чтобы разделитель дробной части был как привык юзер (у нас запятая)
                totalText.setText(String.format(Locale.getDefault(), "%.2f ₽", r.totalCommissionRevenue));
                // чищу старые строки, иначе при повторном ответе месяцы задублируются
                monthsContainer.removeAllViews();
                if (r.byMonth == null || r.byMonth.isEmpty()) {
                    // цвет берём из ресурсов через ContextCompat - так он сам поменяется в тёмной теме
                    monthsContainer.addView(row("Пока нет завершённых заказов", androidx.core.content.ContextCompat.getColor(AdminRevenueActivity.this, R.color.c_text_secondary)));
                    return;
                }
                for (Admin.MonthlyRevenue m : r.byMonth) {
                    monthsContainer.addView(row(String.format(Locale.getDefault(), "%s   —   %.2f ₽", m.month, m.commissionRevenue), androidx.core.content.ContextCompat.getColor(AdminRevenueActivity.this, R.color.c_text)));
                }
            }

            @Override
            public void onFailure(Call<Admin.Revenue> call, Throwable t) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                Toast.makeText(AdminRevenueActivity.this, ApiErrors.network(t), Toast.LENGTH_SHORT).show();
            }
        });
    }

    // строку списка создаю прямо из кода, отдельный layout под одну надпись не стал делать
    private TextView row(String text, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(15);
        // отступы в пикселях, не в dp - на разных экранах будет чуть по-разному, для админки не критично
        tv.setPadding(32, 28, 32, 28);
        tv.setBackgroundResource(R.drawable.rounded_bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 16;
        tv.setLayoutParams(lp);
        return tv;
    }

    // заглушка! выглядит как настоящий вывод, но деньги никуда не идут
    private void showWithdrawDialog() {
        // разметку диалога беру ту же, что и у обычного вывода из кошелька (dialog_withdraw)
        android.view.View view = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_withdraw, null);
        android.widget.EditText amount = view.findViewById(R.id.amountEditText);
        android.widget.EditText card = view.findViewById(R.id.cardNumberEditText);
        // Locale.US специально: нужна точка в числе, иначе parseDouble ниже не разберёт то, что сам же подставил
        if (totalRevenue > 0) amount.setText(String.format(Locale.US, "%.2f", totalRevenue));

        // делаю create(), а не сразу show() - ссылка на dialog нужна, чтобы закрыть его из кнопки
        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setView(view).create();
        view.findViewById(R.id.confirmWithdrawButton).setOnClickListener(v -> {
            double value;
            try {
                // юзер может ввести запятую - меняю на точку, иначе parseDouble упадёт
                value = Double.parseDouble(amount.getText().toString().trim().replace(',', '.'));
            } catch (NumberFormatException e) {
                value = 0;
            }
            // из номера карты оставляю только цифры (пробелы и тире выкидываю)
            String digits = card.getText().toString().replaceAll("\\D", "");
            if (value <= 0) {
                Toast.makeText(this, "Введите сумму", Toast.LENGTH_SHORT).show();
                return;
            }
            if (digits.length() < 12) {
                Toast.makeText(this, "Введите номер карты", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            // показываю только последние 4 цифры, как в банковских приложениях
            String last4 = digits.substring(digits.length() - 4);
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Заявка на вывод создана")
                    .setMessage(String.format(Locale.getDefault(),
                            "%.2f ₽ на карту •••• %s.\n\nДемо-режим: в бета-версии реальный вывод не выполняется.",
                            value, last4))
                    .setPositiveButton("OK", null)
                    .show();
        });
        dialog.show();
    }
}
