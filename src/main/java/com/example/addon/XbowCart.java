package com.example.addon;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
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
import net.minecraft.world.phys.Vec3;

public class XbowCart extends Module {

    public enum Stage {
        IDLE,
        PLACE_RAIL,
        PLACE_CART,
        LIGHT_FIRE,
        READY_CROSSBOW
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> minPitch = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-pitch")
        .description("Angulo minimo olhando para baixo para ativar.")
        .defaultValue(70.0)
        .min(45.0)
        .max(90.0)
        .sliderRange(45.0, 90.0)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay-ticks")
        .description("Delay em ticks entre cada acao (0 para instantaneo).")
        .defaultValue(1)
        .min(0)
        .max(5)
        .sliderRange(0, 5)
        .build()
    );

    private final Setting<Boolean> autoShoot = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-shoot")
        .description("Dispara a besta carregada automaticamente.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder()
        .name("swap-back")
        .description("Volta para o item anterior apos o combo.")
        .defaultValue(true)
        .build()
    );

    private Stage stage = Stage.IDLE;
    private int tickDelay = 0;
    private BlockPos targetPos = null;

    public XbowCart() {
        super(Categories.Combat, "xbow-cart", "Combo automatico de Xbow TNT Cart com 100% de precisao.");
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

        boolean isLookingDown = mc.player.getXRot() >= minPitch.get();
        ItemStack mainHand = mc.player.getMainHandItem();
        boolean isHoldingRail = isRail(mainHand);

        if (isLookingDown && isHoldingRail && stage == Stage.IDLE) {
            startXbowCart();
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

    private void startXbowCart() {
        FindItemResult cart = InvUtils.findInHotbar(Items.TNT_MINECART);
        FindItemResult flint = InvUtils.findInHotbar(Items.FLINT_AND_STEEL);
        FindItemResult xbow = InvUtils.findInHotbar(itemStack -> itemStack.is(Items.CROSSBOW) && CrossbowItem.isCharged(itemStack));

        if (!cart.found() || !flint.found() || !xbow.found()) return;

        if (mc.hitResult instanceof BlockHitResult hitResult) {
            this.targetPos = hitResult.getBlockPos();
            this.stage = Stage.PLACE_RAIL;
            this.tickDelay = 0;
        }
    }

    private void executeStage() {
        if (targetPos == null) {
            reset(true);
            return;
        }

        Vec3 hitVec = new Vec3(targetPos.getX() + 0.5, targetPos.getY() + 1.0, targetPos.getZ() + 0.5);
        BlockHitResult blockHit = new BlockHitResult(hitVec, Direction.UP, targetPos, false);
        BlockPos railPos = targetPos.above();
        BlockHitResult railHit = new BlockHitResult(new Vec3(railPos.getX() + 0.5, railPos.getY() + 0.5, railPos.getZ() + 0.5), Direction.UP, railPos, false);

        switch (stage) {
            case PLACE_RAIL:
                FindItemResult rail = InvUtils.findInHotbar(this::isRail);
                if (rail.found()) {
                    InvUtils.swap(rail.slot(), true);
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, blockHit);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                }
                stage = Stage.PLACE_CART;
                tickDelay = delay.get();
                break;

            case PLACE_CART:
                FindItemResult cart = InvUtils.findInHotbar(Items.TNT_MINECART);
                if (cart.found()) {
                    InvUtils.swap(cart.slot(), true);
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, railHit);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                }
                stage = Stage.LIGHT_FIRE;
                tickDelay = delay.get();
                break;

            case LIGHT_FIRE:
                FindItemResult flint = InvUtils.findInHotbar(Items.FLINT_AND_STEEL);
                if (flint.found()) {
                    InvUtils.swap(flint.slot(), true);
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, blockHit);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                }
                stage = Stage.READY_CROSSBOW;
                tickDelay = delay.get();
                break;

            case READY_CROSSBOW:
                FindItemResult xbow = InvUtils.findInHotbar(itemStack -> itemStack.is(Items.CROSSBOW) && CrossbowItem.isCharged(itemStack));
                if (xbow.found()) {
                    InvUtils.swap(xbow.slot(), false);
                    if (autoShoot.get()) {
                        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                        mc.player.swing(InteractionHand.MAIN_HAND);
                    }
                }
                reset(swapBack.get());
                break;

            default:
                reset(false);
                break;
        }
    }

    private void reset(boolean restoreSlot) {
        if (restoreSlot && mc.player != null) {
            InvUtils.swapBack();
        }
        this.stage = Stage.IDLE;
        this.targetPos = null;
        this.tickDelay = 0;
    }
}
