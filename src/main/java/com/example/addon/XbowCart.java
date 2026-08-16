package com.example.addon;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ThreadLocalRandom;

public class XbowCart extends Module {

    public enum Stage {
        IDLE,
        PLACE_RAIL,
        AWAIT_RAIL,
        PLACE_CART,
        LIGHT_FIRE,
        AIM_AND_SHOOT
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> grimBypass = sgGeneral.add(new BoolSetting.Builder()
        .name("grim-polar-bypass")
        .description("Verifica confirmacao de blocos no servidor e aplica timing humanizado com variacao.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minDelay = sgGeneral.add(new IntSetting.Builder()
        .name("min-delay-ticks")
        .description("Delay minimo entre acoes (1 tick = 50ms).")
        .defaultValue(1)
        .min(1)
        .max(3)
        .sliderRange(1, 3)
        .build()
    );

    private final Setting<Integer> maxDelay = sgGeneral.add(new IntSetting.Builder()
        .name("max-delay-ticks")
        .description("Delay maximo para variacao humana (impede flags de cadencia fixa).")
        .defaultValue(2)
        .min(1)
        .max(4)
        .sliderRange(1, 4)
        .build()
    );

    private final Setting<Boolean> autoShoot = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-shoot")
        .description("Dispara a besta automaticamente apos confirmar o alinhamento da mira.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder()
        .name("swap-back")
        .description("Restaura o item original apos o termino do combo.")
        .defaultValue(true)
        .build()
    );

    private Stage stage = Stage.IDLE;
    private int tickDelay = 0;
    private int timeoutTicks = 0;
    private BlockHitResult targetBlockHit = null;

    public XbowCart() {
        super(Categories.Combat, "xbow-cart", "Xbow Cart com sincronizacao de pacotes e bypass de GrimAC/Polar.");
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
                startXbowCart(hitResult);
            }
        }

        if (stage != Stage.IDLE) {
            timeoutTicks++;
            if (timeoutTicks > 20) { // Se demorar mais de 1 segundo por lag de rede, cancela com seguranca
                reset(true);
                return;
            }

            if (tickDelay > 0) {
                tickDelay--;
                return;
            }
            executeStage();
        }
    }

    private boolean isRail(ItemStack stack) {
        return stack.is(Items.RAIL) || stack.is(Items.POWERED_RAIL) || stack.is(Items.DETECTOR_RAIL) || stack.is(Items.ACTIVATOR_RAIL);
    }

    private FindItemResult findBestCrossbow() {
        FindItemResult charged = InvUtils.findInHotbar(stack -> stack.is(Items.CROSSBOW) && CrossbowItem.isCharged(stack));
        if (charged.found()) return charged;
        return InvUtils.findInHotbar(stack -> stack.is(Items.CROSSBOW));
    }

