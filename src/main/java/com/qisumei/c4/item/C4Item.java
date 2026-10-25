package com.qisumei.c4.item;

import com.qisumei.c4.C4Constants;
import com.qisumei.c4.Config;
import com.qisumei.c4.entity.C4Entity;
import com.qisumei.c4.entity.C4SavedData;
import com.qisumei.c4.network.C4HudPacket;
import com.qisumei.c4.network.PacketHandler;
import com.qisumei.c4.qis4c4;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.scores.Team;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

public class C4Item extends Item {

    /** 提前松手后，若按住时间小于该值则视为“取消”，否则视为“中断”。 */
    private static final int CANCEL_THRESHOLD_TICKS = 10;

    public C4Item() {
        super(new Item.Properties().stacksTo(1));
    }

    @Override
    @Nonnull
    public UseAnim getUseAnimation(@Nonnull ItemStack stack) {
        return UseAnim.BLOCK;
    }

    @Override
    public int getUseDuration(@Nonnull ItemStack stack) {
        return C4Constants.PLANT_TICKS;
    }

    // ======================= 校验 =======================

    @Nullable
    private static String getInstallError(Player player, Level world) {
        if (!isInstallTeamAllowed(player)) {
            String allowedTeams = String.join(", ", Config.allowedInstallTeams);
            return "§c 您的队伍不允许安装C4！允许的队伍: §e" + allowedTeams;
        }

        String blockId = getStandingBlockId(player, world);
        List<? extends String> allowedBlocks = Config.allowedBlocks;
        if (allowedBlocks == null || !allowedBlocks.contains(blockId)) {
            String allowedList = allowedBlocks != null ? String.join(", ", allowedBlocks) : "minecraft:end_stone_bricks";
            return "§c C4只能放置在指定方块上！允许: §e" + allowedList;
        }

        if (findPlacePos(player, world) == null) {
            return "§c 无法在此位置安装C4！";
        }
        if (!world.getEntitiesOfClass(C4Entity.class, player.getBoundingBox().inflate(1.0)).isEmpty()) {
            return "§c 附近已经有C4了！";
        }
        return null;
    }

    private static String getStandingBlockId(Player player, Level world) {
        BlockPos belowPos = player.blockPosition().below();
        BlockState belowState = world.getBlockState(belowPos);
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(belowState.getBlock());
        return key == null ? "" : key.toString();
    }

    /**
     * 找到可以放置 C4 的位置：优先玩家脚下，被占用则抬高一层。
     *
     * @return 可放置坐标；都不行时返回 {@code null}
     */
    @Nullable
    private static BlockPos findPlacePos(Player player, Level world) {
        BlockPos pos = player.blockPosition();
        if (world.getBlockState(pos).canBeReplaced()) {
            return pos;
        }
        BlockPos above = pos.above();
        if (world.getBlockState(above).canBeReplaced()) {
            return above;
        }
        return null;
    }

    private static boolean isInstallTeamAllowed(Player player) {
        if (Config.allowedInstallTeams == null || Config.allowedInstallTeams.isEmpty()) {
            return true;
        }
        Team team = player.getTeam();
        String teamName = team == null ? "" : team.getName();
        return Config.allowedInstallTeams.contains(teamName);
    }

    private static void sendSelf(Level world, Player player, String message, int duration) {
        if (!world.isClientSide && player instanceof ServerPlayer serverPlayer) {
            PacketHandler.sendToPlayer(serverPlayer, C4HudPacket.selfMessage(message, duration));
        }
    }

    // ======================= 交互 =======================

    @Override
    @Nonnull
    public InteractionResultHolder<ItemStack> use(@Nonnull Level world, @Nonnull Player player, @Nonnull InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        String error = getInstallError(player, world);
        if (error != null) {
            sendSelf(world, player, error, 3000);
            return InteractionResultHolder.fail(stack);
        }

        player.startUsingItem(hand);
        if (!world.isClientSide && player instanceof ServerPlayer serverPlayer) {
            PacketHandler.sendToPlayer(serverPlayer, C4HudPacket.installStart(C4Constants.PLANT_TICKS * 50));
        }
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public void releaseUsing(@Nonnull ItemStack stack, @Nonnull Level world, @Nonnull LivingEntity user, int timeCharged) {
        if (world.isClientSide || !(user instanceof Player player)) {
            return;
        }

        int usedTicks = C4Constants.PLANT_TICKS - timeCharged;
        if (usedTicks < C4Constants.PLANT_TICKS) {
            if (usedTicks < CANCEL_THRESHOLD_TICKS) {
                sendSelf(world, player, "§c 安装已取消！", 2000);
            } else {
                sendSelf(world, player, "§c 安装中断！请长按完成安装", 2000);
            }
        }
        if (player instanceof ServerPlayer serverPlayer) {
            PacketHandler.sendToPlayer(serverPlayer, C4HudPacket.installEnd());
        }
    }

    @Override
    public void onUseTick(@Nonnull Level world, @Nonnull LivingEntity user, @Nonnull ItemStack stack, int remainingUseTicks) {
        if (world.isClientSide || !(user instanceof Player player)) {
            return;
        }

        int usedTicks = C4Constants.PLANT_TICKS - remainingUseTicks;
        if (usedTicks > 0 && usedTicks < C4Constants.PLANT_TICKS) {
            // 安装时轻微限制水平移动，避免“边走边装”，但不影响竖直方向（台阶/掉落）
            player.setDeltaMovement(0.0D, player.getDeltaMovement().y, 0.0D);
            player.setSprinting(false);
            player.hurtMarked = true;
        }

        if (remainingUseTicks != 1) {
            return;
        }

        // ---- 安装完成 ----
        String error = getInstallError(player, world);
        if (error != null) {
            sendSelf(world, player, error, 3000);
            return;
        }
        BlockPos placePos = findPlacePos(player, world);
        if (placePos == null) {
            sendSelf(world, player, "§c 无法在此位置安装C4！", 2000);
            return;
        }

        world.playSound(null, placePos, SoundEvents.NOTE_BLOCK_HAT.value(),
                SoundSource.BLOCKS, 0.8F, 0.9F);

        if (world instanceof ServerLevel serverLevel) {
            // 这个位置可能还挂着一条历史清理记录（上次 /c4clear 时区块没加载的那个 C4）。
            // 玩家现在能站在这儿，说明旧实体早就不在了，先销掉记录，免得刚装好就被判死刑。
            C4SavedData.get(serverLevel).consumeTombstone(placePos);
            C4Entity c4 = new C4Entity(serverLevel, placePos, true);
            serverLevel.addFreshEntity(c4);
        }

        sendSelf(world, player, "§a C4已安装在脚下！40秒后引爆 ", 3000);
        if (player instanceof ServerPlayer serverPlayer) {
            PacketHandler.sendToPlayer(serverPlayer, C4HudPacket.installEnd());
        }

        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        qis4c4.LOGGER.info("[C4] {} planted at {}", player.getName().getString(), placePos.toShortString());
    }
}
