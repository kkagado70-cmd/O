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

    public enum Stage {
        IDLE,
        PLACE_RAIL,
        PLACE_CART,
        LIGHT_FIRE,
        SMOOTH_AIMING,
        DISCHARGE
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgKinematics = settings.createGroup("Cinematica de Mira");

    // --- CONFIGURACOES GERAIS ---
    private final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder()
        .name("action-delay-ticks")
        .description("Intervalo em ticks entre cada colocacao de item.")
        .defaultValue(1)
        .min(1)
        .max(5)
        .sliderRange(1, 5)
        .build()
    );

    private final Setting<Boolean> preferCharged = sgGeneral.add(new BoolSetting.Builder()
        .name("prefer-charged-xbow")
        .description("Prioriza estritamente bestas carregadas na hotbar.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoShoot = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-shoot")
        .description("Dispara a besta imediatamente apos concluir o alinhamento da mira.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder()
        .name("swap-back")
        .description("Restaura o slot de item original apos o termino do combo.")
        .defaultValue(true)
        .build()
    );

    // --- CONFIGURACOES DE MIRA SUAVE (S-CURVE) ---
    private final Setting<Boolean> smoothAim = sgKinematics.add(new BoolSetting.Builder()
        .name("enable-smooth-aim")
        .description("Ativa a cinematica de mira curva para parecer 100% natural em gravacoes.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> maxRotationSpeed = sgKinematics.add(new DoubleSetting.Builder()
        .name("max-speed-deg-tick")
        .description("Velocidade maxima de giro da camera por tick.")
        .defaultValue(28.0)
        .min(5.0)
        .max(90.0)
        .sliderRange(5.0, 90.0)
        .visible(smoothAim::get)
        .build()
    );

    private final Setting<Double> dampingFactor = sgKinematics.add(new DoubleSetting.Builder()
        .name("ease-damping")
        .description("Fator de amortecimento ao se aproximar do alvo (0.1 suave, 0.9 rapido).")
        .defaultValue(0.35)
        .min(0.1)
        .max(0.9)
        .sliderRange(0.1, 0.9)
        .visible(smoothAim::get)
        .build()
    );

    private Stage stage = Stage.IDLE;
    private int tickTimer = 0;
    private BlockHitResult targetBlockHit = null;

    private float targetYaw = 0.0f;
    private float targetPitch = 0.0f;

    public XbowCart() {
        super(Categories.Combat, "xbow-cart", "Xbow Cart profissional com cinematica de mira S-Curve e suporte direcional.");
    }

    @Override
    public void onActivate() {
        reset(false);
    }

    @Override
    public void onDeactivate() {
        reset(true);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;

        ItemStack mainHand = mc.player.getMainHandItem();
        boolean isHoldingRail = isRail(mainHand);

        if (isHoldingRail && stage == Stage.IDLE) {
            if (mc.hitResult instanceof BlockHitResult hitResult && hitResult.getType() == HitResult.Type.BLOCK) {
                initiateCombo(hitResult);
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

    private boolean isRail(ItemStack stack) {
        return stack.is(Items.RAIL) || stack.is(Items.POWERED_RAIL) || stack.is(Items.DETECTOR_RAIL) || stack.is(Items.ACTIVATOR_RAIL);
    }

    private FindItemResult resolveCrossbowSlot() {
        if (preferCharged.get()) {
            FindItemResult charged = InvUtils.findInHotbar(stack -> stack.is(Items.CROSSBOW) && CrossbowItem.isCharged(stack));
            if (charged.found()) return charged;
        }
        return InvUtils.findInHotbar(stack -> stack.is(Items.CROSSBOW));
    }

    private void initiateCombo(BlockHitResult hitResult) {
        FindItemResult cart = InvUtils.findInHotbar(Items.TNT_MINECART);
        FindItemResult flint = InvUtils.findInHotbar(Items.FLINT_AND_STEEL);
        FindItemResult xbow = resolveCrossbowSlot();

        if (!cart.found()) {
            ChatUtils.warning("XbowCart: Carrinho de TNT ausente na hotbar!");
            return;
        }
        if (!flint.found()) {
            ChatUtils.warning("XbowCart: Isqueiro ausente na hotbar!");
            return;
        }
        if (!xbow.found()) {
            ChatUtils.warning("XbowCart: Besta ausente na hotbar!");
            return;
        }

        this.targetBlockHit = hitResult;
        this.stage = Stage.PLACE_RAIL;
        this.tickTimer = 0;
    }

    private void processStateTransition() {
        if (targetBlockHit == null) {
            reset(true);
            return;
        }

        BlockPos groundPos = targetBlockHit.getBlockPos();
        Direction clickedFace = targetBlockHit.getDirection();
        BlockPos railPos = groundPos.relative(clickedFace);

        // Geometria de posicionamento
        BlockHitResult railHit = new BlockHitResult(targetBlockHit.getLocation(), clickedFace, groundPos, false);
        Vec3 cartCenter = new Vec3(railPos.getX() + 0.5, railPos.getY() + 0.1, railPos.getZ() + 0.5);
        BlockHitResult cartHit = new BlockHitResult(cartCenter, Direction.UP, railPos, false);

        Direction toPlayer = mc.player.getDirection().getOpposite();
        BlockHitResult fireHit;
        boolean isElevated = groundPos.getY() >= mc.player.getBlockY() + 1;

        if (isElevated) {
            // Em pilares elevados/torres: acende na face lateral da madeira voltada para o jogador
            fireHit = new BlockHitResult(new Vec3(groundPos.getX() + 0.5, groundPos.getY() + 0.5, groundPos.getZ() + 0.5), toPlayer, groundPos, false);
        } else {
            // No chão/buraco: acende no bloco intermediário à frente do trilho
            BlockPos fireBase = groundPos.relative(toPlayer);
            fireHit = new BlockHitResult(new Vec3(fireBase.getX() + 0.5, fireBase.getY() + 1.0, fireBase.getZ() + 0.5), Direction.UP, fireBase, false);
        }

        switch (stage) {
            case PLACE_RAIL:
                FindItemResult rail = InvUtils.findInHotbar(this::isRail);
                if (rail.found()) {
                    InvUtils.swap(rail.slot(), true);
                    dispatchInteraction(railHit);
                }
                stage = Stage.PLACE_CART;
                tickTimer = actionDelay.get();
                break;

            case PLACE_CART:
                FindItemResult cart = InvUtils.findInHotbar(Items.TNT_MINECART);
                if (cart.found()) {
                    InvUtils.swap(cart.slot(), false);
                    dispatchInteraction(cartHit);
                }
                stage = Stage.LIGHT_FIRE;
                tickTimer = actionDelay.get();
                break;

            case LIGHT_FIRE:
                FindItemResult flint = InvUtils.findInHotbar(Items.FLINT_AND_STEEL);
                if (flint.found()) {
                    InvUtils.swap(flint.slot(), false);
                    dispatchInteraction(fireHit);
                }
                computeAimKinematics(railPos);
                stage = Stage.SMOOTH_AIMING;
                tickTimer = 0;
                break;

            case SMOOTH_AIMING:
                FindItemResult xbow = resolveCrossbowSlot();
                if (xbow.found()) {
                    InvUtils.swap(xbow.slot(), false);
                    
                    if (smoothAim.get()) {
                        boolean aligned = stepKinematicRotation(targetYaw, targetPitch);
                        if (aligned) {
                            stage = Stage.DISCHARGE;
                            tickTimer = 1;
                        }
                    } else {
                        applyCameraOrientation(targetYaw, targetPitch);
                        stage = Stage.DISCHARGE;
                        tickTimer = 1;
                    }
                } else {
                    reset(swapBack.get());
                }
                break;

            case DISCHARGE:
                if (autoShoot.get()) {
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                }
                reset(swapBack.get());
                break;

            default:
                reset(false);
                break;
        }
    }

    private void computeAimKinematics(BlockPos railPos) {
        Vec3 eyePos = mc.player.getEyePosition();

        // Calculo de trajetoria tridimensional:
        // Se atirando de baixo para cima (elevado), mira em Y + 0.35 para nao colidir na quina do pilar.
        // Se atirando nivelado ou para baixo, mira na base Y + 0.15 para passar pelo fogo no chao.
        double yOffset = (eyePos.y < railPos.getY() + 0.2) ? 0.35 : 0.15;
        Vec3 target = new Vec3(railPos.getX() + 0.5, railPos.getY() + yOffset, railPos.getZ() + 0.5);

        double dx = target.x - eyePos.x;
        double dy = target.y - eyePos.y;
        double dz = target.z - eyePos.z;
        double dist = Math.sqrt(dx * dx + dz * dz);

        this.targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        this.targetPitch = (float) -Math.toDegrees(Math.atan2(dy, dist));
    }

    private boolean stepKinematicRotation(float destYaw, float destPitch) {
        float currentYaw = mc.player.getYRot();
        float currentPitch = mc.player.getXRot();

        float yawDelta = normalizeAngle(destYaw - currentYaw);
        float pitchDelta = destPitch - currentPitch;

        double maxStep = maxRotationSpeed.get();
        double damp = dampingFactor.get();

        // Aplicacao de curva Ease-Out amortecida
        float stepYaw = (float) (yawDelta * damp);
        float stepPitch = (float) (pitchDelta * damp);

        // Clamp de velocidade maxima
        stepYaw = (float) Math.max(-maxStep, Math.min(maxStep, stepYaw));
        stepPitch = (float) Math.max(-maxStep, Math.min(maxStep, stepPitch));

        if (Math.abs(yawDelta) <= 1.2f && Math.abs(pitchDelta) <= 1.2f) {
            applyCameraOrientation(destYaw, destPitch);
            return true;
        }

        applyCameraOrientation(currentYaw + stepYaw, currentPitch + stepPitch);
        return false;
    }

    private void applyCameraOrientation(float yaw, float pitch) {
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.yRotO = yaw;
        mc.player.xRotO = pitch;
        mc.player.yHeadRot = yaw;
        mc.player.yHeadRotO = yaw;
    }

    private float normalizeAngle(float angle) {
        float wrapped = angle % 360.0f;
        if (wrapped >= 180.0f) wrapped -= 360.0f;
        if (wrapped < -180.0f) wrapped += 360.0f;
        return wrapped;
    }

    private void dispatchInteraction(BlockHitResult hitResult) {
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hitResult);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    private void reset(boolean restoreSlot) {
        if (restoreSlot && mc.player != null) {
            InvUtils.swapBack();
        }
        this.stage = Stage.IDLE;
        this.targetBlockHit = null;
        this.tickTimer = 0;
    }
}
