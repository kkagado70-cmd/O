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
    public enum Stage { IDLE, PLACE_RAIL, PLACE_CART, LIGHT_FIRE, SMOOTH_AIMING, DISCHARGE }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder().name("action-delay-ticks").defaultValue(1).min(1).max(3).build());
    private final Setting<Boolean> aimJitter = sgGeneral.add(new BoolSetting.Builder().name("humanize-jitter").defaultValue(true).build());

    private Stage stage = Stage.IDLE;
    private int tickTimer = 0;
    private BlockHitResult targetBlockHit = null;
    private float targetYaw = 0.0f, targetPitch = 0.0f;

    public XbowCart() { super(Categories.Combat, "xbow-cart", "Xbow Cart otimizado com cliques simulados e anti-cheat bypass."); }

    @Override public void onActivate() { reset(false); }
    @Override public void onDeactivate() { reset(true); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (isRail(mc.player.getMainHandItem()) && stage == Stage.IDLE) {
            if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) initiateCombo(hit);
        }
        if (stage != Stage.IDLE) {
            if (tickTimer > 0) { tickTimer--; return; }
            processStateTransition();
        }
    }

    private boolean isRail(ItemStack s) { return s.is(Items.RAIL) || s.is(Items.POWERED_RAIL) || s.is(Items.DETECTOR_RAIL) || s.is(Items.ACTIVATOR_RAIL); }

    private FindItemResult resolveCrossbow() {
        FindItemResult charged = InvUtils.findInHotbar(s -> s.is(Items.CROSSBOW) && CrossbowItem.isCharged(s));
        if (charged.found()) return charged;
        return InvUtils.findInHotbar(Items.CROSSBOW);
    }

    private void initiateCombo(BlockHitResult hit) {
        if (!InvUtils.findInHotbar(Items.TNT_MINECART).found() || !InvUtils.findInHotbar(Items.FLINT_AND_STEEL).found() || !resolveCrossbow().found()) {
            ChatUtils.warning("XbowCart: Faltam itens na hotbar.");
            return;
        }
        targetBlockHit = hit;
        stage = Stage.PLACE_RAIL;
        tickTimer = 0;
    }

    private void processStateTransition() {
        if (targetBlockHit == null) { reset(true); return; }
        BlockPos ground = targetBlockHit.getBlockPos();
        Direction face = targetBlockHit.getDirection();
        BlockPos railPos = ground.relative(face);
        BlockHitResult railHit = new BlockHitResult(targetBlockHit.getLocation(), face, ground, false);
        BlockHitResult cartHit = new BlockHitResult(new Vec3(railPos.getX() + 0.5, railPos.getY() + 0.1, railPos.getZ() + 0.5), Direction.UP, railPos, false);
        BlockPos fireBase = ground.relative(mc.player.getDirection().getOpposite());
        BlockHitResult fireHit = new BlockHitResult(new Vec3(fireBase.getX() + 0.5, fireBase.getY() + 1.0, fireBase.getZ() + 0.5), Direction.UP, fireBase, false);

        switch (stage) {
            case PLACE_RAIL -> {
                FindItemResult r = InvUtils.findInHotbar(this::isRail);
                if (r.found()) { InvUtils.swap(r.slot(), true); simulateClick(railHit); }
                stage = Stage.PLACE_CART;
                tickTimer = actionDelay.get();
            }
            case PLACE_CART -> {
                FindItemResult c = InvUtils.findInHotbar(Items.TNT_MINECART);
                if (c.found()) { InvUtils.swap(c.slot(), false); simulateClick(cartHit); }
                stage = Stage.LIGHT_FIRE;
                tickTimer = actionDelay.get();
            }
            case LIGHT_FIRE -> {
                FindItemResult f = InvUtils.findInHotbar(Items.FLINT_AND_STEEL);
                if (f.found()) { InvUtils.swap(f.slot(), false); simulateClick(fireHit); }
                computeAim(railPos);
                stage = Stage.SMOOTH_AIMING;
                tickTimer = 1;
            }
            case SMOOTH_AIMING -> {
                FindItemResult x = resolveCrossbow();
                if (x.found()) {
                    InvUtils.swap(x.slot(), false);
                    applyCamera(targetYaw, targetPitch);
                    stage = Stage.DISCHARGE;
                    tickTimer = 1;
                } else { reset(true); }
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
        Vec3 eye = mc.player.getEyePosition();
        double j = aimJitter.get() ? (Math.random() - 0.5) * 0.06 : 0;
        Vec3 target = new Vec3(railPos.getX() + 0.5 + j, railPos.getY() + 0.3 + j, railPos.getZ() + 0.5 + j);
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        targetPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
    }

    private void applyCamera(float yaw, float pitch) {
        mc.player.setYRot(yaw); mc.player.setXRot(pitch);
        mc.player.yRotO = yaw; mc.player.xRotO = pitch;
        mc.player.yHeadRot = yaw; mc.player.yHeadRotO = yaw;
    }

    private void simulateClick(BlockHitResult hit) {
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
