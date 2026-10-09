package com.lunarforge.market.controller;

import com.lunarforge.market.dto.TransferDtos.CreateTransferRequest;
import com.lunarforge.market.dto.TransferDtos.TransferResponse;
import com.lunarforge.market.security.AppUserDetails;
import com.lunarforge.market.service.TransferService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// переводы денег между пользователями (с кошелька на кошелёк).
// списание/зачисление и блокировки кошельков сделаны в TransferService
@RestController
@RequestMapping("/api/transfers")
public class TransferController {
    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    // отправить перевод: кому и сколько лежит в CreateTransferRequest, отправитель - текущий юзер
    @PostMapping
    public TransferResponse send(@AuthenticationPrincipal AppUserDetails principal, @RequestBody CreateTransferRequest request) {
        return transferService.send(principal.getUser(), request);
    }

    // история переводов, где я либо отправитель, либо получатель
    @GetMapping
    public List<TransferResponse> history(@AuthenticationPrincipal AppUserDetails principal) {
        return transferService.history(principal.getUser().getId());
    }
}
