package com.sandpickup;

// Mod Fabric (client-only) cho Minecraft Java 1.21.11, mappings Yarn.
// /sand hoặc phím J: bật/tắt. Mod tự đi tới từng item cát rơi trong 50 ô và nhặt.
// Mod tự tắt ngay khi túi đầy hoặc khi không còn cát nào trong phạm vi.
// CHƯA ĐƯỢC BIÊN DỊCH/CHẠY THỬ trong game.

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SandPickupClient implements ClientModInitializer {

    // ===== CẤU HÌNH =====
    static final double RANGE = 50.0;            // bán kính quét item cát (block)
    static final boolean INCLUDE_RED_SAND = true; // true = nhặt cả cát đỏ, false = chỉ cát thường
    static final int STUCK_WINDOW = 30;          // sau 30 tick mà gần như không nhúc nhích = kẹt
    static final int TARGET_MAX_TICKS = 300;     // quá 15 giây không nhặt được 1 item thì bỏ qua nó
    static final int BLACKLIST_TICKS = 600;      // item bị bỏ qua sẽ không được thử lại trong 30 giây
    // =====================

    private boolean running = false;
    private ItemEntity target;
    private final Map<Integer, Integer> blacklist = new HashMap<>();
    private int tickCount = 0, stuckTimer = 0, targetAge = 0;
    private double lastX, lastZ;
    private KeyBinding toggleKey;

    @Override
    public void onInitializeClient() {
        // Phím bật/tắt: mặc định J, đổi được trong Cài đặt > Điều khiển
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("sandpickup", "main"));
        toggleKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.sandpickup.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, category));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        // Gõ /sand trong chat để bật/tắt
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("sand").executes(ctx -> {
                    toggle(MinecraftClient.getInstance());
                    return 1;
                })));
    }

    private void toggle(MinecraftClient mc) {
        if (running) { stop(mc, "đã tắt"); return; }
        running = true;
        target = null;
        blacklist.clear();
        log(mc, "BẬT: tự nhặt cát trong " + (int) RANGE + " ô. Tắt bằng /sand, phím J hoặc nút Back");
    }

    private void stop(MinecraftClient mc, String why) {
        running = false;
        target = null;
        releaseKeys(mc);
        log(mc, "DỪNG: " + why);
    }

    private void log(MinecraftClient mc, String msg) {
        if (mc.player != null) mc.player.sendMessage(Text.literal("[SandPickup] " + msg), false);
    }

    private void releaseKeys(MinecraftClient mc) {
        mc.options.forwardKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
    }

    private static boolean isSand(ItemStack s) {
        return s.isOf(Items.SAND) || (INCLUDE_RED_SAND && s.isOf(Items.RED_SAND));
    }

    /** Túi đầy = không còn ô trống và không còn chồng cát nào chưa đủ 64. */
    private static boolean isFull(ClientPlayerEntity p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty() || (isSand(s) && s.getCount() < s.getMaxCount())) return false;
        }
        return true;
    }

    private boolean isBlacklisted(ItemEntity e) {
        Integer until = blacklist.get(e.getId());
        return until != null && until > tickCount;
    }

    private void blacklist(ItemEntity e) {
        blacklist.put(e.getId(), tickCount + BLACKLIST_TICKS);
        if (blacklist.size() > 300) blacklist.values().removeIf(t -> t <= tickCount);
    }

    private ItemEntity findNearest(MinecraftClient mc, ClientPlayerEntity p) {
        Box box = p.getBoundingBox().expand(RANGE);
        List<ItemEntity> items = mc.world.getEntitiesByClass(ItemEntity.class, box,
                e -> isSand(e.getStack()) && !isBlacklisted(e) && e.squaredDistanceTo(p) <= RANGE * RANGE);
        ItemEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (ItemEntity e : items) {
            double d = e.squaredDistanceTo(p);
            if (d < bestD) { bestD = d; best = e; }
        }
        return best;
    }

    /** Có nguy hiểm ngay phía trước không: dung nham, lửa, xương rồng, hoặc vực/hố sâu hơn 4 ô. */
    private boolean dangerAhead(MinecraftClient mc, ClientPlayerEntity p, double dx, double dz) {
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.5) return false;
        double ax = p.getX() + dx / len * 1.2;
        double az = p.getZ() + dz / len * 1.2;
        BlockPos feet = BlockPos.ofFloored(ax, p.getY(), az);
        for (int dy = -1; dy <= 1; dy++) {
            Block b = mc.world.getBlockState(feet.up(dy)).getBlock();
            if (b == Blocks.LAVA || b == Blocks.FIRE || b == Blocks.SOUL_FIRE
                    || b == Blocks.CACTUS || b == Blocks.MAGMA_BLOCK) return true;
        }
        for (int i = 0; i <= 4; i++) {
            BlockPos q = feet.down(i);
            if (!mc.world.getBlockState(q).getCollisionShape(mc.world, q).isEmpty()) return false;
        }
        return true; // không có nền trong 4 ô phía dưới
    }

    private void giveUp(MinecraftClient mc, ItemEntity e) {
        blacklist(e);
        target = null;
        releaseKeys(mc);
    }

    private void tick(MinecraftClient mc) {
        tickCount++;
        if (toggleKey.wasPressed()) {
            while (toggleKey.wasPressed()) { /* bỏ các lần bấm dồn */ }
            toggle(mc);
        }
        if (!running) return;
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) { running = false; target = null; return; }

        // Bấm ESC/Back mở menu tạm dừng: dừng mod luôn
        if (mc.currentScreen instanceof GameMenuScreen) { stop(mc, "bạn mở menu tạm dừng"); return; }
        // Đang gõ chat hoặc mở menu khác: đứng yên, không bấm phím nào
        if (mc.currentScreen != null) { releaseKeys(mc); return; }

        if (isFull(p)) { stop(mc, "túi đã đầy"); return; }

        // Chọn mục tiêu: giữ mục tiêu hiện tại tới khi nhặt xong hoặc phải bỏ, rồi quét ngay để lấy mục tiêu kế tiếp
        if (target == null || target.isRemoved() || isBlacklisted(target)) {
            target = findNearest(mc, p);
            if (target == null) { stop(mc, "không còn cát nào trong " + (int) RANGE + " ô"); return; }
            targetAge = 0;
            stuckTimer = 0;
            lastX = p.getX();
            lastZ = p.getZ();
        }

        double dx = target.getX() - p.getX();
        double dz = target.getZ() - p.getZ();
        double dy = target.getY() - p.getY();
        double dist = Math.sqrt(dx * dx + dz * dz);

        if (++targetAge > TARGET_MAX_TICKS) { giveUp(mc, target); return; }
        if (dangerAhead(mc, p, dx, dz)) { giveUp(mc, target); return; }
        if (++stuckTimer >= STUCK_WINDOW) {
            double moved = Math.hypot(p.getX() - lastX, p.getZ() - lastZ);
            if (moved < 0.5 && dist > 1.0) { giveUp(mc, target); return; }
            stuckTimer = 0;
            lastX = p.getX();
            lastZ = p.getZ();
        }

        // Quay về phía item rồi đi tới
        if (dist > 0.05) p.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        mc.options.forwardKey.setPressed(dist > 0.3);
        mc.options.sprintKey.setPressed(dist > 3.0);
        boolean jump = p.horizontalCollision || p.isTouchingWater() || (dy > 0.6 && dist < 2.0);
        mc.options.jumpKey.setPressed(jump);
    }
}
