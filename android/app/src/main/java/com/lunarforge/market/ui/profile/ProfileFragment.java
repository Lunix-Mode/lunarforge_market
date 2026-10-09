package com.lunarforge.market.ui.profile;

import com.lunarforge.market.util.ApiErrors;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.lunarforge.market.R;
import com.lunarforge.market.api.ApiClient;
import com.lunarforge.market.model.TopUpRequest;
import com.lunarforge.market.model.Transfer;
import com.lunarforge.market.model.User;
import com.lunarforge.market.model.UserStats;
import com.lunarforge.market.model.WithdrawRequest;
import com.lunarforge.market.ui.auth.LoginActivity;
import com.lunarforge.market.ui.listing.MyListingsActivity;
import com.lunarforge.market.ui.order.OrderListActivity;
import com.lunarforge.market.ui.wallet.TransactionHistoryActivity;
import com.lunarforge.market.util.SessionManager;

import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

// вкладка "Профиль" на главном экране. показывает ник, аватар, почту, баланс (доступный и замороженный),
// рейтинг и статистику, плюс кнопки пополнить/вывести/перевести и переходы в мои лоты, покупки, продажи, историю.
// это фрагмент, а не активити, поэтому перед каждым обращением к UI после запроса проверяю isAdded()
public class ProfileFragment extends Fragment {
    private TextView nicknameText, usernameText, ratingText, emailText, balanceText, frozenBalanceText;
    private TextView statSpentText, statEarnedText, statDealsText;
    private SessionManager session;
    private android.widget.ImageView avatarImage;
    private String myNickname; // для буквы на аватаре без фото

