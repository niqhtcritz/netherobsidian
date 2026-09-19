package me.netherkey;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class NetherKey extends JavaPlugin implements Listener {

    private NamespacedKey itemKey;
    private Scoreboard board;
    private Team glowTeam;

    private boolean netherUnlocked;

    // Players currently marked as holders, and the team they were on before
    private final Set<UUID> holders = new HashSet<>();
    private final Map<UUID, String> oldTeams = new HashMap<>();

    // Stops the "sealed" message from spamming
    private final Map<UUID, Long> messageCooldown = new HashMap<>();

    // ------------------------------------------------------------
    // Startup / shutdown
    // ------------------------------------------------------------

    @Override
    public void onEnable() {
        saveDefaultConfig();
        netherUnlocked = getConfig().getBoolean("nether-unlocked", false);

        itemKey = new NamespacedKey(this, "nether_obsidian");
        getServer().getPluginManager().registerEvents(this, this);
        registerRecipe();

        // Yellow glow team
        board = Bukkit.getScoreboardManager().getMainScoreboard();
        glowTeam = board.getTeam("nk_yellow");
        if (glowTeam == null) glowTeam = board.registerNewTeam("nk_yellow");
        glowTeam.color(NamedTextColor.YELLOW);
        // Clear stale entries left over from a crash or restart
        for (String entry : new HashSet<>(glowTeam.getEntries())) glowTeam.removeEntry(entry);

        // Check player inventories twice a second
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                boolean has = hasKeyItem(p);
                boolean marked = holders.contains(p.getUniqueId());
                if (has && !marked) {
                    markHolder(p);
                } else if (!has && marked) {
                    unmarkHolder(p);
                } else if (has && !p.hasPotionEffect(PotionEffectType.GLOWING)) {
                    applyGlow(p); // e.g. milk bucket removed it
                }
            }
        }, 10L, 10L);

        // Key items already lying in loaded chunks
        for (World w : Bukkit.getWorlds()) {
            for (Item i : w.getEntitiesByClass(Item.class)) {
                if (isKeyItem(i.getItemStack())) glowItem(i);
            }
        }
    }

    @Override
    public void onDisable() {
        for (UUID id : new HashSet<>(holders)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) unmarkHolder(p);
        }
        if (glowTeam != null) {
            for (String entry : new HashSet<>(glowTeam.getEntries())) glowTeam.removeEntry(entry);
        }
        Bukkit.removeRecipe(itemKey);
    }

    // ------------------------------------------------------------
    // The custom item
    // ------------------------------------------------------------

    private ItemStack createItem() {
        ItemStack item = new ItemStack(Material.OBSIDIAN);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Nether Obsidian", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Right-click to unlock the Nether", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Holding it makes you glow.", NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false)));
        meta.setCustomModelData(1001); // only matters if you use a resource pack
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isKeyItem(ItemStack item) {
        return item != null
                && item.getType() == Material.OBSIDIAN
                && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    private void registerRecipe() {
        Bukkit.removeRecipe(itemKey); // avoids duplicates
        ShapedRecipe recipe = new ShapedRecipe(itemKey, createItem());
        recipe.shape("OOO", "ONO", "OOO");
        recipe.setIngredient('O', Material.OBSIDIAN);
        recipe.setIngredient('N', Material.NETHER_STAR); // change this ingredient if you want
        Bukkit.addRecipe(recipe);
    }

    private boolean hasKeyItem(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (isKeyItem(it)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------
    // Glow helpers
    // ------------------------------------------------------------

    private void applyGlow(Player p) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING,
                PotionEffect.INFINITE_DURATION, 0, false, false, false));
    }

    private void markHolder(Player p) {
        holders.add(p.getUniqueId());

        // Remember any team they were already on so we can put them back
        Team old = board.getEntryTeam(p.getName());
        if (old != null && !old.equals(glowTeam)) {
            oldTeams.put(p.getUniqueId(), old.getName());
        }

        glowTeam.addEntry(p.getName());
        applyGlow(p);
        p.playerListName(Component.text(p.getName(), NamedTextColor.YELLOW));
    }

    private void unmarkHolder(Player p) {
        holders.remove(p.getUniqueId());
        glowTeam.removeEntry(p.getName());
        p.removePotionEffect(PotionEffectType.GLOWING);
        p.playerListName(null); // back to default

        String oldName = oldTeams.remove(p.getUniqueId());
        if (oldName != null) {
            Team t = board.getTeam(oldName);
            if (t != null) t.addEntry(p.getName());
        }
    }

    private void glowItem(Item item) {
        item.setGlowing(true);
        glowTeam.addEntry(item.getUniqueId().toString()); // non-players use their UUID
    }

    // ------------------------------------------------------------
    // Events: dropped items
    // ------------------------------------------------------------

    @EventHandler
    public void onItemSpawn(ItemSpawnEvent e) {
        if (isKeyItem(e.getEntity().getItemStack())) glowItem(e.getEntity());
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            if (en instanceof Item i && isKeyItem(i.getItemStack())) glowItem(i);
        }
    }

    // Covers pickup, despawn, burning and chunk unload
    @EventHandler
    public void onEntityRemove(EntityRemoveEvent e) {
        if (e.getEntity() instanceof Item i) {
            glowTeam.removeEntry(i.getUniqueId().toString());
        }
    }

    // ------------------------------------------------------------
    // Events: players
    // ------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        e.getPlayer().discoverRecipe(itemKey);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (holders.contains(e.getPlayer().getUniqueId())) unmarkHolder(e.getPlayer());
        messageCooldown.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------
    // Events: nether lock and unlock
    // ------------------------------------------------------------

    @EventHandler
    public void onPortal(PlayerPortalEvent e) {
        if (e.getCause() != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) return;
        if (netherUnlocked) return;

        e.setCancelled(true);

        Player p = e.getPlayer();
        long now = System.currentTimeMillis();
        Long last = messageCooldown.get(p.getUniqueId());
        if (last == null || now - last > 3000) {
            p.sendMessage(Component.text("The Nether is still sealed...", NamedTextColor.RED));
            messageCooldown.put(p.getUniqueId(), now);
        }
    }

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() == null) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = e.getItem();
        if (!isKeyItem(item)) return;

        e.setCancelled(true); // never place it as a block

        Player p = e.getPlayer();
        if (netherUnlocked) {
            p.sendMessage(Component.text("The Nether is already unlocked.", NamedTextColor.YELLOW));
            return;
        }

        // Consume one item from the hand that was used
        ItemStack copy = item.clone();
        copy.setAmount(item.getAmount() - 1);
        p.getInventory().setItem(e.getHand(), copy.getAmount() <= 0 ? new ItemStack(Material.AIR) : copy);

        setNetherUnlocked(true);
        Bukkit.broadcast(Component.text(p.getName() + " has unlocked the Nether!", NamedTextColor.GOLD));
    }

    // Backup: never let the key item be placed
    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (isKeyItem(e.getItemInHand())) e.setCancelled(true);
    }

    private void setNetherUnlocked(boolean value) {
        netherUnlocked = value;
        getConfig().set("nether-unlocked", value);
        saveConfig();
    }

    // ------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase();

        if (name.equals("givenetherobsidian")) {
            Player found = null;
            if (args.length > 0) found = Bukkit.getPlayerExact(args[0]);
            else if (sender instanceof Player p) found = p;

            if (found == null) {
                sender.sendMessage(Component.text("Usage: /givenetherobsidian <player>", NamedTextColor.RED));
                return true;
            }
            final Player target = found;
            target.getInventory().addItem(createItem()).values()
                    .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
            sender.sendMessage(Component.text("Gave Nether Obsidian to " + target.getName(), NamedTextColor.GREEN));
            return true;
        }

        if (name.equals("netherlock")) {
            String sub = args.length > 0 ? args[0].toLowerCase() : "status";
            switch (sub) {
                case "lock" -> {
                    setNetherUnlocked(false);
                    sender.sendMessage(Component.text("The Nether is now locked.", NamedTextColor.RED));
                }
                case "unlock" -> {
                    setNetherUnlocked(true);
                    sender.sendMessage(Component.text("The Nether is now unlocked.", NamedTextColor.GREEN));
                }
                default -> sender.sendMessage(Component.text(
                        "Nether is " + (netherUnlocked ? "unlocked" : "locked")
                                + ". Use /netherlock <lock|unlock>", NamedTextColor.YELLOW));
            }
            return true;
        }

        return false;
    }
}
