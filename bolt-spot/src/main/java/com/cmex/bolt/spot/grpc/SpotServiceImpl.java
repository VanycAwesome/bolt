package com.cmex.bolt.spot.grpc;

import com.cmex.bolt.spot.api.*;
import com.cmex.bolt.spot.domain.Balance;
import com.cmex.bolt.spot.domain.Symbol;
import com.cmex.bolt.spot.dto.DepthDto;
import com.cmex.bolt.spot.handler.AccountDispatcher;
import com.cmex.bolt.spot.handler.MatchDispatcher;
import com.cmex.bolt.spot.service.AccountService;
import com.cmex.bolt.spot.service.MatchService;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.LifecycleAware;
import com.lmax.disruptor.LiteBlockingWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import io.grpc.stub.StreamObserver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static com.cmex.bolt.spot.grpc.SpotServiceGrpc.SpotServiceImplBase;
import static com.cmex.bolt.spot.grpc.SpotServiceProto.*;

public class SpotServiceImpl extends SpotServiceImplBase {

    private final RingBuffer<Message> accountRingBuffer;
    private final AtomicLong requestId = new AtomicLong();
    private final ConcurrentHashMap<Long, StreamObserver<?>> observers;

    private final List<AccountService> accountServices;

    private final List<MatchService> matchServices;

    public SpotServiceImpl() {
        observers = new ConcurrentHashMap<>();
        Disruptor<Message> accountDisruptor =
                new Disruptor<>(Message.FACTORY, 1024 * 1024 * 2, DaemonThreadFactory.INSTANCE,
                        ProducerType.MULTI, new LiteBlockingWaitStrategy());
        Disruptor<Message> matchDisruptor =
                new Disruptor<>(Message.FACTORY, 1024 * 1024 * 2, DaemonThreadFactory.INSTANCE,
                        ProducerType.MULTI, new LiteBlockingWaitStrategy());
        Disruptor<Message> responseDisruptor =
                new Disruptor<>(Message.FACTORY, 1024 * 1024 * 2, DaemonThreadFactory.INSTANCE,
                        ProducerType.MULTI, new LiteBlockingWaitStrategy());
        List<AccountDispatcher> accountDispatchers = createAccountDispatchers();
        List<MatchDispatcher> matchDispatchers = createMatchDispatchers();
        matchDisruptor.handleEventsWith(matchDispatchers.toArray(new MatchDispatcher[0]));
        responseDisruptor.handleEventsWith(new ResponseEventHandler());
        accountDisruptor.handleEventsWith(accountDispatchers.toArray(new AccountDispatcher[0]));
        accountRingBuffer = accountDisruptor.start();
        RingBuffer<Message> matchRingBuffer = matchDisruptor.start();
        RingBuffer<Message> responseRingBuffer = responseDisruptor.start();
        matchServices = new ArrayList<>(10);
        for (MatchDispatcher dispatcher : matchDispatchers) {
            dispatcher.getMatchService().setSequencerRingBuffer(accountRingBuffer);
            dispatcher.getMatchService().setResponseRingBuffer(responseRingBuffer);
            matchServices.add(dispatcher.getMatchService());
        }
        accountServices = new ArrayList<>(10);
        for (AccountDispatcher dispatcher : accountDispatchers) {
            dispatcher.getAccountService().setMatchRingBuffer(matchRingBuffer);
            dispatcher.getAccountService().setResponseRingBuffer(responseRingBuffer);
            dispatcher.setMatchServices(matchServices);
            accountServices.add(dispatcher.getAccountService());
        }
    }

