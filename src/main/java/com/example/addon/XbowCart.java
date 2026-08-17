package com.example.addon;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class XbowCart extends Module {
    public enum Stage { IDLE, PLACE_RAIL, PLACE_CART, LIGHT_FIRE, PRO_AIMING, DISCHARGE }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder().name("action-delay-ticks").defaultValue(1).min(1).max(3).build());

    private Stage stage = Stage.IDLE;
    private int tickTimer = 0;
    private BlockHitResult targetBlockHit = null;
    private float targetYaw = 0.0f;
    private float targetPitch = 0.0f;

    public XbowCart() {
        super(Categories.Combat, "xbow-cart", "Xbow Cart otimizado para execucao profissional ultra rapida e 0 flags.");
    }

    @Override public void onActivate() { reset(false); }
    @Override public void onDeactivate() { reset(true); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;

        if (isRail(mc.player.getMainHandItem()) && stage == Stage.IDLE) {
            if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
                initiateCombo(hit);
            }
        }

        if (stage != Stage.IDLE) {
            if (tickTimer > 0) {
                tickTimer--;
                return;
            }
            processStateTransition();
        }
    }

    private boolean isRail(ItemStack s) {
        return s.is(Items.RAIL) || s.is(Items.POWERED_RAIL) || s.is(Items.DETECTOR_RAIL) || s.is(Items.ACTIVATOR_RAIL);
    }

    private FindItemResult resolveCrossbow() {
        FindItemResult charged = InvUtils.findInHotbar(s -> s.is(Items.CROSSBOW) && CrossbowItem.isCharged(s));
        if (charged.found()) return charged;
        return InvUtils.findInHotbar(Items.CROSSBOW);
    }

    private void initiateCombo(BlockHitResult hit) {
        if (!InvUtils.findInHotbar(Items.TNT_MINECART).found() || 
            !InvUtils.findInHotbar(Items.FLINT_AND_STEEL).found() || 
            !resolveCrossbow().found()) {
            ChatUtils.warning("XbowCart: Faltam itens na hotbar.");
            return;
        }
        this.targetBlockHit = hit;
        this.stage = Stage.PLACE_RAIL;
        this.tickTimer = 0;
    }

    private void processStateTransition() {
        if (targetBlockHit == null) { reset(true); return; }

        BlockPos groundPos = targetBlockHit.getBlockPos();
        Direction clickedFace = targetBlockHit.getDirection();
        BlockPos railPos = groundPos.relative(clickedFace);

        BlockHitResult railHit = new BlockHitResult(targetBlockHit.getLocation(), clickedFace, groundPos, false);
        Vec3 cartCenter = new Vec3(railPos.getX() + 0.5, railPos.getY() + 0.1, railPos.getZ() + 0.5);
        BlockHitResult cartHit = new BlockHitResult(cartCenter, Direction.UP, railPos, false);

        Direction toPlayer = mc.player.getDirection().getOpposite();
        BlockPos fireBase = groundPos.relative(toPlayer);
        BlockHitResult fireHit = new BlockHitResult(new Vec3(fireBase.getX() + 0.5, fireBase.getY() + 1.0, fireBase.getZ() + 0.5), Direction.UP, fireBase, false);

        switch (stage) {
            case PLACE_RAIL -> {
                FindItemResult rail = InvUtils.findInHotbar(this::isRail);
                if (rail.found()) {
                    InvUtils.swap(rail.slot(), true);
                    interactWithBlock(railHit);
                }
                stage = Stage.PLACE_CART;
                tickTimer = actionDelay.get();
            }
            case PLACE_CART -> {
                FindItemResult cart = InvUtils.findInHotbar(Items.TNT_MINECART);
                if (cart.found()) {
                    InvUtils.swap(cart.slot(), false);
                    interactWithBlock(cartHit);
                }
                stage = Stage.LIGHT_FIRE;
                tickTimer = actionDelay.get();
            }
            case LIGHT_FIRE -> {
                FindItemResult flint = InvUtils.findInHotbar(Items.FLINT_AND_STEEL);
                if (flint.found()) {
                    InvUtils.swap(flint.slot(), false);
                    interactWithBlock(fireHit);
                }
                computeAim(railPos);
                stage = Stage.PRO_AIMING;
                tickTimer = 1;
            }
            case PRO_AIMING -> {
                FindItemResult xbow = resolveCrossbow();
                if (xbow.found()) {
                    InvUtils.swap(xbow.slot(), false);
                    applyCamera(targetYaw, targetPitch);
                    stage = Stage.DISCHARGE;
                    tickTimer = 1;
                } else {
                    reset(true);
                }
            }
            case DISCHARGE -> {
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                mc.player.swing(InteractionHand.MAIN_HAND);
                reset(true);
            }
            default -> reset(false);
        }
    }

    private void computeAim(BlockPos railPos) {
        Vec3 eyePos = mc.player.getEyePosition();
        Vec3 target = new Vec3(railPos.getX() + 0.5, railPos.getY() + 0.22, railPos.getZ() + 0.5);

        double dx = target.x - eyePos.x;
        double dy = target.y - eyePos.y;
        double dz = target.z - eyePos.z;
        double dist = Math.sqrt(dx * dx + dz * dz);

        this.targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        this.targetPitch = (float) -Math.toDegrees(Math.atan2(dy, dist));
    }

    private void applyCamera(float yaw, float pitch) {
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.yRotO = yaw;
        mc.player.xRotO = pitch;
        mc.player.yHeadRot = yaw;
        mc.player.yHeadRotO = yaw;
    }

    private void interactWithBlock(BlockHitResult hit) {
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    private void reset(boolean restore) {
        if (restore && mc.player != null) InvUtils.swapBack();
        stage = Stage.IDLE;
        targetBlockHit = null;
        tickTimer = 0;
    }
    }
