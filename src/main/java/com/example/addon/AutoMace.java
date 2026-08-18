package com.example.addon;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class AutoMace extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAim = settings.createGroup("Aim Fast & Smooth");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Double> aimRange = sgGeneral.add(new DoubleSetting.Builder().name("vertical-search-range").defaultValue(350.0).min(10.0).max(500.0).build());
    private final Setting<Double> swingRange = sgGeneral.add(new DoubleSetting.Builder().name("max-reach").defaultValue(2.95).min(1.0).max(3.0).build());
    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder().name("auto-switch").defaultValue(true).build());
    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder().name("swap-back-on-attack").defaultValue(true).description("Troca de volta para o slot anterior imediatamente ao atacar.").build());
    private final Setting<Double> minFallDist = sgGeneral.add(new DoubleSetting.Builder().name("min-fall-distance").defaultValue(3.0).min(1.0).max(400.0).build());
    
    private final Setting<Double> breachThreshold = sgGeneral.add(new DoubleSetting.Builder().name("density-threshold-blocks").defaultValue(7.0).min(1.0).max(20.0).description("Acima de 7 blocos usa Density; abaixo/igual usa Breach.").build());
    
    // Configurações de mira rápida e suave otimizada para Divebomb
    private final Setting<Double> aimSpeed = sgAim.add(new DoubleSetting.Builder().name("aim-response-speed").defaultValue(160.0).min(60.0).max(360.0).description("Velocidade de mira acelerada para acompanhar quedas rapidas.").build());

    private final Setting<Boolean> renderPred = sgRender.add(new BoolSetting.Builder().name("render-predictions").defaultValue(true).build());
    private final Setting<SettingColor> fillColor = sgRender.add(new ColorSetting.Builder().name("fill-color").defaultValue(new SettingColor(225, 25, 25, 50)).build());

    private long lastAttackTime = 0;
    private boolean isSwapped = false;
    private LivingEntity currentTarget = null;
    
    private float smoothYaw = 0.0f;
    private float smoothPitch = 0.0f;

    public AutoMace() {
        super(Categories.Combat, "auto-mace", "AutoMace veloz para divebombs com switch back imediato ao atacar.");
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
            if (isSwapped && swapBack.get()) resetState();
            return;
        }

        boolean isFalling = mc.player.fallDistance >= minFallDist.get() && !mc.player.onGround() && !mc.player.isInWater();

        if (isFalling) {
            if (System.currentTimeMillis() - lastAttackTime < 35) return;

            if (autoSwitch.get()) {
                boolean preferDensity = mc.player.fallDistance > breachThreshold.get();
                int bestMace = findBestMaceSlot(preferDensity);
                if (bestMace != -1) {
                    InvUtils.swap(bestMace, false);
                    isSwapped = true;
                }
            }

            applyFastSmoothAim(currentTarget);

            if (mc.player.distanceTo(currentTarget) <= swingRange.get() && mc.options.keyAttack.isDown()) {
                executeAttack(currentTarget);
            }
        } else if (isSwapped && swapBack.get() && (mc.player.onGround() || mc.player.fallDistance <= 0.3f)) {
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

        // Interpolação responsiva acelerada (75% da distância por tick com trava de velocidade máxima)
        float stepYaw = yawDiff * 0.75f;
        float stepPitch = pitchDiff * 0.75f;

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

    private void executeAttack(LivingEntity target) {
        mc.gameMode.attack(mc.player, target);
        mc.player.swing(InteractionHand.MAIN_HAND);
        lastAttackTime = System.currentTimeMillis();

        // Faz o switch back imediatamente ao golpear o inimigo
        if (swapBack.get() && isSwapped) {
            resetState();
        }
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
        if (isSwapped && mc.player != null) {
            InvUtils.swapBack();
        }
        isSwapped = false;
        currentTarget = null;
        smoothYaw = 0.0f;
        smoothPitch = 0.0f;
    }
}
