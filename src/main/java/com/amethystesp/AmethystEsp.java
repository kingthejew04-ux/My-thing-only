package com.amethystesp;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Amethyst ESP (client-side, singleplayer use).
 *
 * What it does:
 *  - Every second, scans the blocks around the player for fully grown amethyst clusters
 *    (the biggest growth stage, block "minecraft:amethyst_cluster").
 *  - Each frame, draws a transparent purple box around every cluster found, with depth testing
 *    turned off so the boxes are visible through walls and terrain.
 *
 * Commands:
 *  - /amethystesp toggle        turns the highlighting on or off
 *  - /amethystesp range <n>     sets the scan radius in blocks (8-128, default 64)
 *
 * The rendering code follows the Fabric docs example "Custom Render Pipeline" for Minecraft 26.2.
 */
public class AmethystEsp implements ClientModInitializer {
	// Settings
	private static boolean enabled = true;
	private static int range = 64;
	private static final int MAX_BOXES = 500;

	// Scan results (written on the tick, copied into the render snapshot during extraction)
	private static List<BlockPos> found = List.of();
	private static List<BlockPos> snapshot = List.of();
	private static int scanDy = Integer.MAX_VALUE; // current layer being scanned (relative to scanCenter)
	private static BlockPos scanCenter = BlockPos.ZERO;
	private static List<BlockPos> pending = new ArrayList<>();

	// Box color: purple, semi-transparent
	private static final float RED = 0.6f;
	private static final float GREEN = 0.2f;
	private static final float BLUE = 1.0f;
	private static final float ALPHA = 0.4f;

