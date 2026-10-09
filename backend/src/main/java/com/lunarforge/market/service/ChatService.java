package com.lunarforge.market.service;

import com.lunarforge.market.dto.ChatDtos.MessageResponse;
import com.lunarforge.market.dto.ChatDtos.ThreadResponse;
import com.lunarforge.market.entity.ChatMessage;
import com.lunarforge.market.entity.ChatThread;
import com.lunarforge.market.entity.Listing;
import com.lunarforge.market.entity.User;
import com.lunarforge.market.exception.ApiException;
import com.lunarforge.market.repository.ChatMessageRepository;
import com.lunarforge.market.repository.ChatThreadRepository;
import com.lunarforge.market.repository.ListingRepository;
import com.lunarforge.market.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

// вся логика чатов: создание диалога, отправка сообщений, выдача истории и превью для списка.
// контроллер только достаёт текущего юзера из токена и зовёт методы отсюда.
// ещё сюда ходят OrderService и заявки - через бота пишут системные сообщения в чат сделки
@Service
public class ChatService {
    private final ChatThreadRepository threadRepository;
    private final ChatMessageRepository messageRepository;
    private final UserRepository userRepository;
    private final ListingRepository listingRepository;
    private final com.lunarforge.market.repository.TicketRepository ticketRepository;

    // по этому email ищу бота в базе (создаёт его DataSeeder при старте)
    public static final String BOT_EMAIL = "bot@lunarforge.market";

    public ChatService(ChatThreadRepository threadRepository, ChatMessageRepository messageRepository,
                        UserRepository userRepository, ListingRepository listingRepository,
                       com.lunarforge.market.repository.TicketRepository ticketRepository) {
        this.threadRepository = threadRepository;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
        this.listingRepository = listingRepository;
        this.ticketRepository = ticketRepository;
    }

    // (про сообщения от Lunar Bot - это postBotMessage ниже, там тоже если чата ещё нет - создаётся)
    @Transactional
    // чат между двумя людьми, если нет - создаём. для заявок: возврат = покупатель+продавец, жалоба = автор+бот
    // findOneBetween ищет в обе стороны (a-b и b-a), так что неважно кто из них "покупатель" в записи
    public ChatThread threadBetween(User a, User b, Long listingId) {
        return threadRepository.findOneBetween(a.getId(), b.getId()).orElseGet(() -> {
            ChatThread t = new ChatThread();
            t.setBuyer(a);
            t.setSeller(b);
            // объявление тут только для красоты (в шапке чата), если его удалили - просто без него
            if (listingId != null) listingRepository.findById(listingId).ifPresent(t::setListing);
            return threadRepository.save(t);
        });
    }

    // может вернуть null, если бота почему-то нет в базе - вызывающий код это проверяет
    public User botUser() {
        return userRepository.findByEmail(BOT_EMAIL).orElse(null);
    }

    // пишу сообщение от имени бота в чат между покупателем и продавцом (например "заказ оплачен", "деньги заморожены").
    // нет бота - тихо выхожу, заказ из-за этого падать не должен
    public void postBotMessage(User buyer, User seller, Long listingId, String text) {
        User bot = userRepository.findByEmail(BOT_EMAIL).orElse(null);
        if (bot == null) return;

        ChatThread thread = threadRepository.findOneBetween(buyer.getId(), seller.getId())
                .orElseGet(() -> {
                    ChatThread t = new ChatThread();
                    t.setBuyer(buyer);
                    t.setSeller(seller);
                    if (listingId != null) {
                        listingRepository.findById(listingId).ifPresent(t::setListing);
                    }
                    return threadRepository.save(t);
                });

        // отправитель - бот, а не кто-то из людей
        ChatMessage message = new ChatMessage();
        message.setThread(thread);
        message.setSender(bot);
        message.setText(text);
        messageRepository.save(message);

        // обновляю время последнего сообщения - по нему сортируется список чатов, чат поднимется наверх
        thread.setLastMessageAt(Instant.now());
        threadRepository.save(thread);
    }