    private List<AccountDispatcher> createAccountDispatchers() {
        List<AccountDispatcher> dispatchers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            dispatchers.add(new AccountDispatcher(10, i));
        }
        return dispatchers;
    }

    private List<MatchDispatcher> createMatchDispatchers() {
        List<MatchDispatcher> dispatchers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            dispatchers.add(new MatchDispatcher(10, i));
        }
        return dispatchers;
    }

    private AccountService getAccountService(int accountId) {
        return accountServices.get(accountId % 10);
    }

    @Override
    public void getAccount(GetAccountRequest request, StreamObserver<GetAccountResponse> responseObserver) {
        Map<Integer, SpotServiceProto.Balance> balances =
                getAccountService(request.getAccountId()).getBalances(request.getAccountId(), request.getCurrencyId()).entrySet().stream()
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                entry -> {
                                    Balance balance = entry.getValue();
                                    return SpotServiceProto.Balance.newBuilder()
                                            .setCurrency(balance.getCurrency().getName())
                                            .setFrozen(balance.getFormatFrozen())
                                            .setAvailable(balance.getFormatAvailable())
                                            .setValue(balance.getFormatValue())
                                            .build();
                                }));
        GetAccountResponse response = GetAccountResponse.newBuilder()
                .setCode(1)
                .putAllData(balances)
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    public void increase(IncreaseRequest request, StreamObserver<IncreaseResponse> responseObserver) {
        getAccountService(request.getAccountId()).getCurrency(request.getCurrencyId()).ifPresentOrElse(currency -> {
            long id = requestId.incrementAndGet();
            observers.put(id, responseObserver);
            accountRingBuffer.publishEvent((message, sequence) -> {
                message.id.set(id);
                message.type.set(EventType.INCREASE);
                Increase increase = message.payload.asIncrease;
                increase.accountId.set(request.getAccountId());
                increase.currencyId.set((short) request.getCurrencyId());
                increase.amount.set(currency.parse(request.getAmount()));
            });
        }, () -> {
            IncreaseResponse response = IncreaseResponse.newBuilder()
                    .setCode(RejectionReason.CURRENCY_NOT_EXIST.getCode())
                    .setMessage(RejectionReason.CURRENCY_NOT_EXIST.name())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        });
    }

    public void decrease(DecreaseRequest request, StreamObserver<DecreaseResponse> responseObserver) {
        getAccountService(request.getAccountId()).getCurrency(request.getCurrencyId()).ifPresentOrElse(currency -> {
            long id = requestId.incrementAndGet();
            observers.put(id, responseObserver);
            accountRingBuffer.publishEvent((message, sequence) -> {
                message.id.set(id);
                message.type.set(EventType.DECREASE);
                Decrease decrease = message.payload.asDecrease;
                decrease.accountId.set(request.getAccountId());
                decrease.currencyId.set((short) request.getCurrencyId());
                decrease.amount.set(currency.parse(request.getAmount()));
            });
        }, () -> {
            DecreaseResponse response = DecreaseResponse.newBuilder()
                    .setCode(RejectionReason.CURRENCY_NOT_EXIST.getCode())
                    .setMessage(RejectionReason.CURRENCY_NOT_EXIST.name())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        });

    }

    public void placeOrder(PlaceOrderRequest request, StreamObserver<PlaceOrderResponse> responseObserver) {
        getSymbol(request.getSymbolId()).ifPresentOrElse(symbol -> {
                    long id = requestId.incrementAndGet();
                    observers.put(id, responseObserver);
                    accountRingBuffer.publishEvent((message, sequence) -> {
                        message.id.set(id);
                        message.type.set(EventType.PLACE_ORDER);
                        PlaceOrder placeOrder = message.payload.asPlaceOrder;
                        placeOrder.symbolId.set((short) request.getSymbolId());
                        placeOrder.accountId.set(request.getAccountId());
                        placeOrder.type.set(request.getType() == PlaceOrderRequest.Type.LIMIT ?
                                OrderType.LIMIT : OrderType.MARKET);
                        placeOrder.side.set(request.getSide() == PlaceOrderRequest.Side.BID ?
                                OrderSide.BID : OrderSide.ASK);
                        placeOrder.price.set(symbol.formatPrice(request.getPrice()));
                        placeOrder.quantity.set(symbol.formatQuantity(request.getQuantity()));
                        placeOrder.volume.set(symbol.formatPrice(request.getVolume()));
                        placeOrder.takerRate.set(request.getTakerRate());
                        placeOrder.makerRate.set(request.getMakerRate());
                    });
                }, () -> {
                    PlaceOrderResponse response = PlaceOrderResponse.newBuilder()
                            .setCode(RejectionReason.SYMBOL_NOT_EXIST.getCode())
                            .setMessage(RejectionReason.SYMBOL_NOT_EXIST.name())
                            .build();
                    responseObserver.onNext(response);
                    responseObserver.onCompleted();
                }
        );
    }

    @Override
    public void cancelOrder(CancelOrderRequest request, StreamObserver<CancelOrderResponse> responseObserver) {
        long id = requestId.incrementAndGet();
        observers.put(id, responseObserver);
        accountRingBuffer.publishEvent((message, sequence) -> {
            message.id.set(id);
            message.type.set(EventType.CANCEL_ORDER);
            message.payload.asCancelOrder.orderId.set(request.getOrderId());
        });
    }

    @Override
    public void getDepth(GetDepthRequest request, StreamObserver<GetDepthResponse> responseObserver) {
        int symbolId = request.getSymbolId();
        MatchService matchService = matchServices.get(symbolId % 10);
        DepthDto dto = matchService.getDepth(symbolId);
        GetDepthResponse response = GetDepthResponse.newBuilder()
                .setCode(1)
                .setData(Depth.newBuilder().setSymbol(dto.getSymbol()).putAllAsks(dto.getAsks()).putAllBids(dto.getBids()))
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    private class ResponseEventHandler implements EventHandler<Message>, LifecycleAware {

        @SuppressWarnings("unchecked")
        @Override
        public void onEvent(Message message, long sequence, boolean endOfBatch) {
            long id = message.id.get();
            if (id > 0) {
                StreamObserver<Object> observer = (StreamObserver<Object>) observers.get(id);
                EventType type = message.type.get();
                Object response = switch (type) {
                    case ORDER_CREATED -> message.payload.asOrderCreated.get();
                    case PLACE_ORDER_REJECTED -> message.payload.asPlaceOrderRejected.get();
                    case ORDER_CANCELED -> message.payload.asOrderCanceled.get();
                    case INCREASED -> message.payload.asIncreased.get();
                    case DECREASED -> message.payload.asDecreased.get();
                    case DECREASE_REJECTED -> message.payload.asDecreaseRejected.get();
                    default -> throw new RuntimeException();
                };
                observer.onNext(response);
                observer.onCompleted();
            }
        }

        @Override
        public void onStart() {
            final Thread currentThread = Thread.currentThread();
            currentThread.setName(ResponseEventHandler.class.getSimpleName() + "-thread");
        }

        @Override
        public void onShutdown() {

        }
    }

    private Optional<Symbol> getSymbol(int symbolId) {
        return matchServices.get(symbolId % 10).getSymbol(symbolId);
    }
}
