package com.easytrading.paper.trade;

import com.easytrading.paper.EasyTradingPlugin;
import com.easytrading.paper.data.EconomyData;
import com.easytrading.paper.data.TradeAuditLog;
import com.easytrading.paper.data.TransactionHistoryData;
import com.easytrading.paper.util.Items;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents an active trade session between two players.
 *
 * <p>The GUI is an escrow container. Offer-slot clicks are cancelled and then
 * applied here synchronously, so the cached offer and visual escrow always
 * change together before a confirmation can be processed.</p>
 */
public class TradeSession {

    private enum State { OPEN, SETTLING, FINISHED }

    private record OfferSnapshot(List<ItemStack> player1, List<ItemStack> player2) { }

    private final TradeManager manager;
    private final UUID player1;
    private final UUID player2;
    private final ItemStack[] player1Items = new ItemStack[16];
    private final ItemStack[] player2Items = new ItemStack[16];

    private long player1Money;
    private long player2Money;
    private boolean player1Confirmed;
    private boolean player2Confirmed;
    private int countdownTaskId = -1;
    private int countdownSeconds = -1;
    /** Guarded by this; SETTLING prevents close/click callbacks racing commit. */
    private State state = State.OPEN;
    private TradeGui gui;

    static final int[] P1_SLOTS = {0, 1, 2, 3, 9, 10, 11, 12, 18, 19, 20, 21, 27, 28, 29, 30};
    static final int[] P2_SLOTS = {5, 6, 7, 8, 14, 15, 16, 17, 23, 24, 25, 26, 32, 33, 34, 35};
    static final int[] SEPARATOR_SLOTS = {4, 13, 22, 31, 40};
    static final int P1_MONEY_INFO = 36;
    static final int P1_MONEY_ADD = 37;
    static final int P1_MONEY_SUB = 38;
    static final int P2_MONEY_SUB = 41;
    static final int P2_MONEY_ADD = 42;
    static final int P2_MONEY_INFO = 43;
    static final int P1_CONFIRM = 45;
    static final int P1_CANCEL = 47;
    static final int STATUS_SLOT = 49;
    static final int P2_CANCEL = 51;
    static final int P2_CONFIRM = 53;

