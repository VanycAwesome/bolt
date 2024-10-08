package com.cmex.bolt.spot.api;

import com.cmex.bolt.spot.grpc.SpotServiceProto;

import static com.cmex.bolt.spot.grpc.SpotServiceProto.DecreaseResponse;

import javolution.io.Struct;

import java.util.function.Supplier;

public class DecreaseRejected extends Struct implements Supplier<DecreaseResponse> {
    public final Enum32<RejectionReason> reason = new Enum32<RejectionReason>(RejectionReason.values());

    @Override
    public DecreaseResponse get() {
        return SpotServiceProto.DecreaseResponse.newBuilder()
                .setCode(reason.get().getCode()).setMessage(reason.get().name()).build();
    }
}
