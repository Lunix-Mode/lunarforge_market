package com.lunarforge.market.controller;

import com.lunarforge.market.dto.ChatDtos.MessageResponse;
import com.lunarforge.market.dto.ChatDtos.SendMessageRequest;
import com.lunarforge.market.dto.ChatDtos.StartThreadRequest;
import com.lunarforge.market.dto.ChatDtos.ThreadResponse;
import com.lunarforge.market.security.AppUserDetails;
import com.lunarforge.market.service.ChatService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

// чаты. вебсокетов нет, приложение само опрашивает раз в 3 сек - для беты хватает
@RestController
@RequestMapping("/api/chat")
// все адреса начинаются с /api/chat. текущего юзера беру из JWT через @AuthenticationPrincipal, логика вся в ChatService
public class ChatController {
    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    // начать чат с продавцом (или получить уже существующий)
    @PostMapping("/threads")
    public ThreadResponse startThread(@AuthenticationPrincipal AppUserDetails principal,
                                       @RequestBody StartThreadRequest request) {
        return chatService.startOrGetThread(principal.getUser(), request.sellerId(), request.listingId());
    }

    // список моих чатов
    @GetMapping("/threads")
    public List<ThreadResponse> myThreads(@AuthenticationPrincipal AppUserDetails principal) {
        return chatService.myThreads(principal.getUser().getId());
    }

    // отправить сообщение в чат id. вложение сначала грузится отдельным запросом, сюда приходит только его url и тип
    @PostMapping("/threads/{id}/messages")
    public MessageResponse send(@AuthenticationPrincipal AppUserDetails principal,
                                 @PathVariable Long id, @RequestBody SendMessageRequest request) {
        return chatService.sendMessage(principal.getUser(), id, request.text(),
                request.attachmentUrl(), request.attachmentType(), request.attachmentDurationSeconds());
    }

    // after = отдать только новее этого времени, чтобы не гонять всю историю каждые 3 сек
    @GetMapping("/threads/{id}/messages")
    public List<MessageResponse> messages(@AuthenticationPrincipal AppUserDetails principal,
                                           @PathVariable Long id,
                                           // все параметры необязательные: after - для опроса, beforeId + limit - для подгрузки старых при прокрутке вверх
                                           @RequestParam(required = false) Instant after,
                                           @RequestParam(required = false) Long beforeId,
                                           @RequestParam(required = false) Integer limit) {
        return chatService.messages(principal.getUser(), id, after, beforeId, limit);
    }
}