    public TradeSession(TradeManager manager, UUID player1, UUID player2) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.player1 = Objects.requireNonNull(player1, "player1");
        this.player2 = Objects.requireNonNull(player2, "player2");
    }

    public UUID getPlayer1() { return player1; }
    public UUID getPlayer2() { return player2; }

    /** A session is terminal while it is settling as well as after completion. */
    public synchronized boolean isFinished() { return state != State.OPEN; }
    public EasyTradingPlugin getPlugin() { return manager.getPlugin(); }

    public synchronized void openGui(Player p1, Player p2) {
        if (state != State.OPEN || p1 == null || p2 == null
                || !isParticipant(p1.getUniqueId()) || !isParticipant(p2.getUniqueId())) return;
        gui = new TradeGui(this, p1, p2);
        gui.open();
    }

    private boolean isParticipant(UUID uuid) {
        return uuid != null && (player1.equals(uuid) || player2.equals(uuid));
    }

    public synchronized boolean isPlayerSlot(UUID playerUuid, int rawSlot) {
        if (!isParticipant(playerUuid)) return false;
        int[] slots = playerUuid.equals(player1) ? P1_SLOTS : P2_SLOTS;
        for (int slot : slots) if (slot == rawSlot) return true;
        return false;
    }

    public synchronized int getItemIndex(UUID playerUuid, int rawSlot) {
        if (!isParticipant(playerUuid)) return -1;
        int[] slots = playerUuid.equals(player1) ? P1_SLOTS : P2_SLOTS;
        for (int i = 0; i < slots.length; i++) if (slots[i] == rawSlot) return i;
        return -1;
    }

    /** Update a cached offer slot for callers that do not use the live GUI. */
    public synchronized void setItem(UUID playerUuid, int index, ItemStack item) {
        if (state != State.OPEN || !isParticipant(playerUuid) || index < 0 || index >= 16) return;
        if (!ensureAllowed(playerUuid)) return;
        ItemStack[] items = playerUuid.equals(player1) ? player1Items : player2Items;
        items[index] = copyOrNull(item);
        resetConfirmations();
        if (gui != null) gui.refresh();
    }

    /** Return a defensive copy; callers cannot mutate the escrow cache. */
    public synchronized ItemStack[] getItems(UUID playerUuid) {
        if (!isParticipant(playerUuid)) return new ItemStack[0];
        ItemStack[] source = playerUuid.equals(player1) ? player1Items : player2Items;
        ItemStack[] copy = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) copy[i] = copyOrNull(source[i]);
        return copy;
    }

    /** Add money to a participant's offer without overflowing or reserving twice. */
    public synchronized void addMoney(UUID playerUuid, long amount) {
        if (state != State.OPEN || !isParticipant(playerUuid) || amount <= 0) return;
        if (!ensureAllowed(playerUuid)) return;
        EconomyData eco = manager.getPlugin().getEconomy();
        if (eco == null) return;
        long balance = eco.get(playerUuid);
        long currentOffer = getMoneyUnsafe(playerUuid);
        if (balance < 0 || currentOffer < 0 || balance <= currentOffer) {
            sendInsufficientFunds(playerUuid);
            return;
        }
        long toAdd = Math.min(amount, balance - currentOffer);
        if (toAdd <= 0) {
            sendInsufficientFunds(playerUuid);
            return;
        }
        try {
            setMoneyUnsafe(playerUuid, Math.addExact(currentOffer, toAdd));
        } catch (ArithmeticException overflow) {
            sendInsufficientFunds(playerUuid);
            return;
        }
        resetConfirmations();
        if (gui != null) gui.refresh();
    }

    private void sendInsufficientFunds(UUID playerUuid) {
        Player p = Bukkit.getPlayer(playerUuid);
        if (p != null) manager.getPlugin().sendMessage(p,
                manager.getPlugin().trc("error.not_enough_funds", NamedTextColor.RED));
    }

    public synchronized void removeMoney(UUID playerUuid, long amount) {
        if (state != State.OPEN || !isParticipant(playerUuid) || amount <= 0) return;
        if (!ensureAllowed(playerUuid)) return;
        setMoneyUnsafe(playerUuid, Math.max(0L, getMoneyUnsafe(playerUuid) - amount));
        resetConfirmations();
        if (gui != null) gui.refresh();
    }

    public synchronized long getMoney(UUID playerUuid) {
        return isParticipant(playerUuid) ? getMoneyUnsafe(playerUuid) : 0L;
    }

    private long getMoneyUnsafe(UUID playerUuid) {
        return playerUuid.equals(player1) ? player1Money : player2Money;
    }

    private void setMoneyUnsafe(UUID playerUuid, long amount) {
        if (playerUuid.equals(player1)) player1Money = amount;
        else player2Money = amount;
    }

    /** Toggle confirmation for a participant. */
    public synchronized void toggleConfirm(UUID playerUuid) {
        if (state != State.OPEN || !isParticipant(playerUuid)) return;
        if (!ensureAllowed(playerUuid)) return;
        if (playerUuid.equals(player1)) player1Confirmed = !player1Confirmed;
        else player2Confirmed = !player2Confirmed;
        if (gui != null) gui.refresh();
        if (player1Confirmed && player2Confirmed) startCountdown();
        else cancelCountdown();
    }

    public synchronized boolean isConfirmed(UUID playerUuid) {
        if (!isParticipant(playerUuid)) return false;
        return playerUuid.equals(player1) ? player1Confirmed : player2Confirmed;
    }

    /** Reset both confirmations when an offer changes. */
    private void resetConfirmations() {
        if (state != State.OPEN) return;
        boolean wasAnyConfirmed = player1Confirmed || player2Confirmed;
        player1Confirmed = false;
        player2Confirmed = false;
        cancelCountdown();
        if (wasAnyConfirmed) {
            Player p1 = Bukkit.getPlayer(player1);
            Player p2 = Bukkit.getPlayer(player2);
            if (p1 != null) manager.getPlugin().sendMessage(p1,
                    manager.getPlugin().trc("trade.reset_confirmations", NamedTextColor.YELLOW));
            if (p2 != null) manager.getPlugin().sendMessage(p2,
                    manager.getPlugin().trc("trade.reset_confirmations", NamedTextColor.YELLOW));
        }
    }

    /** Called before an offer interaction to invalidate countdown. */
    public synchronized void noteOfferInteraction(UUID playerUuid) {
        if (state == State.OPEN && isParticipant(playerUuid)) resetConfirmations();
    }

    private void startCountdown() {
        cancelCountdown();
        countdownSeconds = 3;
        countdownTaskId = Bukkit.getScheduler().runTaskTimer(manager.getPlugin(), () -> {
            synchronized (TradeSession.this) {
                if (state != State.OPEN) {
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
                if (cp1 != null) manager.getPlugin().sendMessage(cp1,
                        manager.getPlugin().trc("trade.completing_in", NamedTextColor.GOLD, countdownSeconds));
                if (cp2 != null) manager.getPlugin().sendMessage(cp2,
                        manager.getPlugin().trc("trade.completing_in", NamedTextColor.GOLD, countdownSeconds));
                if (gui != null) gui.updateStatus(manager.getPlugin().tr("trade.gui.status.countdown", countdownSeconds));
                countdownSeconds--;
            }
        }, 0L, 20L).getTaskId();
    }

    private void cancelCountdown() {
        if (countdownTaskId != -1) {
            Bukkit.getScheduler().cancelTask(countdownTaskId);
            countdownTaskId = -1;
        }
        countdownSeconds = -1;
    }

    /** Validate and settle; no state is terminal until all preflight checks pass. */
    private synchronized void executeTrade() {
        if (state != State.OPEN || !player1Confirmed || !player2Confirmed) return;
        cancelCountdown();

        // Defensively verify the escrow in case another plugin changed the GUI.
        if (syncItemsInternal()) return;

        Player p1 = Bukkit.getPlayer(player1);
        Player p2 = Bukkit.getPlayer(player2);
        if (!isOnline(p1) || !isOnline(p2)) {
            OfferSnapshot offers = snapshotSafely();
            EconomyData currentEconomy = manager.getPlugin().getEconomy();
            long balance1 = balanceFor(currentEconomy, player1);
            long balance2 = balanceFor(currentEconomy, player2);
            auditTrade("CANCELLED", "player_offline", balance1, balance2,
                    balance1, balance2, balance1, balance2, offers, true, false, false);
            cancelAndReturnItems(manager.getPlugin().tr("trade.cancel.player_offline"));
            return;
        }
        if (isRestrictedGameMode(p1) || isRestrictedGameMode(p2)) {
            cancelAndReturnItems(manager.getPlugin().tr("trade.cancel.restricted_gamemode"));
            return;
        }
        EconomyData eco = manager.getPlugin().getEconomy();
        if (eco == null) {
            OfferSnapshot offers = snapshotSafely();
            auditTrade("CANCELLED", "economy_unavailable", 0L, 0L, 0L, 0L,
                    0L, 0L, offers, true, false, false);
            cancelAndReturnItems(manager.getPlugin().tr("trade.cancel.internal_error"));
            return;
        }

        TradeSettlement.Result plan = TradeSettlement.plan(
                eco.get(player1), eco.get(player2), player1Money, player2Money);
        if (!plan.valid()) {
            auditTrade("CANCELLED", plan.failure().name().toLowerCase(Locale.ROOT),
                    eco.get(player1), eco.get(player2), eco.get(player1), eco.get(player2),
                    eco.get(player1), eco.get(player2), snapshotSafely(), true, false, false);
            cancelAndReturnItems(reasonFor(plan.failure()));
            return;
        }

        OfferSnapshot offers = snapshotSafely();
        long balance1Before = eco.get(player1);
        long balance2Before = eco.get(player2);
        // Enter the terminal-in-progress state before touching either side.
        // Close/click callbacks can no longer race the escrow drain.
        state = State.SETTLING;
        boolean escrowCleared = true;
        try {
            // Remove the virtual escrow before committing money. If the
            // checked economy operation rejects the trade, finishCancelled()
            // returns this exact snapshot; no account is charged.
            clearOfferEscrow();
        } catch (RuntimeException clearFailure) {
            escrowCleared = false;
            // Keep going with the immutable snapshot. The GUI is closed while
            // SETTLING, so a third-party inventory implementation cannot use a
            // stale slot to submit a second trade.
            manager.getPlugin().getLogger().warning("Could not clear trade escrow: "
                    + clearFailure.getMessage());
        }

        EconomyData.TradeSettlementResult result;
        try {
            result = eco.applyTradeSettlement(player1, player2, player1Money, player2Money);
        } catch (RuntimeException settlementFailure) {
            manager.getPlugin().getLogger().warning("Could not settle trade balances: "
                    + settlementFailure.getMessage());
            auditTrade("CANCELLED", "settlement_exception", balance1Before, balance2Before,
                    eco.get(player1), eco.get(player2), balance1Before, balance2Before,
                    offers, escrowCleared, false, false);
            finishCancelled(manager.getPlugin().tr("trade.cancel.internal_error"),
                    offers.player1(), offers.player2());
            return;
        }
        if (result != EconomyData.TradeSettlementResult.SUCCESS) {
            // applyTradeSettlement promises no mutation on failure.
            auditTrade("CANCELLED", reasonFor(result), balance1Before, balance2Before,
                    balance1Before, balance2Before, balance1Before, balance2Before,
                    offers, escrowCleared, false, false);
            finishCancelled(reasonFor(result), offers.player1(), offers.player2());
            return;
        }

        try {
            // deliverItems catches inventory/persistence failures per stack and
            // queues only the undelivered remainder; do not retry the complete
            // offer here or a partial exception could duplicate delivered items.
            boolean player1Delivery = deliverItems(p2, player2, offers.player1());
            boolean player2Delivery = deliverItems(p1, player1, offers.player2());
            boolean deliveryVerified = player1Delivery && player2Delivery;
            long balance1After = eco.get(player1);
            long balance2After = eco.get(player2);
            boolean balancesVerified = balance1After == plan.player1Balance()
                    && balance2After == plan.player2Balance();
            boolean economyPersisted;
            try {
                economyPersisted = eco.save();
            } catch (RuntimeException saveFailure) {
                economyPersisted = false;
                manager.getPlugin().getLogger().warning("Could not immediately save trade balances: "
                        + saveFailure.getMessage());
            }
            auditTrade("COMMITTED", balancesVerified && deliveryVerified && escrowCleared ? "verified" : "post_commit_check",
                    balance1Before, balance2Before, balance1After, balance2After,
                    plan.player1Balance(), plan.player2Balance(), offers,
                    escrowCleared, balancesVerified && deliveryVerified, economyPersisted);

            TransactionHistoryData history = manager.getPlugin().getTransactionHistory();
            if (history != null) {
                try {
                    int p1ItemCount = itemCount(offers.player1());
                    int p2ItemCount = itemCount(offers.player2());
                    long p1NetMoney = safeDifference(player2Money, player1Money);
                    long p2NetMoney = safeDifference(player1Money, player2Money);
                    history.record(player1, "TRADE", p2.getName(), p1ItemCount, p1NetMoney, p2.getName());
                    history.record(player2, "TRADE", p1.getName(), p2ItemCount, p2NetMoney, p1.getName());
                    history.save();
                } catch (RuntimeException historyFailure) {
                    manager.getPlugin().getLogger().warning("Could not record trade history: "
                            + historyFailure.getMessage());
                }
            }

            try {
                manager.getPlugin().sendMessage(p1,
                        manager.getPlugin().trc("trade.completed", NamedTextColor.GREEN));
                manager.getPlugin().sendMessage(p2,
                        manager.getPlugin().trc("trade.completed", NamedTextColor.GREEN));
            } catch (RuntimeException messageFailure) {
                manager.getPlugin().getLogger().warning("Could not notify completed trade: "
                        + messageFailure.getMessage());
            }
        } finally {
            state = State.FINISHED;
            closeGuiSafely();
            manager.removeSession(this);
        }
    }

    private static boolean isOnline(Player p) { return p != null && p.isOnline(); }

    private static int itemCount(List<ItemStack> items) {
        long total = 0L;
        for (ItemStack item : items) total += item.getAmount();
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    private static long safeDifference(long left, long right) {
        try { return Math.subtractExact(left, right); }
        catch (ArithmeticException overflow) { return left >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE; }
    }

    private String reasonFor(TradeSettlement.Failure failure) {
        return switch (failure) {
            case PLAYER1_INSUFFICIENT_FUNDS, PLAYER2_INSUFFICIENT_FUNDS ->
                    manager.getPlugin().tr("trade.cancel.insufficient_funds");
            case NEGATIVE_VALUE, BALANCE_OVERFLOW -> manager.getPlugin().tr("trade.cancel.invalid_amount");
            case NONE -> manager.getPlugin().tr("trade.cancel.internal_error");
        };
    }

    private String reasonFor(EconomyData.TradeSettlementResult result) {
        return switch (result) {
            case INSUFFICIENT_FUNDS -> manager.getPlugin().tr("trade.cancel.insufficient_funds");
            case INVALID_AMOUNT, OVERFLOW -> manager.getPlugin().tr("trade.cancel.invalid_amount");
            case INVALID_PLAYER -> manager.getPlugin().tr("trade.cancel.player_offline");
            case SUCCESS -> manager.getPlugin().tr("trade.cancel.internal_error");
        };
    }

    private OfferSnapshot snapshotSafely() {
        try {
            return currentOffers();
        } catch (RuntimeException failure) {
            manager.getPlugin().getLogger().warning("Could not snapshot trade escrow: "
                    + failure.getMessage());
            // The cached copy is preferable to losing escrow if a nonstandard
            // inventory implementation fails while being read. The normal
            // Bukkit inventory path never reaches this fallback.
            return new OfferSnapshot(copyItems(player1Items), copyItems(player2Items));
        }
    }

    /** Cancel and return the live escrow; safe to call repeatedly. */
    public synchronized void cancelAndReturnItems(String reason) {
        if (state != State.OPEN) return;
        cancelCountdown();
        OfferSnapshot offers = snapshotSafely();
        state = State.SETTLING;
        try {
            clearOfferEscrow();
        } catch (RuntimeException clearFailure) {
            manager.getPlugin().getLogger().warning("Could not clear cancelled trade escrow: "
                    + clearFailure.getMessage());
        }
        finishCancelled(reason, offers.player1(), offers.player2());
    }

    private void finishCancelled(String reason, List<ItemStack> p1Items, List<ItemStack> p2Items) {
        boolean player1Returned = false;
        boolean player2Returned = false;
        try {
            try {
                player1Returned = returnItems(player1, p1Items);
            } catch (RuntimeException failure) {
                manager.getPlugin().getLogger().warning("Could not return player 1 trade escrow: "
                        + failure.getMessage());
            }
            try {
                player2Returned = returnItems(player2, p2Items);
            } catch (RuntimeException failure) {
                manager.getPlugin().getLogger().warning("Could not return player 2 trade escrow: "
                        + failure.getMessage());
            }
        } finally {
            // Never leave a terminal session indexed forever because an
            // inventory adapter or persistence provider threw unexpectedly.
            state = State.FINISHED;
            closeGuiSafely();
            String safeReason = reason == null || reason.isBlank()
                    ? manager.getPlugin().tr("trade.cancel.internal_error") : reason;
            EconomyData economy = manager.getPlugin().getEconomy();
            long balance1 = balanceFor(economy, player1);
            long balance2 = balanceFor(economy, player2);
            auditTrade("CANCELLED", safeReason, balance1, balance2, balance1, balance2,
                    balance1, balance2, new OfferSnapshot(p1Items == null ? List.of() : p1Items,
                            p2Items == null ? List.of() : p2Items), true, player1Returned && player2Returned,
                    true);
            Player p1 = Bukkit.getPlayer(player1);
            Player p2 = Bukkit.getPlayer(player2);
            try {
                if (isOnline(p1)) manager.getPlugin().sendMessage(p1,
                        manager.getPlugin().trc("trade.cancelled", NamedTextColor.RED, safeReason));
                if (isOnline(p2)) manager.getPlugin().sendMessage(p2,
                        manager.getPlugin().trc("trade.cancelled", NamedTextColor.RED, safeReason));
            } catch (RuntimeException messageFailure) {
                manager.getPlugin().getLogger().warning("Could not notify cancelled trade: "
                        + messageFailure.getMessage());
            } finally {
                manager.removeSession(this);
            }
        }
    }

    /** Return items to an online player, or queue them for reconnect. */
    private boolean returnItems(UUID owner, List<ItemStack> items) {
        if (items == null || items.isEmpty()) return true;
        Player online = Bukkit.getPlayer(owner);
        List<ItemStack> queued = new ArrayList<>();
        boolean returned = true;
        for (ItemStack item : items) {
            if (Items.isEmpty(item)) continue;
            if (!isOnline(online) || online.isDead()) {
                returned = false;
                queued.add(item.clone());
                continue;
            }
            try {
                Map<Integer, ItemStack> leftover = online.getInventory().addItem(item.clone());
                if (!leftover.isEmpty()) returned = false;
                for (ItemStack left : leftover.values()) queueLeftover(left, queued);
            } catch (RuntimeException failure) {
                returned = false;
                manager.getPlugin().getLogger().warning("Could not return trade item to " + owner + ": "
                        + failure.getMessage());
                queued.add(item.clone());
            }
        }
        return returned && persistQueued(owner, queued);
    }

    private boolean deliverItems(Player recipient, UUID recipientId, List<ItemStack> items) {
        if (items == null || items.isEmpty()) return true;
        List<ItemStack> queued = new ArrayList<>();
        boolean verified = true;
        for (ItemStack item : items) {
            if (Items.isEmpty(item)) continue;
            if (!isOnline(recipient) || recipient.isDead()) {
                verified = false;
                queued.add(item.clone());
                continue;
            }
            try {
                Map<Integer, ItemStack> leftover = recipient.getInventory().addItem(item.clone());
                if (!leftover.isEmpty()) verified = false;
                for (ItemStack left : leftover.values()) queueLeftover(left, queued);
            } catch (RuntimeException failure) {
                verified = false;
                manager.getPlugin().getLogger().warning("Could not deliver trade item to " + recipientId + ": "
                        + failure.getMessage());
                queued.add(item.clone());
            }
        }
        return verified && persistQueued(recipientId, queued);
    }

    private void queueLeftover(ItemStack item, List<ItemStack> queued) {
        if (Items.isEmpty(item)) return;
        // Keep leftovers in the durable delivery queue instead of dropping
        // them into a potentially unloaded or hostile world. HUD/login
        // delivery retries when inventory space becomes available.
        queued.add(item.clone());
    }

    private boolean persistQueued(UUID owner, List<ItemStack> queued) {
        if (queued.isEmpty()) return true;
        if (manager.getPlugin().getMarketDeliveries() == null) {
            manager.getPlugin().getLogger().severe("Trade item could not be queued for " + owner);
            return false;
        }
        try {
            for (ItemStack item : queued) manager.getPlugin().getMarketDeliveries().add(owner, item);
        } catch (RuntimeException failure) {
            manager.getPlugin().getLogger().severe("Could not queue trade item for " + owner + ": "
                    + failure.getMessage());
            return false;
        }
        try {
            manager.getPlugin().getMarketDeliveries().save();
        } catch (RuntimeException failure) {
            manager.getPlugin().getLogger().warning("Could not persist queued trade item: "
                    + failure.getMessage());
            return false;
        }
        return true;
    }

    private void auditTrade(String outcome, String reason,
                            long balance1Before, long balance2Before,
                            long balance1After, long balance2After,
                            long expected1, long expected2,
                            OfferSnapshot offers, boolean escrowCleared,
                            boolean verified, boolean economyPersisted) {
        try {
            TradeAuditLog.append(manager.getPlugin().getDataFolder().toPath(), outcome, reason,
                    player1, nameOf(player1), player2, nameOf(player2),
                    balance1Before, balance2Before, balance1After, balance2After,
                    expected1, expected2, player1Money, player2Money,
                    itemCount(offers.player1()), itemCount(offers.player2()), escrowCleared,
                    balance1After == expected1 && balance2After == expected2,
                    verified, economyPersisted, reason);
        } catch (RuntimeException failure) {
            manager.getPlugin().getLogger().warning("Could not write trade audit log: "
                    + failure.getMessage());
        }
    }

    private String nameOf(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        return player == null ? uuid.toString() : player.getName();
    }

    private static long balanceFor(EconomyData economy, UUID player) {
        return economy == null ? 0L : economy.get(player);
    }

    private void closeGuiSafely() {
        if (gui == null) return;
        try { gui.closeAll(); }
        catch (RuntimeException failure) {
            manager.getPlugin().getLogger().warning("Could not close trade GUI: " + failure.getMessage());
        }
    }

    public synchronized void cancelByPlayer(UUID playerUuid) {
        if (state != State.OPEN || !isParticipant(playerUuid)) return;
        Player who = Bukkit.getPlayer(playerUuid);
        String name = who != null ? who.getName() : manager.getPlugin().tr("common.unknown");
        cancelAndReturnItems(manager.getPlugin().tr("trade.cancel.by_player", name));
    }

    /** Compatibility overload retained for existing integrations. */
    public boolean handleClick(Player player, int rawSlot, ItemStack cursor,
                               ItemStack slotItem, boolean isTradeInventory) {
        return handleClick(player, rawSlot, cursor, slotItem, isTradeInventory, null, null);
    }

    public synchronized boolean handleClick(Player player, int rawSlot, ItemStack cursor,
                                             ItemStack slotItem, boolean isTradeInventory,
                                             InventoryAction action, ClickType clickType) {
        if (state != State.OPEN || player == null || !isParticipant(player.getUniqueId())) return true;
        UUID uuid = player.getUniqueId();
        if (!ensureAllowed(uuid)) return true;
        // A double click initiated in the player's lower inventory can still
        // collect matching stacks from the top inventory. Never let it touch
        // the counterparty's escrow. Creative cloning and unknown actions are
        // also fail-closed regardless of the clicked half.
        if (clickType == ClickType.DOUBLE_CLICK
                || action == InventoryAction.COLLECT_TO_CURSOR
                || action == InventoryAction.CLONE_STACK
                || action == InventoryAction.UNKNOWN) return true;
        if (!isTradeInventory) return false;
        if (isControlSlot(uuid, rawSlot)) {
            handleControlClick(uuid, rawSlot);
            return true;
        }
        if (!isPlayerSlot(uuid, rawSlot)) return true;
        // Offer slots are always cancelled and updated synchronously below.
        // This removes Bukkit's post-event mutation window before confirmation.
        applyOfferClick(player, rawSlot, cursor, clickType);
        return true;
    }

    private boolean isControlSlot(UUID playerUuid, int rawSlot) {
        if (playerUuid.equals(player1)) {
            return rawSlot == P1_CONFIRM || rawSlot == P1_CANCEL
                    || rawSlot == P1_MONEY_ADD || rawSlot == P1_MONEY_SUB || rawSlot == P1_MONEY_INFO;
        }
        if (playerUuid.equals(player2)) {
            return rawSlot == P2_CONFIRM || rawSlot == P2_CANCEL
                    || rawSlot == P2_MONEY_ADD || rawSlot == P2_MONEY_SUB || rawSlot == P2_MONEY_INFO;
        }
        return false;
    }

    private void handleControlClick(UUID playerUuid, int rawSlot) {
        boolean isP1 = playerUuid.equals(player1);
        int confirmSlot = isP1 ? P1_CONFIRM : P2_CONFIRM;
        int cancelSlot = isP1 ? P1_CANCEL : P2_CANCEL;
        int addSlot = isP1 ? P1_MONEY_ADD : P2_MONEY_ADD;
        int subSlot = isP1 ? P1_MONEY_SUB : P2_MONEY_SUB;
        if (rawSlot == confirmSlot) toggleConfirm(playerUuid);
        else if (rawSlot == cancelSlot) cancelByPlayer(playerUuid);
        else if (rawSlot == addSlot) addMoney(playerUuid, 10);
        else if (rawSlot == subSlot) removeMoney(playerUuid, 10);
    }

    /** Synchronize cached snapshots after an external inventory modification. */
    public synchronized void syncItems() {
        if (state != State.OPEN || gui == null || gui.getInventory() == null) return;
        syncItemsInternal();
    }

    private boolean syncItemsInternal() {
        if (gui == null || gui.getInventory() == null) return false;
        Inventory inventory = gui.getInventory();
        boolean changed = false;
        for (int i = 0; i < P1_SLOTS.length; i++) {
            ItemStack current = copyOrNull(inventory.getItem(P1_SLOTS[i]));
            if (!Objects.equals(current, player1Items[i])) {
                player1Items[i] = current;
                changed = true;
            }
        }
        for (int i = 0; i < P2_SLOTS.length; i++) {
            ItemStack current = copyOrNull(inventory.getItem(P2_SLOTS[i]));
            if (!Objects.equals(current, player2Items[i])) {
                player2Items[i] = current;
                changed = true;
            }
        }
        if (changed) {
            resetConfirmations();
            if (gui != null) gui.refresh();
        }
        return changed;
    }

    private void applyOfferClick(Player player, int rawSlot, ItemStack cursor, ClickType clickType) {
        // Shift, number-key, double-click and creative inventory actions are
        // deliberately unsupported: accepting any of them would require
        // reproducing multiple inventory mutations atomically.
        if (clickType == null || clickType.isShiftClick()
                || (!clickType.isLeftClick() && !clickType.isRightClick())) {
            return;
        }
        Inventory inventory = gui == null ? null : gui.getInventory();
        int index = getItemIndex(player.getUniqueId(), rawSlot);
        if (inventory == null || index < 0) return;

        ItemStack slot = copyOrNull(inventory.getItem(rawSlot));
        ItemStack held = copyOrNull(cursor);
        boolean rightClick = clickType.isRightClick();

        if (held == null) {
            if (slot == null) return;
            if (rightClick) {
                int taken = (slot.getAmount() + 1) / 2;
                held = slot.clone();
                held.setAmount(taken);
                slot.setAmount(slot.getAmount() - taken);
                slot = copyOrNull(slot);
            } else {
                held = slot;
                slot = null;
            }
        } else if (slot == null) {
            if (rightClick) {
                slot = held.clone();
                slot.setAmount(1);
                held.setAmount(held.getAmount() - 1);
                held = copyOrNull(held);
            } else {
                slot = held;
                held = null;
            }
        } else if (slot.isSimilar(held)) {
            int space = Math.max(0, slot.getMaxStackSize() - slot.getAmount());
            int moved = rightClick ? Math.min(1, space) : Math.min(space, held.getAmount());
            if (moved == 0) return;
            slot.setAmount(slot.getAmount() + moved);
            held.setAmount(held.getAmount() - moved);
            held = copyOrNull(held);
        } else {
            ItemStack swap = slot;
            slot = held;
            held = swap;
        }

        resetConfirmations();
        inventory.setItem(rawSlot, slot);
        ItemStack[] cache = player.getUniqueId().equals(player1) ? player1Items : player2Items;
        cache[index] = copyOrNull(slot);
        player.setItemOnCursor(held == null ? Items.empty() : held);
        if (gui != null) gui.refresh();
    }

    private boolean ensureAllowed(UUID playerUuid) {
        Player player = Bukkit.getPlayer(playerUuid);
        if (!isRestrictedGameMode(player)) return true;
        manager.getPlugin().sendMessage(player,
                manager.getPlugin().trc("trade.error.restricted_gamemode", NamedTextColor.RED));
        cancelAndReturnItems(manager.getPlugin().tr("trade.cancel.restricted_gamemode"));
        return false;
    }

    private boolean isRestrictedGameMode(Player player) {
        return player != null
                && manager.getPlugin().getConfig().getBoolean("trade.block-creative-and-spectator", true)
                && (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR);
    }

    private OfferSnapshot currentOffers() {
        Inventory inventory = gui == null ? null : gui.getInventory();
        if (inventory == null) return new OfferSnapshot(copyItems(player1Items), copyItems(player2Items));
        return new OfferSnapshot(readSlots(inventory, P1_SLOTS), readSlots(inventory, P2_SLOTS));
    }

    private static List<ItemStack> readSlots(Inventory inventory, int[] slots) {
        List<ItemStack> result = new ArrayList<>();
        for (int slot : slots) {
            ItemStack item = copyOrNull(inventory.getItem(slot));
            if (item != null) result.add(item);
        }
        return result;
    }

    private static List<ItemStack> copyItems(ItemStack[] source) {
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack item : source) {
            ItemStack copy = copyOrNull(item);
            if (copy != null) result.add(copy);
        }
        return result;
    }

    private void clearOfferEscrow() {
        Inventory inventory = gui == null ? null : gui.getInventory();
        if (inventory != null) {
            for (int slot : P1_SLOTS) inventory.setItem(slot, null);
            for (int slot : P2_SLOTS) inventory.setItem(slot, null);
        }
        Arrays.fill(player1Items, null);
        Arrays.fill(player2Items, null);
    }

    private static ItemStack copyOrNull(ItemStack item) {
        return item == null || Items.isEmpty(item) ? null : item.clone();
    }

    public synchronized TradeGui getGui() { return gui; }
}
