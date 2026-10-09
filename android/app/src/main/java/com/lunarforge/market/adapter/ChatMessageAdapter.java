package com.lunarforge.market.adapter;

import com.lunarforge.market.util.MediaCache;
import com.lunarforge.market.util.VideoThumbs;

import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.lunarforge.market.R;
import com.lunarforge.market.api.ApiClient;
import com.lunarforge.market.model.Chat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// адаптер для RecyclerView со списком сообщений в чате. 3 вида строк: мои (справа), чужие (слева), бот (по центру).
// кружки играют прямо в круге, голосовые тоже тут. ChatActivity создаёт его, отдаёт сообщения
// через submitList/appendMessages/prependMessages и вызывает stopPlayback, когда уходит с экрана
// кружки играют прямо в круге, голосовые тоже тут
public class ChatMessageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_SENT = 1;
    private static final int TYPE_RECEIVED = 2;
    private static final int TYPE_SYSTEM = 3;

    // username бота на сервере, по нему узнаю системные сообщения
    public static final String BOT_USERNAME = "lunarbot";

    // id уже показанных сообщений - чтобы одно и то же сообщение не появилось в списке два раза
    private final java.util.Set<Long> shownIds = new java.util.HashSet<>();

    private final List<Chat.Message> messages = new ArrayList<>();
    // мой id нужен, чтобы понять какие сообщения мои (рисовать справа)
    private final long myUserId;

    // плеер голосового, которое сейчас играет. одновременно играет максимум одно голосовое
    private MediaPlayer activePlayer;

    // всё для кружка, который сейчас играет: плеер, TextureView куда рисуется видео, иконка play и кольцо прогресса.
    // держу ссылки, чтобы при нажатии на другой кружок остановить предыдущий
    private MediaPlayer notePlayer;
    private android.view.TextureView activeNoteTexture;
    private View activeNoteIcon;
    private com.lunarforge.market.ui.chat.NoteRingView activeNoteRing;
    // кольцо прогресса обновляем ~20 раз в секунду, пока кружок играет
    private final android.os.Handler ringHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable ringTick = new Runnable() {
        @Override
        public void run() {
            // кружок уже остановили - перестаю тикать, иначе крутилось бы вечно
            if (notePlayer == null || activeNoteRing == null) return;
            try {
                int dur = notePlayer.getDuration();
                // прогресс от 0 до 1 = текущая позиция / длительность
                if (dur > 0) activeNoteRing.setProgress(notePlayer.getCurrentPosition() / (float) dur);
            } catch (IllegalStateException ignored) {
                return; // плеер уже освобождён
            }
            // перезапускаю себя через 50 мс - так и получается ~20 раз в секунду
            ringHandler.postDelayed(this, 50);
        }
    };
    // кнопка play/pause у голосового, которое сейчас играет (чтобы потом вернуть ей иконку play)
    private ImageButton activePlayButton;

    public ChatMessageAdapter(long myUserId) {
        this.myUserId = myUserId;
    }

    // полная замена списка (первая загрузка чата). заодно выкидываю дубли по id
    public void submitList(List<Chat.Message> newMessages) {
        messages.clear();
        shownIds.clear();
        for (Chat.Message m : newMessages) {
            if (shownIds.add(m.id)) messages.add(m);
        }
        // notifyDataSetChanged перерисовывает всё - для первой загрузки нормально
        notifyDataSetChanged();
    }

    // одно и то же сообщение может прийти два раза (ответ на отправку + опрос), поэтому фильтр по
    // id
    public int appendMessages(List<Chat.Message> newOnes) {
        // новые сообщения добавляю в конец и сообщаю адаптеру только о вставленном диапазоне,
        // так список не дёргается и не перерисовывается целиком. возвращаю сколько реально добавилось (для прокрутки вниз)
        int start = messages.size();
        for (Chat.Message m : newOnes) {
            if (shownIds.add(m.id)) messages.add(m);
        }
        int added = messages.size() - start;
        if (added > 0) {
            notifyItemRangeInserted(start, added);
            if (start > 0) notifyItemChanged(start - 1); // бывший последний: время и угол облака зависят от соседа
        }
        return added;
    }

    public boolean isEmpty() {
        return messages.isEmpty();
    }

    // старые сообщения (прокрутили вверх) - в начало. возвращает, сколько реально добавлено
    public int prependMessages(List<Chat.Message> older) {
        List<Chat.Message> fresh = new ArrayList<>();
        for (Chat.Message m : older) {
            if (shownIds.add(m.id)) fresh.add(m);
        }
        // все уже были - ничего не делаю
        if (fresh.isEmpty()) return 0;
        messages.addAll(0, fresh);
        notifyItemRangeInserted(0, fresh.size());
        notifyItemChanged(fresh.size()); // бывшее первое: ник/аватар и углы зависят от нового соседа сверху
        return fresh.size();
    }

    // id самого старого загруженного сообщения - с него подгружаю следующую страницу истории
    public Long firstMessageId() {
        return messages.isEmpty() ? null : messages.get(0).id;
    }

    // время последнего сообщения - по нему опрос сервера спрашивает "что нового после этого"
    public String lastMessageTime() {
        return messages.isEmpty() ? null : messages.get(messages.size() - 1).sentAt;
    }

    // бота определяем по @lunarbot, НЕ по нику - ники не уникальные
    @Override
    public int getItemViewType(int position) {
        Chat.Message m = messages.get(position);
        if (BOT_USERNAME.equals(m.senderUsername)) return TYPE_SYSTEM;
        return m.senderId == myUserId ? TYPE_SENT : TYPE_RECEIVED;
    }

    // создаю строку нужного вида: для каждого типа своя разметка
    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = viewType == TYPE_SYSTEM ? R.layout.item_message_system
                : viewType == TYPE_SENT ? R.layout.item_message_sent : R.layout.item_message_received;
        View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new VH(v);
    }

    // строки переиспользуются, поэтому сначала всё сбрасываем, потом показываем нужное
    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Chat.Message message = messages.get(position);
        VH vh = (VH) holder;

        vh.time.setText(formatTime(message.sentAt));

        // старую подпись сотрудника заменила строка "аватар + ник" (в ней и значок модератора/создателя)
        if (vh.senderLabel != null) vh.senderLabel.setVisibility(View.GONE);
        // у бота только текст, вложений и группировки нет
        if (getItemViewType(position) == TYPE_SYSTEM) {
            vh.text.setText(message.text);
            return;
        }
        bindGrouping(vh, message, position);

        // эта строка переиспользуется, а в ней сейчас играл кружок - останавливаю, иначе он продолжит играть
        // в строке, где уже совсем другое сообщение
        if (vh.noteTexture != null && vh.noteTexture == activeNoteTexture) stopNote();
        vh.attachmentImageContainer.setVisibility(View.GONE);
        makeRectangular(vh.attachmentImageContainer);
        // строка могла раньше показывать видео: забываем, чьё превью ждём, иначе оно перерисует чужую картинку
        vh.attachmentImage.setTag(R.id.videoThumbTag, null);
        vh.attachmentImage.setBackground(null); // подложку ставим ниже только видео и кружкам
        if (vh.noteRing != null) vh.noteRing.setVisibility(View.GONE);
        vh.voiceContainer.setVisibility(View.GONE);
        vh.videoPlayIcon.setVisibility(View.GONE);

        boolean hasText = message.text != null && !message.text.trim().isEmpty();
        vh.text.setVisibility(hasText ? View.VISIBLE : View.GONE);
        if (hasText) vh.text.setText(message.text);

        // вложения нет - дальше делать нечего
        if (message.attachmentType == null || message.attachmentUrl == null) {
            return;
        }

        // с сервера приходит относительный путь, превращаю в полный адрес
        String fullUrl = ApiClient.absoluteUrl(message.attachmentUrl);

        switch (message.attachmentType) {
            case "PHOTO":
                vh.attachmentImageContainer.setVisibility(View.VISIBLE);
                // Glide сам грузит картинку в фоне, кэширует и отменяет загрузку, если строку переиспользовали
                Glide.with(vh.itemView).load(fullUrl).into(vh.attachmentImage);
                vh.attachmentImageContainer.setOnClickListener(v -> openViewer(v, fullUrl, false));
                break;
            case "VIDEO":
                vh.attachmentImageContainer.setVisibility(View.VISIBLE);
                vh.videoPlayIcon.setVisibility(View.VISIBLE);
                vh.attachmentImage.setBackgroundColor(0xFF1B2138); // пока нет кадра - тёмный фон, а не белый пузырь
                VideoThumbs.load(vh.attachmentImage, fullUrl); // первый кадр (Glide по ссылке на видео не умеет)
                vh.attachmentImageContainer.setOnClickListener(v -> openViewer(v, fullUrl, true));
                break;
            case "VIDEO_NOTE":
                vh.attachmentImageContainer.setVisibility(View.VISIBLE);
                vh.videoPlayIcon.setVisibility(View.VISIBLE);
                makeCircular(vh.attachmentImageContainer, 200);
                vh.attachmentImage.setBackgroundColor(0xFF1B2138); // пока кадр не готов - тёмный круг, а не белый
                VideoThumbs.load(vh.attachmentImage, fullUrl); // первый кадр - видно, с чего начинается кружок
                vh.attachmentImageContainer.setOnClickListener(v -> toggleNote(vh, fullUrl));
                MediaCache.prefetch(vh.itemView.getContext(), fullUrl); // к нажатию уже скачан
                break;
            case "VOICE":
                vh.voiceContainer.setVisibility(View.VISIBLE);
                int duration = message.attachmentDurationSeconds != null ? message.attachmentDurationSeconds : 0;
                vh.voiceDuration.setText(String.format(Locale.getDefault(), "%d:%02d", duration / 60, duration % 60));
                vh.voicePlayButton.setImageResource(R.drawable.ic_play_circle);
                vh.voicePlayButton.setOnClickListener(v -> togglePlayback(fullUrl, vh.voicePlayButton));
                MediaCache.prefetch(vh.itemView.getContext(), fullUrl); // к нажатию уже скачано
                break;
        }
    }

    // нажатие на кружок. варианты: тот же кружок (пауза/продолжить/отмена загрузки) или новый (запускаю его)
    private void toggleNote(VH vh, String url) {
        if (vh.noteTexture == null) return;
        if (activeNoteTexture == vh.noteTexture) {
            if (notePlayer == null) { // ещё качается - второй тап отменяет
                stopNote();
                return;
            }
            try {
                if (notePlayer.isPlaying()) { // как в телеге: тап = пауза, кольцо и кадр остаются
                    notePlayer.pause();
                    ringHandler.removeCallbacks(ringTick);
                    if (activeNoteRing != null) activeNoteRing.setAutoHide(false); // на паузе кольцо видно
                    if (activeNoteIcon != null) activeNoteIcon.setVisibility(View.VISIBLE);
                } else {                      // ещё тап - продолжить с того же места
                    notePlayer.start();
                    if (activeNoteIcon != null) activeNoteIcon.setVisibility(View.GONE);
                    if (activeNoteRing != null) activeNoteRing.setAutoHide(true); // играет - кольцо прячем
                    ringHandler.removeCallbacks(ringTick);
                    ringHandler.post(ringTick);
                }
            } catch (IllegalStateException e) {
                stopNote();
            }
            return;
        }
        // новый кружок: останавливаю всё что играло (и голосовое, и прошлый кружок) и запоминаю новый как активный
        stopPlayback();
        activeNoteTexture = vh.noteTexture;
        activeNoteIcon = vh.videoPlayIcon;
        activeNoteRing = vh.noteRing;
        if (vh.noteRing != null) {
            vh.noteRing.setProgress(0f);
            vh.noteRing.setVisibility(View.VISIBLE);
            vh.noteRing.setAutoHide(true); // кольцо невидимо, пока не начнут перематывать
            // перемотка пальцем по кольцу
            vh.noteRing.setOnSeekListener(fraction -> {
                if (notePlayer == null) return;
                try {
                    int dur = notePlayer.getDuration();
                    if (dur <= 0) return;
                    int ms = (int) (fraction * dur);
                    // SEEK_CLOSEST есть только с android 8 (api 26), он перематывает точно на кадр. на старых - обычный seekTo
                    if (android.os.Build.VERSION.SDK_INT >= 26) notePlayer.seekTo(ms, MediaPlayer.SEEK_CLOSEST);
                    else notePlayer.seekTo(ms);
                } catch (IllegalStateException ignored) {
                    // плеер ещё не готов
                }
            });
        }
        vh.noteTexture.setVisibility(View.VISIBLE);
        vh.videoPlayIcon.setVisibility(View.GONE);

        // видео рисуется на Surface от TextureView. если он ещё не готов - жду onSurfaceTextureAvailable
        android.view.TextureView texture = vh.noteTexture;
        if (texture.isAvailable() && texture.getSurfaceTexture() != null) {
            startNotePlayer(texture, texture.getSurfaceTexture(), url);
        } else {
            texture.setSurfaceTextureListener(new android.view.TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(@NonNull android.graphics.SurfaceTexture st, int w, int h) {
                    // проверяю что пока ждали, пользователь не переключился на другой кружок
                    if (activeNoteTexture == texture) startNotePlayer(texture, st, url);
                }
                @Override public void onSurfaceTextureSizeChanged(@NonNull android.graphics.SurfaceTexture st, int w, int h) {}
                // поверхность уничтожилась (строку убрали с экрана) - плеер надо остановить, ему больше некуда рисовать
                @Override public boolean onSurfaceTextureDestroyed(@NonNull android.graphics.SurfaceTexture st) {
                    if (activeNoteTexture == texture) stopNote();
                    return true;
                }
                @Override public void onSurfaceTextureUpdated(@NonNull android.graphics.SurfaceTexture st) {}
            });
        }
    }

    // кружок играем из файла в кэше (см. MediaCache) - стартует сразу, а не после переговоров плеера с сервером
    private void startNotePlayer(android.view.TextureView texture, android.graphics.SurfaceTexture st, String url) {
        MediaCache.get(texture.getContext(), url, new MediaCache.Ready() {
            @Override
            public void onReady(java.io.File file) {
                if (activeNoteTexture != texture) return; // пока качали - остановили или включили другой
                try {
                    // плеер сразу кладу в notePlayer, чтобы stopNote мог его освободить, даже если он ещё готовится
                    MediaPlayer player = new MediaPlayer();
                    notePlayer = player;
                    player.setSurface(new android.view.Surface(st));
                    player.setDataSource(file.getAbsolutePath());
                    player.setOnVideoSizeChangedListener((mp, w, h) -> centerCrop(texture, w, h));
                    // prepareAsync готовит плеер в фоне, а когда готов - стартую и запускаю обновление кольца
                    player.setOnPreparedListener(mp -> {
                        mp.start();
                        ringHandler.removeCallbacks(ringTick);
                        ringHandler.post(ringTick);
                    });
                    player.setOnCompletionListener(mp -> stopNote());
                    // при ошибке плеера просто останавливаю кружок, return true - ошибку обработал сам
                    player.setOnErrorListener((mp, what, extra) -> { stopNote(); return true; });
                    player.prepareAsync();
                } catch (Exception e) {
                    stopNote();
                }
            }

            @Override
            public void onError() {
                if (activeNoteTexture == texture) stopNote();
            }
        });
    }

    // камера пишет 4:3, а круг квадратный. без этого видео сплющивалось.
    // растягиваем чтобы заполнить круг, лишнее обрезается
    private static void centerCrop(android.view.TextureView view, int videoW, int videoH) {
        float viewW = view.getWidth(), viewH = view.getHeight();
        if (videoW <= 0 || videoH <= 0 || viewW <= 0 || viewH <= 0) return;
        // беру больший масштаб из двух, чтобы видео закрыло весь круг (как centerCrop у картинок)
        float scale = Math.max(viewW / videoW, viewH / videoH);
        android.graphics.Matrix m = new android.graphics.Matrix();
        // setTransform растягивает картинку относительно центра view, края за кругом обрежутся
        m.setScale(videoW * scale / viewW, videoH * scale / viewH, viewW / 2f, viewH / 2f);
        view.setTransform(m);
    }

    // останавливаю кружок и возвращаю строку в исходный вид (превью + иконка play)
    private void stopNote() {
        if (notePlayer != null) {
            // release освобождает плеер и декодер. если не освобождать, после нескольких кружков плееры кончатся
            try { notePlayer.release(); } catch (RuntimeException ignored) { }
            notePlayer = null;
        }
        ringHandler.removeCallbacks(ringTick);
        if (activeNoteRing != null) {
            activeNoteRing.setOnSeekListener(null);
            activeNoteRing.setVisibility(View.GONE);
        }
        if (activeNoteTexture != null) activeNoteTexture.setVisibility(View.GONE);
        if (activeNoteIcon != null) activeNoteIcon.setVisibility(View.VISIBLE);
        activeNoteTexture = null;
        activeNoteIcon = null;
        activeNoteRing = null;
    }

    // вызывать при уходе с экрана, иначе звук продолжает играть
    public void stopPlayback() {
        ringHandler.removeCallbacks(ringTick); // явно: ушли с экрана - кольцо больше не обновляем
        stopNote();
        if (activePlayer != null) {
            try { activePlayer.release(); } catch (RuntimeException ignored) { }
            activePlayer = null;
        }
        if (activePlayButton != null) {
            activePlayButton.setImageResource(R.drawable.ic_play_circle);
            activePlayButton = null;
        }
    }

    // делаю контейнер квадратным нужного размера и обрезаю по кругу через outline
    private void makeCircular(View container, int sizeDp) {
        int px = (int) (sizeDp * container.getResources().getDisplayMetrics().density);
        ViewGroup.LayoutParams lp = container.getLayoutParams();
        lp.width = px;
        lp.height = px;
        container.setLayoutParams(lp);
        container.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        container.setClipToOutline(true);
    }

    // обязательно сбрасывать форму - строка могла до этого быть кружком
    private void makeRectangular(View container) {
        float d = container.getResources().getDisplayMetrics().density;
        ViewGroup.LayoutParams lp = container.getLayoutParams();
        lp.width = (int) (200 * d);
        lp.height = (int) (150 * d);
        container.setLayoutParams(lp);
        container.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        container.setClipToOutline(false);
    }

    // фото и видео открываем внутри приложения (зум, плеер, кнопка "Скачать")
    private void openViewer(View view, String url, boolean isVideo) {
        stopPlayback(); // не играть голосовое/кружок поверх видео
        Intent intent = new Intent(view.getContext(), com.lunarforge.market.ui.chat.MediaViewerActivity.class);
        intent.putExtra(com.lunarforge.market.ui.chat.MediaViewerActivity.EXTRA_URL, url);
        intent.putExtra(com.lunarforge.market.ui.chat.MediaViewerActivity.EXTRA_IS_VIDEO, isVideo);
        view.getContext().startActivity(intent);
    }

    // голосовое: тоже из файла в кэше. иконка "пауза" ставится сразу, пока файл докачивается
    // нажатие на голосовое. если играет это же - останавливаю, если другое - останавливаю старое и включаю новое
    private void togglePlayback(String url, ImageButton button) {
        // кружок и голосовое одновременно не играют
        stopNote();
        if (activePlayButton != null) { // что-то уже играет или качается
            boolean wasThisOne = button == activePlayButton;
            if (activePlayer != null) {
                try { activePlayer.release(); } catch (RuntimeException ignored) { }
                activePlayer = null;
            }
            activePlayButton.setImageResource(R.drawable.ic_play_circle);
            activePlayButton = null;
            if (wasThisOne) return; // нажали на то же самое - это был "стоп"
        }
        activePlayButton = button;
        button.setImageResource(R.drawable.ic_pause_circle);
        // MediaCache скачивает файл (или берёт уже скачанный) и вызывает onReady
        MediaCache.get(button.getContext(), url, new MediaCache.Ready() {
            @Override
            public void onReady(java.io.File file) {
                if (activePlayButton != button) return; // пока качали - нажали стоп или другое голосовое
                try {
                    MediaPlayer player = new MediaPlayer();
                    player.setDataSource(file.getAbsolutePath());
                    // доиграло - возвращаю иконку play и освобождаю плеер
                    player.setOnCompletionListener(mp -> {
                        button.setImageResource(R.drawable.ic_play_circle);
                        mp.release();
                        if (activePlayer == mp) {
                            activePlayer = null;
                            activePlayButton = null;
                        }
                    });
                    player.setOnErrorListener((mp, what, extra) -> {
                        button.setImageResource(R.drawable.ic_play_circle);
                        if (activePlayer == mp) {
                            activePlayer = null;
                            activePlayButton = null;
                        }
                        mp.release();
                        return true;
                    });
                    player.setOnPreparedListener(MediaPlayer::start);
                    activePlayer = player;
                    player.prepareAsync();
                } catch (IOException e) {
                    button.setImageResource(R.drawable.ic_play_circle);
                    activePlayButton = null;
                }
            }

            @Override
            public void onError() {
                if (activePlayButton != button) return;
                button.setImageResource(R.drawable.ic_play_circle);
                activePlayButton = null;
            }
        });
    }

    // из "2026-10-05T11:32:00Z" беру символы 11..16, то есть "11:32".
    // часовой пояс тут не учитывается - показывается время как пришло с сервера
    private String formatTime(String isoInstant) {
        if (isoInstant == null || isoInstant.length() < 16) return "";
        return isoInstant.substring(11, 16);
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    // ViewHolder держит ссылки на вьюшки строки, чтобы не вызывать findViewById при каждой прокрутке.
    // у разных видов строк каких-то вьюшек нет - тогда там null, поэтому выше везде проверки на null
    static class VH extends RecyclerView.ViewHolder {
        TextView text, time, voiceDuration;
        FrameLayout attachmentImageContainer;
        ImageView attachmentImage, videoPlayIcon;
        LinearLayout voiceContainer;
        ImageButton voicePlayButton;
        android.view.TextureView noteTexture;
        TextView senderLabel;
        View senderHeader;
        com.lunarforge.market.ui.chat.NoteRingView noteRing;
        ImageView senderAvatar;
        TextView senderName;

        VH(@NonNull View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.messageText);
            time = itemView.findViewById(R.id.timeText);
            attachmentImageContainer = itemView.findViewById(R.id.attachmentImageContainer);
            attachmentImage = itemView.findViewById(R.id.attachmentImage);
            videoPlayIcon = itemView.findViewById(R.id.videoPlayIcon);
            voiceContainer = itemView.findViewById(R.id.voiceContainer);
            voicePlayButton = itemView.findViewById(R.id.voicePlayButton);
            noteTexture = itemView.findViewById(R.id.noteTexture);
            senderLabel = itemView.findViewById(R.id.senderLabel);
            senderHeader = itemView.findViewById(R.id.senderHeader); // только у чужих сообщений, у своих - null
            noteRing = itemView.findViewById(R.id.noteRing);
            senderAvatar = itemView.findViewById(R.id.senderAvatar);
            senderName = itemView.findViewById(R.id.senderName);
            voiceDuration = itemView.findViewById(R.id.voiceDurationText);
        }
    }

    // ===== как в Telegram: подряд от одного человека (в пределах 5 минут) - одна "стопка" =====

    // 5 минут в миллисекундах
    private static final long GROUP_GAP_MS = 5 * 60_000;

    // два сообщения в одной группе, если от одного человека и между ними не больше 5 минут. бот в группы не попадает
    private boolean sameGroup(Chat.Message a, Chat.Message b) {
        if (a == null || b == null || a.senderId != b.senderId) return false;
        if (BOT_USERNAME.equals(a.senderUsername) || BOT_USERNAME.equals(b.senderUsername)) return false;
        long ta = com.lunarforge.market.util.Ui.parseIso(a.sentAt), tb = com.lunarforge.market.util.Ui.parseIso(b.sentAt);
        return ta >= 0 && tb >= 0 && Math.abs(tb - ta) <= GROUP_GAP_MS;
    }

    // смотрю на соседей сверху и снизу: от этого зависит, показывать ли ник/аватар, время и какие углы у облака
    private void bindGrouping(VH vh, Chat.Message message, int position) {
        Chat.Message prev = position > 0 ? messages.get(position - 1) : null;
        Chat.Message next = position + 1 < messages.size() ? messages.get(position + 1) : null;
        boolean joinPrev = sameGroup(prev, message);
        boolean joinNext = sameGroup(message, next);
        boolean mine = message.senderId == myUserId;
        android.content.Context ctx = vh.itemView.getContext();
        float d = ctx.getResources().getDisplayMetrics().density;

        // аватар + ник - над первым сообщением группы и только у чужих
        if (vh.senderHeader != null) {
            boolean show = !mine && !joinPrev;
            vh.senderHeader.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) {
                // у модераторов и админа к нику добавляется значок должности
                boolean staff = "MODERATOR".equals(message.senderRole) || "ADMIN".equals(message.senderRole);
                vh.senderName.setText(staff
                        ? com.lunarforge.market.util.Ui.staffLabel(message.senderRole, message.senderNickname)
                        : message.senderNickname);
                com.lunarforge.market.util.Avatars.load(vh.senderAvatar, message.senderAvatarUrl, message.senderNickname);
                // id в отдельную переменную, чтобы лямбда клика не держала весь объект сообщения
                long userId = message.senderId;
                vh.senderHeader.setOnClickListener(v -> {
                    Intent i = new Intent(v.getContext(), com.lunarforge.market.ui.profile.PublicProfileActivity.class);
                    i.putExtra(com.lunarforge.market.ui.profile.PublicProfileActivity.EXTRA_USER_ID, userId);
                    v.getContext().startActivity(i);
                });
            }
        }
        // внутри группы - почти без отступов, время только под последним сообщением
        vh.itemView.setPadding(vh.itemView.getPaddingLeft(), (int) ((joinPrev ? 1 : 4) * d),
                vh.itemView.getPaddingRight(), (int) ((joinNext ? 1 : 4) * d));
        vh.time.setVisibility(joinNext ? View.GONE : View.VISIBLE);
        // форма облаков: на стороне "хвоста" в местах стыка углы маленькие - стопка выглядит как одно целое
        int color = mine ? accentColor(ctx) : androidx.core.content.ContextCompat.getColor(ctx, R.color.c_surface_alt);
        applyBubble(vh.text, color, mine, joinPrev, joinNext, d);
        applyBubble(vh.voiceContainer, color, mine, joinPrev, joinNext, d);
    }

    // рисую фон облака в коде через GradientDrawable с разными радиусами углов.
    // у моих облаков "хвост" справа, у чужих слева - на этой стороне углы на стыке делаю маленькими
    private static void applyBubble(View v, int color, boolean mine, boolean joinPrev, boolean joinNext, float d) {
        if (v == null) return;
        float big = 16 * d, small = 4 * d;
        float tl, tr, br, bl;
        if (mine) {
            tl = big; bl = big;
            tr = joinPrev ? small : big;
            br = joinNext ? small : big;
        } else {
            tr = big; br = big;
            tl = joinPrev ? small : big;
            bl = joinNext ? small : big;
        }
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(color);
        // радиусы идут парами (x, y) по часовой стрелке: верх-лево, верх-право, низ-право, низ-лево
        bg.setCornerRadii(new float[]{tl, tl, tr, tr, br, br, bl, bl});
        v.setBackground(bg);
    }

    private Integer accent; // цвет моих облаков = акцент темы (берём из темы один раз)

    private int accentColor(android.content.Context ctx) {
        if (accent == null) {
            android.util.TypedValue tv = new android.util.TypedValue();
            // достаю цвет из атрибута темы, так он меняется вместе с темой приложения
            ctx.getTheme().resolveAttribute(R.attr.appAccent, tv, true);
            accent = tv.data;
        }
        return accent;
    }
}
