package com.easytrading.paper.trade;

import com.easytrading.paper.EasyTradingPlugin;
import com.easytrading.paper.data.EconomyData;
import com.easytrading.paper.data.TransactionHistoryData;
import com.easytrading.paper.gui.MarketGui;
import com.easytrading.paper.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Represents an active trade session between two players.
 *
 * GUI Layout (6 rows = 54 slots):
 *
 * Rows 0-1 (slots 0-8, 9-17):   Player1's offer area (left side slots 0-3, 9-12)
 *                                  Separator col 4 (slots 4, 13)
 *                                  Player2's offer area (right side slots 5-8, 14-17)
 * Rows 2-3 (slots 18-26, 27-35): More offer rows (same pattern)
 * Row 4 (slots 36-44):           Money offers + status display
 * Row 5 (slots 45-53):           Confirm/Cancel buttons
 *
 * Detailed slot mapping:
 * - Player1 item slots: 0-3, 9-12, 18-21, 27-30 (16 slots)
 * - Separator:          4, 13, 22, 31, 40 (glass pane)
 * - Player2 item slots: 5-8, 14-17, 23-26, 32-35 (16 slots)
 * - Row 4: slot 36=P1 money info, 37=P1 money+, 38=P1 money-, 40=separator, 41=P2 money-, 42=P2 money+, 43=P2 money info
 * - Row 5: slot 45=P1 confirm, 46=P1 cancel, 49=status, 52=P2 cancel, 53=P2 confirm
 */
public class TradeSession {

    private final TradeManager manager;
    private final UUID player1;
    private final UUID player2;

    // Items each player has placed in the trade
    private final ItemStack[] player1Items = new ItemStack[16];
    private final ItemStack[] player2Items = new ItemStack[16];

    // Money each player offers
    private long player1Money = 0;
    private long player2Money = 0;

    // Confirmation state
    private boolean player1Confirmed = false;
    private boolean player2Confirmed = false;

    // Countdown task
    private int countdownTaskId = -1;
    private int countdownSeconds = -1;

    // Whether the trade is completed or cancelled (prevent double execution)
    private boolean finished = false;

    // The GUI
    private TradeGui gui;

    // Player1's slots in the GUI
    static final int[] P1_SLOTS = {0, 1, 2, 3, 9, 10, 11, 12, 18, 19, 20, 21, 27, 28, 29, 30};
    // Player2's slots in the GUI
    static final int[] P2_SLOTS = {5, 6, 7, 8, 14, 15, 16, 17, 23, 24, 25, 26, 32, 33, 34, 35};
    // Separator slots
    static final int[] SEPARATOR_SLOTS = {4, 13, 22, 31, 40};

    // Row 4: money controls
    static final int P1_MONEY_INFO = 36;
    static final int P1_MONEY_ADD = 37;
    static final int P1_MONEY_SUB = 38;
    static final int P2_MONEY_SUB = 41;
    static final int P2_MONEY_ADD = 42;
    static final int P2_MONEY_INFO = 43;

    // Row 5: controls
    static final int P1_CONFIRM = 45;
    static final int P1_CANCEL = 47;
    static final int STATUS_SLOT = 49;
    static final int P2_CANCEL = 51;
    static final int P2_CONFIRM = 53;

    // Money increment amounts
    static final long[] MONEY_INCREMENTS = {1, 10, 100, 1000};

    public TradeSession(TradeManager manager, UUID player1, UUID player2) {
        this.manager = manager;
        this.player1 = player1;
        this.player2 = player2;
        Arrays.fill(player1Items, null);
        Arrays.fill(player2Items, null);
    }

    public UUID getPlayer1() { return player1; }
    public UUID getPlayer2() { return player2; }
    public boolean isFinished() { return finished; }
    public EasyTradingPlugin getPlugin() { return manager.getPlugin(); }

    public void openGui(Player p1, Player p2) {
        gui = new TradeGui(this, p1, p2);
        gui.open();
    }

    /**
     * Determine if a slot belongs to the given player's offer area.
     */
    public boolean isPlayerSlot(UUID playerUuid, int rawSlot) {
        int[] slots = playerUuid.equals(player1) ? P1_SLOTS : P2_SLOTS;
        for (int s : slots) {
            if (s == rawSlot) return true;
        }
        return false;
    }

