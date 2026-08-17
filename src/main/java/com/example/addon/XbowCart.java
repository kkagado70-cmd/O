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
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class XbowCart extends Module {
    public enum Stage { IDLE, PLACE_RAIL, PLACE_CART, LIGHT_FIRE, WAIT_SERVER_ACK, PRO_AIMING, DISCHARGE }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder().name("action-delay-ticks").defaultValue(1).min(1).max(3).build());

    private Stage stage = Stage.IDLE;
    private int tickTimer = 0;
    private BlockHitResult targetBlockHit = null;
    private float targetYaw = 0.0f;
    private float targetPitch = 0.0f;

    public XbowCart() {
        super(Categories.Combat, "xbow-cart", "Xbow Cart com sincronizacao de face e espera de tick para o fogo nao sumir no Grim.");
    }

    @Override public void onActivate() { reset(false); }
    @Override public void onDeactivate() { reset(true); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;

        if (isRail(mc.player.getMainHandItem()) && stage == Stage.IDLE) {
            if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
                initiateCombo(hit);
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

    private boolean isRail(ItemStack s) {
        return s.is(Items.RAIL) || s.is(Items.POWERED_RAIL) || s.is(Items.DETECTOR_RAIL) || s.is(Items.ACTIVATOR_RAIL);
    }

    private FindItemResult resolveCrossbow() {
        FindItemResult charged
