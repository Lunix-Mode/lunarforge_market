package com.lunarforge.market.ui.chat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.HapticFeedbackConstants;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.common.util.concurrent.ListenableFuture;
import com.lunarforge.market.R;
import com.lunarforge.market.adapter.ChatMessageAdapter;
import com.lunarforge.market.api.ApiClient;
import com.lunarforge.market.model.Chat;
import com.lunarforge.market.util.ApiErrors;
import com.lunarforge.market.util.BaseActivity;
import com.lunarforge.market.util.FileUploadHelper;
import com.lunarforge.market.util.SessionManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

// экран чата, самый сложный.
// новые сообщения - опрос раз в 3 сек пока экран открыт (в onPause выключаем, батарея)
// 
// запись как в телеге:
// тап по кнопке - переключить микрофон/камеру
// держать - пишем, отпустил - отправилось
// свайп вверх к замку - пишем без рук
// свайп влево - отмена
// кружки снимаются через CameraX прямо в приложении
// фото и видео выбираются из галереи. любое вложение сначала заливается на сервер (api/files/upload),
// и только потом отправляется сообщение со ссылкой на файл
// в Intent приходят: id чата, ник собеседника (для шапки), его id (для аватарки и профиля) и id заказа, если чат про заказ
public class ChatActivity extends BaseActivity {
    public static final String EXTRA_THREAD_ID = "thread_id";
    public static final String EXTRA_OTHER_NICKNAME = "other_nickname";
    public static final String EXTRA_OTHER_USER_ID = "other_user_id";
    public static final String EXTRA_ORDER_ID = "order_id";

    private static final long POLL_INTERVAL_MS = 3000;
    // меньше этого = тап, а не запись
    private static final long HOLD_DELAY_MS = 160;
    // короче этого запись не отправляем - скорее всего случайно зажали
    private static final long MIN_RECORD_MS = 800;
    // лимиты длины: голосовое 5 минут, кружок минута. по достижении запись сама отправляется
    private static final long MAX_VOICE_MS = 5 * 60_000;
    private static final long MAX_NOTE_MS = 60_000;

    // что пишем: голосовое или кружок
    private enum Mode { VOICE, VIDEO_NOTE }
    // IDLE - не пишем, RECORDING - пишем и палец на кнопке, LOCKED - закрепили замком и пишем без рук
    private enum RecState { IDLE, RECORDING, LOCKED }

    private long threadId;
    private ChatMessageAdapter adapter;
    private RecyclerView recyclerView;
    private EditText messageEditText;
    private ImageButton recordButton, sendButton;
    private View attachButton, recordingBar, lockIndicator, noteOverlay, discardButton, sendRecordingButton, recordDot;
    private TextView recordingTimeText, slideToCancelText;
    private PreviewView notePreview;
    private Drawable recordButtonDefaultBg;

    // handler на главном потоке - через него делаю все задержки: опрос, таймер записи, распознавание удержания.
    // главный поток, потому что из этих задач трогаю вьюшки
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pollRunnable;

    // mode - что сейчас выбрано на кнопке, activeMode - чем пишем текущую запись.
    // разделил, чтобы переключение во время записи не сломало её остановку
    private Mode mode = Mode.VOICE;
    private Mode activeMode = Mode.VOICE;
    private RecState recState = RecState.IDLE;
    // true, если палец продержали дольше HOLD_DELAY_MS и запись началась
    private boolean holdStarted;
    // где палец коснулся кнопки - от этой точки считаю свайп влево/вверх
    private float downX, downY;
    // elapsedRealtime, а не текущее время: не скачет, если человек поменяет часы на телефоне
    private long recordingStartedAt;
    private Runnable timerRunnable;

    private MediaRecorder voiceRecorder;
    private File voiceFile;

    private ProcessCameraProvider cameraProvider;
    private View flipCameraButton;
    private Recording noteRecording;
    private Preview notePreviewUseCase;               // держим, чтобы перепривязать к другой камере
    private VideoCapture<Recorder> noteVideoCapture;
    private boolean noteFrontCamera = true;           // какой камерой начинать (запоминаем последний выбор)
    private File noteFile;
    // палец отпустили, пока камера ещё запускалась - тогда запись вообще не начинаем
    private boolean noteAborted;
    // отправлять ли кружок, когда файл допишется (решается в stopVideoNote, отправка в onNoteFinalized)
    private boolean noteSendOnFinalize;
    private int noteSeconds;

