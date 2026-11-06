package kyrielie.printer.client.mixin;

import com.mojang.authlib.GameProfile;
import kyrielie.printer.client.player.PrinterController;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin extends AbstractClientPlayer {

    @Shadow private float yRotLast;
    @Shadow private float xRotLast;

    @Unique private Float printer$savedYaw = null;
    @Unique private Float printer$savedPitch = null;

    protected LocalPlayerMixin(ClientLevel clientLevel, GameProfile gameProfile) {
        super(clientLevel, gameProfile);
    }

    @Inject(method = "sendPosition", at = @At("HEAD"))
    private void printer$onSendPositionHead(CallbackInfo ci) {
        try {
            LocalPlayer self = (LocalPlayer) (Object) this;
            PrinterController.LookOverride override = PrinterController.INSTANCE.getActiveLookOverride(self);
            if (override != null) {
                this.printer$savedYaw = this.getYRot();
                this.printer$savedPitch = this.getXRot();
                this.setYRot(override.getYaw());
                this.setXRot(override.getPitch());

                // Guarantee deltaYRot != 0.0 in sendPosition so the rotation is definitely transmitted
                if (this.yRotLast == override.getYaw() && this.xRotLast == override.getPitch()) {
                    this.yRotLast = override.getYaw() + 0.001f;
                }
            }
        } catch (Throwable t) {
            PrinterController.INSTANCE.handleFatalError("LocalPlayerMixin.sendPosition(HEAD)", t);
        }
    }

    @Inject(method = "sendPosition", at = @At("RETURN"))
    private void printer$onSendPositionReturn(CallbackInfo ci) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        try {
            if (this.printer$savedYaw != null) {
                this.setYRot(this.printer$savedYaw);
                this.setXRot(this.printer$savedPitch);
                this.printer$savedYaw = null;
                this.printer$savedPitch = null;
            }
        } catch (Throwable t) {
            PrinterController.INSTANCE.handleFatalError("LocalPlayerMixin.sendPosition(RETURN/restore)", t);
        } finally {
            try {
                PrinterController.INSTANCE.onAfterSendPosition(self);
            } catch (Throwable t) {
                PrinterController.INSTANCE.handleFatalError("LocalPlayerMixin.sendPosition(RETURN/after)", t);
            }
        }
    }
}
