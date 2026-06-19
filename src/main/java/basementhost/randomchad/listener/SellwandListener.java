package basementhost.randomchad.listener;

import basementhost.randomchad.SellwandPlugin;
import basementhost.randomchad.service.SellResult;
import basementhost.randomchad.service.SellService;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class SellwandListener implements Listener {
    private final SellwandPlugin plugin;
    private final SellService sellService;
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    public SellwandListener(SellwandPlugin plugin, SellService sellService) {
        this.plugin = plugin;
        this.sellService = sellService;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        if (plugin.getConfig().getBoolean("require-sneak", true) && !player.isSneaking()) {
            return;
        }

        ItemStack hand = event.getItem();
        if (!isWand(hand)) {
            return;
        }

        Block block = event.getClickedBlock();
        if (block == null || !(block.getState() instanceof Container container)) {
            return;
        }

        event.setCancelled(true);

        if (!player.hasPermission("simplesellwand.use")) {
            player.sendMessage(plugin.message("no-permission"));
            return;
        }

        if (!sellService.isAllowedContainer(block.getType())) {
            player.sendMessage(plugin.message("invalid-container"));
            return;
        }

        long now = System.currentTimeMillis();
        long cooldownMs = Math.max(0L, plugin.getConfig().getLong("cooldown-ms", 750L));
        long nextAllowed = cooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (cooldownMs > 0 && now < nextAllowed) {
            player.sendMessage(plugin.message("cooldown"));
            return;
        }
        cooldowns.put(player.getUniqueId(), now + cooldownMs);

        SellResult result = sellService.sellContainer(player, container.getInventory());
        switch (result.status()) {
            case SOLD -> {
                player.sendMessage(plugin.message("sold")
                        .replace("{amount}", String.valueOf(result.amountSold()))
                        .replace("{money}", plugin.economy().format(result.money())));
                player.sendMessage(plugin.message("fee-applied").replace("{fee}", plugin.economy().format(result.fee())));
            }
            case NOTHING_TO_SELL -> player.sendMessage(plugin.message("no-items"));
            case VAULT_FAILED -> player.sendMessage(plugin.message("vault-error"));
        }
    }

    private boolean isWand(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }

        String configured = plugin.getConfig().getString("wand-material", "STICK");
        Material material = Material.matchMaterial(configured == null ? "STICK" : configured);
        return item.getType() == (material == null ? Material.STICK : material);
    }
}