    // запускается через HOLD_DELAY_MS после касания. если палец отпустили раньше - его отменяют, и это был тап
    private final Runnable holdRunnable = () -> {
        holdStarted = true;
        beginRecording();
    };

    // лаунчеры для выбора файла и запроса разрешений. регистрировать их можно только в onCreate (до старта экрана),
    // поэтому они полями, а запускаю уже по клику
    private ActivityResultLauncher<String> pickImageLauncher;
    private ActivityResultLauncher<String[]> permissionsLauncher;
    private ActivityResultLauncher<String> pickVideoLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);
        useSurfaceColorBehindSystemBars();

        // если ника не передали - в шапке просто "Чат"
        threadId = getIntent().getLongExtra(EXTRA_THREAD_ID, -1);
        String otherNickname = getIntent().getStringExtra(EXTRA_OTHER_NICKNAME);
        ((TextView) findViewById(R.id.titleText)).setText(otherNickname != null ? otherNickname : "Чат");
        findViewById(R.id.backButton).setOnClickListener(v -> finish());

        // тап по имени в шапке - профиль собеседника
        long otherUserId = getIntent().getLongExtra(EXTRA_OTHER_USER_ID, -1);
        if (otherUserId != -1) {
            View header = findViewById(R.id.headerUser);
            header.setOnClickListener(v -> {
                Intent intent = new Intent(this, com.lunarforge.market.ui.profile.PublicProfileActivity.class);
                intent.putExtra(com.lunarforge.market.ui.profile.PublicProfileActivity.EXTRA_USER_ID, otherUserId);
                startActivity(intent);
            });
            loadHeaderAvatar(otherUserId);
        }

        // адаптеру нужен мой id, чтобы мои сообщения рисовать справа, а чужие слева
        SessionManager session = new SessionManager(this);
        adapter = new ChatMessageAdapter(session.getUserId());
        recyclerView = findViewById(R.id.messagesRecyclerView);
        // кнопке записи разрешено рисовать за краями (см. allowRecordButtonToOverflow) - из-за этого и список перестал
        // обрезаться и при прокрутке налезал на шапку. обрезаем список по его собственным границам явно
        recyclerView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                v.setClipBounds(new android.graphics.Rect(0, 0, r - l, b - t)));
        LinearLayoutManager lm = new LinearLayoutManager(this);
        // чтобы короткая переписка была внизу как в мессенджерах
        lm.setStackFromEnd(true);
        recyclerView.setLayoutManager(lm);
        recyclerView.setAdapter(adapter);
        // в чате наоборот - старые сообщения подгружаются, когда листаешь вверх
        com.lunarforge.market.util.Paging.onNearStart(recyclerView, this::loadOlderMessages);

        messageEditText = findViewById(R.id.messageEditText);
        attachButton = findViewById(R.id.attachButton);
        recordButton = findViewById(R.id.recordButton);
        allowRecordButtonToOverflow();
        sendButton = findViewById(R.id.sendButton);
        recordingBar = findViewById(R.id.recordingBar);
        recordingTimeText = findViewById(R.id.recordingTimeText);
        slideToCancelText = findViewById(R.id.slideToCancelText);
        discardButton = findViewById(R.id.discardRecordingButton);
        sendRecordingButton = findViewById(R.id.sendRecordingButton);
        recordDot = findViewById(R.id.recordDot);
        lockIndicator = findViewById(R.id.lockIndicator);
        noteOverlay = findViewById(R.id.noteOverlay);
        notePreview = findViewById(R.id.notePreview);
        flipCameraButton = findViewById(R.id.flipCameraButton);
        flipCameraButton.setOnClickListener(v -> flipCamera());
        // запоминаю обычный фон кнопки, чтобы вернуть его после записи (во время записи она красная)
        recordButtonDefaultBg = recordButton.getBackground();

        // COMPATIBLE = TextureView, только его можно обрезать кругом (SurfaceView не обрезается)
        notePreview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        View circle = findViewById(R.id.notePreviewCircle);
        // делаю контур контейнера овалом и включаю обрезку по контуру - так превью камеры становится кругом
        circle.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        circle.setClipToOutline(true);

        sendButton.setOnClickListener(v -> sendTextMessage());
        attachButton.setOnClickListener(this::showAttachMenu);
        discardButton.setOnClickListener(v -> finishRecording(false));
        sendRecordingButton.setOnClickListener(v -> finishRecording(true));
        setupRecordButton();

        // как в мессенджерах: есть текст - кнопка "отправить", поле пустое - кнопка микрофона/камеры
        messageEditText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override
            public void afterTextChanged(Editable e) {
                boolean hasText = e.toString().trim().length() > 0;
                sendButton.setVisibility(hasText ? View.VISIBLE : View.GONE);
                recordButton.setVisibility(hasText ? View.GONE : View.VISIBLE);
            }
        });

        permissionsLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
            // all = все запрошенные разрешения выданы. запись сама не начинается - просим зажать ещё раз
            boolean all = !result.isEmpty() && !result.containsValue(false);
            Toast.makeText(this, all
                    ? "Готово! Теперь удерживайте кнопку, чтобы записать"
                    : "Без доступа к микрофону/камере запись невозможна", Toast.LENGTH_LONG).show();
        });
        // GetContent открывает системный выбор файла. uri == null если человек ничего не выбрал и вышел.
        // второй аргумент uploadAndSend - тип на случай, если система сама его не скажет
        pickImageLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri, "image/jpeg", "PHOTO", null); });
        pickVideoLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri, "video/mp4", "VIDEO", null); });

        // если чат открыт из заказа - сверху полоска со ссылкой на заказ, чтобы не искать его отдельно
        long orderId = getIntent().getLongExtra(EXTRA_ORDER_ID, -1);
        if (orderId != -1) {
            TextView orderStrip = findViewById(R.id.orderStrip);
            orderStrip.setText("Заказ #" + orderId + " · открыть и подтвердить получение →");
            orderStrip.setVisibility(View.VISIBLE);
            orderStrip.setOnClickListener(v -> {
                Intent intent = new Intent(this, com.lunarforge.market.ui.order.OrderStatusActivity.class);
                intent.putExtra(com.lunarforge.market.ui.order.OrderStatusActivity.EXTRA_ORDER_ID, orderId);
                startActivity(intent);
            });
        }

        // первая загрузка последних сообщений. опрос новых запустится в onResume
        loadAllMessages();
    }

    // отправка текста. поле очищаю сразу, не дожидаясь ответа, чтобы интерфейс не тормозил.
    // само сообщение появится в списке, когда сервер его вернёт (simpleSendCallback)
    private void sendTextMessage() {
        String text = messageEditText.getText().toString().trim();
        if (text.isEmpty()) return;
        messageEditText.setText("");
        ApiClient.getApiService(this).sendMessage(threadId, new Chat.SendMessageRequest(text))
                .enqueue(simpleSendCallback());
    }

    // меню скрепки: фото или видео из галереи
    private void showAttachMenu(View anchor) {
        final int ID_PHOTO = 1, ID_VIDEO = 2;
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(Menu.NONE, ID_PHOTO, 0, "Фото");
        menu.getMenu().add(Menu.NONE, ID_VIDEO, 1, "Видео");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == ID_PHOTO) pickImageLauncher.launch("image/*");
            else if (item.getItemId() == ID_VIDEO) pickVideoLauncher.launch("video/*");
            return true;
        });
        menu.show();
    }

    // файл из галереи (по uri) -> загрузить на сервер -> отправить сообщение с ним
    private void uploadAndSend(Uri uri, String fallbackMime, String attachmentType, Integer durationSeconds) {
        Toast.makeText(this, "Отправка…", Toast.LENGTH_SHORT).show();
        FileUploadHelper.uploadFromUri(this, uri, fallbackMime, uploadThenSend(attachmentType, durationSeconds));
    }

    // общий колбэк для всех вложений: когда файл загрузился и сервер вернул ссылку,
    // отправляю сообщение без текста, но с url, типом вложения и длительностью (для голосовых и кружков)
    private FileUploadHelper.UploadCallback uploadThenSend(String attachmentType, Integer durationSeconds) {
        return new FileUploadHelper.UploadCallback() {
            @Override
            public void onSuccess(String url) {
                Chat.SendMessageRequest request = new Chat.SendMessageRequest(null, url, attachmentType, durationSeconds);
                ApiClient.getApiService(ChatActivity.this).sendMessage(threadId, request).enqueue(simpleSendCallback());
            }

            @Override
            public void onFailure(String message) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                Toast.makeText(ChatActivity.this, "Не удалось отправить: " + message, Toast.LENGTH_LONG).show();
            }
        };
    }

    // вся логика пальца на кнопке. ACTION_CANCEL приходит например когда вылез диалог разрешений
    @SuppressLint("ClickableViewAccessibility")
    private void setupRecordButton() {
        recordButton.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                // палец коснулся: запоминаю точку и откладываю старт записи на HOLD_DELAY_MS.
                // return true - говорю системе, что я обрабатываю это касание, иначе MOVE/UP не придут
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    holdStarted = false;
                    handler.postDelayed(holdRunnable, HOLD_DELAY_MS);
                    return true;

                // палец двигается - смотрю, куда тянут. в LOCKED сюда не реагируем, там кнопки уже нет
                case MotionEvent.ACTION_MOVE:
                    if (recState == RecState.RECORDING) {
                        float dx = e.getRawX() - downX;
                        float dy = e.getRawY() - downY;
                        // далеко влево - отмена
                        if (dx < -dp(110)) {
                            finishRecording(false);
                        // вверх до замка
                        } else if (dy < -dp(90)) {
                            lockRecording();
                        } else {
                            // пока не дотянули - двигаю подсказки вслед за пальцем (вполовину, чтобы было плавнее)
                            slideToCancelText.setTranslationX(Math.min(0, dx) / 2f);
                            lockIndicator.setTranslationY(Math.min(0, dy) / 2f);
                        }
                    }
                    return true;

                // палец отпустили. отменяю отложенный старт, если он ещё не сработал
                case MotionEvent.ACTION_UP:
                    handler.removeCallbacks(holdRunnable);
                    // отпустили быстро = это был тап
                    if (!holdStarted) {
                        toggleMode();
                    } else if (recState == RecState.RECORDING) {
                        finishRecording(true);
                    }
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(holdRunnable);
                    if (recState == RecState.RECORDING) finishRecording(false);
                    return true;
            }
            return false;
        });
    }

    // тап по кнопке: переключение голосовое <-> кружок, меняю иконку и подсказываю тостом
    private void toggleMode() {
        mode = mode == Mode.VOICE ? Mode.VIDEO_NOTE : Mode.VOICE;
        recordButton.setImageResource(mode == Mode.VOICE ? R.drawable.ic_mic : R.drawable.ic_videocam);
        Toast.makeText(this, mode == Mode.VOICE
                ? "Голосовое: удерживайте кнопку, чтобы записать"
                : "Кружок: удерживайте кнопку, чтобы записать видео", Toast.LENGTH_SHORT).show();
    }

    // нет разрешения - просто спрашиваем, запись не начинаем (юзер зажмёт ещё раз)
    private void beginRecording() {
        List<String> missing = new ArrayList<>();
        if (!granted(Manifest.permission.RECORD_AUDIO)) missing.add(Manifest.permission.RECORD_AUDIO);
        if (mode == Mode.VIDEO_NOTE && !granted(Manifest.permission.CAMERA)) missing.add(Manifest.permission.CAMERA);
        if (!missing.isEmpty()) {
            permissionsLauncher.launch(missing.toArray(new String[0]));
            return;
        }

        // фиксирую режим записи и время начала, вибрирую - чтобы человек почувствовал, что запись пошла
        activeMode = mode;
        recState = RecState.RECORDING;
        recordingStartedAt = SystemClock.elapsedRealtime();
        recordButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        showRecordingUi();

        if (activeMode == Mode.VOICE) startVoice();
        else startVideoNote();
    }

    // закрепили запись свайпом вверх: палец можно убрать. прячу большую кнопку,
    // показываю "удалить" и "отправить" на полоске записи
    private void lockRecording() {
        if (recState != RecState.RECORDING) return;
        recState = RecState.LOCKED;
        recordButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        lockIndicator.setVisibility(View.GONE);
        recordButton.setVisibility(View.GONE);
        discardButton.setVisibility(View.VISIBLE);
        sendRecordingButton.setVisibility(View.VISIBLE);
        slideToCancelText.setTranslationX(0);
        slideToCancelText.setText(activeMode == Mode.VOICE ? "Запись закреплена" : "Кружок записывается");
        recordingBar.setPaddingRelative(recordingBar.getPaddingStart(), 0, (int) dp(8), 0);
    }

    // send=false значит выкинуть. короче секунды не отправляем - это скорее всего случайный тап
    private void finishRecording(boolean send) {
        if (recState == RecState.IDLE) return;
        long duration = SystemClock.elapsedRealtime() - recordingStartedAt;
        boolean tooShort = duration < MIN_RECORD_MS;
        if (send && tooShort) {
            Toast.makeText(this, "Удерживайте кнопку, чтобы записать", Toast.LENGTH_SHORT).show();
        }
        // секунды для подписи у сообщения, округляю, минимум 1
        boolean reallySend = send && !tooShort;
        int seconds = (int) Math.max(1, Math.round(duration / 1000.0));

        // сначала IDLE, чтобы повторный вызов (например из таймера и из UP одновременно) сразу вышел
        recState = RecState.IDLE;
        hideRecordingUi();
        if (activeMode == Mode.VOICE) stopVoice(reallySend, seconds);
        else stopVideoNote(reallySend, seconds);
    }

    // голосовое пишу MediaRecorder-ом во временный файл в кэше: m4a (контейнер mp4 + AAC), 64 кбит/с -
    // голос нормально слышно, а файл маленький. uuid в имени - чтобы файлы не перезаписывали друг друга
    private void startVoice() {
        voiceFile = new File(getCacheDir(), "voice_" + UUID.randomUUID() + ".m4a");
        voiceRecorder = newMediaRecorder();
        try {
            voiceRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            voiceRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            voiceRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            voiceRecorder.setAudioEncodingBitRate(64_000);
            voiceRecorder.setAudioSamplingRate(44_100);
            voiceRecorder.setOutputFile(voiceFile.getAbsolutePath());
            // порядок вызовов у MediaRecorder строгий: источник -> формат -> кодек -> файл -> prepare -> start
            voiceRecorder.prepare();
            voiceRecorder.start();
        } catch (Exception e) {
            // микрофон может быть занят другим приложением - тогда откатываю всё назад
            releaseVoiceRecorder();
            recState = RecState.IDLE;
            hideRecordingUi();
            Toast.makeText(this, "Микрофон недоступен: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // остановить голосовое и либо отправить файл, либо удалить
    private void stopVoice(boolean send, int seconds) {
        if (voiceRecorder == null) return;
        try {
            voiceRecorder.stop();
        } catch (RuntimeException e) {
            // stop() кидает исключение, если толком ничего не записалось - такой файл битый, не отправляю
            send = false;
        }
        releaseVoiceRecorder();
        if (send) {
            FileUploadHelper.uploadFile(this, voiceFile, "audio/mp4", uploadThenSend("VOICE", seconds));
        } else if (voiceFile != null) {
            voiceFile.delete();
        }
    }

    // освободить микрофон. без release() рекордер держит микрофон, и другие приложения не смогут писать
    private void releaseVoiceRecorder() {
        if (voiceRecorder != null) {
            try { voiceRecorder.release(); } catch (RuntimeException ignored) { }
            voiceRecorder = null;
        }
    }

    // камера включается не мгновенно. если палец отпустили раньше - noteAborted и ничего не пишем
    @SuppressLint("MissingPermission")
    // asPersistentRecording - экспериментальное API CameraX: запись НЕ прерывается при смене камеры
    @androidx.annotation.OptIn(markerClass = androidx.camera.video.ExperimentalPersistentRecording.class)
    private void startVideoNote() {
        noteAborted = false;
        noteSendOnFinalize = false;
        noteFile = new File(getCacheDir(), "note_" + UUID.randomUUID() + ".mp4");
        noteOverlay.setVisibility(View.VISIBLE);

        // провайдер камеры отдаётся асинхронно (future), код внутри addListener выполнится на главном потоке,
        // когда камера будет готова. к тому моменту палец могли отпустить или закрыть экран - проверяю
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            if (noteAborted || isFinishing() || isDestroyed()) return;
            try {
                cameraProvider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(notePreview.getSurfaceProvider());
                notePreviewUseCase = preview;
                // качество SD - кружок маленький, HD не нужен, а файл был бы тяжёлый.
                // если SD камера не умеет - берётся ближайшее похуже или получше
                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.SD,
                                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                        .build();
                VideoCapture<Recorder> videoCapture = VideoCapture.withOutput(recorder);
                noteVideoCapture = videoCapture;
                boolean hasFront = cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA);
                boolean hasBack = cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA);
                // если какой-то камеры нет (планшет, эмулятор) - берём ту что есть, и кнопку переворота прячем
                if (!hasFront) noteFrontCamera = false;
                if (!hasBack) noteFrontCamera = true;
                flipCameraButton.setVisibility(hasFront && hasBack ? View.VISIBLE : View.GONE);
                CameraSelector selector = noteFrontCamera ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
                // привязываю камеру к жизненному циклу экрана: CameraX сам выключит её, когда экран уйдёт в фон
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, selector, preview, videoCapture);

                PendingRecording pending = videoCapture.getOutput()
                        .prepareRecording(this, new FileOutputOptions.Builder(noteFile).build());
                // звук включаю только если есть разрешение на микрофон, иначе start() упадёт
                if (granted(Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled();
                // "постоянная" запись: при перепривязке к другой камере не обрывается.
                // останавливаем её только явно через stop() (см. stopVideoNote / releaseCamera)
                pending = pending.asPersistentRecording();
                // события записи приходят в главный поток. мне нужен только Finalize - файл полностью дописан
                noteRecording = pending.start(ContextCompat.getMainExecutor(this), event -> {
                    if (event instanceof VideoRecordEvent.Finalize) {
                        onNoteFinalized((VideoRecordEvent.Finalize) event);
                    }
                });
            } catch (Exception e) {
                releaseCamera();
                if (recState != RecState.IDLE) {
                    recState = RecState.IDLE;
                    hideRecordingUi();
                }
                Toast.makeText(this, "Камера недоступна: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // файл дописывается асинхронно, отправка в onNoteFinalized
    private void stopVideoNote(boolean send, int seconds) {
        // запоминаю решение "отправлять или нет", а саму отправку делаю, когда придёт Finalize
        noteOverlay.setVisibility(View.GONE);
        noteSendOnFinalize = send;
        noteSeconds = seconds;
        if (noteRecording != null) {
            noteRecording.stop();
            noteRecording = null;
        } else {
            // запись ещё не успела начаться (камера запускалась) - помечаю, чтобы она и не началась
            noteAborted = true;
            releaseCamera();
        }
    }

    // файл кружка дописан. отправляю, только если просили отправить, ошибки нет и файл не пустой.
    // иначе удаляю временный файл, чтобы кэш не забивался
    private void onNoteFinalized(VideoRecordEvent.Finalize event) {
        releaseCamera();
        if (noteSendOnFinalize && !event.hasError() && noteFile != null && noteFile.length() > 0) {
            FileUploadHelper.uploadFile(this, noteFile, "video/mp4", uploadThenSend("VIDEO_NOTE", noteSeconds));
        } else {
            if (noteSendOnFinalize) {
                Toast.makeText(this, "Не удалось записать кружок, попробуйте ещё раз", Toast.LENGTH_SHORT).show();
            }
            if (noteFile != null) noteFile.delete();
        }
    }

    private void releaseCamera() {
        // постоянная запись сама при отвязке камеры не останавливается - глушим явно, чтобы не писала в фоне
        if (noteRecording != null) {
            noteRecording.stop();
            noteRecording = null;
        }
        if (cameraProvider != null) cameraProvider.unbindAll();
        flipCameraButton.setVisibility(View.GONE);
    }

    // смена камеры прямо во время записи. паузу НЕ ставим - по совету инженеров CameraX от неё
    // расходятся звук и видео; просто перепривязываем тот же VideoCapture к другой камере
    private void flipCamera() {
        if (cameraProvider == null || noteVideoCapture == null || notePreviewUseCase == null) return;
        boolean toFront = !noteFrontCamera;
        CameraSelector selector = toFront ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
        try {
            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle(this, selector, notePreviewUseCase, noteVideoCapture);
            noteFrontCamera = toFront;
        } catch (Exception e) {
            // не вышло - возвращаемся на прежнюю камеру, запись при этом продолжается
            try {
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this,
                        noteFrontCamera ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA,
                        notePreviewUseCase, noteVideoCapture);
            } catch (Exception ignored) { }
            Toast.makeText(this, "Не удалось переключить камеру", Toast.LENGTH_SHORT).show();
        }
    }

    // кнопка записи при удержании увеличивается в 1.35 раза - разрешаем всем родителям рисовать её за своими краями,
    // иначе увеличенный круг обрезается
    private void allowRecordButtonToOverflow() {
        View v = recordButton;
        while (v.getParent() instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v.getParent();
            g.setClipChildren(false);
            g.setClipToPadding(false);
            v = g;
        }
    }

    // во время записи вместо поля ввода показываю полоску с таймером, кнопка увеличивается и краснеет
    private void showRecordingUi() {
        attachButton.setVisibility(View.INVISIBLE);
        messageEditText.setVisibility(View.INVISIBLE);
        recordingBar.setVisibility(View.VISIBLE);
        recordingBar.setPaddingRelative(recordingBar.getPaddingStart(), 0, (int) dp(72), 0);
        discardButton.setVisibility(View.GONE);
        sendRecordingButton.setVisibility(View.GONE);
        slideToCancelText.setText("‹  Влево - отмена");
        slideToCancelText.setTranslationX(0);
        lockIndicator.setTranslationY(0);
        lockIndicator.setVisibility(View.VISIBLE);

        recordButton.setBackgroundResource(R.drawable.bg_record_button_active);
        recordButton.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        recordButton.animate().scaleX(1.35f).scaleY(1.35f).setDuration(150).start();

        // таймер обновляется 4 раза в секунду: пишет время, мигает красной точкой
        // и сам завершает запись, если дошли до лимита длины
        recordingTimeText.setText("0:00");
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                if (recState == RecState.IDLE) return;
                long ms = SystemClock.elapsedRealtime() - recordingStartedAt;
                long s = ms / 1000;
                recordingTimeText.setText(String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60));
                recordDot.setAlpha((s % 2 == 0) ? 1f : 0.3f);
                long max = activeMode == Mode.VOICE ? MAX_VOICE_MS : MAX_NOTE_MS;
                if (ms >= max) {
                    finishRecording(true);
                    return;
                }
                handler.postDelayed(this, 250);
            }
        };
        handler.post(timerRunnable);
    }

    // вернуть всё как было: убрать таймер и полоску, показать поле ввода, уменьшить кнопку обратно
    private void hideRecordingUi() {
        if (timerRunnable != null) handler.removeCallbacks(timerRunnable);
        recordingBar.setVisibility(View.GONE);
        lockIndicator.setVisibility(View.GONE);
        attachButton.setVisibility(View.VISIBLE);
        messageEditText.setVisibility(View.VISIBLE);
        recordButton.setVisibility(messageEditText.getText().toString().trim().isEmpty() ? View.VISIBLE : View.GONE);
        recordButton.setBackground(recordButtonDefaultBg);
        recordButton.setImageTintList(null);
        recordButton.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
    }

    // выдано ли разрешение (микрофон / камера)
    private boolean granted(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    // dp -> пиксели, чтобы пороги свайпа были одинаковые на любом экране
    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }


    // общий ответ на отправку любого сообщения. сервер возвращает созданное сообщение - сразу добавляю в список.
    // appendMessages не добавит дубль, если опрос уже успел это сообщение подтянуть (возвращает сколько реально добавил)
    private Callback<Chat.Message> simpleSendCallback() {
        return new Callback<Chat.Message>() {
            @Override
            public void onResponse(Call<Chat.Message> call, Response<Chat.Message> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                if (response.isSuccessful() && response.body() != null) {
                    if (adapter.appendMessages(Collections.singletonList(response.body())) > 0) scrollToBottom();
                } else {
                    Toast.makeText(ChatActivity.this, ApiErrors.message(response, "Не удалось отправить сообщение"),
                            Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<Chat.Message> call, Throwable t) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                Toast.makeText(ChatActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
            }
        };
    }

    // при открытии - только последние сообщения (экран + 50%), старые подгружаются при прокрутке вверх
    private int messagePageSize;
    private boolean hasOlder = false, loadingOlder = false;

    // загрузка последней порции сообщений (null вместо id = "самые новые"). заменяет весь список и листает вниз
    private void loadAllMessages() {
        // размер порции считаю один раз. 60 - примерная высота одного сообщения в dp
        if (messagePageSize == 0) messagePageSize = com.lunarforge.market.util.Paging.pageSize(this, 60);
        ApiClient.getApiService(this).messagesPage(threadId, null, messagePageSize).enqueue(new Callback<List<Chat.Message>>() {
            @Override
            public void onResponse(Call<List<Chat.Message>> call, Response<List<Chat.Message>> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                if (response.isSuccessful() && response.body() != null) {
                    adapter.submitList(response.body());
                    // пришла полная порция - значит выше, скорее всего, есть ещё старые
                    hasOlder = response.body().size() >= messagePageSize;
                    scrollToBottom();
                }
            }

            @Override
            public void onFailure(Call<List<Chat.Message>> call, Throwable t) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                Toast.makeText(ChatActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
            }
        });
    }

    // опрос сервера: каждые 3 секунды спрашиваю новые сообщения. runnable сам себя перезапускает через postDelayed.
    // вебсокеты не делал - для учебного проекта опроса хватает, а он проще
    private void startPolling() {
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                pollNewMessages();
                handler.postDelayed(this, POLL_INTERVAL_MS);
            }
        };
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
    }

    // прошу у сервера только сообщения новее последнего, что у меня есть (по времени отправки).
    // если список пустой (чат новый или первая загрузка не удалась) - просто гружу всё заново
    private void pollNewMessages() {
        String after = adapter.lastMessageTime();
        if (after == null) {
            loadAllMessages();
            return;
        }
        ApiClient.getApiService(this).messages(threadId, after).enqueue(new Callback<List<Chat.Message>>() {
            @Override
            public void onResponse(Call<List<Chat.Message>> call, Response<List<Chat.Message>> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                if (response.isSuccessful() && response.body() != null && !response.body().isEmpty()) {
                    if (adapter.appendMessages(response.body()) > 0) scrollToBottom();
                }
            }

            @Override
            public void onFailure(Call<List<Chat.Message>> call, Throwable t) {
                // молча: через 3 секунды будет следующая попытка, тостами каждые 3 сек спамить нельзя
            }
        });
    }

    // прокрутка к последнему сообщению
    private void scrollToBottom() {
        if (!adapter.isEmpty()) recyclerView.scrollToPosition(adapter.getItemCount() - 1);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // сначала снимаю старый опрос, если он был, иначе после нескольких onResume их бы крутилось несколько.
        // и сразу проверяю новые, чтобы после возврата на экран не ждать 3 секунды
        if (pollRunnable != null) handler.removeCallbacks(pollRunnable);
        startPolling();
        if (!adapter.isEmpty()) pollNewMessages();
    }

    // ушли с экрана - выключаем опрос, отменяем запись, глушим звук
    @Override
    protected void onPause() {
        super.onPause();
        if (pollRunnable != null) handler.removeCallbacks(pollRunnable);
        handler.removeCallbacks(holdRunnable);
        if (recState != RecState.IDLE) finishRecording(false);
        adapter.stopPlayback();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // null = убрать вообще все отложенные задачи этого handler, чтобы ничего не выполнилось на мёртвом экране
        handler.removeCallbacksAndMessages(null);
        releaseVoiceRecorder();
        releaseCamera();
        adapter.stopPlayback();
    }

    // аватар собеседника в шапке. берём из его профиля, так работает откуда бы чат ни открыли
    private void loadHeaderAvatar(long userId) {
        ApiClient.getApiService(this).publicProfile(userId).enqueue(new Callback<com.lunarforge.market.model.PublicProfile>() {
            @Override
            public void onResponse(Call<com.lunarforge.market.model.PublicProfile> call,
                                   Response<com.lunarforge.market.model.PublicProfile> response) {
                if (isFinishing() || isDestroyed() || !response.isSuccessful() || response.body() == null) return;
                com.lunarforge.market.util.Avatars.load((android.widget.ImageView) findViewById(R.id.headerAvatar),
                        response.body().avatarUrl, response.body().nickname);
            }

            @Override
            public void onFailure(Call<com.lunarforge.market.model.PublicProfile> call, Throwable t) {
                // не страшно, останется заглушка
            }
        });
    }

    // на android 12+ конструктор без контекста устарел
    @SuppressWarnings("deprecation")
    private MediaRecorder newMediaRecorder() {
        return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                ? new MediaRecorder(this) : new MediaRecorder();
    }

    // прокрутили вверх - подгружаем порцию постарше. LinearLayoutManager сам держит позицию,
    // поэтому то, что человек сейчас читает, не прыгает
    private void loadOlderMessages() {
        // прошу сообщения старше самого верхнего (по его id). флаг loadingOlder - чтобы при прокрутке
        // не ушло несколько одинаковых запросов подряд
        Long first = adapter.firstMessageId();
        if (loadingOlder || !hasOlder || first == null) return;
        loadingOlder = true;
        ApiClient.getApiService(this).messagesPage(threadId, first, messagePageSize).enqueue(new Callback<List<Chat.Message>>() {
            @Override
            public void onResponse(Call<List<Chat.Message>> call, Response<List<Chat.Message>> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                loadingOlder = false;
                if (!response.isSuccessful() || response.body() == null) return;
                hasOlder = response.body().size() >= messagePageSize;
                // добавляю в начало списка
                adapter.prependMessages(response.body());
            }

            @Override
            public void onFailure(Call<List<Chat.Message>> call, Throwable t) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                loadingOlder = false;
            }
        });
    }
}
