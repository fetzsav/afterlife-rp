package com.afterlife.rp.shared.gui;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Opens and guards session-bound GUIs. Every interaction with a GUI inventory
 * is cancelled at the event level; only vetted primary clicks reach handlers,
 * and permissions are revalidated on each click (master plan §14).
 */
public final class GuiManager implements Listener {

    private final Plugin plugin;
    private final Duration sessionTimeout;
    private final Map<UUID, GuiSession> sessionsByPlayer = new ConcurrentHashMap<>();
    private BukkitTask sweepTask;

    public GuiManager(Plugin plugin, Duration sessionTimeout) {
        this.plugin = plugin;
        this.sessionTimeout = sessionTimeout;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        sweepTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sweepExpired, 100L, 100L);
    }

    /** Main-thread only (rule 2). */
    public void open(Player player, GuiMenu menu) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("GUIs must be opened on the server thread");
        }
        GuiSession session = new GuiSession(player.getUniqueId(), menu);
        GuiHolder holder = new GuiHolder(session);
        Inventory inventory = Bukkit.createInventory(holder, menu.size(), menu.title());
        holder.attach(inventory);
        menu.buttons().forEach((slot, button) -> {
            if (slot >= 0 && slot < menu.size()) {
                inventory.setItem(slot, button.icon());
            }
        });
        sessionsByPlayer.put(player.getUniqueId(), session);
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof GuiHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        GuiSession session = holder.session();
        if (!session.playerId().equals(player.getUniqueId())) {
            player.closeInventory();
            return;
        }
        if (session.expired(sessionTimeout)) {
            player.closeInventory();
            return;
        }
        GuiMenu menu = session.menu();
        if (menu.permission() != null && !player.hasPermission(menu.permission())) {
            player.closeInventory();
            return;
        }
        boolean clickedTop = event.getClickedInventory() == event.getInventory();
        if (!GuiClickPolicy.shouldDispatch(event.getClick(), clickedTop)) {
            return;
        }
        GuiButton button = menu.buttons().get(event.getSlot());
        if (button == null) {
            return;
        }
        if (button.permission() != null && !player.hasPermission(button.permission())) {
            return;
        }
        session.touch();
        button.handler().accept(player, event.getClick());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof GuiHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof GuiHolder holder) {
            sessionsByPlayer.remove(holder.session().playerId(), holder.session());
        }
    }

    private void sweepExpired() {
        for (GuiSession session : new ArrayList<>(sessionsByPlayer.values())) {
            if (session.expired(sessionTimeout)) {
                Player player = Bukkit.getPlayer(session.playerId());
                sessionsByPlayer.remove(session.playerId(), session);
                if (player != null
                        && player.getOpenInventory().getTopInventory().getHolder() instanceof GuiHolder h
                        && h.session() == session) {
                    player.closeInventory();
                }
            }
        }
    }

    /** Closes every managed GUI; called on plugin disable (main thread). */
    public void closeAll() {
        if (sweepTask != null) {
            sweepTask.cancel();
        }
        for (GuiSession session : new ArrayList<>(sessionsByPlayer.values())) {
            Player player = Bukkit.getPlayer(session.playerId());
            if (player != null
                    && player.getOpenInventory().getTopInventory().getHolder() instanceof GuiHolder) {
                player.closeInventory();
            }
        }
        sessionsByPlayer.clear();
    }
}
