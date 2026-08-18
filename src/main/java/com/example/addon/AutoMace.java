package com.example.addon;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
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
    public enum Stage { IDLE, BREAK_SHIELD, SWAP_MACE, SLAM_ATTACK, RESTORE_SLOT }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgLegit = settings.createGroup("Vulcan & Anti-Cheat");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Double> searchRange = sgGeneral.add(new DoubleSetting.Builder().name("vertical-search-range").defaultValue(350.0).min(10.0).max(500.0).build());
    private final Setting<Double> swingRange = sgGeneral.add(new DoubleSetting.Builder().name("max-reach").defaultValue(2.85).min(1.0).max(3.0).description("Alcance seguro contra flags de Reach do Vulcan.").build());
    private final Setting<Boolean> stunSlam = sgGeneral.add(new BoolSetting.Builder().name("stun-slam").defaultValue(true).description("Quebra o escudo com machado antes do golpe de Mace.").build());
    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder().name("auto-switch").defaultValue(true).build());
    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder().name("swap-back").defaultValue(true).build());
    private final Setting<Double> minFallDist = sgGeneral.add(new BoolSetting.Builder().name("min-fall-distance").defaultValue(3.0).min(1.0).max(400.0).build());
    private final Setting<Double> breachThreshold = sgGeneral.add(new BoolSetting.Builder().name("density-threshold-blocks").defaultValue(7.0).min(1.0).max(20.0).build());

    private final Setting<Integer> comboDelayTicks = sgLegit.add(new IntSetting.Builder().name("combo-delay-ticks").defaultValue(2).min(1).max(4).description("Atraso estrito para evitar desync de inventário.").build());
    private final Setting<Double> rotationStep = sgLegit.add(new DoubleSetting.Builder().name("vulcan-rotation-step").defaultValue(45.0).min(10.0).max(90.0).description("Limita a velocidade de snap para zerar flags de Aim/Angle.").build());

    private final Setting<Boolean> renderPred = sgRender.add(new BoolSetting.Builder().name("render-predictions").defaultValue(true).build());
    private final Setting<SettingColor> fillColor = sgRender.add(new ColorSetting.Builder().name("fill-color").defaultValue(new SettingColor(225, 25, 25, 50)).build());

    private Stage stage = Stage.IDLE;
    private int tickTimer = 0;
    private int originalSlot = -1;
    private LivingEntity currentTarget = null;

    private float smoothYaw = 0.0f;
    private float smoothPitch = 0.0f;

    public AutoMace() {
        super(Categories.Combat, "auto-mace", "AutoMace otimizado com limitador de delta para bypass no Vulcan.");
    }

    @Override public void onActivate() { resetState(); }
    @Override public void onDeactivate() { resetState(); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        if (tickTimer > 0) {
            tickTimer--;
            return;
        }

        currentTarget = mc.level.getEntitiesOfClass(LivingEntity.class, mc.player.getBoundingBox().inflate(6.0, searchRange.get(), 6.0),
            e -> e != mc.player && e.isAlive() && !e.isDeadOrDying() && mc.player.getY() > e.getY()
        ).stream().min(java.util.Comparator.comparingDouble(e -> mc.player.distanceToSqr(e))).orElse(null);

        if (currentTarget == null) {
            resetState();
            return;
        }

        boolean isFalling = mc.player.fallDistance >= minFallDist.get() && !mc.player.onGround() && !mc.player.isInWater();

        if (isFalling) {
            applyVulcanSafeAim(currentTarget);

            double distance = mc.player.distanceTo(currentTarget);

            if (distance <= swingRange.get()) {
                processComboLogic();
            }
        } else if (mc.player.onGround() || mc.player.fallDistance <= 0.3f) {
            resetState();
        }
    }

    private void processComboLogic() {
        if (currentTarget == null) return;

        boolean isShielding = false;
        if (currentTarget instanceof Player p) {
            isShielding = p.isUsingItem() && p.getUseItem().getItem() instanceof ShieldItem;
        }

        int delay = Math.max(1, comboDelayTicks.get());

        switch (stage) {
            case IDLE -> {
                if (originalSlot == -1) {
                    originalSlot = mc.player.getInventory().selected;
                }

                if (stunSlam.get() && isShielding) {
                    FindItemResult axe = InvUtils.findInHotbar(s -> s.getItem() instanceof AxeItem);
                    if (axe.found()) {
                        InvUtils.swap(axe.slot(), false);
                        mc.gameMode.attack(mc.player, currentTarget);
                        mc.player.swing(InteractionHand.MAIN_HAND);
                        stage = Stage.SWAP_MACE;
                        tickTimer = delay;
                        return;
                    }
                }
                stage = Stage.SWAP_MACE;
                tickTimer = 0;
            }
            case SWAP_MACE -> {
                if (autoSwitch.get()) {
                    boolean preferDensity = mc.player.fallDistance > breachThreshold.get();
                    int bestMace = findBestMaceSlot(preferDensity);
                    if (bestMace != -1) {
                        InvUtils.swap(bestMace, false);
                    }
                }
                stage = Stage.SLAM_ATTACK;
                tickTimer = 1; // Pequena pausa de segurança para sincronizar o inventário com o servidor
            }
            case SLAM_ATTACK -> {
                mc.gameMode.attack(mc.player, currentTarget);
                mc.player.swing(InteractionHand.MAIN_HAND);
                stage = Stage.RESTORE_SLOT;
                tickTimer = delay;
            }
            case RESTORE_SLOT -> {
                resetState();
            }
        }
    }

    private void applyVulcanSafeAim(LivingEntity target) {
        Vec3 eyePos = mc.player.getEyePosition();
        Vec3 targetPoint = target.getBoundingBox().getCenter();

        double dx = targetPoint.x - eyePos.x;
        double dy = targetPoint.y - eyePos.y;
        double dz = targetPoint.z - eyePos.z;
        double dist = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, dist));

        if (smoothYaw == 0.0f && smoothPitch == 0.0f) {
            smoothYaw = mc.player.getYRot();
            smoothPitch = mc.player.getXRot();
        }

        float yawDiff = wrapAngle(targetYaw - smoothYaw);
        float pitchDiff = targetPitch - smoothPitch;

        // Limita a velocidade máxima de rotação por tick para prevenir flags de Angle/AimAssist do Vulcan
        float maxStep = (float) rotationStep.get();
        float stepYaw = Math.max(-maxStep, Math.min(maxStep, yawDiff));
        float stepPitch = Math.max(-maxStep, Math.min(maxStep, pitchDiff));

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
        if (swapBack.get()  && originalSlot != -1 && mc.player != null) {
            InvUtils.swap(originalSlot, false);
        }
        stage = Stage.IDLE;
        originalSlot = -1;
        currentTarget = null;
        smoothYaw = 0.0f;
        smoothPitch = 0.0f;
        tickTimer = 0;
    }
}