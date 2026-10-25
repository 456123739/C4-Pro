package com.qisumei.c4.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.qisumei.c4.entity.C4Entity;
import com.qisumei.c4.qis4c4;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nonnull;

public class C4EntityRenderer extends EntityRenderer<C4Entity> {

    private final net.minecraft.client.renderer.entity.ItemRenderer itemRenderer;
    private final ItemStack c4ItemStack;

    public C4EntityRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.itemRenderer = context.getItemRenderer();
        this.c4ItemStack = new ItemStack(qis4c4.QISC4_ITEM.get());
    }

    @Override
    public void render(@Nonnull C4Entity entity, float entityYaw, float partialTicks, @Nonnull PoseStack poseStack, @Nonnull MultiBufferSource buffer, int packedLight) {
        poseStack.pushPose();

        // 将 C4 平躺放置在地面上
        poseStack.translate(0.0, 0.0, 0.0);
        poseStack.scale(0.6f, 0.6f, 0.6f);
        // 让物品平躺：绕 X 轴旋转 90 度
        poseStack.mulPose(Axis.XP.rotationDegrees(90));

        this.itemRenderer.renderStatic(
                this.c4ItemStack,
                ItemDisplayContext.FIXED,
                packedLight,
                packedLight,
                poseStack,
                buffer,
                entity.level(),
                0
        );

        poseStack.popPose();

        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    @Nonnull
    @Override
    public ResourceLocation getTextureLocation(@Nonnull C4Entity entity) {
        return new ResourceLocation(qis4c4.MODID, "textures/block/c4.png");
    }
}