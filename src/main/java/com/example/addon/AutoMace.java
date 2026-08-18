package com.example.addon;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class AutoMace extends Module {
    public enum Stage { IDLE, SLAM_MACE }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAim = settings.createGroup("Aim Fast & Smooth");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Double> aimRange = sgGeneral.add(new DoubleSetting.Builder().name("vertical-search-range").defaultValue(350.0).min(10.0).max(500.0).build());
    private final Setting<Double> swingRange = sgGeneral.add(new DoubleSetting.Builder().name("max-reach").defaultValue(2.95).min(1.0).max(3.0).build());
    private final Setting<Boolean> stunSlam = sgGeneral.add(new BoolSetting.Builder().name("stun-slam").defaultValue(true).description("Quebra o escudo com machado antes do golpe de Mace.").build());
    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder().name("auto-switch").defaultValue(true).build());
    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder().name("swap-back-on-attack").defaultValue(true).build());
    private final Setting<Double> minFallDist = sgGeneral.add(new DoubleSetting.Builder().name("min-fall-distance").defaultValue(3.0).min(1.0).max(400.0).build());
    
    private final Setting<Double> breachThreshold = sgGeneral.add(new DoubleSetting.Builder().name("density-threshold-blocks").defaultValue(7.0).min(1.0).max(20.0).description("Acima de 7 blocos usa Density; abaixo usa Breach.").build());
    private final Setting<Double> aimSpeed = sgAim.add(new DoubleSetting.Builder().name("aim-response-speed").defaultValue(180.0).min(60.0).max(360.0).build());

    private final Setting<Boolean> renderPred = sgRender.add(new BoolSetting.Builder().name("render-predictions").defaultValue(true).build());
    private final Setting<SettingColor> fillColor = sgRender.add(new ColorSetting.Builder().name("fill-color").defaultValue(new SettingColor(225, 25, 25, 50)).build());

    private Stage stage = Stage.IDLE;
    private long lastAttackTime = 0;
    private LivingEntity currentTarget = null;
    private float smoothYaw = 0.0f;
    private float smoothPitch = 0.0f;

    public AutoMace() {
        super(Categories.Combat, "auto-mace", "AutoMace com Stun Slam (Machado + Mace) e troca automatica.");
    }

    @Override public void onActivate() { resetState(); }
    @Override public void onDeactivate() { resetState(); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        currentTarget = mc.level.getEntitiesOfClass(LivingEntity.class, mc.player.getBoundingBox().inflate(6.0, aimRange.get(), 6.0), 
            e -> e != mc.player && e.isAlive() && !e.isDeadOrDying() && mc.player.getY() > e.getY()
        ).stream().min(java.util.Comparator.comparingDouble(e -> mc.player.distanceToSqr(e))).orElse(null);

        if (currentTarget == null) {
            resetState();
            return;
        }

        boolean isFalling = mc.player.fallDistance >= minFallDist.get() && !mc.player.onGround() && !mc.player.isInWater();

        if (isFalling) {
            applyFastSmoothAim(currentTarget);

            if (mc.player.distanceTo(currentTarget) <= swingRange.get()) {
                if (System.currentTimeMillis() - lastAttackTime < 35) return;

                // Checa se o alvo está defendendo com escudo para aplicar Stun com Machado primeiro
                boolean isShielding = false;
                if (currentTarget instanceof Player p) {
                    isShielding = p.isUsingItem() && p.getUseItem().getItem() instanceof ShieldItem;
                }

                if (stunSlam.get() && isShielding && stage == Stage.IDLE) {
                    FindItemResult axe = InvUtils.findInHotbar(s -> s.getItem() instanceof AxeItem);
                    if (axe.found()) {
                        InvUtils.swap(axe.slot(), false);
                        mc.gameMode.attack(mc.player, currentTarget);
                        mc.player.swing(InteractionHand.MAIN_HAND);
                        stage = Stage.SLAM_MACE;
                        return;
                    }
                }

                // Troca para o Mace com o encantamento correto (Density > 7, Breach <= 7)
                if (autoSwitch.get()) {
                    boolean preferDensity = mc.player.fallDistance > breachThreshold.get();
                    int bestMace = findBestMaceSlot(preferDensity);
                    if (bestMace != -1) {
                        InvUtils.swap(bestMace, false);
                    }
                }

                mc.gameMode.attack(mc.player, currentTarget);
                mc.player.swing(InteractionHand.MAIN_HAND);
                lastAttackTime = System.currentTimeMillis();

                // Switch back imediato
                if (swapBack.get()) {
                    InvUtils.swapBack();
                }
                stage = Stage.IDLE;
            }
        } else if (mc.player.onGround() || mc.player.fallDistance <= 0.3f) {
            resetState();
        }
    }

    private void applyFastSmoothAim(LivingEntity target) {
        Vec3 targetCenter = target.getEyePosition();
        Vec3 eyePos = mc.player.getEyePosition();

        double dx = targetCenter.x - eyePos.x;
        double dy = targetCenter.y - eyePos.y;
        double dz = targetCenter.z - eyePos.z;
        double dist = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, dist));

        if (smoothYaw == 0.0f && smoothPitch == 0.0f) {
            smoothYaw = mc.player.getYRot();
            smoothPitch = mc.player.getXRot();
        }

        float yawDiff = wrapAngle(targetYaw - smoothYaw);
        float pitchDiff = targetPitch - smoothPitch;

        float stepYaw = yawDiff * 0.85f;
        float stepPitch = pitchDiff * 0.85f;

        float maxStep = aimSpeed.get().floatValue();
        stepYaw = Math.max(-maxStep, Math.min(maxStep, stepYaw));
        stepPitch = Math.max(-maxStep, Math.min(maxStep, stepPitch));

        smoothYaw += stepYaw;
        smoothPitch = Math.max(-90.0f, Math.min(90.0f, smoothPitch + stepPitch));

        mc.player.setYRot(smoothYaw);
        mc.player.setXRot(smoothPitch);
        mc.player.yRotO = smoothYaw;
        mc.player.xRotO = smoothPitch;
        mc.player.yHeadRot = smoothYaw;
        mc.player.yHeadRotO = smoothYaw;
    }

    private float wrapAngle(float angle) {
        float wrapped = angle % 360.0f;
        if (wrapped >= 180.0f) wrapped -= 360.0f;
        if (wrapped < -180.0f) wrapped += 360.0f;
        return wrapped;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!renderPred.get() || currentTarget == null) return;
        Vec3 predPos = currentTarget.position().add(currentTarget.getDeltaMovement());
        AABB box = currentTarget.getBoundingBox().move(predPos.subtract(currentTarget.position()));
        event.renderer.box(box, fillColor.get(), fillColor.get(), ShapeMode.Both, 0);
    }

    private int findBestMaceSlot(boolean preferDensity) {
        int bestSlot = -1;
        int maxLevel = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty() || !stack.is(Items.MACE)) continue;

            var registry = mc.level.registryAccess();
            var enchant = preferDensity ? registry.get(Enchantments.DENSITY) : registry.get(Enchantments.BREACH);

            if (enchant.isPresent()) {
                int level = EnchantmentHelper.getItemEnchantmentLevel(enchant.get(), stack);
                if (level > maxLevel) {
                    maxLevel = level;
                    bestSlot = i;
                }
            } else if (bestSlot == -1) {
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    private void resetState() {
        if (mc.player != null && swapBack.get()) {
            InvUtils.swapBack();
        }
        stage = Stage.IDLE;
        currentTarget = null;
        smoothYaw = 0.0f;
        smoothPitch = 0.0f;
    }
}
