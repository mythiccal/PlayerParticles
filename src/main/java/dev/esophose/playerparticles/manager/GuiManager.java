package dev.esophose.playerparticles.manager;

import dev.esophose.playerparticles.config.Settings;
import dev.esophose.playerparticles.gui.GuiInventory;
import dev.esophose.playerparticles.gui.GuiInventoryDefault;
import dev.esophose.playerparticles.gui.GuiInventoryLoadPresetGroups;
import dev.esophose.playerparticles.gui.GuiInventoryManageGroups;
import dev.esophose.playerparticles.gui.GuiInventoryManageParticles;
import dev.esophose.playerparticles.particles.PPlayer;
import dev.rosewood.rosegarden.RosePlugin;
import dev.rosewood.rosegarden.manager.Manager;
import dev.rosewood.rosegarden.scheduler.task.ScheduledTask;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;

public class GuiManager extends Manager implements Listener, Runnable {

    private final List<GuiInventory> guiInventories;
    private ScheduledTask guiTask;

    public GuiManager(RosePlugin playerParticles) {
        super(playerParticles);

        this.guiInventories = Collections.synchronizedList(new ArrayList<>());
        this.guiTask = null;

        Bukkit.getPluginManager().registerEvents(this, playerParticles);
    }

    @Override
    public void reload() {
        if (this.guiTask != null)
            this.guiTask.cancel();
        this.guiTask = this.rosePlugin.getScheduler().runTaskTimer(this, 0, 10);
    }

    @Override
    public void disable() {
        this.forceCloseAllOpenGUIs();
    }

    /**
     * Ticks GuiInventories
     */
    public void run() {
        List<GuiInventory> openInventories;
        synchronized (this.guiInventories) {
            openInventories = new ArrayList<>(this.guiInventories);
        }

        for (GuiInventory inventory : openInventories) {
            Player player = inventory.getPPlayer().getPlayer();
            if (player == null)
                continue;

            this.runOnPlayer(player, () -> {
                if (this.guiInventories.contains(inventory))
                    inventory.onTick();
            });
        }
    }
    
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player))
            return;

        Player player = (Player) event.getWhoClicked();
        GuiInventory inventory = this.getGuiInventory(player);
        if (inventory == null)
            return;
        
        event.setCancelled(true);
        inventory.onClick(event);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player))
            return;

        Player player = (Player) event.getPlayer();
        GuiInventory inventory = this.getGuiInventory(player);
        if (inventory == null)
            return;

        this.guiInventories.remove(inventory);
    }

    /**
     * Gets if the GUI is disabled by the server owner or not
     * 
     * @return True if the GUI is disabled
     */
    public boolean isGuiDisabled() {
        return !Settings.GUI_ENABLED.get();
    }

    /**
     * Forcefully closes all open PlayerParticles GUIs
     * Used for when the plugin unloads so players can't take items from the GUI
     */
    public void forceCloseAllOpenGUIs() {
        List<GuiInventory> openInventories;
        synchronized (this.guiInventories) {
            openInventories = new ArrayList<>(this.guiInventories);
        }
        this.guiInventories.clear();

        for (GuiInventory inventory : openInventories) {
            Player player = inventory.getPPlayer().getPlayer();
            if (player != null)
                this.runOnPlayer(player, player::closeInventory);
        }
    }
    
    /**
     * Opens the default GUI screen for a player
     * 
     * @param pplayer The PPlayer to open the GUI screen for
     */
    public void openDefault(PPlayer pplayer) {
        if (Settings.GUI_PRESETS_ONLY.get()) {
            this.openPresetGroups(pplayer);
            return;
        }

        this.openScreen(pplayer, () -> this.openGui(pplayer, new GuiInventoryDefault(pplayer)));
    }

    /**
     * Opens the preset groups GUI screen for a player
     *
     * @param pplayer The PPlayer to open the GUI screen for
     */
    public void openPresetGroups(PPlayer pplayer) {
        this.openScreen(pplayer, () -> this.openGui(pplayer, new GuiInventoryLoadPresetGroups(pplayer, true, 1)));
    }

    /**
     * Opens the groups GUI screen for a player
     *
     * @param pplayer The PPlayer to open the GUI screen for
     */
    public void openGroups(PPlayer pplayer) {
        this.openScreen(pplayer, () -> this.openGui(pplayer, new GuiInventoryManageGroups(pplayer, 1)));
    }

    /**
     * Opens the edit particles GUI screen for a player
     *
     * @param pplayer The PPlayer to open the GUI screen for
     */
    public void openParticles(PPlayer pplayer) {
        this.openScreen(pplayer, () -> this.openGui(pplayer, new GuiInventoryManageParticles(pplayer)));
    }

    /**
     * Opens a GUI screen for a player
     *
     * @param pplayer The PPlayer to open for
     * @param guiInventory The GuiInventory to open
     */
    private void openGui(PPlayer pplayer, GuiInventory guiInventory) {
        pplayer.getPlayer().openInventory(guiInventory.getInventory());
        this.guiInventories.add(guiInventory);
    }
    
    /**
     * Changes the player's inventory to another one
     * 
     * @param nextInventory The GuiInventory to transition to
     */
    public void transition(GuiInventory nextInventory) {
        Player player = nextInventory.getPPlayer().getPlayer();
        if (player == null)
            return;

        // Always wait a tick. Button callbacks run inside InventoryClickEvent, and opening
        // another inventory before that event finishes drops the new screen.
        this.scheduleOnPlayer(player, () -> {
            player.openInventory(nextInventory.getInventory());
            this.guiInventories.add(nextInventory);
        });
    }

    /**
     * Builds and opens a GUI on the thread that owns the player.
     *
     * @param pplayer The player to open the GUI for
     * @param open Opens the GUI once execution is on that player's thread
     */
    private void openScreen(PPlayer pplayer, Runnable open) {
        Player player = pplayer.getPlayer();
        if (player == null)
            return;

        this.runOnPlayer(player, open);
    }

    /**
     * Runs inventory work on the player's region thread. Runs inline when already there.
     *
     * @param player The player the work belongs to
     * @param runnable The work to run
     */
    private void runOnPlayer(Player player, Runnable runnable) {
        if (this.rosePlugin.getScheduler().isEntityThread(player)) {
            if (player.isOnline())
                runnable.run();
            return;
        }

        this.scheduleOnPlayer(player, runnable);
    }

    /**
     * Schedules inventory work for the next tick on the player's region thread.
     *
     * @param player The player the work belongs to
     * @param runnable The work to run
     */
    private void scheduleOnPlayer(Player player, Runnable runnable) {
        if (!this.rosePlugin.isEnabled())
            return;

        this.rosePlugin.getScheduler().runTaskAtEntity(player, () -> {
            if (player.isOnline())
                runnable.run();
        });
    }
    
    /**
     * Gets a GuiInventory by Player
     * 
     * @param player The Player
     * @return The GuiInventory belonging to the Player, if any
     */
    private GuiInventory getGuiInventory(Player player) {
        return this.guiInventories.stream()
                .filter(x -> x.getPPlayer().getUniqueId().equals(player.getUniqueId()))
                .findFirst()
                .orElse(null);
    }

}