	// Pipeline with no depth test: draws through walls
	private static final RenderPipeline FILLED_THROUGH_WALLS = RenderPipelines.register(
		RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath("amethystesp", "pipeline/filled_through_walls"))
			.withDepthStencilState(Optional.empty())
			.build());

	private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
	private static final Vector3f MODEL_OFFSET = new Vector3f();
	private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();

	private static final StagedVertexBuffer stagedBuffer =
		new StagedVertexBuffer(() -> "Amethyst ESP Buffer", RenderType.SMALL_BUFFER_SIZE);

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(AmethystEsp::onTick);
		LevelExtractionEvents.END_EXTRACTION.register(AmethystEsp::extract);
		LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(AmethystEsp::renderAndDraw);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> stagedBuffer.close());

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
			dispatcher.register(ClientCommands.literal("amethystesp")
				.then(ClientCommands.literal("toggle")
					.executes(context -> {
						enabled = !enabled;
						context.getSource().sendFeedback(
							Component.literal("[AmethystESP] " + (enabled ? "ON" : "OFF")));
						return 1;
					}))
				.then(ClientCommands.literal("range")
					.then(ClientCommands.argument("blocks", IntegerArgumentType.integer(8, 128))
						.executes(context -> {
							range = IntegerArgumentType.getInteger(context, "blocks");
							context.getSource().sendFeedback(
								Component.literal("[AmethystESP] Scan range set to " + range + " blocks"));
							return 1;
						}))));
		});
	}

	/**
	 * Scans for clusters one horizontal layer per tick, so large ranges do not cause lag.
	 * When every layer has been scanned, the results are published and a new scan starts
	 * around the player's current position.
	 */
	private static void onTick(Minecraft client) {
		if (!enabled || client.level == null || client.player == null) {
			found = List.of();
			scanDy = Integer.MAX_VALUE;
			return;
		}

		// A full pass is finished (or none has started yet): publish results and start a new pass.
		if (scanDy > range) {
			found = List.copyOf(pending);
			pending = new ArrayList<>();
			scanCenter = client.player.blockPosition();
			scanDy = -range;
		}

		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		int y = scanCenter.getY() + scanDy;
		for (int dx = -range; dx <= range; dx++) {
			for (int dz = -range; dz <= range; dz++) {
				cursor.set(scanCenter.getX() + dx, y, scanCenter.getZ() + dz);
				if (client.level.getBlockState(cursor).is(Blocks.AMETHYST_CLUSTER)) {
					if (pending.size() < MAX_BOXES) {
						pending.add(cursor.immutable());
					}
				}
			}
		}
		scanDy++;
	}

	/** Extraction phase: copy the scan results into the list used for drawing this frame. */
	private static void extract(LevelExtractionContext context) {
		snapshot = found;
	}

	/** Drawing phase: build the boxes and draw them without a depth test. */
	private static void renderAndDraw(LevelRenderContext context) {
		if (!enabled || snapshot.isEmpty()) {
			return;
		}

		RenderPipeline renderPipeline = FILLED_THROUGH_WALLS;
		VertexFormat formatBinding = renderPipeline.getVertexFormatBinding(0);
		assert formatBinding != null;
		PrimitiveTopology primitive = renderPipeline.getPrimitiveTopology();

		StagedVertexBuffer.Draw draw = stagedBuffer.appendDraw(
			formatBinding,
			primitive,
			primitive == PrimitiveTopology.QUADS ? RenderSystem.getProjectionType().vertexSorting() : null);

		renderBoxes(context, draw);

		stagedBuffer.upload();
		StagedVertexBuffer.ExecuteInfo info = stagedBuffer.getExecuteInfo(draw);
		if (info != null) {
			draw(Minecraft.getInstance(), info, renderPipeline);
		}
		stagedBuffer.endFrame();
	}

	private static void renderBoxes(LevelRenderContext context, StagedVertexBuffer.Draw draw) {
		PoseStack matrices = context.poseStack();
		Vec3 camera = context.levelState().cameraRenderState.pos;

		matrices.pushPose();
		matrices.translate(-camera.x, -camera.y, -camera.z);

		VertexConsumer builder = stagedBuffer.getVertexBuilder(draw);
		float pad = 0.05f; // make the box slightly bigger than the block so it is easy to see
		for (BlockPos pos : snapshot) {
			renderFilledBox(matrices.last().pose(), builder,
				pos.getX() - pad, pos.getY() - pad, pos.getZ() - pad,
				pos.getX() + 1 + pad, pos.getY() + 1 + pad, pos.getZ() + 1 + pad,
				RED, GREEN, BLUE, ALPHA);
		}

		matrices.popPose();
	}

	private static void renderFilledBox(Matrix4fc positionMatrix, VertexConsumer buffer,
			float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
			float red, float green, float blue, float alpha) {
		// Front (max Z)
		buffer.addVertex(positionMatrix, minX, minY, maxZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, maxX, minY, maxZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, maxX, maxY, maxZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, minX, maxY, maxZ).setColor(red, green, blue, alpha);

		// Back (min Z)
		buffer.addVertex(positionMatrix, maxX, minY, minZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, minX, minY, minZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, minX, maxY, minZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, maxX, maxY, minZ).setColor(red, green, blue, alpha);

		// Left (min X)
		buffer.addVertex(positionMatrix, minX, minY, minZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, minX, minY, maxZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, minX, maxY, maxZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, minX, maxY, minZ).setColor(red, green, blue, alpha);

		// Right (max X)
		buffer.addVertex(positionMatrix, maxX, minY, maxZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, maxX, minY, minZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, maxX, maxY, minZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, maxX, maxY, maxZ).setColor(red, green, blue, alpha);

		// Top (max Y)
		buffer.addVertex(positionMatrix, minX, maxY, maxZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, maxX, maxY, maxZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, maxX, maxY, minZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, minX, maxY, minZ).setColor(red, green, blue, alpha);

		// Bottom (min Y)
		buffer.addVertex(positionMatrix, minX, minY, minZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, maxX, minY, minZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, maxX, minY, maxZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, minX, minY, maxZ).setColor(red, green, blue, alpha);
	}

	private static void draw(Minecraft client, StagedVertexBuffer.ExecuteInfo info, RenderPipeline pipeline) {
		GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
			.writeTransform(RenderSystem.getModelViewMatrixCopy(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);

		RenderTarget mainTarget = client.gameRenderer.mainRenderTarget();
		GpuTextureView colorTexture = mainTarget.getColorTextureView();
		assert colorTexture != null;

		try (RenderPass renderPass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
				() -> "amethystesp filled boxes through walls",
				colorTexture,
				Optional.empty(),
				mainTarget.getDepthTextureView(),
				OptionalDouble.empty())) {
			renderPass.setPipeline(pipeline);
			RenderSystem.bindDefaultUniforms(renderPass);
			renderPass.setUniform("DynamicTransforms", dynamicTransforms);
			renderPass.setVertexBuffer(0, info.vertexBuffer().slice());
			renderPass.setIndexBuffer(info.indexBuffer(), info.indexType());
			renderPass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
		}
	}
}