    // лаунчеры обязательно создаются при создании фрагмента (тут прямо в полях), позже регистрировать нельзя - упадёт.
    // цепочка такая: выбрал фото в галерее -> экран обрезки AvatarCropActivity -> загрузка обрезанного файла
    private final androidx.activity.result.ActivityResultLauncher<String> pickAvatarLauncher =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.GetContent(),
                    uri -> {
                        if (uri == null) return;
                        // сначала экран обрезки, там юзер выбирает что попадёт в круг
                        android.content.Intent crop = new android.content.Intent(requireContext(), AvatarCropActivity.class);
                        crop.setData(uri);
                        this.cropAvatarLauncher.launch(crop); // this. обязательно: поле объявлено ниже, без this будет illegal forward reference
                    });

    // результат экрана обрезки: если юзер нажал "готово", там путь к уже обрезанному файлу, его и загружаю
    private final androidx.activity.result.ActivityResultLauncher<android.content.Intent> cropAvatarLauncher =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() != android.app.Activity.RESULT_OK || result.getData() == null) return;
                        String path = result.getData().getStringExtra(AvatarCropActivity.EXTRA_RESULT_PATH);
                        if (path != null) uploadAvatar(new java.io.File(path));
                    });

    // создаю разметку, нахожу вьюшки и вешаю обработчики на все кнопки и строки меню
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_profile, container, false);

        session = new SessionManager(requireContext());

        nicknameText = view.findViewById(R.id.nicknameText);
        // тап по нику - диалог смены ника, тап по аватару - выбор нового фото
        nicknameText.setOnClickListener(v -> showNicknameDialog());
        avatarImage = view.findViewById(R.id.avatarImage);
        avatarImage.setOnClickListener(v -> pickAvatarLauncher.launch("image/*"));
        usernameText = view.findViewById(R.id.usernameText);
        ratingText = view.findViewById(R.id.ratingText);
        emailText = view.findViewById(R.id.emailText);
        balanceText = view.findViewById(R.id.balanceText);
        frozenBalanceText = view.findViewById(R.id.frozenBalanceText);
        statSpentText = view.findViewById(R.id.statSpentText);
        statEarnedText = view.findViewById(R.id.statEarnedText);
        statDealsText = view.findViewById(R.id.statDealsText);

        view.findViewById(R.id.topUpButton).setOnClickListener(v -> showTopUpDialog());
        view.findViewById(R.id.withdrawButton).setOnClickListener(v -> showWithdrawDialog());
        view.findViewById(R.id.transferButton).setOnClickListener(v -> showTransferDialog());

        view.findViewById(R.id.myListingsRow).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), MyListingsActivity.class)));
        view.findViewById(R.id.myPurchasesRow).setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), OrderListActivity.class);
            intent.putExtra(OrderListActivity.EXTRA_MODE, OrderListActivity.MODE_PURCHASES);
            startActivity(intent);
        });
        view.findViewById(R.id.mySalesRow).setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), OrderListActivity.class);
            intent.putExtra(OrderListActivity.EXTRA_MODE, OrderListActivity.MODE_SALES);
            startActivity(intent);
        });
        view.findViewById(R.id.transactionsRow).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), TransactionHistoryActivity.class)));
        // выход: стираю токен и открываю экран входа, а главную активити закрываю,
        // чтобы кнопкой "назад" нельзя было вернуться в аккаунт
        view.findViewById(R.id.logoutRow).setOnClickListener(v -> {
            session.clear();
            startActivity(new Intent(requireContext(), LoginActivity.class));
            requireActivity().finish();
        });

        loadProfile();
        loadStats();
        return view;
    }

    // при возврате на вкладку (например после пополнения или покупки) обновляю баланс и цифры заново
    @Override
    public void onResume() {
        super.onResume();
        loadProfile();
        loadStats();
    }

    // статистика: потрачено, заработано и сколько всего сделок (покупки + продажи)
    private void loadStats() {
        ApiClient.getApiService(requireContext()).myStats().enqueue(new Callback<UserStats>() {
            @Override
            public void onResponse(Call<UserStats> call, Response<UserStats> response) {
                // юзер мог уйти с вкладки пока шёл запрос, тогда requireContext() крашнет
                if (!isAdded()) return;
                if (!isAdded() || !response.isSuccessful() || response.body() == null) return;
                UserStats stats = response.body();
                statSpentText.setText(formatCompact(stats.totalSpent));
                statEarnedText.setText(formatCompact(stats.totalEarned));
                statDealsText.setText(String.valueOf(stats.purchasesCompleted + stats.salesCompleted));
            }

            // при ошибке статистику просто не обновляю, это не критично, тост не показываю
            @Override
            public void onFailure(Call<UserStats> call, Throwable t) {
                if (!isAdded()) return;
            }
        });
    }

    // короткая запись больших сумм, чтобы влезало в маленькую плашку: 1500 -> 2K, 2 500 000 -> 2.5M
    private String formatCompact(double amount) {
        if (amount >= 1_000_000) return String.format(Locale.getDefault(), "%.1fM", amount / 1_000_000);
        if (amount >= 1_000) return String.format(Locale.getDefault(), "%.0fK", amount / 1_000);
        return String.format(Locale.getDefault(), "%.0f", amount);
    }

    // мой профиль с сервера (/me) - там баланс, почта и всё остальное
    private void loadProfile() {
        ApiClient.getApiService(requireContext()).me().enqueue(new Callback<User>() {
            @Override
            public void onResponse(Call<User> call, Response<User> response) {
                if (!isAdded()) return;
                if (response.isSuccessful() && response.body() != null) {
                    bind(response.body());
                }
            }

            @Override
            public void onFailure(Call<User> call, Throwable t) {
                if (!isAdded()) return;
                Toast.makeText(requireContext(), ApiErrors.network(t), Toast.LENGTH_LONG).show();
            }
        });
    }

    // раскладываю данные профиля по полям. значок ✎ у ника - подсказка, что его можно нажать и поменять
    private void bind(User user) {
        nicknameText.setText(user.nickname + "  ✎");
        myNickname = user.nickname;
        showAvatar(user.avatarUrl);
        usernameText.setText("@" + user.username + " · ID " + user.id);
        emailText.setText(user.email);
        balanceText.setText(String.format(Locale.getDefault(), "%.2f ₽", user.balanceAvailable));
        // замороженные деньги - это деньги в незавершённых сделках и на 48-часовом холде после подтверждения,
        // поэтому поясняю, что они станут доступны позже
        frozenBalanceText.setText(user.balanceFrozen > 0
                ? String.format(Locale.getDefault(), "Заморожено: %.2f ₽ (доступно позже, по истечении срока удержания)", user.balanceFrozen)
                : "Заморожено: 0 ₽");
        // если отзывов ещё нет - не показываю 0.0, а пишу что отзывов пока нет
        ratingText.setText(user.ratingCount == 0
                ? "★ — (пока нет отзывов)"
                : String.format(Locale.getDefault(), "★ %.1f (%d отзывов)", user.ratingAverage, user.ratingCount));
    }

    // диалог пополнения: сумма + номер карты. сервер в ответ отдаёт обновлённый профиль, его сразу и рисую,
    // чтобы не делать лишний запрос за балансом
    private void showTopUpDialog() {
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_topup, null);
        EditText amountEditText = dialogView.findViewById(R.id.amountEditText);
        EditText cardEditText = dialogView.findViewById(R.id.cardNumberEditText);

        AlertDialog dialog = new AlertDialog.Builder(requireContext()).setView(dialogView).create();

        dialogView.findViewById(R.id.confirmTopUpButton).setOnClickListener(v -> {
            Double amount = parsePositiveAmount(amountEditText.getText().toString());
            // если сумма кривая, parsePositiveAmount уже показал тост, просто выхожу
            if (amount == null) return;

            ApiClient.getApiService(requireContext()).topUp(new TopUpRequest(amount, cardEditText.getText().toString()))
                    .enqueue(new Callback<User>() {
                        @Override
                        public void onResponse(Call<User> call, Response<User> response) {
                if (!isAdded()) return;
                            if (response.isSuccessful() && response.body() != null) {
                                bind(response.body());
                                Toast.makeText(requireContext(), "Баланс пополнен", Toast.LENGTH_SHORT).show();
                                dialog.dismiss();
                            } else {
                                Toast.makeText(requireContext(), ApiErrors.message(response, "Не удалось пополнить баланс"), Toast.LENGTH_LONG).show();
                            }
                        }

                        @Override
                        public void onFailure(Call<User> call, Throwable t) {
                if (!isAdded()) return;
                            Toast.makeText(requireContext(), ApiErrors.network(t), Toast.LENGTH_SHORT).show();
                        }
                    });
        });

        dialog.show();
    }

    // диалог вывода на карту. номер карты проверяю грубо (минимум 12 цифр без пробелов),
    // а хватает ли денег - проверяет сервер, он же списывает с доступного баланса
    private void showWithdrawDialog() {
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_withdraw, null);
        EditText amountEditText = dialogView.findViewById(R.id.amountEditText);
        EditText cardEditText = dialogView.findViewById(R.id.cardNumberEditText);

        AlertDialog dialog = new AlertDialog.Builder(requireContext()).setView(dialogView).create();

        dialogView.findViewById(R.id.confirmWithdrawButton).setOnClickListener(v -> {
            Double amount = parsePositiveAmount(amountEditText.getText().toString());
            if (amount == null) return;
            if (cardEditText.getText().toString().replaceAll("\\s", "").length() < 12) {
                Toast.makeText(requireContext(), "Укажите корректный номер карты", Toast.LENGTH_SHORT).show();
                return;
            }

            ApiClient.getApiService(requireContext()).withdraw(new WithdrawRequest(amount, cardEditText.getText().toString()))
                    .enqueue(new Callback<User>() {
                        @Override
                        public void onResponse(Call<User> call, Response<User> response) {
                if (!isAdded()) return;
                            if (response.isSuccessful() && response.body() != null) {
                                bind(response.body());
                                Toast.makeText(requireContext(), "Средства выведены", Toast.LENGTH_SHORT).show();
                                dialog.dismiss();
                            } else {
                                Toast.makeText(requireContext(), ApiErrors.message(response, "Не удалось вывести: проверьте доступный баланс"), Toast.LENGTH_LONG).show();
                            }
                        }

                        @Override
                        public void onFailure(Call<User> call, Throwable t) {
                if (!isAdded()) return;
                            Toast.makeText(requireContext(), ApiErrors.network(t), Toast.LENGTH_SHORT).show();
                        }
                    });
        });

        dialog.show();
    }

    // перевод другому пользователю по @юзернейму. собачку в начале убираю, если её ввели
    private void showTransferDialog() {
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_transfer, null);
        EditText usernameEditText = dialogView.findViewById(R.id.usernameEditText);
        EditText amountEditText = dialogView.findViewById(R.id.amountEditText);
        EditText messageEditText = dialogView.findViewById(R.id.messageEditText);

        AlertDialog dialog = new AlertDialog.Builder(requireContext()).setView(dialogView).create();

        dialogView.findViewById(R.id.confirmTransferButton).setOnClickListener(v -> {
            String username = usernameEditText.getText().toString().trim().replaceFirst("^@", "");
            if (username.isEmpty()) {
                Toast.makeText(requireContext(), "Укажите получателя", Toast.LENGTH_SHORT).show();
                return;
            }
            Double amount = parsePositiveAmount(amountEditText.getText().toString());
            if (amount == null) return;

            Transfer.CreateRequest request = new Transfer.CreateRequest(username, amount, messageEditText.getText().toString());
            ApiClient.getApiService(requireContext()).sendTransfer(request).enqueue(new Callback<Transfer>() {
                @Override
                public void onResponse(Call<Transfer> call, Response<Transfer> response) {
                if (!isAdded()) return;
                    if (response.isSuccessful() && response.body() != null) {
                        // перевод отдаёт не профиль, а сам перевод, поэтому баланс перезагружаю отдельно
                        loadProfile();
                        Toast.makeText(requireContext(), "Переведено @" + username, Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    } else {
                        Toast.makeText(requireContext(), ApiErrors.message(response, "Не удалось перевести: проверьте юзернейм и баланс"), Toast.LENGTH_LONG).show();
                    }
                }

                @Override
                public void onFailure(Call<Transfer> call, Throwable t) {
                if (!isAdded()) return;
                    Toast.makeText(requireContext(), ApiErrors.network(t), Toast.LENGTH_SHORT).show();
                }
            });
        });

        dialog.show();
    }

    // и точка и запятая
    // разбор суммы из поля: принимаю и точку, и запятую (на русской клавиатуре запятая).
    // возвращаю null, если сумма пустая, не число или <= 0, и сразу показываю почему
    private Double parsePositiveAmount(String raw) {
        String trimmed = raw.trim().replace(',', '.');
        if (trimmed.isEmpty()) {
            Toast.makeText(requireContext(), "Введите сумму", Toast.LENGTH_SHORT).show();
            return null;
        }
        try {
            double amount = Double.parseDouble(trimmed);
            if (amount <= 0) {
                Toast.makeText(requireContext(), "Сумма должна быть больше нуля", Toast.LENGTH_SHORT).show();
                return null;
            }
            return amount;
        } catch (NumberFormatException e) {
            Toast.makeText(requireContext(), "Некорректная сумма", Toast.LENGTH_SHORT).show();
            return null;
        }
    }

    // диалог смены ника: EditText кладу во FrameLayout с отступами, иначе поле прилипает к краям диалога.
    // текущий ник беру из текста на экране, отрезав значок ✎
    private void showNicknameDialog() {
        android.widget.EditText input = new android.widget.EditText(requireContext());
        String current = nicknameText.getText().toString().replace("✎", "").trim();
        input.setText(current);
        // курсор в конец текста, чтобы удобно было дописывать
        input.setSelection(input.getText().length());
        input.setSingleLine(true);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout box = new android.widget.FrameLayout(requireContext());
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);

        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Изменить никнейм")
                .setMessage("Никнейм видят другие пользователи, он может повторяться. Ваш @username не меняется.")
                .setView(box)
                .setPositiveButton("Сохранить", (d, w) -> saveNickname(input.getText().toString().trim()))
                .setNegativeButton("Отмена", null)
                .show();
    }

    // сохраняю новый ник на сервере. после успеха обновляю его и в SessionManager,
    // потому что ник оттуда берёт MainActivity
    private void saveNickname(String nickname) {
        ApiClient.getApiService(requireContext()).changeNickname(new User.ChangeNicknameRequest(nickname))
                .enqueue(new Callback<User>() {
                    @Override
                    public void onResponse(Call<User> call, Response<User> response) {
                        if (!isAdded()) return;
                        if (response.isSuccessful() && response.body() != null) {
                            nicknameText.setText(response.body().nickname + "  ✎");
                            session.saveSession(session.getToken(), session.getUserId(), response.body().nickname);
                            Toast.makeText(requireContext(), "Никнейм изменён", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(requireContext(), ApiErrors.message(response, "Не удалось изменить никнейм"),
                                    Toast.LENGTH_LONG).show();
                        }
                    }

                    @Override
                    public void onFailure(Call<User> call, Throwable t) {
                        if (!isAdded()) return;
                        Toast.makeText(requireContext(), ApiErrors.network(t), Toast.LENGTH_LONG).show();
                    }
                });
    }

    // Avatars.load сам решает: есть url - грузит фото через Glide, нет - рисует круг с буквой
    // без фото - сезонный круг с первой буквой ника
    private void showAvatar(String url) {
        // фрагмент мог уже открепиться от активити, тогда Glide упадёт - поэтому проверка
        if (avatarImage == null || !isAdded()) return;
        com.lunarforge.market.util.Avatars.load(avatarImage, url, myNickname);
    }

    // два шага: сначала файл на сервер (получаю url), потом этот url сохраняю в профиль через changeAvatar
    // сразу показываем выбранное фото, потом грузим и сохраняем
    private void uploadAvatar(java.io.File file) {
        // сразу показываем обрезанный аватар, пока он грузится на сервер
        com.bumptech.glide.Glide.with(this).load(file).circleCrop().into(avatarImage);
        com.lunarforge.market.util.FileUploadHelper.uploadFile(requireContext(), file, "image/jpeg",
                new com.lunarforge.market.util.FileUploadHelper.UploadCallback() {
                    @Override
                    public void onSuccess(String url) {
                        // пока файл грузился, юзер мог уйти с вкладки - тогда второй запрос не отправляю
                        if (!isAdded()) return;
                        ApiClient.getApiService(requireContext()).changeAvatar(new User.ChangeAvatarRequest(url))
                                .enqueue(new Callback<User>() {
                                    @Override
                                    public void onResponse(Call<User> call, Response<User> response) {
                                        if (!isAdded()) return;
                                        if (response.isSuccessful() && response.body() != null) {
                                            showAvatar(response.body().avatarUrl);
                                            Toast.makeText(requireContext(), "Аватар обновлён", Toast.LENGTH_SHORT).show();
                                        } else {
                                            Toast.makeText(requireContext(), ApiErrors.message(response, "Не удалось сохранить аватар"),
                                                    Toast.LENGTH_LONG).show();
                                        }
                                    }

                                    @Override
                                    public void onFailure(Call<User> call, Throwable t) {
                                        if (!isAdded()) return;
                                        Toast.makeText(requireContext(), ApiErrors.network(t), Toast.LENGTH_LONG).show();
                                    }
                                });
                    }

                    @Override
                    public void onFailure(String message) {
                        if (!isAdded()) return;
                        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
                    }
                });
    }
}
