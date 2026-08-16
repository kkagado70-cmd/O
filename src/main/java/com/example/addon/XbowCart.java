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
        AIM_AND_SHOOT
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay-ticks")
        .description("Delay em ticks entre cada acao.")
        .defaultValue(1)
        .min(0)
        .max(5)
        .sliderRange(0, 5)
        .build()
    );

    private final Setting<Boolean> autoAim = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-aim-through-fire")
        .description("Calcula o angulo exato para a flecha atravessar o fogo e atingir a base da TNT.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> simulateClicks = sgGeneral.add(new BoolSetting.Builder()
        .name("simulate-clicks")
        .description("Ativa o registro de cliques no Keystrokes e CPS.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoShoot = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-shoot")
        .description("Dispara a besta automaticamente.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder()
        .name("swap-back")
        .description("Restaura o item original apos o combo.")
        .defaultValue(true)
        .build()
    );

    private Stage stage = Stage.IDLE;
    private int tickDelay = 0;
    private BlockHitResult targetBlockHit = null;

    public XbowCart() {
        super(Categories.Combat, "xbow-cart", "Xbow Cart com calculo automatico de angulo e trajetoria atraves do fogo.");
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

    private void startXbowCart(BlockHitResult hitResult) {
        FindItemResult cart = InvUtils.findInHotbar(Items.TNT_MINECART);
        FindItemResult flint = InvUtils.findInHotbar(Items.FLINT_AND_STEEL);
        FindItemResult xbow = InvUtils.findInHotbar(itemStack -> itemStack.is(Items.CROSSBOW));

        if (!cart.found()) {
            ChatUtils.warning("XbowCart: Falta Carrinho de TNT na hotbar!");
            return;
        }
        if (!flint.found()) {
            ChatUtils.warning("XbowCart: Falta Isqueiro na hotbar!");
            return;
        }
        if (!xbow.found()) {
            ChatUtils.warning("XbowCart: Falta Besta na hotbar!");
            return;
        }

        this.targetBlockHit = hitResult;
        this.stage = Stage.PLACE_RAIL;
        this.tickDelay = 0;
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

        // 3. Posição do Fogo (no chão entre o jogador e o trilho)
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
                    interact(railHit);
                }
                stage = Stage.PLACE_CART;
                tickDelay = delay.get();
                break;

            case PLACE_CART:
                FindItemResult cart = InvUtils.findInHotbar(Items.TNT_MINECART);
                if (cart.found()) {
                    InvUtils.swap(cart.slot(), false);
                    interact(cartHit);
                }
                stage = Stage.LIGHT_FIRE;
                tickDelay = delay.get();
                break;

            case LIGHT_FIRE:
                FindItemResult flint = InvUtils.findInHotbar(Items.FLINT_AND_STEEL);
                if (flint.found()) {
                    InvUtils.swap(flint.slot(), false);
                    interact(fireHit);
                }
                stage = Stage.AIM_AND_SHOOT;
                tickDelay = delay.get();
                break;

            case AIM_AND_SHOOT:
                FindItemResult xbow = InvUtils.findInHotbar(itemStack -> itemStack.is(Items.CROSSBOW));
                if (xbow.found()) {
                    InvUtils.swap(xbow.slot(), false);

                    if (autoAim.get()) {
                        // CALCULO DE ANGULO: Mira na metade inferior da TNT (Y + 0.15) para atravessar o fogo
                        Vec3 eyePos = mc.player.getEyePosition();
                        Vec3 cartTarget = new Vec3(placedRailPos.getX() + 0.5, placedRailPos.getY() + 0.15, placedRailPos.getZ() + 0.5);

                        double dx = cartTarget.x - eyePos.x;
                        double dy = cartTarget.y - eyePos.y;
                        double dz = cartTarget.z - eyePos.z;
                        double dist = Math.sqrt(dx * dx + dz * dz);

                        float targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
                        float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, dist));

                        mc.player.setYRot(targetYaw);
                        mc.player.setXRot(targetPitch);
                    }

                    if (autoShoot.get()) {
                        interact(null);
                    }
                }
                reset(swapBack.get());
                break;

            default:
                reset(false);
                break;
        }
    }

    private void interact(BlockHitResult hitResult) {
        if (simulateClicks.get() && mc.options != null && mc.options.keyUse != null) {
            mc.options.keyUse.setDown(true);
        }

        if (hitResult != null) {
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hitResult);
        } else {
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        }

        mc.player.swing(InteractionHand.MAIN_HAND);

        if (simulateClicks.get() && mc.options != null && mc.options.keyUse != null) {
            mc.options.keyUse.setDown(false);
        }
    }

    private void reset(boolean restoreSlot) {
        if (restoreSlot && mc.player != null) {
            InvUtils.swapBack();
        }
        this.stage = Stage.IDLE;
        this.targetBlockHit = null;
        this.tickDelay = 0;
    }
}
