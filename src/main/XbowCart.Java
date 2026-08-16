package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.RailItem;

public class XbowCart extends Module {

    public enum Stage {
        IDLE,
        PLACE_RAIL,
        PLACE_CART,
        LIGHT_FIRE,
        READY_CROSSBOW
    }

    private Stage stage = Stage.IDLE;
    private int tickDelay = 0;
    private int previousSlot = -1;

    public XbowCart() {
        super(Categories.Combat, "xbow-cart", "Ativa o combo de Xbow Cart ao olhar para baixo segurando o trilho.");
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
        if (mc.player == null || mc.world == null) return;

        // Condição: Olhar para baixo (pitch >= 70) com o trilho na mão principal
        boolean isLookingDown = mc.player.getPitch() >= 70.0f;
        ItemStack mainHand = mc.player.getMainHandStack();
        boolean isHoldingRail = mainHand.getItem() instanceof RailItem;

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

    private void startXbowCart() {
        FindItemResult cart = InvUtils.findInHotbar(Items.TNT_MINECART);
        FindItemResult flint = InvUtils.findInHotbar(Items.FLINT_AND_STEEL);
        FindItemResult xbow = InvUtils.findInHotbar(itemStack -> itemStack.isOf(Items.CROSSBOW) && CrossbowItem.isCharged(itemStack));

        if (!cart.found() || !flint.found() || !xbow.found()) return;

        this.previousSlot = mc.player.getInventory().selectedSlot;
        this.stage = Stage.PLACE_RAIL;
        this.tickDelay = 0;
    }

    private void executeStage() {
        switch (stage) {
            case PLACE_RAIL:
                FindItemResult rail = InvUtils.findInHotbar(itemStack -> itemStack.getItem() instanceof RailItem);
                if (rail.found()) {
                    InvUtils.swap(rail.slot(), false);
                }
                stage = Stage.PLACE_CART;
                tickDelay = 1;
                break;

            case PLACE_CART:
                FindItemResult cart = InvUtils.findInHotbar(Items.TNT_MINECART);
                if (cart.found()) {
                    InvUtils.swap(cart.slot(), false);
                }
                stage = Stage.LIGHT_FIRE;
                tickDelay = 1;
                break;

            case LIGHT_FIRE:
                FindItemResult flint = InvUtils.findInHotbar(Items.FLINT_AND_STEEL);
                if (flint.found()) {
                    InvUtils.swap(flint.slot(), false);
                }
                stage = Stage.READY_CROSSBOW;
                tickDelay = 1;
                break;

            case READY_CROSSBOW:
                FindItemResult xbow = InvUtils.findInHotbar(itemStack -> itemStack.isOf(Items.CROSSBOW) && CrossbowItem.isCharged(itemStack));
                if (xbow.found()) {
                    InvUtils.swap(xbow.slot(), false);
                }
                reset(true);
                break;

            default:
                reset(false);
                break;
        }
    }

    private void reset(boolean restoreSlot) {
        if (restoreSlot && previousSlot != -1 && mc.player != null) {
            InvUtils.swap(previousSlot, false);
        }
        this.stage = Stage.IDLE;
        this.previousSlot = -1;
        this.tickDelay = 0;
    }
}        .description("The color of the marker.")
        .defaultValue(Color.MAGENTA)
        .build()
    );

    /**
     * The {@code name} parameter should be in kebab-case.
     */
    public ModuleExample() {
        super(AddonTemplate.CATEGORY, "world-origin", "An example module that highlights the center of the world.");
    }

    /**
     * Example event handling method.
     * Requires {@link AddonTemplate#getPackage()} to be setup correctly, otherwise the game will crash whenever the module is enabled.
     */
    @EventHandler
    private void onRender3d(Render3DEvent event) {
        // Create & expand the marker object
        AABB marker = new AABB(BlockPos.ZERO);
        marker = marker.expandTowards(
            scale.get() * marker.getXsize(),
            scale.get() * marker.getYsize(),
            scale.get() * marker.getZsize()
        );

        // Render the marker based on the color setting
        event.renderer.box(marker, color.get(), color.get(), ShapeMode.Both, 0);
    }
}
