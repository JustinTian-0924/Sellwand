package basementhost.randomchad.service;

import basementhost.randomchad.SellwandPlugin;
import me.gypopo.economyshopgui.api.EconomyShopGUIHook;
import me.gypopo.economyshopgui.api.objects.SellPrice;
import me.gypopo.economyshopgui.api.objects.SellPrices;
import me.gypopo.economyshopgui.util.EcoType;
import me.gypopo.economyshopgui.util.EconomyType;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class SellService {
    private final SellwandPlugin plugin;
    private final EcoType vaultEcoType;
    private final Set<Material> allowedContainers;
    private final boolean sellShulkerContents;
    private final int sellFee;

    public SellService(SellwandPlugin plugin) {
        this.plugin = plugin;
        String economyType = plugin.getConfig().getString("economy-type", "VAULT");
        EcoType configuredEcoType = EconomyType.getFromString(economyType == null ? "VAULT" : economyType);
        this.vaultEcoType = configuredEcoType == null ? EconomyType.getFromString("VAULT") : configuredEcoType;
        this.allowedContainers = loadAllowedContainers();
        this.sellShulkerContents = plugin.getConfig().getBoolean("sell-shulker-contents", true);
        this.sellFee = plugin.getConfig().getInt("handling-fee", 5);
    }

    public SellResult sellContainer(Player player, Inventory inventory) {
        SellPlan plan = buildSellPlan(inventory);
        if (plan.targets().isEmpty()) {
            return SellResult.nothingToSell();
        }

        ItemStack[] working = plan.toWorkingArray();
        boolean[] eligibleSlots = markVaultSellableSlots(player, working);

        SellPrices sellPrices = EconomyShopGUIHook.getCutSellPrices(player, working, true);
        if (sellPrices == null || sellPrices.isEmpty()) {
            return SellResult.nothingToSell();
        }

        double money = sellPrices.getPrice(vaultEcoType);
        if (money <= 0D) {
            return SellResult.nothingToSell();
        }

        int amountSold = countSold(plan.targets(), working, eligibleSlots);
        if (amountSold <= 0) {
            return SellResult.nothingToSell();
        }
        double fee = money * (sellFee / 100D);
        money = Math.max(0D, money - fee);
        EconomyResponse response = plugin.economy().depositPlayer(player, money);
        if (response == null || !response.transactionSuccess()) {
            return SellResult.vaultFailed();
        }

        applySoldItems(inventory, plan, working, eligibleSlots);
        sellPrices.updateLimits();
        return SellResult.sold(amountSold, money, fee);
    }

    public boolean isAllowedContainer(Material material) {
        return allowedContainers.contains(material);
    }

    private SellPlan buildSellPlan(Inventory inventory) {
        List<SellTarget> targets = new ArrayList<>();
        ItemStack[] topLevel = inventory.getContents();

        for (int topSlot = 0; topSlot < topLevel.length; topSlot++) {
            ItemStack item = topLevel[topSlot];
            if (isEmpty(item)) {
                continue;
            }

            if (sellShulkerContents && isShulkerBoxItem(item)) {
                addShulkerContents(targets, topSlot, item);
                continue;
            }

            targets.add(SellTarget.topLevel(topSlot, item));
        }

        return new SellPlan(targets);
    }

    private void addShulkerContents(List<SellTarget> targets, int topSlot, ItemStack shulkerItem) {
        ShulkerBoxSnapshot snapshot = readShulkerBox(shulkerItem);
        if (snapshot == null) {
            return;
        }

        ItemStack[] contents = snapshot.contents();
        for (int shulkerSlot = 0; shulkerSlot < contents.length; shulkerSlot++) {
            ItemStack item = contents[shulkerSlot];
            if (isEmpty(item)) {
                continue;
            }

            // Vanilla does not allow shulker boxes inside shulker boxes. If another plugin creates one,
            // keep it untouched rather than recursively editing nested NBT.
            if (isShulkerBoxItem(item)) {
                continue;
            }

            targets.add(SellTarget.shulkerContent(topSlot, shulkerSlot, item));
        }
    }

    private boolean[] markVaultSellableSlots(Player player, ItemStack[] items) {
        boolean[] eligibleSlots = new boolean[items.length];
        for (int i = 0; i < items.length; i++) {
            eligibleSlots[i] = hasVaultSellPrice(player, items[i]);
            if (!eligibleSlots[i]) {
                items[i] = null;
            }
        }
        return eligibleSlots;
    }

    private boolean hasVaultSellPrice(Player player, ItemStack item) {
        if (isEmpty(item)) {
            return false;
        }

        ItemStack single = item.clone();
        single.setAmount(1);
        Optional<?> optional = EconomyShopGUIHook.getSellPrice(player, single);
        if (optional.isEmpty()) {
            return false;
        }

        Object priceObject = optional.get();
        if (!(priceObject instanceof SellPrice sellPrice)) {
            return false;
        }

        return sellPrice.getPrice(vaultEcoType) > 0D;
    }

    private int countSold(List<SellTarget> targets, ItemStack[] workingAfterSell, boolean[] eligibleSlots) {
        int sold = 0;
        for (int i = 0; i < targets.size(); i++) {
            if (!eligibleSlots[i]) {
                continue;
            }

            SellTarget target = targets.get(i);
            ItemStack original = target.original();
            if (isEmpty(original)) {
                continue;
            }

            ItemStack remaining = workingAfterSell[i];
            int remainingAmount = 0;
            if (!isEmpty(remaining) && original.isSimilar(remaining)) {
                remainingAmount = remaining.getAmount();
            }
            sold += Math.max(0, original.getAmount() - remainingAmount);
        }
        return sold;
    }

    private void applySoldItems(Inventory inventory, SellPlan plan, ItemStack[] workingAfterSell, boolean[] eligibleSlots) {
        Map<Integer, ItemStack[]> shulkerUpdates = new HashMap<>();

        for (int i = 0; i < plan.targets().size(); i++) {
            if (!eligibleSlots[i]) {
                continue;
            }

            SellTarget target = plan.targets().get(i);
            ItemStack remaining = normalizeRemaining(workingAfterSell[i]);

            if (target.source() == SellSource.TOP_LEVEL) {
                inventory.setItem(target.topSlot(), remaining);
                continue;
            }

            ItemStack[] contents = shulkerUpdates.computeIfAbsent(target.topSlot(), slot -> {
                ItemStack shulker = inventory.getItem(slot);
                ShulkerBoxSnapshot snapshot = readShulkerBox(shulker);
                return snapshot == null ? null : cloneContents(snapshot.contents());
            });

            if (contents != null) {
                contents[target.shulkerSlot()] = remaining;
            }
        }

        for (Map.Entry<Integer, ItemStack[]> entry : shulkerUpdates.entrySet()) {
            int topSlot = entry.getKey();
            ItemStack[] contents = entry.getValue();
            if (contents == null) {
                continue;
            }

            ItemStack shulker = inventory.getItem(topSlot);
            ItemStack updated = writeShulkerContents(shulker, contents);
            if (updated != null) {
                inventory.setItem(topSlot, updated);
            }
        }
    }

    private ItemStack normalizeRemaining(ItemStack item) {
        if (isEmpty(item) || item.getAmount() <= 0) {
            return null;
        }
        return item.clone();
    }

    private ItemStack[] cloneContents(ItemStack[] source) {
        ItemStack[] clone = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) {
            clone[i] = source[i] == null ? null : source[i].clone();
        }
        return clone;
    }

    private ShulkerBoxSnapshot readShulkerBox(ItemStack item) {
        if (!isShulkerBoxItem(item)) {
            return null;
        }
        if (!(item.getItemMeta() instanceof BlockStateMeta meta)) {
            return null;
        }
        if (!(meta.getBlockState() instanceof ShulkerBox shulkerBox)) {
            return null;
        }
        return new ShulkerBoxSnapshot(cloneContents(shulkerBox.getInventory().getContents()));
    }

    private ItemStack writeShulkerContents(ItemStack original, ItemStack[] contents) {
        if (!isShulkerBoxItem(original)) {
            return null;
        }
        if (!(original.getItemMeta() instanceof BlockStateMeta meta)) {
            return null;
        }
        if (!(meta.getBlockState() instanceof ShulkerBox shulkerBox)) {
            return null;
        }

        ItemStack updated = original.clone();
        shulkerBox.getInventory().setContents(cloneContents(contents));
        meta.setBlockState(shulkerBox);
        updated.setItemMeta(meta);
        return updated;
    }

    private boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    private boolean isShulkerBoxItem(ItemStack item) {
        return item != null && item.getType().name().endsWith("SHULKER_BOX");
    }

    private Set<Material> loadAllowedContainers() {
        Set<Material> materials = new HashSet<>();
        for (String entry : plugin.getConfig().getStringList("allowed-containers")) {
            if (entry.equalsIgnoreCase("SHULKER_BOXES")) {
                addAllShulkerBoxes(materials);
                continue;
            }

            Material material = Material.matchMaterial(entry);
            if (material != null) {
                materials.add(material);
            } else {
                plugin.getLogger().warning("Unknown material in allowed-containers: " + entry);
            }
        }

        if (materials.isEmpty()) {
            materials.add(Material.CHEST);
            materials.add(Material.TRAPPED_CHEST);
            materials.add(Material.BARREL);
            addAllShulkerBoxes(materials);
        }
        return materials;
    }

    private void addAllShulkerBoxes(Set<Material> materials) {
        for (Material material : Material.values()) {
            if (material.name().endsWith("SHULKER_BOX")) {
                materials.add(material);
            }
        }
    }

    private record SellPlan(List<SellTarget> targets) {
        private ItemStack[] toWorkingArray() {
            ItemStack[] working = new ItemStack[targets.size()];
            for (int i = 0; i < targets.size(); i++) {
                working[i] = targets.get(i).original().clone();
            }
            return working;
        }
    }

    private record SellTarget(SellSource source, int topSlot, int shulkerSlot, ItemStack original) {
        private static SellTarget topLevel(int topSlot, ItemStack original) {
            return new SellTarget(SellSource.TOP_LEVEL, topSlot, -1, original.clone());
        }

        private static SellTarget shulkerContent(int topSlot, int shulkerSlot, ItemStack original) {
            return new SellTarget(SellSource.SHULKER_CONTENT, topSlot, shulkerSlot, original.clone());
        }
    }

    private enum SellSource {
        TOP_LEVEL,
        SHULKER_CONTENT
    }

    private record ShulkerBoxSnapshot(ItemStack[] contents) {
    }
}