    /**
     * Get the index (0-15) within the player's item array for the given GUI slot.
     * Returns -1 if not a valid player slot.
     */
    public int getItemIndex(UUID playerUuid, int rawSlot) {
        int[] slots = playerUuid.equals(player1) ? P1_SLOTS : P2_SLOTS;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == rawSlot) return i;
        }
        return -1;
    }

    /**
     * Player places an item in their offer slot.
     */
    public void setItem(UUID playerUuid, int index, ItemStack item) {
        ItemStack[] items = playerUuid.equals(player1) ? player1Items : player2Items;
        items[index] = item != null && !Items.isEmpty(item) ? item.clone() : null;
        resetConfirmations();
        if (gui != null) gui.refresh();
    }

    /**
     * Get a player's offered items.
     */
    public ItemStack[] getItems(UUID playerUuid) {
        return playerUuid.equals(player1) ? player1Items : player2Items;
    }

    /**
     * Add money to a player's offer.
     */
    public void addMoney(UUID playerUuid, long amount) {
        if (amount <= 0) return;
        EconomyData eco = manager.getPlugin().getEconomy();
        long balance = eco.get(playerUuid);
        long currentOffer = playerUuid.equals(player1) ? player1Money : player2Money;
        long maxAdd = balance - currentOffer;
        long toAdd = Math.min(amount, maxAdd);
        if (toAdd <= 0) {
            Player p = Bukkit.getPlayer(playerUuid);
            if (p != null) manager.getPlugin().sendMessage(p, manager.getPlugin().trc("error.not_enough_funds", NamedTextColor.RED));
            return;
        }
        if (playerUuid.equals(player1)) {
            player1Money += toAdd;
        } else {
            player2Money += toAdd;
        }
        resetConfirmations();
        if (gui != null) gui.refresh();
    }

    /**
     * Remove money from a player's offer.
     */
    public void removeMoney(UUID playerUuid, long amount) {
        if (amount <= 0) return;
        if (playerUuid.equals(player1)) {
            player1Money = Math.max(0, player1Money - amount);
        } else {
            player2Money = Math.max(0, player2Money - amount);
        }
        resetConfirmations();
        if (gui != null) gui.refresh();
    }

    public long getMoney(UUID playerUuid) {
        return playerUuid.equals(player1) ? player1Money : player2Money;
    }

    /**
     * Toggle confirm for a player.
     */
    public void toggleConfirm(UUID playerUuid) {
        if (playerUuid.equals(player1)) {
            player1Confirmed = !player1Confirmed;
        } else {
            player2Confirmed = !player2Confirmed;
        }

        if (gui != null) gui.refresh();

        // If both confirmed, start countdown
        if (player1Confirmed && player2Confirmed) {
            startCountdown();
        } else {
            cancelCountdown();
        }
    }

    public boolean isConfirmed(UUID playerUuid) {
        return playerUuid.equals(player1) ? player1Confirmed : player2Confirmed;
    }

    /**
     * Reset both confirmations (when items/money change).
     */
    private void resetConfirmations() {
        boolean wasAnyConfirmed = player1Confirmed || player2Confirmed;
        player1Confirmed = false;
        player2Confirmed = false;
        cancelCountdown();
        if (wasAnyConfirmed) {
            Player p1 = Bukkit.getPlayer(player1);
            Player p2 = Bukkit.getPlayer(player2);
            if (p1 != null) manager.getPlugin().sendMessage(p1, manager.getPlugin().trc("trade.reset_confirmations", NamedTextColor.YELLOW));
            if (p2 != null) manager.getPlugin().sendMessage(p2, manager.getPlugin().trc("trade.reset_confirmations", NamedTextColor.YELLOW));
        }
    }

    private void startCountdown() {
        cancelCountdown();
        countdownSeconds = 3;

        Player p1 = Bukkit.getPlayer(player1);
        Player p2 = Bukkit.getPlayer(player2);

        countdownTaskId = Bukkit.getScheduler().runTaskTimer(manager.getPlugin(), () -> {
            if (finished) {
                cancelCountdown();
                return;
            }
            if (!player1Confirmed || !player2Confirmed) {
                cancelCountdown();
                if (gui != null) gui.refresh();
                return;
            }
            if (countdownSeconds <= 0) {
                cancelCountdown();
                executeTrade();
                return;
            }

            Player cp1 = Bukkit.getPlayer(player1);
            Player cp2 = Bukkit.getPlayer(player2);
            if (cp1 != null) manager.getPlugin().sendMessage(cp1, manager.getPlugin().trc("trade.completing_in", NamedTextColor.GOLD, countdownSeconds));
            if (cp2 != null) manager.getPlugin().sendMessage(cp2, manager.getPlugin().trc("trade.completing_in", NamedTextColor.GOLD, countdownSeconds));

            if (gui != null) gui.updateStatus(manager.getPlugin().tr("trade.gui.status.countdown", countdownSeconds));
            countdownSeconds--;
        }, 0L, 20L).getTaskId();
    }

    private void cancelCountdown() {
        if (countdownTaskId != -1) {
            Bukkit.getScheduler().cancelTask(countdownTaskId);
            countdownTaskId = -1;
        }
        countdownSeconds = -1;
    }

    /**
     * Execute the trade — swap items and money.
     * Validates everything before committing.
     */
    private void executeTrade() {
        if (finished) return;
        finished = true;
        cancelCountdown();

        Player p1 = Bukkit.getPlayer(player1);
        Player p2 = Bukkit.getPlayer(player2);

        if (p1 == null || !p1.isOnline() || p2 == null || !p2.isOnline()) {
            cancelAndReturnItems(manager.getPlugin().tr("trade.cancel.player_offline"));
            return;
        }

        EconomyData eco = manager.getPlugin().getEconomy();

        // Validate money balances
        if (player1Money > 0 && eco.get(player1) < player1Money) {
            cancelAndReturnItems(manager.getPlugin().tr("trade.cancel.insufficient_funds"));
            return;
        }
        if (player2Money > 0 && eco.get(player2) < player2Money) {
            cancelAndReturnItems(manager.getPlugin().tr("trade.cancel.insufficient_funds"));
            return;
        }

        // Collect non-null items
        List<ItemStack> p1Offer = new ArrayList<>();
        for (ItemStack item : player1Items) {
            if (item != null && !Items.isEmpty(item)) p1Offer.add(item.clone());
        }
        List<ItemStack> p2Offer = new ArrayList<>();
        for (ItemStack item : player2Items) {
            if (item != null && !Items.isEmpty(item)) p2Offer.add(item.clone());
        }

        // Transfer money
        if (player1Money > 0) {
            eco.add(player1, -player1Money);
            eco.add(player2, player1Money);
        }
        if (player2Money > 0) {
            eco.add(player2, -player2Money);
            eco.add(player1, player2Money);
        }

        // Give P1's items to P2
        for (ItemStack item : p1Offer) {
            Map<Integer, ItemStack> leftover = p2.getInventory().addItem(item);
            for (ItemStack left : leftover.values()) {
                p2.getWorld().dropItemNaturally(p2.getLocation(), left);
            }
        }

        // Give P2's items to P1
        for (ItemStack item : p2Offer) {
            Map<Integer, ItemStack> leftover = p1.getInventory().addItem(item);
            for (ItemStack left : leftover.values()) {
                p1.getWorld().dropItemNaturally(p1.getLocation(), left);
            }
        }

        // Record history
        TransactionHistoryData history = manager.getPlugin().getTransactionHistory();
        String p1Name = p1.getName();
        String p2Name = p2.getName();

        int p1ItemCount = p1Offer.stream().mapToInt(ItemStack::getAmount).sum();
        int p2ItemCount = p2Offer.stream().mapToInt(ItemStack::getAmount).sum();

        long p1NetMoney = player2Money - player1Money; // what P1 gains in money
        long p2NetMoney = player1Money - player2Money; // what P2 gains in money

        history.record(player1, "TRADE", p2Name, p1ItemCount, p1NetMoney, p2Name);
        history.record(player2, "TRADE", p1Name, p2ItemCount, p2NetMoney, p1Name);

        // Close GUIs and notify
        if (gui != null) gui.closeAll();

        manager.getPlugin().sendMessage(p1, manager.getPlugin().trc("trade.completed", NamedTextColor.GREEN));
        manager.getPlugin().sendMessage(p2, manager.getPlugin().trc("trade.completed", NamedTextColor.GREEN));

        manager.removeSession(this);
    }

    /**
     * Cancel the trade and return all items to their original owners.
     * Safe to call multiple times.
     */
    public void cancelAndReturnItems(String reason) {
        if (finished) return;
        finished = true;
        cancelCountdown();

        // Return P1's items
        Player p1 = Bukkit.getPlayer(player1);
        for (ItemStack item : player1Items) {
            if (item != null && !Items.isEmpty(item)) {
                if (p1 != null && p1.isOnline()) {
                    Map<Integer, ItemStack> leftover = p1.getInventory().addItem(item.clone());
                    for (ItemStack left : leftover.values()) {
                        p1.getWorld().dropItemNaturally(p1.getLocation(), left);
                    }
                }
                // If player is offline, items are lost (they were already removed from inventory)
                // In practice, items taken from cursor/inventory are always returned here or on death
            }
        }

        // Return P2's items
        Player p2 = Bukkit.getPlayer(player2);
        for (ItemStack item : player2Items) {
            if (item != null && !Items.isEmpty(item)) {
                if (p2 != null && p2.isOnline()) {
                    Map<Integer, ItemStack> leftover = p2.getInventory().addItem(item.clone());
                    for (ItemStack left : leftover.values()) {
                        p2.getWorld().dropItemNaturally(p2.getLocation(), left);
                    }
                }
            }
        }

        // Close GUIs
        if (gui != null) gui.closeAll();

        if (p1 != null && p1.isOnline()) {
            manager.getPlugin().sendMessage(p1, manager.getPlugin().trc("trade.cancelled", NamedTextColor.RED, reason));
        }
        if (p2 != null && p2.isOnline()) {
            manager.getPlugin().sendMessage(p2, manager.getPlugin().trc("trade.cancelled", NamedTextColor.RED, reason));
        }

        manager.removeSession(this);
    }

    /**
     * Called when a player requests to cancel.
     */
    public void cancelByPlayer(UUID playerUuid) {
        Player who = Bukkit.getPlayer(playerUuid);
        String name = who != null ? who.getName() : manager.getPlugin().tr("common.unknown");
        cancelAndReturnItems(manager.getPlugin().tr("trade.cancel.by_player", name));
    }

    /**
     * Handle a player clicking in the trade GUI.
     * Returns true if the event should be cancelled (not allowed to move item).
     */
    public boolean handleClick(Player player, int rawSlot, ItemStack cursor, ItemStack slotItem, boolean isTradeInventory) {
        if (finished) return true;
        UUID uuid = player.getUniqueId();

        // Clicks in the trade inventory
        if (isTradeInventory) {
            // Check if it's a control button
            if (isControlSlot(uuid, rawSlot)) {
                handleControlClick(uuid, rawSlot);
                return true; // always cancel control clicks
            }

            // Check if it's the player's own offer area
            if (isPlayerSlot(uuid, rawSlot)) {
                // Player is picking up their own item — allowed via the handleItemInteraction below
                return false; // let the event through, we track via handleItemChange
            }

            // Any other slot (separator, opponent's area, info slots) — block
            return true;
        }

        // Click in player's own inventory (bottom) — allow shift-clicking items into trade
        return false;
    }

    private boolean isControlSlot(UUID playerUuid, int rawSlot) {
        if (playerUuid.equals(player1)) {
            return rawSlot == P1_CONFIRM || rawSlot == P1_CANCEL
                    || rawSlot == P1_MONEY_ADD || rawSlot == P1_MONEY_SUB
                    || rawSlot == P1_MONEY_INFO;
        } else {
            return rawSlot == P2_CONFIRM || rawSlot == P2_CANCEL
                    || rawSlot == P2_MONEY_ADD || rawSlot == P2_MONEY_SUB
                    || rawSlot == P2_MONEY_INFO;
        }
    }

    private void handleControlClick(UUID playerUuid, int rawSlot) {
        boolean isP1 = playerUuid.equals(player1);

        int confirmSlot = isP1 ? P1_CONFIRM : P2_CONFIRM;
        int cancelSlot = isP1 ? P1_CANCEL : P2_CANCEL;
        int addSlot = isP1 ? P1_MONEY_ADD : P2_MONEY_ADD;
        int subSlot = isP1 ? P1_MONEY_SUB : P2_MONEY_SUB;

        if (rawSlot == confirmSlot) {
            toggleConfirm(playerUuid);
        } else if (rawSlot == cancelSlot) {
            cancelByPlayer(playerUuid);
        } else if (rawSlot == addSlot) {
            addMoney(playerUuid, 10);
        } else if (rawSlot == subSlot) {
            removeMoney(playerUuid, 10);
        }
    }

    /**
     * Called after the inventory content changes to sync the item arrays.
     * This is the source of truth — we read actual GUI contents.
     */
    public void syncItems() {
        if (finished || gui == null) return;
        boolean changed = false;

        for (int i = 0; i < P1_SLOTS.length; i++) {
            ItemStack guiItem = gui.getInventory().getItem(P1_SLOTS[i]);
            ItemStack stored = player1Items[i];
            if (!Objects.equals(guiItem, stored)) {
                player1Items[i] = guiItem != null && !Items.isEmpty(guiItem) ? guiItem.clone() : null;
                changed = true;
            }
        }
        for (int i = 0; i < P2_SLOTS.length; i++) {
            ItemStack guiItem = gui.getInventory().getItem(P2_SLOTS[i]);
            ItemStack stored = player2Items[i];
            if (!Objects.equals(guiItem, stored)) {
                player2Items[i] = guiItem != null && !Items.isEmpty(guiItem) ? guiItem.clone() : null;
                changed = true;
            }
        }

        if (changed) {
            resetConfirmations();
            if (gui != null) gui.refresh();
        }
    }

    public TradeGui getGui() { return gui; }
}

