package com.cmex.bolt.spot.service;

import com.cmex.bolt.spot.api.*;
import com.cmex.bolt.spot.domain.Account;
import com.cmex.bolt.spot.domain.Balance;
import com.cmex.bolt.spot.domain.Symbol;
import com.cmex.bolt.spot.repository.impl.AccountRepository;
import com.cmex.bolt.spot.util.Result;
import com.lmax.disruptor.RingBuffer;

import java.util.Optional;

public class AccountService {

    private final AccountRepository repository = new AccountRepository();

    private RingBuffer<Message> responseRingBuffer;
    private RingBuffer<Message> matchRingBuffer;

    public AccountService() {
    }

    public void on(long messageId, PlaceOrder placeOrder) {
        //锁定金额
        int accountId = placeOrder.accountId.get();
        Symbol symbol = Symbol.getSymbol(placeOrder.symbolId.get());
        Optional<Account> optional = repository.get(accountId);
        optional.ifPresentOrElse(account -> {
            //account 存在
            Result<Balance> result;
            if (placeOrder.side.get() == OrderSide.BID) {
                long volume = placeOrder.price.get() * placeOrder.quantity.get();
                result = account.freeze(symbol.getQuote().getId(), volume);
            } else {
                result = account.freeze(symbol.getBase().getId(), placeOrder.quantity.get());
            }
            if (result.isSuccess()) {
                matchRingBuffer.publishEvent((message, sequence) -> {
                    message.id.set(messageId);
                    message.type.set(EventType.PLACE_ORDER);
                    PlaceOrder payload = message.payload.asPlaceOrder;
                    payload.copy(payload);
                });
            } else {
                responseRingBuffer.publishEvent((message, sequence) -> {
                    message.id.set(messageId);
                    message.type.set(EventType.PLACE_ORDER_REJECTED);
                    message.payload.asPlaceOrderRejected.reason.set(result.reason());
                });
            }
        }, () -> {
            //account 不存在
            responseRingBuffer.publishEvent((message, sequence) -> {
                message.id.set(messageId);
                message.type.set(EventType.PLACE_ORDER_REJECTED);
                message.payload.asPlaceOrderRejected.reason.set(RejectionReason.ACCOUNT_NOT_EXIST);
            });
        });
    }

    public void on(long messageId, Increase increase) {
        int accountId = increase.accountId.get();
        Account account = repository.getOrCreate(accountId, new Account(accountId));
        Result<Balance> result = account.increase(increase.currencyId.get(), increase.amount.get());
        responseRingBuffer.publishEvent((message, sequence) -> {
            message.id.set(messageId);
            message.type.set(EventType.INCREASED);
            message.payload.asIncreased.value.set(result.value().getValue());
            message.payload.asIncreased.frozen.set(result.value().getFrozen());
        });
    }

    public void on(long messageId, Decrease decrease) {
        int accountId = decrease.accountId.get();
        Optional<Account> optional = repository.get(accountId);
        Result<Balance> result =
                optional.map(account -> account.decrease(decrease.currencyId.get(), decrease.amount.get()))
                        .orElse(Result.fail(RejectionReason.ACCOUNT_NOT_EXIST));
        responseRingBuffer.publishEvent((message, sequence) -> {
            if (result.isSuccess()) {
                message.id.set(messageId);
                message.type.set(EventType.DECREASED);
                message.payload.asDecreased.value.set(result.value().getValue());
                message.payload.asDecreased.frozen.set(result.value().getFrozen());
            } else {
                message.id.set(messageId);
                message.type.set(EventType.DECREASE_REJECTED);
                message.payload.asDecreaseRejected.reason.set(result.reason());
            }
        });
    }

    public void on(long messageId, Unfreeze unfreeze) {
        System.out.println(unfreeze);
    }

    public void on(long messageId, Cleared cleared) {
        int accountId = cleared.accountId.get();
        Optional<Account> optional = repository.get(accountId);
        Account account = optional.get();
        account.unfreezeAndDecrease(cleared.payCurrencyId.get(), cleared.payAmount.get());
        account.increase(cleared.incomeCurrencyId.get(), cleared.incomeAmount.get());
    }

    public void setMatchRingBuffer(RingBuffer<Message> matchRingBuffer) {
        this.matchRingBuffer = matchRingBuffer;
    }

    public void setResponseRingBuffer(RingBuffer<Message> responseRingBuffer) {
        this.responseRingBuffer = responseRingBuffer;
    }

}
