package com.example.addon;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
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
    private final SettingGroup sgAim = settings.createGroup("High-Speed Divebomb Aim");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Double> aimRange = sgGeneral.add(new DoubleSetting.Builder().name("vertical-search-range").defaultValue(350.0).min(10.0).max(500.0).build());
    private final Setting<Double> swingRange = sgGeneral.add(new DoubleSetting.Builder().name("swing-range").defaultValue(3.0).min(1.0).max(6.0).build());
    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder().name("auto-switch").defaultValue(true).build());
    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder().name("swap-back").defaultValue(true).build());
    private final Setting<Double> minFallDist = sgGeneral.add(new DoubleSetting.Builder().name("min-fall-distance").defaultValue(3.0).min(1.0).max(400.0).build());
    
    private final Setting<Double> breachThreshold = sgGeneral.add(new DoubleSetting.Builder().name("density-threshold-blocks").defaultValue(7.0).min(1.0).max(20.0).build());
    private final Setting<Double> maxRotationSpeed = sgAim.add(new DoubleSetting.Builder().name("divebomb-rotation-speed").defaultValue(150.0).min(45.0).max(360.0).build());

    private final Setting<Boolean> renderPred = sgRender.add(new BoolSetting.Builder().name("render-predictions").defaultValue(true).build());
    private final Setting<SettingColor> fillColor = sgRender.add(new ColorSetting.Builder().name("fill-color").defaultValue(new SettingColor(225, 25, 25, 50)).build());

    private long lastAttackTime = 0;
    private int originalSlot = -1;
    private boolean isSwapped = false;
    private LivingEntity currentTarget = null;

    public AutoMace() {
        super(Categories.Combat, "auto-mace", "AutoMace veloz para divebombs com regra de 7 blocos e bypass total.");
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
                    if (originalSlot == -1) originalSlot = mc.player.getInventory().getSelectedSlot();
                    InvUtils.swap(bestMace, false);
                    isSwapped = true;
                }
            }

            applyDivebombAim(currentTarget);

            if (mc.player.distanceTo(currentTarget) <= swingRange.get() && mc.options.keyAttack.isDown()) {
                executeAttack(currentTarget);
            }
        } else if (isSwapped && swapBack.get() && (mc.player.onGround() || mc.player.fallDistance <= 0.3f)) {
            resetState();
        }
    }

    private void applyDivebombAim(LivingEntity target) {
        Vec3 targetCenter = target.getEyePosition();
        double targetYaw = Rotations.getYaw(targetCenter);
        double targetPitch = Rotations.getPitch(targetCenter);

        Rotations.rotate(targetYaw, targetPitch, maxRotationSpeed.get().intValue(), null);
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
        if (isSwapped && originalSlot != -1 && mc.player != null) {
            InvUtils.swap(originalSlot, false);
        }
        originalSlot = -1;
        isSwapped = false;
        currentTarget = null;
    }
}
