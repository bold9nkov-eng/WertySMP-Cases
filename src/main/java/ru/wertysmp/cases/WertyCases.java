package ru.wertysmp.cases;

import java.lang.reflect.Method;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.ConfigurationSection;

public final class WertyCases extends JavaPlugin implements Listener, TabCompleter {
    private NamespacedKey itemKey;
    private NamespacedKey blockKey;
    private final Random random = new Random();
    private static final List<String> TYPES = List.of("ordinary", "epic", "legendary");
    private static final List<String> TYPE_ALIASES = List.of("обычный", "эпический", "легендарный");
    private static final List<String> ARMOR_SLOTS = List.of("HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS");

    @Override public void onEnable() {
        saveDefaultConfig();
        itemKey = new NamespacedKey(this, "case_type");
        blockKey = new NamespacedKey(this, "placed_case_type");
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("wcases")).setTabCompleter(this);
        getLogger().info("WertySMP Cases включён: 3 кейса; независимые шансы наград.");
    }

    private ItemStack createCase(String type) {
        ItemStack item = new ItemStack(caseMaterial(type));
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(getConfig().getString("cases." + type + ".name", type)));
        meta.setLore(getConfig().getStringList("cases." + type + ".lore").stream().map(this::color).toList());
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, type);
        item.setItemMeta(meta);
        return item;
    }

    private Material caseMaterial(String type) {
        return switch (type) {
            case "epic" -> Material.TRAPPED_CHEST;
            case "legendary" -> Material.ENDER_CHEST;
            default -> Material.CHEST;
        };
    }

    private boolean isChestMaterial(Material material) {
        return material == Material.CHEST || material == Material.TRAPPED_CHEST || material == Material.ENDER_CHEST;
    }

    private String color(String s) { return s == null ? "" : s.replace('&', '§'); }

    private String getCaseType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String type = item.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
        return TYPES.contains(type) ? type : null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        String type = getCaseType(event.getItemInHand());
        if (type == null) return;
        BlockState state = event.getBlockPlaced().getState();
        if (!(state instanceof TileState tile)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cНе удалось установить кейс.");
            return;
        }
        tile.getPersistentDataContainer().set(blockKey, PersistentDataType.STRING, type);
        if (!tile.update(true, false)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cНе удалось сохранить метку кейса. Попробуй ещё раз.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onOpen(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null || event.getHand() != EquipmentSlot.HAND) return;
        Block block = event.getClickedBlock();
        if (!isChestMaterial(block.getType())) return;
        BlockState state = block.getState();
        if (!(state instanceof TileState tile)) return;
        String type = tile.getPersistentDataContainer().get(blockKey, PersistentDataType.STRING);
        if (!TYPES.contains(type)) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        // Сначала убираем отмеченный блок, затем выдаём награды: повторное нажатие не продублирует добычу.
        Location location = block.getLocation().clone();
        block.setType(Material.AIR, false);
        player.playSound(location, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
        World world = location.getWorld();
        world.spawnParticle(Particle.HAPPY_VILLAGER, location.clone().add(0.5, 0.7, 0.5), 24, 0.35, 0.35, 0.35, 0.02);
        player.sendMessage("§6§lWertySMP §8» §fОткрыт " + caseName(type) + "§f!");
        ConfigurationSection rewards = getConfig().getConfigurationSection("rewards." + type);
        if (rewards == null) {
            player.sendMessage("§cНаграды для этого кейса не настроены. Сообщи администрации.");
            return;
        }
        int granted = 0;
        List<String> won = new ArrayList<>();
        for (String key : rewards.getKeys(false)) {
            ConfigurationSection reward = rewards.getConfigurationSection(key);
            if (reward == null) continue;
            double chance = reward.getDouble("chance", 0.0);
            if (chance <= 0 || random.nextDouble() * 100.0 >= chance) continue;
            ItemStack prize = createReward(reward);
            if (prize == null) {
                getLogger().warning("Пропущена неверная награда '" + key + "' в rewards." + type);
                continue;
            }
            int amount = randomAmount(reward);
            prize.setAmount(1);
            giveStacked(player, prize, amount);
            granted++;
            won.add(color(reward.getString("display", key)) + " §7×" + amount);
        }
        if (granted == 0) player.sendMessage("§7В этот раз ничего дополнительного не выпало. Повезёт в следующий раз!");
        else {
            player.sendMessage("§aВыпало наград: §e" + granted);
            // Keep chat readable for legendary cases with many simultaneous drops.
            for (int i = 0; i < Math.min(8, won.size()); i++) player.sendMessage(" §8• " + won.get(i));
            if (won.size() > 8) player.sendMessage(" §7И ещё наград: " + (won.size() - 8));
        }
    }

    private int randomAmount(ConfigurationSection reward) {
        int min = Math.max(1, reward.getInt("min", reward.getInt("amount", 1)));
        int max = Math.max(min, reward.getInt("max", min));
        return min + random.nextInt(max - min + 1);
    }

    private ItemStack createReward(ConfigurationSection reward) {
        String special = reward.getString("special", "");
        if (special.equalsIgnoreCase("random_armor")) {
            String tier = reward.getString("tier", "iron").toUpperCase(Locale.ROOT);
            String slot = ARMOR_SLOTS.get(random.nextInt(ARMOR_SLOTS.size()));
            String prefix = switch (tier) {
                case "LEATHER" -> "LEATHER_";
                case "IRON" -> "IRON_";
                case "GOLD" -> "GOLDEN_";
                case "DIAMOND" -> "DIAMOND_";
                case "NETHERITE" -> "NETHERITE_";
                default -> "IRON_";
            };
            Material mat = Material.matchMaterial(prefix + slot);
            return mat == null ? null : new ItemStack(mat);
        }
        if (special.equalsIgnoreCase("tipped_arrow_slow_falling")) {
            ItemStack arrow = new ItemStack(Material.TIPPED_ARROW);
            if (arrow.getItemMeta() instanceof PotionMeta meta) {
                meta.setBasePotionType(PotionType.SLOW_FALLING);
                arrow.setItemMeta(meta);
            }
            return arrow;
        }
        if (special.equalsIgnoreCase("firework_3")) {
            ItemStack rocket = new ItemStack(Material.FIREWORK_ROCKET);
            if (rocket.getItemMeta() instanceof FireworkMeta meta) {
                meta.setPower(3);
                rocket.setItemMeta(meta);
            }
            return rocket;
        }
        Material material = Material.matchMaterial(reward.getString("material", ""));
        if (material == null || material.isAir()) return null;
        ItemStack item = new ItemStack(material);
        String display = reward.getString("display");
        if (display != null && !display.isBlank() && reward.getBoolean("custom-name", false)) {
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(color(display));
            item.setItemMeta(meta);
        }
        return item;
    }

    private void giveStacked(Player player, ItemStack base, int amount) {
        int remaining = amount;
        int max = Math.max(1, base.getMaxStackSize());
        while (remaining > 0) {
            int count = Math.min(remaining, max);
            ItemStack stack = base.clone();
            stack.setAmount(count);
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack);
            overflow.values().forEach(drop -> player.getWorld().dropItemNaturally(player.getLocation(), drop));
            remaining -= count;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isChestMaterial(block.getType()) || !(block.getState() instanceof TileState tile)) return;
        String type = tile.getPersistentDataContainer().get(blockKey, PersistentDataType.STRING);
        if (!TYPES.contains(type)) return;
        event.setCancelled(true);
        block.setType(Material.AIR, false);
        block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.2, 0.5), createCase(type));
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("wcases")) return false;
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage("§6WertySMP Cases §7— §f/wcases give <игрок> <ordinary|epic|legendary> [количество]");
            sender.sendMessage("§7Также: /wcases give <игрок> <обычный|эпический|легендарный> [количество]");
            return true;
        }
        if (!sender.hasPermission("wertysmpcases.admin")) {
            sender.sendMessage("§cУ тебя нет прав на эту команду.");
            return true;
        }
        if (!args[0].equalsIgnoreCase("give") || args.length < 3) {
            sender.sendMessage("§cИспользование: /wcases give <игрок> <ordinary|epic|legendary> [количество]");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage("§cИгрок должен быть в сети.");
            return true;
        }
        String type = normalizeType(args[2]);
        if (type == null) {
            sender.sendMessage("§cТип: ordinary, epic, legendary (обычный, эпический, легендарный).");
            return true;
        }
        int amount = 1;
        if (args.length >= 4) {
            try { amount = Integer.parseInt(args[3]); }
            catch (NumberFormatException ex) { sender.sendMessage("§cКоличество должно быть числом от 1 до 64."); return true; }
        }
        if (amount < 1 || amount > 64) { sender.sendMessage("§cКоличество должно быть от 1 до 64."); return true; }
        for (int i = 0; i < amount; i++) giveStacked(target, createCase(type), 1);
        sender.sendMessage("§aВыдано игроку §e" + target.getName() + "§a: " + caseName(type) + " §7×" + amount);
        if (!sender.equals(target)) target.sendMessage("§aТы получил: " + caseName(type) + " §7×" + amount);
        return true;
    }

    private String normalizeType(String raw) {
        String value = raw.toLowerCase(Locale.ROOT);
        return switch (value) {
            case "ordinary", "обычный", "обычный_кейс" -> "ordinary";
            case "epic", "эпический", "эпик" -> "epic";
            case "legendary", "легендарный", "легендарка" -> "legendary";
            default -> null;
        };
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("give", "help");
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) return List.of("ordinary", "epic", "legendary", "обычный", "эпический", "легендарный");
        if (args.length == 4 && args[0].equalsIgnoreCase("give")) return List.of("1", "2", "5", "10");
        return Collections.emptyList();
    }

    private String caseName(String type) { return color(getConfig().getString("cases." + type + ".name", type)); }
}