    // сначала ищем существующий чат, чтобы не было двух чатов с одним человеком
    @Transactional
    // вызывается когда покупатель жмёт "написать продавцу" на объявлении
    public ThreadResponse startOrGetThread(User buyer, Long sellerId, Long listingId) {
        // самому себе писать нельзя (например на своём же объявлении)
        if (sellerId.equals(buyer.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Нельзя начать чат с самим собой");
        }
        User seller = userRepository.findById(sellerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Продавец не найден"));

        ChatThread thread = threadRepository.findOneBetween(buyer.getId(), sellerId)
                .orElseGet(() -> {
                    ChatThread t = new ChatThread();
                    t.setBuyer(buyer);
                    t.setSeller(seller);
                    if (listingId != null) {
                        Listing listing = listingRepository.findById(listingId).orElse(null);
                        t.setListing(listing);
                    }
                    return threadRepository.save(t);
                });

        // viewerId - тот, кто открыл; отдаю общий формат, клиент сам разберётся кто собеседник
        return toThreadResponse(thread, buyer.getId());
    }

    // список чатов юзера, уже отсортирован в запросе по lastMessageAt (свежие сверху)
    public List<ThreadResponse> myThreads(Long userId) {
        return threadRepository.findAllForUser(userId).stream()
                .map(t -> toThreadResponse(t, userId))
                .toList();
    }

    // отправка сообщения: сначала все проверки входных данных, потом права, потом сохраняю.
    // @Transactional - сообщение, время чата и таймеры заявок сохраняются вместе, либо ничего
    @Transactional
    public MessageResponse sendMessage(User sender, Long threadId, String text,
                                        String attachmentUrl, String attachmentType, Integer attachmentDurationSeconds) {
        // сообщение должно содержать хотя бы текст или вложение
        boolean hasText = text != null && !text.isBlank();
        boolean hasAttachment = attachmentUrl != null && !attachmentUrl.isBlank();
        if (!hasText && !hasAttachment) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Сообщение не может быть пустым");
        }
        // ограничение длины, чтобы не засунули в базу мегабайты текста
        if (hasText && text.length() > 4000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Сообщение слишком длинное (макс. 4000 символов)");
        }
        // вложения только с нашего сервера, левые ссылки не пускаем
        // формат как у нашего загрузчика: /files/<uuid>.<расширение>. так нельзя подсунуть ссылку на чужой сайт или ../ путь
        if (hasAttachment && !attachmentUrl.matches("^/files/[0-9a-fA-F-]{36}\\.[a-z0-9]{1,5}$")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Некорректное вложение");
        }

        ChatMessage.AttachmentType type = null;
        if (hasAttachment) {
            try {
                // строку с клиента превращаю в enum. valueOf кидает исключение на неизвестное значение - перевожу его в 400, а не 500
                type = attachmentType == null ? null : ChatMessage.AttachmentType.valueOf(attachmentType);
            } catch (IllegalArgumentException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Некорректный тип вложения");
            }
            if (type == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Укажите тип вложения (PHOTO/VIDEO/VOICE)");
            }
        }

        ChatThread thread = threadRepository.findById(threadId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Чат не найден"));
        // заблокированный юзер может писать только в поддержку
        requireNotBlockedOutsideSupport(sender, thread);

        // участник = покупатель или продавец этого чата
        boolean isParty = thread.getBuyer().getId().equals(sender.getId())
                || thread.getSeller().getId().equals(sender.getId());
        // модератор может писать только если он ответственный по открытой заявке на этот чат
        // беру заявки по этому чату, которые сейчас в работе (IN_PROGRESS) - чтобы понять, назначен ли отправитель на одну из них
        List<com.lunarforge.market.entity.Ticket> active = ticketRepository.findByThreadIdAndStatusIn(threadId,
                List.of(com.lunarforge.market.entity.Ticket.Status.IN_PROGRESS));
        // если отправитель не сторона сделки - проверяю что он ответственный модератор хотя бы по одной из этих заявок
        boolean isAssignedStaff = !isParty && active.stream()
                .anyMatch(t -> t.getAssignee() != null && t.getAssignee().getId().equals(sender.getId()));
        if (!isParty && !isAssignedStaff) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Вы не участник этого чата");
        }

        ChatMessage message = new ChatMessage();
        message.setThread(thread);
        message.setSender(sender);
        // trim чтобы не хранить пробелы по краям; пустой текст при вложении храню как null
        message.setText(hasText ? text.trim() : null);
        message.setAttachmentUrl(attachmentUrl);
        message.setAttachmentType(type);
        message.setAttachmentDurationSeconds(attachmentDurationSeconds);
        messageRepository.save(message);

        // поднимаю чат в списке
        thread.setLastMessageAt(Instant.now());
        threadRepository.save(thread);

        // таймеры для правила 8 часов: кто последний писал - стороны или модератор
        // если по чату есть заявка в работе - отмечаю кто ответил: модератор или стороны.
        // по этим временам потом проверяется, не завис ли модератор без ответа
        Instant now = Instant.now();
        for (com.lunarforge.market.entity.Ticket t : active) {
            if (isAssignedStaff) t.setLastStaffActivityAt(now);
            else t.setLastPartyMessageAt(now);
            ticketRepository.save(t);
        }

        return toMessageResponse(message);
    }

    // короткая версия без подгрузки - отдаёт новое после after или всю историю
    public List<MessageResponse> messages(User requester, Long threadId, Instant after) {
        return messages(requester, threadId, after, null, null);
    }

    // after - только новее (опрос раз в 3 сек). limit - последние N сообщений, beforeId - N сообщений старее этого
    // (подгрузка при прокрутке вверх). ничего не задано - вся переписка
    public List<MessageResponse> messages(User requester, Long threadId, Instant after, Long beforeId, Integer limit) {
        // сначала чат должен существовать, и заблокированный может читать только поддержку
        ChatThread thread = threadRepository.findById(threadId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Чат не найден"));
        requireNotBlockedOutsideSupport(requester, thread);

        boolean isParty = thread.getBuyer().getId().equals(requester.getId())
                || thread.getSeller().getId().equals(requester.getId());
        // персонал = админ или модератор
        boolean isStaff = requester.getRole() == User.Role.ADMIN || requester.getRole() == User.Role.MODERATOR;
        // модераторы читают переписку по любой заявке (чтобы разобраться ещё до того как взять её)
        if (!isParty && !(isStaff && ticketRepository.existsByThreadId(threadId))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Вы не участник этого чата");
        }

        // три режима выборки:
        List<ChatMessage> list;
        // 1) опрос новых сообщений - только то что пришло после after, по возрастанию
        if (after != null) {
            list = messageRepository.findByThreadIdAndSentAtAfterOrderBySentAtAsc(threadId, after);
        // 2) порция последних сообщений. limit зажимаю от 1 до 100, чтобы не запросили миллион за раз.
        // беру с конца (по id убыванию) - так проще получить именно последние N
        } else if (limit != null) {
            org.springframework.data.domain.PageRequest pr =
                    org.springframework.data.domain.PageRequest.of(0, Math.max(1, Math.min(100, limit)));
            list = new java.util.ArrayList<>(beforeId != null
                    ? messageRepository.findByThreadIdAndIdLessThanOrderByIdDesc(threadId, beforeId, pr)
                    : messageRepository.findByThreadIdOrderByIdDesc(threadId, pr));
            java.util.Collections.reverse(list); // в чат - от старых к новым
        // 3) ничего не передали - вся переписка целиком
        } else {
            list = messageRepository.findByThreadIdOrderBySentAtAsc(threadId);
        }

        return list.stream().map(this::toMessageResponse).toList();
    }

    // переписка между двумя людьми целиком, без проверок прав (задумывалось для админки, сейчас нигде не вызывается)
    public List<MessageResponse> adminMessages(Long buyerId, Long sellerId) {
        ChatThread thread = threadRepository.findOneBetween(buyerId, sellerId).orElse(null);
        if (thread == null) return List.of();
        return messageRepository.findByThreadIdOrderBySentAtAsc(thread.getId()).stream()
                .map(this::toMessageResponse).toList();
    }

    // для превью берём только последнее сообщение, раньше грузилась вся история - тормозило
    // собираю dto для списка чатов
    private ThreadResponse toThreadResponse(ChatThread t, Long viewerId) {
        ChatMessage last = messageRepository.findTopByThreadIdOrderBySentAtDesc(t.getId()).orElse(null);
        String preview = "";
        if (last != null) {
            // если есть текст - он и есть превью, иначе подпись по типу вложения
            if (last.getText() != null && !last.getText().isBlank()) {
                preview = last.getText();
            } else if (last.getAttachmentType() != null) {
                // switch по enum без default - если добавят новый тип, компилятор сам заставит его сюда дописать
                preview = switch (last.getAttachmentType()) {
                    case PHOTO -> "📷 Фото";
                    case VIDEO -> "🎥 Видео";
                    case VOICE -> "🎤 Голосовое сообщение";
                    case VIDEO_NOTE -> "⭕ Видеосообщение";
                };
            }
        }
        return new ThreadResponse(
                t.getId(),
                t.getBuyer().getId(),
                t.getBuyer().getNickname(),
                t.getBuyer().getAvatarUrl(),
                t.getSeller().getId(),
                t.getSeller().getNickname(),
                t.getSeller().getAvatarUrl(),
                // объявления может не быть
                t.getListing() != null ? t.getListing().getId() : null,
                t.getListing() != null ? t.getListing().getTitle() : null,
                preview,
                t.getLastMessageAt()
        );
    }

    // entity -> dto для клиента. роль отправителя отдаю, чтобы в приложении выделить сообщения модератора
    private MessageResponse toMessageResponse(ChatMessage m) {
        return new MessageResponse(
                m.getId(),
                m.getThread().getId(),
                m.getSender().getId(),
                m.getSender().getNickname(),
                m.getSender().getUsername(),
                m.getSender().getRole().name(),
                m.getText(),
                m.getAttachmentUrl(),
                m.getAttachmentType() != null ? m.getAttachmentType().name() : null,
                m.getAttachmentDurationSeconds(),
                m.getSentAt(),
                // аватар идёт последним, потому что в record MessageResponse он объявлен последним полем
                m.getSender().getAvatarUrl());
    }

    // заблокированный общается только в чате поддержки (с ботом) - там идёт его апелляция
    private void requireNotBlockedOutsideSupport(User u, ChatThread thread) {
        if (!u.isBlocked()) return;
        User bot = botUser();
        // "поддержка" это чат, где одна сторона бот, а другая сам юзер (в любом порядке)
        boolean support = bot != null
                && ((thread.getBuyer().getId().equals(bot.getId()) && thread.getSeller().getId().equals(u.getId()))
                || (thread.getSeller().getId().equals(bot.getId()) && thread.getBuyer().getId().equals(u.getId())));
        if (!support) throw new ApiException(HttpStatus.FORBIDDEN, "Аккаунт заблокирован. Писать можно только в чат поддержки");
    }
}
