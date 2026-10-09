package com.lunarforge.market.ui.listing;

import com.lunarforge.market.util.ApiErrors;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.lunarforge.market.R;
import com.lunarforge.market.api.ApiClient;
import com.lunarforge.market.model.Category;
import com.lunarforge.market.model.Game;
import com.lunarforge.market.model.Listing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

// экран "добавить товар": продавец выбирает игру, категорию, тип, пишет название/цену/количество,
// прикрепляет обложку и до 5 доп. фото и отправляет лот на сервер.
// фото загружаются сразу при выборе (через FileUploadHelper), а в сам лот уходят уже готовые url
public class AddProductActivity extends com.lunarforge.market.util.BaseActivity {
    // через этот extra можно открыть экран с уже выбранной игрой (например со страницы игры)
    public static final String EXTRA_PRESELECT_GAME_ID = "preselect_game_id";

    // подписи для спиннера и коды, которые ждёт сервер, идут в одинаковом порядке:
    // выбранная позиция в спиннере = индекс в TYPE_CODES
    private static final String[] TYPE_LABELS = {"Предмет", "Услуга", "Донат"};
    private static final String[] TYPE_CODES = {"ITEM", "SERVICE", "DONATE"};

    private Spinner gameSpinner, categorySpinner, typeSpinner, deliverySpinner;
    private EditText nameEditText, priceEditText, quantityEditText, descriptionEditText;

    // url обложки после загрузки на сервер и флаг "ещё грузится" - чтобы не отправить лот без фото
    private String uploadedImageUrl;
    private boolean imageUploading = false;

    // доп. фото: храню вьюшку миниатюры и url (url = null пока файл ещё грузится).
    // счётчик extraUploadsInProgress нужен, чтобы кнопка "добавить" ждала, пока догрузятся все фото
    private static final int MAX_EXTRA_PHOTOS = 5;
    private static class ExtraPhoto { android.view.View view; String url; }
    private final List<ExtraPhoto> extraPhotos = new ArrayList<>();
    private int extraUploadsInProgress = 0;
    // лаунчеры для выбора картинок из галереи (новый способ вместо startActivityForResult).
    // регистрировать их можно только в onCreate, до того как экран запустился, иначе упадёт
    private androidx.activity.result.ActivityResultLauncher<String> pickExtraPhotosLauncher;
    private androidx.activity.result.ActivityResultLauncher<String> pickImageLauncher;
    private CheckBox activeCheckBox, unlimitedCheckBox;
    private TextView addButton, buyerPriceHintText;

    private final List<Game> games = new ArrayList<>();
    private final List<Category> categories = new ArrayList<>();
    // должно совпадать с ListingService.COMMISSION_RATE на сервере
    private static final java.math.BigDecimal COMMISSION_RATE = new java.math.BigDecimal("0.05");