    private int getRandomDelay() {
        if (!grimBypass.get()) return minDelay.get();
        int min = Math.min(minDelay.get(), maxDelay.get());
        int max = Math.max(minDelay.get(), maxDelay.get());
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private void startXbowCart(BlockHitResult hitResult) {
        FindItemResult cart = InvUtils.findInHotbar(Items.TNT_MINECART);
        FindItemResult flint = InvUtils.findInHotbar(Items.FLINT_AND_STEEL);
        FindItemResult xbow = findBestCrossbow();

        if (!cart.found() || !flint.found() || !xbow.found()) return;

        this.targetBlockHit = hitResult;
        this.stage = Stage.PLACE_RAIL;
        this.tickDelay = 0;
        this.timeoutTicks = 0;
    }

    private void executeStage() {
        if (targetBlockHit == null) {
            reset(true);
            return;
        }

        BlockPos groundPos = targetBlockHit.getBlockPos();
        Direction clickedFace = targetBlockHit.getDirection();
        BlockPos placedRailPos = groundPos.relative(clickedFace);

        // 1. Posição do Trilho
        BlockHitResult railHit = new BlockHitResult(targetBlockHit.getLocation(), clickedFace, groundPos, false);

        // 2. Posição do Carrinho de TNT
        Vec3 railCenter = new Vec3(placedRailPos.getX() + 0.5, placedRailPos.getY() + 0.1, placedRailPos.getZ() + 0.5);
        BlockHitResult cartHit = new BlockHitResult(railCenter, Direction.UP, placedRailPos, false);

        // 3. Posição do Fogo
        Direction toPlayer = mc.player.getDirection().getOpposite();
        BlockPos fireBasePos = groundPos.relative(toPlayer);
        BlockHitResult fireHit = new BlockHitResult(
            new Vec3(fireBasePos.getX() + 0.5, fireBasePos.getY() + 1.0, fireBasePos.getZ() + 0.5),
            Direction.UP,
            fireBasePos,
            false
        );

        switch (stage) {
            case PLACE_RAIL:
                FindItemResult rail = InvUtils.findInHotbar(this::isRail);
                if (rail.found()) {
                    InvUtils.swap(rail.slot(), true);
                    interactBlock(railHit);
                }
                // Se o modo Grim/Polar estiver ativo, aguarda o trilho existir no mundo
                stage = grimBypass.get() ? Stage.AWAIT_RAIL : Stage.PLACE_CART;
                tickDelay = getRandomDelay();
                break;

            case AWAIT_RAIL:
                // Anti-Ghost: Só coloca o carrinho se o trilho REALMENTE estiver presente no mundo
                if (mc.level.getBlockState(placedRailPos).getBlock() instanceof BaseRailBlock) {
                    stage = Stage.PLACE_CART;
                    tickDelay = 0;
                } else {
                    tickDelay = 1; // Espera o próximo tick de rede
                }
                break;

            case PLACE_CART:
                FindItemResult cart = InvUtils.findInHotbar(Items.TNT_MINECART);
                if (cart.found()) {
                    InvUtils.swap(cart.slot(), false);
                    interactBlock(cartHit);
                }
                stage = Stage.LIGHT_FIRE;
                tickDelay = getRandomDelay();
                break;

            case LIGHT_FIRE:
                FindItemResult flint = InvUtils.findInHotbar(Items.FLINT_AND_STEEL);
                if (flint.found()) {
                    InvUtils.swap(flint.slot(), false);
                    interactBlock(fireHit);
                }
                stage = Stage.AIM_AND_SHOOT;
                tickDelay = getRandomDelay();
                break;

            case AIM_AND_SHOOT:
                FindItemResult xbow = findBestCrossbow();
                if (xbow.found()) {
                    InvUtils.swap(xbow.slot(), false);

                    // Trajetória calculada para a base da TNT através do fogo
                    Vec3 eyePos = mc.player.getEyePosition();
                    Vec3 cartTarget = new Vec3(placedRailPos.getX() + 0.5, placedRailPos.getY() + 0.15, placedRailPos.getZ() + 0.5);

                    double dx = cartTarget.x - eyePos.x;
                    double dy = cartTarget.y - eyePos.y;
                    double dz = cartTarget.z - eyePos.z;
                    double dist = Math.sqrt(dx * dx + dz * dz);

                    float targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
                    float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, dist));

                    // Rotação com curva GCD legítima do Meteor antes de enviar o disparo
                    Rotations.rotate(targetYaw, targetPitch, 100, () -> {
                        if (autoShoot.get()) {
                            shootCrossbow();
                        }
                        reset(swapBack.get());
                    });
                    return;
                }
                reset(swapBack.get());
                break;

            default:
                reset(false);
                break;
        }
    }

    private void interactBlock(BlockHitResult hitResult) {
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hitResult);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    private void shootCrossbow() {
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    private void reset(boolean restoreSlot) {
        if (restoreSlot && mc.player != null) {
            InvUtils.swapBack();
        }
        this.stage = Stage.IDLE;
        this.targetBlockHit = null;
        this.tickDelay = 0;
        this.timeoutTicks = 0;
    }
}
