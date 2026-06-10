package com.easytrading.paper.trade;

import com.easytrading.paper.EasyTradingPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
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

    public TradeManager(EasyTradingPlugin plugin) {
        this.plugin = plugin;
    }

    public EasyTradingPlugin getPlugin() {
        return plugin;
    }

    /**
     * Send a trade request from sender to target.
     */
    public void sendRequest(Player sender, Player target) {
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
                .clickEvent(ClickEvent.runCommand("/market trade accept"));

        Component declineBtn = Component.text(plugin.tr("trade.request.decline"))
                .color(NamedTextColor.RED)
                .decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/market trade decline"));

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
        TradeSession session = new TradeSession(this, player1.getUniqueId(), player2.getUniqueId());
        activeSessions.put(player1.getUniqueId(), session);
        activeSessions.put(player2.getUniqueId(), session);

        session.openGui(player1, player2);
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
        activeSessions.remove(session.getPlayer1());
        activeSessions.remove(session.getPlayer2());
    }

    /**
     * Handle player disconnect — cancel any pending requests and active sessions safely.
     */
    public void handleDisconnect(Player player) {
        UUID uuid = player.getUniqueId();

        // Remove pending requests where player is sender or target
        pendingRequests.entrySet().removeIf(entry ->
                entry.getValue().getSender().equals(uuid) || entry.getKey().equals(uuid));

        // Cancel active session
        TradeSession session = activeSessions.get(uuid);
        if (session != null) {
            session.cancelAndReturnItems("Player disconnected");
        }
    }

    /**
     * Handle player death — cancel active trade and return items.
     */
    public void handleDeath(Player player) {
        UUID uuid = player.getUniqueId();
        TradeSession session = activeSessions.get(uuid);
        if (session != null) {
            session.cancelAndReturnItems("Player died");
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

