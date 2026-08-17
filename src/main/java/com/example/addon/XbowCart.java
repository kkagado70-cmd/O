package meteordevelopment.meteorclient.systems.modules.combat;

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
    private final SettingGroup sgKinematics = settings.createGroup("Cinematica de Mira");
    private final SettingGroup sgHumanize = settings.createGroup("Humanizacao de Mira");

    private final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder().name("action-delay-ticks").defaultValue(2).min(2).max(6).build());
    private final Setting<Boolean> preferCharged = sgGeneral.add(new BoolSetting.Builder().name("prefer-charged-xbow").defaultValue(true).build());
    private final Setting<Boolean> autoShoot = sgGeneral.add(new BoolSetting.Builder().name("auto-shoot").defaultValue(true).build());
    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder().name("swap-back").defaultValue(true).build());
    private final Setting<Double> explosionRadius = sgGeneral.add(new DoubleSetting.Builder().name("max-safety-distance").defaultValue(4.5).min(3.0).max(6.0).build());
    private final Setting<Boolean> predictMovement = sgGeneral.add(new BoolSetting.Builder().name("predict-target-velocity").defaultValue(true).build());
    private final Setting<Boolean> smoothAim = sgKinematics.add(new BoolSetting.Builder().name("enable-smooth-aim").defaultValue(true).build());
    private final Setting<Double> maxRotationSpeed = sgKinematics.add(new DoubleSetting.Builder().name("max-speed-deg-tick").defaultValue(20.0).min(5.0).max(45.0).visible(smoothAim::get).build());
    private final Setting<Boolean> aimJitter = sgHumanize.add(new BoolSetting.Builder().name("aim-jitter").defaultValue(true).build());
    private final Setting<Double> jitterIntensity = sgHumanize.add(new DoubleSetting.Builder().name("jitter-intensity").defaultValue(0.04).min(0.01).max(0.12).visible(aimJitter::get).build());

    private Stage stage = Stage.IDLE;
    private int tickTimer = 0;
    private BlockHitResult targetBlockHit = null;
    private float targetYaw = 0.0f, targetPitch = 0.0f;
    private double randomOffsetX = 0.0, randomOffsetY = 0.0, randomOffsetZ = 0.0;

    public XbowCart() { super(Categories.Combat, "xbow-cart", "Xbow Cart completo com bypass e aim jitter."); }

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

    private FindItemResult resolveCrossbowSlot() {
        if (preferCharged.get()) {
            FindItemResult c = InvUtils.findInHotbar(s -> s.is(Items.CROSSBOW) && CrossbowItem.isCharged(s));
            if (c.found()) return c;
        }
        return InvUtils.findInHotbar(Items.CROSSBOW);
    }

    private void initiateCombo(BlockHitResult hit) {
        if (!InvUtils.findInHotbar(Items.TNT_MINECART).found() || !InvUtils.findInHotbar(Items.FLINT_AND_STEEL).found() || !resolveCrossbowSlot().found()) {
            ChatUtils.warning("XbowCart: Recursos insuficientes na hotbar.");
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
                if (r.found()) { InvUtils.swap(r.slot(), true); dispatchInteraction(railHit); }
                stage = Stage.PLACE_CART;
                tickTimer = actionDelay.get();
            }
            case PLACE_CART -> {
                FindItemResult c = InvUtils.findInHotbar(Items.TNT_MINECART);
                if (c.found()) { InvUtils.swap(c.slot(), false); dispatchInteraction(cartHit); }
                stage = Stage.LIGHT_FIRE;
                tickTimer = actionDelay.get();
            }
            case LIGHT_FIRE -> {
                FindItemResult f = InvUtils.findInHotbar(Items.FLINT_AND_STEEL);
                if (f.found()) { InvUtils.swap(f.slot(), false); dispatchInteraction(fireHit); }
                computeAimKinematics(railPos);
                stage = Stage.SMOOTH_AIMING;
                tickTimer = 1;
            }
            case SMOOTH_AIMING -> {
                FindItemResult x = resolveCrossbowSlot();
                if (x.found()) {
                    InvUtils.swap(x.slot(), false);
                    if (smoothAim.get()) {
                        if (stepKinematicRotation(targetYaw, targetPitch)) { stage = Stage.DISCHARGE; tickTimer = 1; }
                    } else {
                        applyCameraOrientation(targetYaw, targetPitch);
                        stage = Stage.DISCHARGE;
                        tickTimer = 1;
                    }
                } else { reset(swapBack.get()); }
            }
            case DISCHARGE -> {
                if (autoShoot.get()) { mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND); mc.player.swing(InteractionHand.MAIN_HAND); }
                reset(swapBack.get());
            }
            default -> reset(false);
        }
    }

    private void computeAimKinematics(BlockPos railPos) {
        Vec3 eye = mc.player.getEyePosition();
        if (aimJitter.get()) {
            double i = jitterIntensity.get();
            randomOffsetX = (Math.random() - 0.5) * i;
            randomOffsetY = (Math.random() - 0.5) * i;
            randomOffsetZ = (Math.random() - 0.5) * i;
        } else { randomOffsetX = 0; randomOffsetY = 0; randomOffsetZ = 0; }
        Vec3 target = new Vec3(railPos.getX() + 0.5 + randomOffsetX, railPos.getY() + 0.3 + randomOffsetY, railPos.getZ() + 0.5 + randomOffsetZ);
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        targetPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
    }

    private boolean stepKinematicRotation(float dYaw, float dPitch) {
        float curYaw = mc.player.getYRot(), curPitch = mc.player.getXRot();
        float yDelta = normalizeAngle(dYaw - curYaw), pDelta = dPitch - curPitch;
        double max = maxRotationSpeed.get();
        float sYaw = (float) Math.max(-max, Math.min(max, yDelta * 0.4f));
        float sPitch = (float) Math.max(-max, Math.min(max, pDelta * 0.4f));
        if (Math.abs(yDelta) <= 1.0f && Math.abs(pDelta) <= 1.0f) { applyCameraOrientation(dYaw, dPitch); return true; }
        applyCameraOrientation(curYaw + sYaw, curPitch + sPitch);
        return false;
    }

    private void applyCameraOrientation(float yaw, float pitch) {
        mc.player.setYRot(yaw); mc.player.setXRot(pitch);
        mc.player.yRotO = yaw; mc.player.xRotO = pitch;
        mc.player.yHeadRot = yaw; mc.player.yHeadRotO = yaw;
    }

    private float normalizeAngle(float angle) {
        float w = angle % 360.0f;
        if (w >= 180.0f) w -= 360.0f;
        if (w < -180.0f) w += 360.0f;
        return w;
    }

    private void dispatchInteraction(BlockHitResult hit) {
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
