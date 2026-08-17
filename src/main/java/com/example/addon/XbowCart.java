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
    public enum Stage { IDLE, PLACE_RAIL, PLACE_CART, LIGHT_FIRE, LEGIT_AIMING, DISCHARGE }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    
    private final Setting<Integer> humanDelay = sgGeneral.add(new IntSetting.Builder()
        .name("step-delay-ticks")
        .defaultValue(2)
        .min(2)
        .max(4)
        .build()
    );

    private Stage stage = Stage.IDLE;
    private int tickTimer = 0;
    private BlockHitResult targetBlockHit = null;
    private float targetYaw = 0.0f;
    private float targetPitch = 0.0f;
    private int originalSlot = -1;

    public XbowCart() {
        super(Categories.Combat, "xbow-cart", "Xbow Cart totalmente blindado contra flags e falhas de fogo.");
    }

    @Override public void onActivate() { reset(false); }
    @Override public void onDeactivate() { reset(true); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;

        if (isRail(mc.player.getMainHandItem()) && stage == Stage.IDLE) {
            if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
                if (mc.player.getEyePosition().distanceTo(hit.getLocation()) <= 4.5) {
                    initiateCombo(hit);
                }
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
        FindItemResult rail = InvUtils.findInHotbar(this::isRail);
        FindItemResult cart = InvUtils.findInHotbar(Items.TNT_MINECART);
        FindItemResult flint = InvUtils.findInHotbar(Items.FLINT_AND_STEEL);
        FindItemResult xbow = resolveCrossbow();

        if (!rail.found() || !cart.found() || !flint.found() || !xbow.found()) {
            ChatUtils.warning("XbowCart: Faltam itens na hotbar.");
            return;
        }

        this.originalSlot = mc.player.getInventory().selectedSlot;
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
        BlockHitResult cartHit = new BlockHitResult(new Vec3(railPos.getX() + 0.5, railPos.getY() + 0.05, railPos.getZ() + 0.5), Direction.UP, railPos, false);
        BlockHitResult fireHit = new BlockHitResult(new Vec3(railPos.getX() + 0.5, railPos.getY() + 0.5, railPos.getZ() + 0.5), Direction.UP, railPos, false);

        int dynamicDelay = Math.max(2, humanDelay.get() + ((Math.random() < 0.3) ? 1 : 0));

        switch (stage) {
            case PLACE_RAIL -> {
                FindItemResult rail = InvUtils.findInHotbar(this::isRail);
                if (rail.found()) {
                    mc.player.getInventory().selectedSlot = rail.slot();
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, railHit);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                }
                stage = Stage.PLACE_CART;
                tickTimer = dynamicDelay;
            }
            case PLACE_CART -> {
                FindItemResult cart = InvUtils.findInHotbar(Items.TNT_MINECART);
                if (cart.found()) {
                    mc.player.getInventory().selectedSlot = cart.slot();
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, cartHit);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                }
                stage = Stage.LIGHT_FIRE;
                tickTimer = dynamicDelay;
            }
            case LIGHT_FIRE -> {
                FindItemResult flint = InvUtils.findInHotbar(Items.FLINT_AND_STEEL);
                if (flint.found()) {
                    mc.player.getInventory().selectedSlot = flint.slot();
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, fireHit);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                }
                computeLegitAim(railPos);
                stage = Stage.LEGIT_AIMING;
                tickTimer = 2;
            }
            case LEGIT_AIMING -> {
                FindItemResult xbow = resolveCrossbow();
                if (xbow.found()) {
                    mc.player.getInventory().selectedSlot = xbow.slot();
                    applyLegitCamera(targetYaw, targetPitch);
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

    private void computeLegitAim(BlockPos railPos) {
        Vec3 eyePos = mc.player.getEyePosition();
        double jitterX = (Math.random() - 0.5) * 0.04;
        double jitterY = (Math.random() - 0.5) * 0.03;
        double jitterZ = (Math.random() - 0.5) * 0.04;

        Vec3 target = new Vec3(railPos.getX() + 0.5 + jitterX, railPos.getY() + 0.22 + jitterY, railPos.getZ() + 0.5 + jitterZ);

        double dx = target.x - eyePos.x;
        double dy = target.y - eyePos.y;
        double dz = target.z - eyePos.z;
        double dist = Math.sqrt(dx * dx + dz * dz);

        this.targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        this.targetPitch = (float) -Math.toDegrees(Math.atan2(dy, dist));
    }

    private void applyLegitCamera(float yaw, float pitch) {
        float curYaw = mc.player.getYRot();
        float curPitch = mc.player.getXRot();

        float maxStep = 35.0f;
        float steppedYaw = curYaw + Math.max(-maxStep, Math.min(maxStep, wrapAngle(yaw - curYaw)));
        float steppedPitch = curPitch + Math.max(-maxStep, Math.min(maxStep, pitch - curPitch));

        mc.player.setYRot(steppedYaw);
        mc.player.setXRot(Math.max(-90.0f, Math.min(90.0f, steppedPitch)));
        mc.player.yRotO = steppedYaw;
        mc.player.xRotO = steppedYaw;
        mc.player.yHeadRot = steppedYaw;
        mc.player.yHeadRotO = steppedYaw;
    }

    private float wrapAngle(float angle) {
        float wrapped = angle % 360.0f;
        if (wrapped >= 180.0f) wrapped -= 360.0f;
        if (wrapped < -180.0f) wrapped += 360.0f;
        return wrapped;
    }

    private void reset(boolean restore) {
        if (restore && originalSlot != -1 && mc.player != null) {
            mc.player.getInventory().selectedSlot = originalSlot;
        }
        this.stage = Stage.IDLE;
        this.targetBlockHit = null;
        this.tickTimer = 0;
        this.originalSlot = -1;
    }
                        }