    // в onCreate нахожу все поля, вешаю обработчики и запускаю загрузку списка игр
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_product);

        findViewById(R.id.backButton).setOnClickListener(v -> finish());

        gameSpinner = findViewById(R.id.gameSpinner);
        categorySpinner = findViewById(R.id.categorySpinner);
        typeSpinner = findViewById(R.id.typeSpinner);
        deliverySpinner = findViewById(R.id.deliveryMethodSpinner);
        nameEditText = findViewById(R.id.productNameEditText);
        priceEditText = findViewById(R.id.priceEditText);
        quantityEditText = findViewById(R.id.quantityEditText);
        descriptionEditText = findViewById(R.id.descriptionEditText);
        android.widget.ImageView preview = findViewById(R.id.productAvatarImageView);
        // выбор обложки: показываю превью сразу из Uri, а в фоне отправляю файл на сервер
        pickImageLauncher = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.GetContent(), uri -> {
                    if (uri == null) return;
                    preview.setImageURI(uri);
                    imageUploading = true;
                    com.lunarforge.market.util.FileUploadHelper.uploadFromUri(this, uri, "image/jpeg",
                            new com.lunarforge.market.util.FileUploadHelper.UploadCallback() {
                                @Override
                                public void onSuccess(String url) {
                                    imageUploading = false;
                                    uploadedImageUrl = url;
                                    Toast.makeText(AddProductActivity.this, "Фото загружено", Toast.LENGTH_SHORT).show();
                                }

                                @Override
                                public void onFailure(String message) {
                                    // если загрузка не удалась - убираю превью обратно на заглушку, чтобы продавец не думал, что фото есть
                                    if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                                    imageUploading = false;
                                    uploadedImageUrl = null;
                                    preview.setImageResource(R.drawable.ic_image_placeholder);
                                    Toast.makeText(AddProductActivity.this, message, Toast.LENGTH_LONG).show();
                                }
                            });
                });
        // и тап по картинке, и кнопка камеры открывают галерею (GetContent с типом image/*)
        findViewById(R.id.imageContainer).setOnClickListener(v -> pickImageLauncher.launch("image/*"));
        findViewById(R.id.cameraButton).setOnClickListener(v -> pickImageLauncher.launch("image/*"));

        // выбор нескольких доп. фото сразу. если выбрали больше, чем осталось мест, - беру только первые free штук
        pickExtraPhotosLauncher = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents(), uris -> {
                    if (uris == null || uris.isEmpty()) return;
                    int free = MAX_EXTRA_PHOTOS - extraPhotos.size();
                    if (uris.size() > free) {
                        Toast.makeText(this, "Можно добавить ещё только " + free + " фото", Toast.LENGTH_SHORT).show();
                    }
                    for (int i = 0; i < Math.min(free, uris.size()); i++) addExtraPhoto(uris.get(i));
                });
        // кнопку "добавить фото" не пускаю дальше лимита в 5 штук
        findViewById(R.id.addExtraPhotoButton).setOnClickListener(v -> {
            if (extraPhotos.size() >= MAX_EXTRA_PHOTOS) {
                Toast.makeText(this, "Максимум " + MAX_EXTRA_PHOTOS + " дополнительных фото", Toast.LENGTH_SHORT).show();
            } else {
                pickExtraPhotosLauncher.launch("image/*");
            }
        });
        activeCheckBox = findViewById(R.id.activeCheckBox);
        unlimitedCheckBox = findViewById(R.id.unlimitedCheckBox);
        addButton = findViewById(R.id.addProductButton);
        buyerPriceHintText = findViewById(R.id.buyerPriceHintText);

        typeSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, TYPE_LABELS));

        // бесконечный товар - поле количества не нужно, выключаю его
        unlimitedCheckBox.setOnCheckedChangeListener((btn, checked) -> quantityEditText.setEnabled(!checked));

        // при каждом изменении цены пересчитываю подсказку "покупатель заплатит / вы получите"
        priceEditText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateBuyerPricePreview(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        // когда меняют игру - подгружаю категории именно этой игры, у каждой игры они свои.
        // проверка границ на всякий случай, если список игр ещё пустой
        gameSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                if (position >= 0 && position < games.size()) {
                    loadCategories(games.get(position).id);
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        addButton.setOnClickListener(v -> submit());

        // -1 значит игру заранее не выбирали
        loadGames(getIntent().getLongExtra(EXTRA_PRESELECT_GAME_ID, -1));
    }

    // считаем так же как сервер (BigDecimal, HALF_UP), чтобы цифры совпадали до копейки
    private void updateBuyerPricePreview() {
        String priceStr = priceEditText.getText().toString().trim().replace(',', '.');
        try {
            java.math.BigDecimal price = new java.math.BigDecimal(priceStr);
            // цена покупателя = цена продавца * 1.05, округление до копеек
            java.math.BigDecimal buyerPrice = price.multiply(java.math.BigDecimal.ONE.add(COMMISSION_RATE))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            buyerPriceHintText.setText(String.format(Locale.getDefault(),
                    "Покупатель заплатит: %s ₽ · вы получите: %s ₽ (комиссия площадки 5%%)",
                    buyerPrice.toPlainString(), price.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()));
        // пока поле пустое или там не число - показываю прочерк вместо суммы
        } catch (NumberFormatException e) {
            buyerPriceHintText.setText("Покупатель заплатит: — ₽ (с учётом комиссии площадки 5%)");
        }
    }

    // загружаю список игр для спиннера и сразу выбираю ту, что передали в extra (если передали)
    private void loadGames(long preselectGameId) {
        ApiClient.getApiService(this).games(null).enqueue(new Callback<List<Game>>() {
            @Override
            public void onResponse(Call<List<Game>> call, Response<List<Game>> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                if (response.isSuccessful() && response.body() != null) {
                    games.clear();
                    games.addAll(response.body());

                    List<String> names = new ArrayList<>();
                    int preselectIndex = 0;
                    for (int i = 0; i < games.size(); i++) {
                        names.add(games.get(i).name);
                        if (games.get(i).id == preselectGameId) {
                            preselectIndex = i;
                        }
                    }

                    ArrayAdapter<String> adapter = new ArrayAdapter<>(
                            AddProductActivity.this, android.R.layout.simple_spinner_dropdown_item, names);
                    gameSpinner.setAdapter(adapter);
                    gameSpinner.setSelection(preselectIndex);
                    // setSelection не всегда вызывает onItemSelected (например если выбран 0 и он уже был выбран),
                    // поэтому категории первой игры гружу явно, чтобы спиннер категорий не остался пустым
                    if (!games.isEmpty()) {
                        loadCategories(games.get(preselectIndex).id);
                    }
                } else {
                    Toast.makeText(AddProductActivity.this, ApiErrors.message(response, "Не удалось загрузить список игр"), Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<List<Game>> call, Throwable t) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                Toast.makeText(AddProductActivity.this, ApiErrors.network(t), Toast.LENGTH_SHORT).show();
            }
        });
    }

    // категории выбранной игры. первым пунктом всегда "Другое" (лот без категории),
    // поэтому позиция в спиннере на 1 больше индекса в списке categories
    private void loadCategories(long gameId) {
        ApiClient.getApiService(this).categoriesByGame(gameId).enqueue(new Callback<List<Category>>() {
            @Override
            public void onResponse(Call<List<Category>> call, Response<List<Category>> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                categories.clear();
                if (response.isSuccessful() && response.body() != null) {
                    categories.addAll(response.body());
                }

                List<String> labels = new ArrayList<>();
                labels.add("Другое");
                for (Category c : categories) labels.add(c.name);

                categorySpinner.setAdapter(new ArrayAdapter<>(
                        AddProductActivity.this, android.R.layout.simple_spinner_dropdown_item, labels));
            }

            // если сервер не ответил, оставляю только "Другое", чтобы лот всё равно можно было создать
            @Override
            public void onFailure(Call<List<Category>> call, Throwable t) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                categories.clear();
                categorySpinner.setAdapter(new ArrayAdapter<>(
                        AddProductActivity.this, android.R.layout.simple_spinner_dropdown_item, List.of("Другое")));
            }
        });
    }

    // пока фото грузятся - не отправляем. цену можно через запятую, с русской клавы так удобнее
    // отправка лота: проверяю поля, собираю CreateRequest и шлю на сервер.
    // серверу я всё равно не доверяю - он проверяет цену, количество и т.д. сам, а тут проверки просто для удобства
    private void submit() {
        if (imageUploading || extraUploadsInProgress > 0) {
            Toast.makeText(this, "Подождите, фото ещё загружается", Toast.LENGTH_SHORT).show();
            return;
        }
        if (games.isEmpty()) {
            Toast.makeText(this, "Список игр ещё не загружен", Toast.LENGTH_SHORT).show();
            return;
        }

        String title = nameEditText.getText().toString().trim();
        String priceStr = priceEditText.getText().toString().trim().replace(',', '.');
        String quantityStr = quantityEditText.getText().toString().trim();
        boolean unlimited = unlimitedCheckBox.isChecked();

        if (title.isEmpty() || priceStr.isEmpty()) {
            Toast.makeText(this, "Укажите название и цену", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!unlimited && quantityStr.isEmpty()) {
            Toast.makeText(this, "Укажите количество или отметьте «Бесконечное количество»", Toast.LENGTH_SHORT).show();
            return;
        }

        // цену тут парсю в double только для отправки, на сервере она превращается в BigDecimal
        double price;
        try {
            price = Double.parseDouble(priceStr);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Некорректная цена", Toast.LENGTH_SHORT).show();
            return;
        }

        int quantity;
        try {
            // для бесконечного товара количество не важно, ставлю 1. Integer.parseInt упадёт, если ввели слишком большое число
            quantity = unlimited || quantityStr.isEmpty() ? 1 : Integer.parseInt(quantityStr);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Слишком большое количество", Toast.LENGTH_SHORT).show();
            return;
        }

        // позиция 0 = "Другое" -> categoryId = null, иначе сдвиг на 1 (см. loadCategories)
        int categoryIndex = categorySpinner.getSelectedItemPosition();
        Long categoryId = categoryIndex > 0 && categoryIndex - 1 < categories.size()
                ? categories.get(categoryIndex - 1).id : null;

        Listing.CreateRequest request = new Listing.CreateRequest();
        request.gameId = games.get(gameSpinner.getSelectedItemPosition()).id;
        request.categoryId = categoryId;
        request.type = TYPE_CODES[typeSpinner.getSelectedItemPosition()];
        request.title = title;
        request.description = descriptionEditText.getText().toString().trim();
        request.price = price;
        request.quantity = quantity;
        request.unlimited = unlimited;
        request.deliveryMethod = deliverySpinner.getSelectedItem() != null
                ? deliverySpinner.getSelectedItem().toString() : null;
        request.imageUrl = uploadedImageUrl;
        request.extraImageUrls = new ArrayList<>();
        // в лот кладу только те доп. фото, что успели загрузиться (url не null)
        for (ExtraPhoto p : extraPhotos) if (p.url != null) request.extraImageUrls.add(p.url);
        request.active = activeCheckBox.isChecked();

        // выключаю кнопку на время запроса, чтобы двойным тапом не создать два одинаковых лота
        addButton.setEnabled(false);
        ApiClient.getApiService(this).createListing(request).enqueue(new Callback<Listing>() {
            @Override
            public void onResponse(Call<Listing> call, Response<Listing> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                addButton.setEnabled(true);
                if (response.isSuccessful()) {
                    Toast.makeText(AddProductActivity.this, "Товар добавлен", Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    Toast.makeText(AddProductActivity.this, com.lunarforge.market.util.ApiErrors.message(response, "Не удалось добавить товар"), Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<Listing> call, Throwable t) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                addButton.setEnabled(true);
                Toast.makeText(AddProductActivity.this, ApiErrors.network(t), Toast.LENGTH_SHORT).show();
            }
        });
    }

    // миниатюра сразу (полупрозрачная пока грузится). тап по ней - удалить
    // добавляю миниатюру доп. фото в ряд и сразу запускаю её загрузку
    private void addExtraPhoto(android.net.Uri uri) {
        android.widget.LinearLayout row = findViewById(R.id.extraPhotosRow);
        // density - чтобы переводить dp в пиксели, иначе на разных экранах миниатюры были бы разного размера
        float d = getResources().getDisplayMetrics().density;

        android.widget.ImageView thumb = new android.widget.ImageView(this);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams((int) (80 * d), (int) (80 * d));
        lp.setMarginEnd((int) (8 * d));
        thumb.setLayoutParams(lp);
        thumb.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        thumb.setBackgroundResource(R.drawable.rounded_photo_bg);
        // clipToOutline обрезает картинку по скруглённому фону rounded_photo_bg
        thumb.setClipToOutline(true);
        thumb.setAlpha(0.5f);
        thumb.setImageURI(uri);
        // вставляю перед последним элементом ряда - последний это кнопка "+", она должна оставаться в конце
        row.addView(thumb, row.getChildCount() - 1);

        ExtraPhoto photo = new ExtraPhoto();
        photo.view = thumb;
        extraPhotos.add(photo);
        extraUploadsInProgress++;

        thumb.setOnClickListener(v -> new androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage("Удалить это фото?")
                .setPositiveButton("Удалить", (dlg, w) -> {
                    row.removeView(thumb);
                    extraPhotos.remove(photo);
                })
                .setNegativeButton("Отмена", null)
                .show());

        // загрузка доп. фото. после успеха делаю миниатюру непрозрачной - так видно, что фото уже на сервере
        com.lunarforge.market.util.FileUploadHelper.uploadFromUri(this, uri, "image/jpeg",
                new com.lunarforge.market.util.FileUploadHelper.UploadCallback() {
                    @Override
                    public void onSuccess(String url) {
                        extraUploadsInProgress--;
                        photo.url = url;
                        thumb.setAlpha(1f);
                    }

                    // при ошибке убираю миниатюру совсем, чтобы в лот не попало фото без url
                    @Override
                    public void onFailure(String message) {
                        if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                        extraUploadsInProgress--;
                        row.removeView(thumb);
                        extraPhotos.remove(photo);
                        Toast.makeText(AddProductActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
    }
}
