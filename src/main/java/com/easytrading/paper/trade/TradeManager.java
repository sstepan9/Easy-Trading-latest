package com.easytrading.paper.trade;

import com.easytrading.paper.EasyTradingPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages trade requests and active trade sessions between players.
 */
public class TradeManager {

    private final EasyTradingPlugin plugin;

    /** Pending trade requests: target UUID -> request */
    private final Map<UUID, TradeRequest> pendingRequests = new ConcurrentHashMap<>();

    /** Active trade sessions: player UUID -> session (both players mapped) */
    private final Map<UUID, TradeSession> activeSessions = new ConcurrentHashMap<>();

    private static final long REQUEST_TIMEOUT_MS = 60_000L; // 60 seconds

    /*
     * Do not use the short /market label for chat actions. Another plugin can
     * register the same label, sending a click to the wrong command. Bukkit
     * exposes the plugin-prefixed command as an unambiguous fallback.
     */
    private static final String TRADE_COMMAND_PREFIX = "/easytrading:market trade ";

    public TradeManager(EasyTradingPlugin plugin) {
        this.plugin = plugin;
    }

    public EasyTradingPlugin getPlugin() {
        return plugin;
    }

    /** Whether the server configuration excludes this game mode from direct trades. */
    public boolean isRestrictedGameMode(Player player) {
        if (player == null || !plugin.getConfig().getBoolean("trade.block-creative-and-spectator", true)) {
            return false;
        }
        return player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR;
    }

    public boolean canTrade(Player player) {
        return player != null && player.isOnline() && !isRestrictedGameMode(player);
    }

    private void sendRestrictedModeMessage(Player player) {
        if (player != null) {
            plugin.sendMessage(player, plugin.trc("trade.error.restricted_gamemode", NamedTextColor.RED));
        }
    }

    /**
     * Send a trade request from sender to target.
     */
    public void sendRequest(Player sender, Player target) {
        if (sender == null || target == null || !sender.isOnline() || !target.isOnline()
                || sender.getUniqueId().equals(target.getUniqueId())) {
            return;
        }
        if (!canTrade(sender)) {
            sendRestrictedModeMessage(sender);
            return;
        }
        if (!canTrade(target)) {
            plugin.sendMessage(sender, plugin.trc("trade.error.target_restricted_gamemode", NamedTextColor.RED));
            return;
        }
        UUID senderUuid = sender.getUniqueId();
        UUID targetUuid = target.getUniqueId();

        // Check if sender already has an active session
        if (activeSessions.containsKey(senderUuid)) {
            plugin.sendMessage(sender, plugin.trc("trade.error.already_in_trade", NamedTextColor.RED));
            return;
        }
        if (activeSessions.containsKey(targetUuid)) {
            plugin.sendMessage(sender, plugin.trc("trade.error.target_in_trade", NamedTextColor.RED));
            return;
        }

        // Check if target already has a pending request from this sender
        TradeRequest existing = pendingRequests.get(targetUuid);
        if (existing != null && existing.getSender().equals(senderUuid)) {
            plugin.sendMessage(sender, plugin.trc("trade.error.request_already_sent", NamedTextColor.YELLOW));
            return;
        }

        // Check if sender has a pending request (as target) from the target player — auto-accept
        TradeRequest reverseRequest = pendingRequests.get(senderUuid);
        if (reverseRequest != null && reverseRequest.getSender().equals(targetUuid)) {
            // Both want to trade with each other — accept
            pendingRequests.remove(senderUuid);
            startSession(target, sender);
            return;
        }

        // Remove old request to same target if exists
        pendingRequests.remove(targetUuid);

        TradeRequest request = new TradeRequest(senderUuid, targetUuid, System.currentTimeMillis());
        pendingRequests.put(targetUuid, request);

        plugin.sendMessage(sender, plugin.trc("trade.request.sent", NamedTextColor.GREEN, target.getName()));

        // Send clickable message to target
        Component acceptBtn = Component.text(plugin.tr("trade.request.accept"))
                .color(NamedTextColor.GREEN)
                .decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(TRADE_COMMAND_PREFIX + "accept"));

