package kyrielie.printer.client.mixin;

import kyrielie.printer.client.telemetry.ServerTelemetryTracker;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @Inject(method = "handleBlockChangedAck", at = @At("TAIL"))
    private void printer$onBlockChangedAck(ClientboundBlockChangedAckPacket packet, CallbackInfo ci) {
        ServerTelemetryTracker.INSTANCE.onBlockChangedAck(packet.sequence());
    }

    @Inject(method = "handleBlockUpdate", at = @At("TAIL"))
    private void printer$onBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        ServerTelemetryTracker.INSTANCE.onBlockUpdate(packet.getPos(), packet.getBlockState());
    }

    @Inject(method = "handleContainerSetSlot", at = @At("TAIL"))
    private void printer$onContainerSetSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        ServerTelemetryTracker.INSTANCE.onContainerSetSlot(packet.getContainerId(), packet.getSlot(), packet.getItem());
    }

    @Inject(method = "handleMovePlayer", at = @At("TAIL"))
    private void printer$onMovePlayer(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        ServerTelemetryTracker.INSTANCE.onPlayerPosition(packet);
    }

    @Inject(method = "handleSystemChat", at = @At("TAIL"))
    private void printer$onSystemChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        ServerTelemetryTracker.INSTANCE.onSystemChat(packet.content(), packet.overlay());
    }
}
