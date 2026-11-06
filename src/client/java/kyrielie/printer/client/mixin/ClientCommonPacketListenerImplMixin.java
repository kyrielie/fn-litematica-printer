package kyrielie.printer.client.mixin;

import kyrielie.printer.client.telemetry.ServerTelemetryTracker;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerImplMixin {

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void printer$onSendPacket(Packet<?> packet, CallbackInfo ci) {
        try {
            kyrielie.printer.client.player.ProtocolMovementGuard.INSTANCE.observeOutgoing(packet);
            if (packet instanceof ServerboundUseItemOnPacket usePacket) {
                ServerTelemetryTracker.INSTANCE.onOutgoingUseItemOn(usePacket);
            }
        } catch (Throwable ignored) {
        }
    }
}