        Component declineBtn = Component.text(plugin.tr("trade.request.decline"))
                .color(NamedTextColor.RED)
                .decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(TRADE_COMMAND_PREFIX + "decline"));

        plugin.sendMessage(target, 
                Component.text(plugin.tr("trade.request.incoming", sender.getName())).color(NamedTextColor.GOLD)
                        .append(acceptBtn)
                        .append(Component.text(" ").color(NamedTextColor.WHITE))
                        .append(declineBtn)
        );
    }

    /**
     * Accept a pending trade request.
     */
    public void acceptRequest(Player target) {
        if (target == null || !target.isOnline()) {
            return;
        }
        if (!canTrade(target)) {
            sendRestrictedModeMessage(target);
            return;
        }
        UUID targetUuid = target.getUniqueId();
        TradeRequest request = pendingRequests.remove(targetUuid);

        if (request == null || System.currentTimeMillis() - request.getTimestamp() > REQUEST_TIMEOUT_MS) {
            pendingRequests.remove(targetUuid);
            plugin.sendMessage(target, plugin.trc("trade.error.no_request_or_expired", NamedTextColor.RED));
            return;
        }

        if (activeSessions.containsKey(targetUuid)) {
            plugin.sendMessage(target, plugin.trc("trade.error.already_in_trade", NamedTextColor.RED));
            return;
        }

        Player sender = Bukkit.getPlayer(request.getSender());
        if (sender == null || !sender.isOnline()) {
            plugin.sendMessage(target, plugin.trc("trade.error.requester_offline", NamedTextColor.RED));
            return;
        }
        if (!canTrade(sender)) {
            plugin.sendMessage(target, plugin.trc("trade.error.target_restricted_gamemode", NamedTextColor.RED));
            return;
        }

        if (activeSessions.containsKey(sender.getUniqueId())) {
            plugin.sendMessage(target, plugin.trc("trade.error.requester_busy", NamedTextColor.RED));
            return;
        }

        startSession(sender, target);
    }

    /**
     * Decline a pending trade request.
     */
    public void declineRequest(Player target) {
        if (target == null || !target.isOnline()) {
            return;
        }
        UUID targetUuid = target.getUniqueId();
        TradeRequest request = pendingRequests.remove(targetUuid);

        if (request == null) {
            plugin.sendMessage(target, plugin.trc("trade.error.no_request", NamedTextColor.RED));
            return;
        }

        plugin.sendMessage(target, plugin.trc("trade.request.declined_self", NamedTextColor.YELLOW));

        Player sender = Bukkit.getPlayer(request.getSender());
        if (sender != null && sender.isOnline()) {
            plugin.sendMessage(sender, plugin.trc("trade.request.declined_other", NamedTextColor.RED, target.getName()));
        }
    }

    /**
     * Start a trade session between two players.
     */
    private void startSession(Player player1, Player player2) {
        if (player1 == null || player2 == null || !player1.isOnline() || !player2.isOnline()
                || player1.getUniqueId().equals(player2.getUniqueId())) {
            return;
        }
        if (!canTrade(player1) || !canTrade(player2)) {
            if (!canTrade(player1)) sendRestrictedModeMessage(player1);
            if (!canTrade(player2)) sendRestrictedModeMessage(player2);
            return;
        }
        TradeSession session = new TradeSession(this, player1.getUniqueId(), player2.getUniqueId());
        UUID first = player1.getUniqueId();
        UUID second = player2.getUniqueId();

        // putIfAbsent makes the check-and-register operation safe even if a
        // command/plugin callback races this method.
        if (activeSessions.putIfAbsent(first, session) != null) {
            return;
        }
        if (activeSessions.putIfAbsent(second, session) != null) {
            activeSessions.remove(first, session);
            return;
        }

        try {
            session.openGui(player1, player2);
        } catch (RuntimeException openFailure) {
            activeSessions.remove(first, session);
            activeSessions.remove(second, session);
            plugin.getLogger().warning("Could not open trade GUI: " + openFailure.getMessage());
        }
    }

    /**
     * Get the active trade session for a player.
     */
    public TradeSession getSession(UUID playerUuid) {
        return activeSessions.get(playerUuid);
    }

    /**
     * End and remove a session (called by TradeSession when done).
     */
    public void removeSession(TradeSession session) {
        if (session == null) {
            return;
        }
        // Do not remove a newer session which reused one of these UUIDs while
        // an old close/cancel callback was still pending.
        activeSessions.remove(session.getPlayer1(), session);
        activeSessions.remove(session.getPlayer2(), session);
    }

    /**
     * Cancel every active session during plugin shutdown.  Distinct sessions
     * are collected because each is indexed twice in activeSessions.
     */
    public void shutdown() {
        Set<TradeSession> sessions = new HashSet<>(activeSessions.values());
        pendingRequests.clear();
        for (TradeSession session : sessions) {
            session.cancelAndReturnItems(plugin.tr("trade.cancel.server_shutdown"));
        }
        activeSessions.clear();
    }

    /**
     * Handle player disconnect — cancel any pending requests and active sessions safely.
     */
    public void handleDisconnect(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();

        // Remove pending requests where player is sender or target
        pendingRequests.entrySet().removeIf(entry ->
                entry.getValue().getSender().equals(uuid) || entry.getKey().equals(uuid));

        // Cancel active session
        TradeSession session = activeSessions.get(uuid);
        if (session != null) {
            session.cancelAndReturnItems(plugin.tr("trade.cancel.player_disconnected"));
        }
    }

    /**
     * Handle player death — cancel active trade and return items.
     */
    public void handleDeath(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        TradeSession session = activeSessions.get(uuid);
        if (session != null) {
            session.cancelAndReturnItems(plugin.tr("trade.cancel.player_died"));
        }
    }

    /**
     * Cleanup expired requests. Called periodically.
     */
    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        pendingRequests.entrySet().removeIf(entry -> {
            if (now - entry.getValue().getTimestamp() > REQUEST_TIMEOUT_MS) {
                Player sender = Bukkit.getPlayer(entry.getValue().getSender());
                if (sender != null && sender.isOnline()) {
                    plugin.sendMessage(sender, plugin.trc("trade.request.expired", NamedTextColor.YELLOW));
                }
                return true;
            }
            return false;
        });
    }
}

