package dev.subcraft.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.subcraft.capture.VboCapture;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Raw vertex-buffer draws (mods' own models, Tesselator): see VboCapture. */
@Mixin(VertexBuffer.class)
public abstract class VertexBufferMixin {
	@Inject(method = "upload", at = @At("HEAD"))
	private void subcraft$upload(MeshData mesh, CallbackInfo ci) {
		VboCapture.uploaded((VertexBuffer) (Object) this, mesh);
	}

	@Inject(method = "drawWithShader", at = @At("HEAD"), cancellable = true)
	private void subcraft$drawWithShader(Matrix4f modelView, Matrix4f projection, ShaderInstance shader, CallbackInfo ci) {
		if (VboCapture.drawn((VertexBuffer) (Object) this, modelView)) {
			ci.cancel();
		}
	}

	@Inject(method = "draw", at = @At("HEAD"), cancellable = true)
	private void subcraft$draw(CallbackInfo ci) {
		if (VboCapture.drawn((VertexBuffer) (Object) this, VboCapture.boundModelView())) {
			ci.cancel();
		}
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void subcraft$close(CallbackInfo ci) {
		VboCapture.closed((VertexBuffer) (Object) this);
	}
}
