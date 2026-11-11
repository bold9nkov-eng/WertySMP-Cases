package ru.wertysmp.cases;

import java.lang.reflect.Method;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.Color;
import org.bukkit.Particle.DustOptions;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
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
    private int namePhase = 0;
    private final Map<UUID, Long> openingLocks = new HashMap<>();
    private final Map<UUID, Integer> dailyClaimed = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> stats = new HashMap<>();
    private final Map<UUID, Integer> dailyDay = new HashMap<>();
    private final Map<UUID, PendingInput> pendingInputs = new java.util.concurrent.ConcurrentHashMap<>();
    private record PendingInput(String action, String type, String rewardKey) {}
    private java.time.ZoneId dailyZone = java.time.ZoneId.of("Europe/Moscow");
    private static final List<String> TYPES = List.of("ordinary", "epic", "legendary", "void");
    private static final List<String> TYPE_ALIASES = List.of("обычный", "эпический", "легендарный");
    private static final List<String> ARMOR_SLOTS = List.of("HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS");

    @Override public void onEnable() {
        migrateLegacyData();
        saveDefaultConfig();
        org.bukkit.configuration.file.YamlConfiguration bundledDefaults = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(Objects.requireNonNull(getResource("config.yml")), java.nio.charset.StandardCharsets.UTF_8));
        getConfig().setDefaults(bundledDefaults);
        getConfig().options().copyDefaults(true);
        saveConfig();
        try { dailyZone = java.time.ZoneId.of(getConfig().getString("daily.timezone", "Europe/Moscow")); } catch (Exception ex) { getLogger().warning("Неверный daily.timezone, используется Europe/Moscow"); dailyZone = java.time.ZoneId.of("Europe/Moscow"); }
        // Keep the original namespace so existing WertySMP-Cases items and placed blocks remain valid after renaming.
        itemKey = new NamespacedKey("wertysmp-cases", "case_type");
        blockKey = new NamespacedKey("wertysmp-cases", "placed_case_type");
        loadPersistentData();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("case"), "Команда /case отсутствует в plugin.yml").setExecutor(this);
        Objects.requireNonNull(getCommand("case"), "Команда /case отсутствует в plugin.yml").setTabCompleter(this);
        getLogger().info("Void-cases-v2 включён: редактор кейсов и наград готов.");
        getServer().getScheduler().runTaskTimer(this, () -> {
            namePhase = (namePhase + 1) % 4;
            for (Player online : Bukkit.getOnlinePlayers()) {
                ItemStack[] contents = online.getInventory().getContents();
                boolean changed = false;
                for (ItemStack stack : contents) {
                    String type = getCaseType(stack);
                    if (type == null) continue;
                    ItemMeta meta = stack.getItemMeta();
                    meta.setDisplayName(animatedText(getConfig().getString("cases." + type + ".name", caseNameFallback(type)), type, namePhase));
                    meta.setLore(getConfig().getStringList("cases." + type + ".lore").stream().map(line -> animatedText(line, type, namePhase)).toList());
                    stack.setItemMeta(meta);
                    changed = true;
                }
                if (changed) online.getInventory().setContents(contents);
            }
        }, 1L, 8L);
    }

    private ItemStack createCase(String type) {
        Object savedTemplate = getConfig().get("cases." + type + ".item-template");
        ItemStack item = savedTemplate instanceof ItemStack template ? template.clone() : new ItemStack(caseMaterial(type));
        if (!isCaseBlockMaterial(item.getType())) item = new ItemStack(caseMaterial(type));
        item.setAmount(1);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(animatedText(getConfig().getString("cases." + type + ".name", caseNameFallback(type)), type, namePhase));
        meta.setLore(getConfig().getStringList("cases." + type + ".lore").stream().map(line -> animatedText(line, type, namePhase)).toList());
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, type);
        item.setItemMeta(meta);
        return item;
    }

    private Material caseMaterial(String type) {
        Material configured = Material.matchMaterial(getConfig().getString("cases." + type + ".material", ""));
        if (configured != null && isCaseBlockMaterial(configured)) return configured;
        return switch (type) {
            case "epic" -> Material.TRAPPED_CHEST;
            case "legendary" -> Material.ENDER_CHEST;
            case "void" -> Material.PURPLE_SHULKER_BOX;
            default -> Material.CHEST;
        };
    }

    private boolean isCaseBlockMaterial(Material material) {
        if (material == null) return false;
        return switch (material) {
            case CHEST, TRAPPED_CHEST, ENDER_CHEST, BARREL, SHULKER_BOX,
                 WHITE_SHULKER_BOX, ORANGE_SHULKER_BOX, MAGENTA_SHULKER_BOX,
                 LIGHT_BLUE_SHULKER_BOX, YELLOW_SHULKER_BOX, LIME_SHULKER_BOX,
                 PINK_SHULKER_BOX, GRAY_SHULKER_BOX, LIGHT_GRAY_SHULKER_BOX,
                 CYAN_SHULKER_BOX, PURPLE_SHULKER_BOX, BLUE_SHULKER_BOX,
                 BROWN_SHULKER_BOX, GREEN_SHULKER_BOX, RED_SHULKER_BOX,
                 BLACK_SHULKER_BOX -> true;
            default -> false;
        };
    }

    private boolean isChestMaterial(Material material) {
        return isCaseBlockMaterial(material);
    }

    private void migrateLegacyData() {
        java.io.File oldFolder = new java.io.File(getDataFolder().getParentFile(), "WertySMP-Cases");
        if (!oldFolder.isDirectory()) return;
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Не удалось создать папку для миграции старых настроек.");
            return;
        }
        for (String fileName : List.of("config.yml", "data.yml")) {
            java.io.File oldFile = new java.io.File(oldFolder, fileName);
            java.io.File newFile = new java.io.File(getDataFolder(), fileName);
            if (oldFile.isFile() && !newFile.exists()) {
                try {
                    java.nio.file.Files.copy(oldFile.toPath(), newFile.toPath());
                    getLogger().info("Перенесён старый файл " + fileName + " из WertySMP-Cases.");
                } catch (java.io.IOException ex) {
                    getLogger().warning("Не удалось перенести " + fileName + ": " + ex.getMessage());
                }
            }
        }
    }

    private boolean isConfiguredCaseType(String type) {
        return type != null && type.matches("[a-zA-Z0-9_-]+")
                && getConfig().isConfigurationSection("cases." + type)
                && getConfig().isConfigurationSection("rewards." + type);
    }

    private List<String> getConfiguredCaseTypes() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String type : TYPES) if (isConfiguredCaseType(type)) result.add(type);
        ConfigurationSection cases = getConfig().getConfigurationSection("cases");
        if (cases != null) for (String type : cases.getKeys(false)) if (isConfiguredCaseType(type)) result.add(type);
        return new ArrayList<>(result);
    }

    private boolean isAdmin(CommandSender sender) {
        return sender.hasPermission("voidcases.admin") || sender.hasPermission("wertysmpcases.admin");
    }

    private String color(String s) { return s == null ? "" : s.replace('&', '§'); }

    private String getCaseType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String type = item.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
        return isConfiguredCaseType(type) ? type : null;
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
        if (!isConfiguredCaseType(type)) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        Location location = block.getLocation().clone().add(0.5, 0.15, 0.5);
        World world = location.getWorld();
        if (world == null) return;

        // Кейс убирается сразу, поэтому повторно запустить открытие нельзя.
        block.setType(Material.AIR, false);
        int duration = switch (type) { case "epic" -> 80; case "legendary" -> 120; case "void" -> 160; default -> 48; };
        player.sendMessage("§5§lWertySMP §8» §fОткрывается " + caseName(type) + "§f...");
        world.playSound(location, (type.equals("legendary") || type.equals("void")) ? Sound.BLOCK_ENDER_CHEST_OPEN : Sound.BLOCK_CHEST_OPEN, 0.9f, type.equals("ordinary") ? 1.2f : 0.7f);

        // Shared world animation: every nearby player sees the same rotating reward items.
        List<ItemDisplay> orbitItems = new ArrayList<>();
        ConfigurationSection previewSection = getConfig().getConfigurationSection("rewards." + type);
        if (previewSection != null) {
            List<String> previewKeys = new ArrayList<>(previewSection.getKeys(false));
            Collections.shuffle(previewKeys, random);
            for (String key : previewKeys) {
                if (orbitItems.size() >= 5) break;
                ConfigurationSection reward = previewSection.getConfigurationSection(key);
                if (reward == null) continue;
                ItemStack preview = createReward(reward);
                if (preview == null) continue;
                preview.setAmount(1);
                ItemDisplay display = world.spawn(location.clone().add(0, 0.9, 0), ItemDisplay.class);
                display.setItemStack(preview);
                display.setGravity(false);
                display.setInvulnerable(true);
                display.setSilent(true);
                orbitItems.add(display);
            }
        }

        new org.bukkit.scheduler.BukkitRunnable() {
            int tick = 0;
            @Override public void run() {
                double progress = (double) tick / duration;
                double radius = 0.25 + 0.85 * progress;
                double angle = tick * ((type.equals("legendary") || type.equals("void")) ? 0.42 : type.equals("epic") ? 0.30 : 0.22);
                Location center = location.clone().add(0, 0.65 + Math.sin(angle) * 0.12, 0);
                for (int i = 0; i < orbitItems.size(); i++) {
                    double itemAngle = angle + i * Math.PI * 2.0 / Math.max(1, orbitItems.size());
                    double itemRadius = 0.95 - progress * 0.45;
                    Location itemLocation = center.clone().add(Math.cos(itemAngle) * itemRadius,
                            0.15 + Math.sin(itemAngle * 1.7) * 0.12,
                            Math.sin(itemAngle) * itemRadius);
                    ItemDisplay display = orbitItems.get(i);
                    if (display.isValid()) {
                        display.teleport(itemLocation);
                        display.setRotation((float) Math.toDegrees(itemAngle + Math.PI / 2.0), 0.0f);
                    }
                }
                for (int i = 0; i < 3; i++) {
                    double a = angle + i * Math.PI * 2 / 3;
                    Location point = center.clone().add(Math.cos(a) * radius, Math.sin(angle * 0.7 + i) * 0.18, Math.sin(a) * radius);
                    if (type.equals("legendary") || type.equals("void")) {
                        world.spawnParticle(Particle.DUST, point, type.equals("void") ? 6 : 3, 0.05, 0.08, 0.05, new DustOptions(Color.fromRGB((tick % 16 < 8) ? 12 : 90, 0, (tick % 16 < 8) ? 18 : 190), type.equals("void") ? 1.8f : 1.2f));
                        world.spawnParticle(Particle.PORTAL, point, 2, 0.04, 0.08, 0.04, 0.02);
                    } else if (type.equals("epic")) {
                        world.spawnParticle(Particle.WITCH, point, 3, 0.06, 0.08, 0.06, 0.02);
                        world.spawnParticle(Particle.PORTAL, point, 2, 0.04, 0.08, 0.04, 0.02);
                    } else {
                        world.spawnParticle(Particle.END_ROD, point, 1, 0.02, 0.04, 0.02, 0.005);
                        world.spawnParticle(Particle.HAPPY_VILLAGER, point, 2, 0.06, 0.08, 0.06, 0.01);
                    }
                }
                if (tick % 8 == 0) world.playSound(location, (type.equals("legendary") || type.equals("void")) ? Sound.BLOCK_BEACON_AMBIENT : Sound.BLOCK_NOTE_BLOCK_PLING, 0.35f, 0.6f + (float) progress * 1.3f);
                if (tick >= duration) {
                    world.spawnParticle(Particle.EXPLOSION, center, type.equals("legendary") ? 2 : 1, 0.2, 0.2, 0.2, 0);
                    if (type.equals("void")) {
                        world.spawnParticle(Particle.DUST, center, 180, 0.9, 0.9, 0.9, new DustOptions(Color.fromRGB(20, 0, 35), 2.4f));
                        world.spawnParticle(Particle.PORTAL, center, 160, 0.8, 0.8, 0.8, 0.35);
                        world.spawnParticle(Particle.WITCH, center, 100, 0.7, 0.7, 0.7, 0.12);
                        world.playSound(location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 0.55f);
                    } else if (type.equals("legendary")) {
                        world.spawnParticle(Particle.DUST, center, 100, 0.8, 0.8, 0.8, new DustOptions(Color.fromRGB(35, 0, 55), 2.0f));
                        world.spawnParticle(Particle.PORTAL, center, 100, 0.8, 0.8, 0.8, 0.25);
                        world.playSound(location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 0.7f);
                    } else if (type.equals("epic")) {
                        world.spawnParticle(Particle.WITCH, center, 70, 0.6, 0.7, 0.6, 0.1);
                        world.playSound(location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 0.8f);
                    } else {
                        world.spawnParticle(Particle.HAPPY_VILLAGER, center, 45, 0.5, 0.5, 0.5, 0.1);
                        world.playSound(location, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
                    }
                    for (ItemDisplay display : orbitItems) {
                        if (display.isValid()) display.remove();
                    }
                    finishOpen(player, location, type);
                    cancel();
                    return;
                }
                tick += 2;
            }
        }.runTaskTimer(this, 0L, 2L);
    }

    private void finishOpen(Player player, Location location, String type) {
        World world = location.getWorld();
        ConfigurationSection rewards = getConfig().getConfigurationSection("rewards." + type);
        if (rewards == null) {
            if (player.isOnline()) player.sendMessage("§5Для этого кейса не настроены награды. Сообщи администрации.");
            return;
        }
        int granted = 0;
        List<String> won = new ArrayList<>();
        List<String> rewardKeys = new ArrayList<>(rewards.getKeys(false));
        if (type.equals("void")) {
            // В кейсе Пустоты: 75% шанс получить одну награду; веса из config.yml выбирают награду.
            rewardKeys.clear();
            if (random.nextDouble() < 0.75) {
                double totalWeight = 0.0;
                for (String key : rewards.getKeys(false)) {
                    ConfigurationSection candidate = rewards.getConfigurationSection(key);
                    if (candidate != null) totalWeight += Math.max(0.0, candidate.getDouble("chance", 0.0));
                }
                if (totalWeight > 0.0) {
                    double roll = random.nextDouble() * totalWeight;
                    for (String key : rewards.getKeys(false)) {
                        ConfigurationSection candidate = rewards.getConfigurationSection(key);
                        if (candidate == null) continue;
                        roll -= Math.max(0.0, candidate.getDouble("chance", 0.0));
                        if (roll < 0.0) { rewardKeys.add(key); break; }
                    }
                }
            }
        }
        for (String key : rewardKeys) {
            ConfigurationSection reward = rewards.getConfigurationSection(key);
            if (reward == null) continue;
            double chance = reward.getDouble("chance", 0.0);
            if (!type.equals("void") && (chance <= 0 || random.nextDouble() * 100.0 >= chance)) continue;
            ItemStack prize = createReward(reward);
            if (prize == null) {
                getLogger().warning("Пропущена неверная награда '" + key + "' в rewards." + type);
                continue;
            }
            int amount = randomAmount(reward);
            if (type.equals("void")) amount = Math.min(2304, amount * 3);
            prize.setAmount(1);
            if (player.isOnline()) giveStacked(player, prize, amount);
            else if (world != null) {
                int remaining = amount;
                while (remaining > 0) {
                    int count = Math.min(remaining, Math.max(1, prize.getMaxStackSize()));
                    ItemStack drop = prize.clone();
                    drop.setAmount(count);
                    world.dropItemNaturally(location, drop);
                    remaining -= count;
                }
            }
            granted++;
            won.add(color(reward.getString("display", key)) + " §7×" + amount);
        }
        if (!player.isOnline()) return;
        player.sendMessage("§5§lWertySMP §8» §fОткрыт " + caseName(type) + "§f!");
        stats.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>()).merge(type, 1, Integer::sum); savePersistentData();
        if (granted == 0) player.sendMessage("§7В этот раз ничего дополнительного не выпало. Повезёт в следующий раз!");
        else {
            player.sendMessage("§fВыпало наград: §5" + granted);
            for (int i = 0; i < Math.min(8, won.size()); i++) player.sendMessage(" §8• " + won.get(i));
            if (won.size() > 8) player.sendMessage(" §7И ещё наград: " + (won.size() - 8));
        }
    }

    private String caseNameFallback(String type) {
        return switch (type) {
            case "epic" -> "&5&lЭпический кейс";
            case "legendary" -> "&5&l✦ Легендарный кейс ✦";
            case "void" -> "&5&l☄ Кейс Пустоты ☄";
            default -> "&f&lОбычный кейс";
        };
    }

    private String animatedText(String raw, String type, int phase) {
        if (raw == null) return "";
        String plain = raw.replaceAll("&[0-9a-fk-orA-FK-OR]", "");
        StringBuilder out = new StringBuilder();
        String[] ordinary = {"§f", "§b", "§f", "§3"};
        String[] epic = {"§5", "§d", "§5", "§8"};
        String[] legendary = {"§5", "§f", "§5", "§8"};
        String[] palette = type.equals("legendary") ? legendary : type.equals("epic") ? epic : ordinary;
        boolean bold = raw.contains("&l");
        for (int i = 0; i < plain.length(); i++) {
            out.append(palette[(i + phase) % palette.length]);
            if (bold) out.append("§l");
            out.append(plain.charAt(i));
        }
        return out.toString();
    }

    private int randomAmount(ConfigurationSection reward) {
        int min = Math.max(1, reward.getInt("min", reward.getInt("amount", 1)));
        int max = Math.max(min, reward.getInt("max", min));
        return min + random.nextInt(max - min + 1);
    }

    private ItemStack createReward(ConfigurationSection reward) {
        Object storedItem = reward.get("item");
        if (storedItem instanceof ItemStack savedItem) return savedItem.clone();
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
        if (!isConfiguredCaseType(type)) return;
        event.setCancelled(true);
        block.setType(Material.AIR, false);
        block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.2, 0.5), createCase(type));
    }


    private void loadPersistentData() {
        saveDefaultConfig();
        org.bukkit.configuration.file.YamlConfiguration data = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.File(getDataFolder(), "data.yml"));
        for (String key : data.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                dailyClaimed.put(id, data.getInt(key + ".claimed-day", 0));
                dailyDay.put(id, data.getInt(key + ".cycle-day", 1));
                Map<String,Integer> map = new HashMap<>();
                ConfigurationSection opens = data.getConfigurationSection(key + ".opens");
                if (opens != null) for (String type : opens.getKeys(false)) map.put(type, opens.getInt(type, 0));
                for (String type : TYPES) map.putIfAbsent(type, data.getInt(key + ".opens." + type, 0));
                stats.put(id, map);
            } catch (IllegalArgumentException ignored) { }
        }
    }
    private void savePersistentData() {
        org.bukkit.configuration.file.YamlConfiguration data = new org.bukkit.configuration.file.YamlConfiguration();
        Set<UUID> ids = new HashSet<>(); ids.addAll(dailyClaimed.keySet()); ids.addAll(stats.keySet()); ids.addAll(dailyDay.keySet());
        for (UUID id : ids) {
            data.set(id + ".claimed-day", dailyClaimed.getOrDefault(id, 0));
            data.set(id + ".cycle-day", dailyDay.getOrDefault(id, 1));
            for (Map.Entry<String, Integer> entry : stats.getOrDefault(id, Map.of()).entrySet()) data.set(id + ".opens." + entry.getKey(), entry.getValue());
        }
        try { data.save(new java.io.File(getDataFolder(), "data.yml")); }
        catch (java.io.IOException ex) { getLogger().severe("Не удалось сохранить data.yml: " + ex.getMessage()); }
    }
    private int today() { return (int) java.time.LocalDate.now(dailyZone).toEpochDay(); }
    private void openDaily(Player p) {
        int today = today();
        int claimed = dailyClaimed.getOrDefault(p.getUniqueId(), -1);
        int cycle = dailyDay.getOrDefault(p.getUniqueId(), 1);
        org.bukkit.inventory.Inventory inv = Bukkit.createInventory(null, 27, "§5Ежедневные награды");
        for (int day=1; day<=15; day++) {
            Material m = day < cycle ? Material.LIME_STAINED_GLASS_PANE : day == cycle && claimed != today ? Material.GOLD_INGOT : Material.GRAY_STAINED_GLASS_PANE;
            ItemStack item = new ItemStack(m); ItemMeta meta = item.getItemMeta();
            meta.setDisplayName((day < cycle ? "§a✓ День " : day == cycle && claimed != today ? "§e★ День " : "§8🔒 День ") + day);
            List<String> lore = new ArrayList<>();
            lore.add(day < cycle ? "§aУже получено" : day == cycle && claimed != today ? "§eНажми, чтобы получить награду" : "§7Пока недоступно");
            if (day == 5 || day == 10) lore.add("§5Эпический кейс");
            else if (day == 15) lore.add("§6Легендарный кейс");
            else lore.add("§fОбычный кейс");
            meta.setLore(lore); item.setItemMeta(meta); inv.setItem(day-1, item);
        }
        ItemStack info = new ItemStack(Material.CLOCK); ItemMeta im = info.getItemMeta();
        im.setDisplayName("§dЦикл: день " + cycle + "/15");
        im.setLore(List.of("§7Часовой пояс: " + dailyZone, "§7Следующая смена дня — в полночь"));
        info.setItemMeta(im); inv.setItem(22, info);
        p.openInventory(inv);
    }
    private void openPreview(Player p, String type, int page) {
        ConfigurationSection section = getConfig().getConfigurationSection("rewards." + type);
        if (section == null) { p.sendMessage("§cНаграды для этого кейса не настроены."); return; }
        List<ItemStack> items = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection r = section.getConfigurationSection(key); if (r == null) continue;
            ItemStack item = createReward(r); if (item == null) continue;
            item.setAmount(Math.max(1, Math.min(item.getMaxStackSize(), r.getInt("min", r.getInt("amount", 1)))));
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(color(r.getString("display", key)));
            List<String> lore = new ArrayList<>(); lore.add("§7Количество: §f" + r.getInt("min", r.getInt("amount",1)) + (r.getInt("max", r.getInt("min",1)) != r.getInt("min", r.getInt("amount",1)) ? "–" + r.getInt("max",r.getInt("min",1)) : ""));
            lore.add("§7Шанс на каждую награду: §e" + r.getDouble("chance",0) + "%"); lore.add("§8Каждая награда проверяется отдельно.");
            meta.setLore(lore); item.setItemMeta(meta); items.add(item);
        }
        int pages = Math.max(1, (items.size()+44)/45); int safePage = Math.max(0, Math.min(page,pages-1));
        org.bukkit.inventory.Inventory inv = Bukkit.createInventory(null, 54, "§5Void Cases • Preview|" + type + "|" + safePage);
        for(int i=safePage*45;i<Math.min(items.size(),(safePage+1)*45);i++) inv.setItem(i-safePage*45,items.get(i));
        if(safePage>0) inv.setItem(45, named(Material.ARROW,"§eПредыдущая страница"));
        inv.setItem(49,named(Material.BOOK,"§dСтраница "+(safePage+1)+" из "+pages));
        if(safePage+1<pages) inv.setItem(53,named(Material.ARROW,"§eСледующая страница"));
        p.openInventory(inv);
    }
    private ItemStack named(Material material, String name) { ItemStack i=new ItemStack(material); ItemMeta m=i.getItemMeta(); m.setDisplayName(name); i.setItemMeta(m); return i; }

    private void openEditorHome(Player p) {
        org.bukkit.inventory.Inventory inv = Bukkit.createInventory(null, 54, "§5Void Cases • Управление");
        List<String> types = getConfiguredCaseTypes();
        int slot = 0;
        for (String type : types) {
            if (slot >= 45) break;
            ItemStack item = createCase(type);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName("§d" + caseName(type));
            int rewardCount = getConfig().getConfigurationSection("rewards." + type).getKeys(false).size();
            meta.setLore(List.of("§7ID: §f" + type, "§7Наград: §f" + rewardCount, "§eНажми, чтобы открыть настройки"));
            item.setItemMeta(meta);
            inv.setItem(slot++, item);
        }
        inv.setItem(49, named(Material.LIME_DYE, "§a＋ Создать новый кейс"));
        inv.setItem(53, named(Material.BOOK, "§dПодсказки"));
        p.openInventory(inv);
    }

    private void openCreationPanel(Player p) {
        org.bukkit.inventory.Inventory inv = Bukkit.createInventory(null, 27, "§5Void Cases • Создание");
        ItemStack create = named(Material.WRITABLE_BOOK, "§aСоздать кейс");
        ItemMeta meta = create.getItemMeta();
        meta.setLore(List.of("§7Нажми, затем введи название в чат", "§7После создания можно выбрать модель", "§7и добавить предметы с шансами"));
        create.setItemMeta(meta);
        inv.setItem(13, create);
        inv.setItem(22, named(Material.ARROW, "§eК панели управления"));
        p.openInventory(inv);
    }

    private void openCaseSettings(Player p, String type) {
        if (!isConfiguredCaseType(type)) return;
        org.bukkit.inventory.Inventory inv = Bukkit.createInventory(null, 27, "§5Void Cases • Настройка|" + type);
        inv.setItem(10, named(Material.NAME_TAG, "§eИзменить название"));
        inv.setItem(12, named(Material.CHEST, "§dНастроить лут"));
        ItemStack model = named(caseMaterial(type), "§bВыбрать модель из руки");
        ItemMeta modelMeta = model.getItemMeta();
        modelMeta.setLore(List.of("§7Возьми сундук, бочку или шалкеровый ящик", "§7в основную руку и нажми сюда", "§8Метаданные предмета сохраняются"));
        model.setItemMeta(modelMeta);
        inv.setItem(14, model);
        inv.setItem(16, named(Material.BOOK, "§fПредпросмотр наград"));
        inv.setItem(22, named(type.startsWith("custom_") ? Material.BARRIER : Material.OBSIDIAN,
                type.startsWith("custom_") ? "§cУдалить этот кейс" : "§8Встроенный кейс нельзя удалить"));
        inv.setItem(26, named(Material.ARROW, "§eНазад"));
        p.openInventory(inv);
    }

    private void openRewardEditor(Player p, String type) {
        ConfigurationSection section = getConfig().getConfigurationSection("rewards." + type);
        if (section == null) { p.sendMessage("§cУ этого кейса пока нет раздела наград."); return; }
        org.bukkit.inventory.Inventory inv = Bukkit.createInventory(null, 54, "§5Void Cases • Лут|" + type);
        int slot = 0;
        for (String key : section.getKeys(false)) {
            if (slot >= 45) break;
            ConfigurationSection reward = section.getConfigurationSection(key);
            if (reward == null) continue;
            ItemStack item = createReward(reward);
            if (item == null) continue;
            item.setAmount(Math.max(1, Math.min(item.getMaxStackSize(), reward.getInt("min", reward.getInt("amount", 1)))));
            ItemMeta meta = item.getItemMeta();
            int min = reward.getInt("min", reward.getInt("amount", 1));
            int max = reward.getInt("max", min);
            meta.setDisplayName(color(reward.getString("display", key)));
            meta.setLore(List.of("§7Шанс: §e" + reward.getDouble("chance", 0.0) + "%",
                    "§7Количество: §f" + min + (max == min ? "" : "–" + max),
                    "§eНажми для настройки",
                    "§8Перетащи предмет из инвентаря в пустую ячейку, чтобы добавить"));
            item.setItemMeta(meta);
            inv.setItem(slot++, item);
        }
        inv.setItem(45, named(Material.ARROW, "§eНазад к настройкам кейса"));
        inv.setItem(49, named(Material.HOPPER, "§aДобавить предмет перетаскиванием"));
        inv.setItem(53, named(Material.BOOK, "§dПосмотреть все награды"));
        p.openInventory(inv);
    }

    private void openRewardSettings(Player p, String type, String key) {
        ConfigurationSection reward = getConfig().getConfigurationSection("rewards." + type + "." + key);
        if (reward == null) return;
        org.bukkit.inventory.Inventory inv = Bukkit.createInventory(null, 27, "§5Void Cases • Награда|" + type + "|" + key);
        ItemStack chance = named(Material.CLOCK, "§eИзменить шанс");
        ItemMeta chanceMeta = chance.getItemMeta();
        chanceMeta.setLore(List.of("§7Сейчас: §f" + reward.getDouble("chance", 0.0) + "%", "§7Введи значение от 0 до 100 в чате"));
        chance.setItemMeta(chanceMeta);
        inv.setItem(10, chance);
        ItemStack min = named(Material.PAPER, "§bМинимальное количество");
        ItemMeta minMeta = min.getItemMeta();
        minMeta.setLore(List.of("§7Сейчас: §f" + reward.getInt("min", reward.getInt("amount", 1)), "§7Введи число от 1 до 2304"));
        min.setItemMeta(minMeta);
        inv.setItem(12, min);
        ItemStack max = named(Material.MAP, "§bМаксимальное количество");
        ItemMeta maxMeta = max.getItemMeta();
        maxMeta.setLore(List.of("§7Сейчас: §f" + reward.getInt("max", reward.getInt("min", 1)), "§7Введи число от 1 до 2304"));
        max.setItemMeta(maxMeta);
        inv.setItem(14, max);
        inv.setItem(16, named(Material.NAME_TAG, "§dИзменить название награды"));
        inv.setItem(22, named(Material.BARRIER, "§cУдалить награду"));
        inv.setItem(26, named(Material.ARROW, "§eНазад к списку лута"));
        p.openInventory(inv);
    }

    private void requestInput(Player p, String action, String type, String rewardKey, String message) {
        pendingInputs.put(p.getUniqueId(), new PendingInput(action, type, rewardKey));
        p.closeInventory();
        p.sendMessage("§5§lVoid Cases §8» §f" + message);
        p.sendMessage("§7Введи значение в чат. Для отмены напиши §cотмена§7.");
    }

    @EventHandler
    public void onAdminChat(AsyncPlayerChatEvent event) {
        PendingInput pending = pendingInputs.remove(event.getPlayer().getUniqueId());
        if (pending == null) return;
        event.setCancelled(true);
        String input = event.getMessage();
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> handlePendingInput(player, pending, input));
    }

    private void handlePendingInput(Player player, PendingInput pending, String rawInput) {
        if (!player.isOnline()) return;
        String input = rawInput == null ? "" : rawInput.trim();
        if (input.equalsIgnoreCase("отмена") || input.equalsIgnoreCase("cancel")) {
            player.sendMessage("§7Изменение отменено.");
            openEditorHome(player);
            return;
        }
        String type = pending.type();
        String path = "rewards." + type + "." + pending.rewardKey();
        try {
            switch (pending.action()) {
                case "create_case" -> {
                    if (input.isBlank()) { player.sendMessage("§cНазвание не может быть пустым."); openCreationPanel(player); return; }
                    String id = "custom_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
                    getConfig().set("cases." + id + ".name", "&5&l" + input);
                    getConfig().set("cases." + id + ".material", "CHEST");
                    getConfig().set("cases." + id + ".lore", List.of("&7Авторский кейс Void Cases", "&dНажми ПКМ для открытия"));
                    getConfig().createSection("rewards." + id);
                    saveConfig();
                    player.sendMessage("§aКейс создан: §f" + input + " §7(ID: " + id + ")");
                    openCaseSettings(player, id);
                }
                case "rename_case" -> {
                    if (!isConfiguredCaseType(type) || input.isBlank()) { player.sendMessage("§cНекорректное название."); return; }
                    getConfig().set("cases." + type + ".name", "&5&l" + input);
                    saveConfig();
                    player.sendMessage("§aНазвание кейса обновлено.");
                    openCaseSettings(player, type);
                }
                case "chance" -> {
                    double value = Double.parseDouble(input.replace(',', '.'));
                    if (!Double.isFinite(value) || value < 0 || value > 100) throw new IllegalArgumentException();
                    getConfig().set(path + ".chance", value);
                    saveConfig();
                    player.sendMessage("§aШанс установлен: §e" + value + "%");
                    openRewardSettings(player, type, pending.rewardKey());
                }
                case "min", "max" -> {
                    int value = Integer.parseInt(input);
                    if (value < 1 || value > 2304) throw new IllegalArgumentException();
                    ConfigurationSection reward = getConfig().getConfigurationSection(path);
                    if (reward == null) return;
                    int currentMin = reward.getInt("min", reward.getInt("amount", 1));
                    int currentMax = reward.getInt("max", currentMin);
                    if (pending.action().equals("min")) {
                        if (value > currentMax) currentMax = value;
                        getConfig().set(path + ".min", value);
                        getConfig().set(path + ".max", currentMax);
                    } else {
                        if (value < currentMin) currentMin = value;
                        getConfig().set(path + ".min", currentMin);
                        getConfig().set(path + ".max", value);
                    }
                    saveConfig();
                    player.sendMessage("§aКоличество обновлено.");
                    openRewardSettings(player, type, pending.rewardKey());
                }
                case "display" -> {
                    if (input.isBlank()) throw new IllegalArgumentException();
                    getConfig().set(path + ".display", input.replace('&', '§'));
                    saveConfig();
                    player.sendMessage("§aНазвание награды обновлено.");
                    openRewardSettings(player, type, pending.rewardKey());
                }
                default -> player.sendMessage("§cНеизвестное действие редактора.");
            }
        } catch (IllegalArgumentException ex) {
            player.sendMessage("§cНекорректное значение. Проверь формат и попробуй снова.");
            if (pending.action().equals("create_case")) openCreationPanel(player);
            else if (pending.action().equals("rename_case")) openCaseSettings(player, type);
            else if (pending.action().equals("chance") || pending.action().equals("min") || pending.action().equals("max") || pending.action().equals("display")) openRewardSettings(player, type, pending.rewardKey());
        }
    }

    private void addRewardFromItem(Player p, String type, ItemStack source) {
        if (!isConfiguredCaseType(type) || source == null || source.getType().isAir()) return;
        String key = "reward_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String path = "rewards." + type + "." + key;
        ItemStack stored = source.clone();
        int amount = Math.max(1, Math.min(2304, stored.getAmount()));
        stored.setAmount(1);
        getConfig().set(path + ".item", stored);
        getConfig().set(path + ".material", stored.getType().name());
        getConfig().set(path + ".min", amount);
        getConfig().set(path + ".max", amount);
        getConfig().set(path + ".chance", 100.0);
        String display = stored.hasItemMeta() && stored.getItemMeta().hasDisplayName()
                ? stored.getItemMeta().getDisplayName() : stored.getType().name();
        getConfig().set(path + ".display", display);
        saveConfig();
        p.sendMessage("§aПредмет добавлен в лут. Нажми на него, чтобы настроить шанс и количество.");
        openRewardEditor(p, type);
    }

    private void setCaseModelFromHand(Player p, String type) {
        ItemStack held = p.getInventory().getItemInMainHand();
        if (held.getType().isAir() || !isCaseBlockMaterial(held.getType())) {
            p.sendMessage("§cНужен блок-модель: сундук, сундук-ловушка, эндер-сундук, бочка или шалкеровый ящик.");
            return;
        }
        ItemStack template = held.clone();
        template.setAmount(1);
        getConfig().set("cases." + type + ".item-template", template);
        getConfig().set("cases." + type + ".material", held.getType().name());
        saveConfig();
        p.sendMessage("§aМодель кейса сохранена: §f" + held.getType().name());
        openCaseSettings(p, type);
    }

    private String rewardKeyAtSlot(String type, int slot) {
        ConfigurationSection section = getConfig().getConfigurationSection("rewards." + type);
        if (section == null) return null;
        int visible = 0;
        for (String key : section.getKeys(false)) {
            ConfigurationSection reward = section.getConfigurationSection(key);
            if (reward == null || createReward(reward) == null) continue;
            if (visible++ == slot) return key;
        }
        return null;
    }
    private void openTop(Player p) {
        org.bukkit.inventory.Inventory inv=Bukkit.createInventory(null,27,"§5Топ кейсов");
        String[] types={"ordinary","epic","legendary","void"};
        Material[] icons={Material.CHEST,Material.TRAPPED_CHEST,Material.ENDER_CHEST,Material.RESPAWN_ANCHOR};
        for(int i=0;i<types.length;i++){
            final String type=types[i];
            ItemStack item=new ItemStack(icons[i]); ItemMeta meta=item.getItemMeta();
            meta.setDisplayName("§d"+caseName(type));
            List<String> lore=new ArrayList<>(); stats.entrySet().stream().sorted((a,b)->Integer.compare(b.getValue().getOrDefault(type,0),a.getValue().getOrDefault(type,0))).limit(10).forEach(e->{String name=Bukkit.getOfflinePlayer(e.getKey()).getName(); lore.add("§7"+(name==null?e.getKey().toString().substring(0,8):name)+" §8— §f"+e.getValue().getOrDefault(type,0));});
            if(lore.isEmpty()) lore.add("§7Пока нет открытий"); meta.setLore(lore); item.setItemMeta(meta); inv.setItem(10+i*2,item);
        }
        p.openInventory(inv);
    }
    @EventHandler public void onMenuClick(org.bukkit.event.inventory.InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title = e.getView().getTitle();
        if (title.equals("§5Ежедневные награды")) {
            e.setCancelled(true);
            if (e.getRawSlot() < 0 || e.getRawSlot() >= 27) return;
            int day = e.getRawSlot() + 1;
            int today = today();
            int cycle = dailyDay.getOrDefault(p.getUniqueId(), 1);
            if (day != cycle || dailyClaimed.getOrDefault(p.getUniqueId(), -1) == today) return;
            String type = (day == 5 || day == 10) ? "epic" : day == 15 ? "legendary" : "ordinary";
            ItemStack reward = createCase(type);
            if (p.getInventory().firstEmpty() == -1) { p.sendMessage("§cОсвободи слот инвентаря и попробуй снова."); return; }
            p.getInventory().addItem(reward);
            dailyClaimed.put(p.getUniqueId(), today);
            dailyDay.put(p.getUniqueId(), day == 15 ? 1 : day + 1);
            savePersistentData();
            p.sendMessage("§aЕжедневная награда получена: " + caseName(type));
            p.closeInventory();
            return;
        }

        if (title.startsWith("§5Void Cases • Preview|")) {
            e.setCancelled(true);
            if (e.getRawSlot() != 45 && e.getRawSlot() != 53) return;
            String[] parts = title.split("\\|");
            if (parts.length < 3) return;
            int page;
            try { page = Integer.parseInt(parts[2]); } catch (NumberFormatException ex) { return; }
            openPreview(p, parts[1], page + (e.getRawSlot() == 53 ? 1 : -1));
            return;
        }

        if (title.equals("§5Void Cases • Управление")) {
            e.setCancelled(true);
            if (!isAdmin(p)) { p.closeInventory(); return; }
            if (e.getRawSlot() == 49) { openCreationPanel(p); return; }
            if (e.getRawSlot() == 53) {
                p.sendMessage("§dПодсказка: §fвыбери кейс, затем измени его название, модель и лут.");
                p.sendMessage("§dСоздание: §f/case create §7(новый кейс), /case editor §7(список кейсов).");
                return;
            }
            if (e.getRawSlot() >= 0 && e.getRawSlot() < 45) {
                List<String> types = getConfiguredCaseTypes();
                if (e.getRawSlot() < types.size()) openCaseSettings(p, types.get(e.getRawSlot()));
            }
            return;
        }

        if (title.equals("§5Void Cases • Создание")) {
            e.setCancelled(true);
            if (!isAdmin(p)) { p.closeInventory(); return; }
            if (e.getRawSlot() == 13) requestInput(p, "create_case", "", "", "Напиши название нового кейса:");
            else if (e.getRawSlot() == 22) openEditorHome(p);
            return;
        }

        if (title.startsWith("§5Void Cases • Настройка|")) {
            e.setCancelled(true);
            if (!isAdmin(p)) { p.closeInventory(); return; }
            String type = title.substring("§5Void Cases • Настройка|".length());
            if (!isConfiguredCaseType(type)) { p.closeInventory(); return; }
            switch (e.getRawSlot()) {
                case 10 -> requestInput(p, "rename_case", type, "", "Введи новое название кейса:");
                case 12 -> openRewardEditor(p, type);
                case 14 -> setCaseModelFromHand(p, type);
                case 16 -> openPreview(p, type, 0);
                case 22 -> {
                    if (!type.startsWith("custom_")) { p.sendMessage("§cВстроенные кейсы нельзя удалить."); return; }
                    getConfig().set("cases." + type, null);
                    getConfig().set("rewards." + type, null);
                    saveConfig();
                    p.sendMessage("§eКейс удалён из конфигурации.");
                    openEditorHome(p);
                }
                case 26 -> openEditorHome(p);
                default -> { }
            }
            return;
        }

        if (title.startsWith("§5Void Cases • Лут|")) {
            e.setCancelled(true);
            if (!isAdmin(p)) { p.closeInventory(); return; }
            String type = title.substring("§5Void Cases • Лут|".length());
            if (!isConfiguredCaseType(type)) { p.closeInventory(); return; }
            if (e.getRawSlot() < 0 || e.getRawSlot() >= 54) return;
            if (e.getRawSlot() < 45 && e.getCursor() != null && !e.getCursor().getType().isAir()) {
                ItemStack cursor = e.getCursor().clone();
                p.setItemOnCursor(new ItemStack(Material.AIR));
                addRewardFromItem(p, type, cursor);
                return;
            }
            if (e.getRawSlot() == 45) { openCaseSettings(p, type); return; }
            if (e.getRawSlot() == 49) {
                p.sendMessage("§dДобавление лута: §fперетащи предмет из инвентаря в пустую ячейку верхней панели.");
                p.sendMessage("§dНастройка: §fнажми на предмет в списке, чтобы задать шанс и количество.");
                return;
            }
            if (e.getRawSlot() == 53) { openPreview(p, type, 0); return; }
            if (e.getRawSlot() < 45) {
                String key = rewardKeyAtSlot(type, e.getRawSlot());
                if (key != null) openRewardSettings(p, type, key);
            }
            return;
        }

        if (title.startsWith("§5Void Cases • Награда|")) {
            e.setCancelled(true);
            if (!isAdmin(p)) { p.closeInventory(); return; }
            String[] parts = title.split("\\|");
            if (parts.length < 3 || !isConfiguredCaseType(parts[1])) { p.closeInventory(); return; }
            String type = parts[1];
            String key = parts[2];
            String action = switch (e.getRawSlot()) {
                case 10 -> "chance";
                case 12 -> "min";
                case 14 -> "max";
                case 16 -> "display";
                default -> "";
            };
            if (!action.isEmpty()) {
                String prompt = switch (action) {
                    case "chance" -> "Введи шанс выпадения от 0 до 100:";
                    case "min" -> "Введи минимальное количество (1–2304):";
                    case "max" -> "Введи максимальное количество (1–2304):";
                    default -> "Введи новое название награды:";
                };
                requestInput(p, action, type, key, prompt);
                return;
            }
            if (e.getRawSlot() == 22) {
                getConfig().set("rewards." + type + "." + key, null);
                saveConfig();
                p.sendMessage("§eНаграда удалена.");
                openRewardEditor(p, type);
            } else if (e.getRawSlot() == 26) openRewardEditor(p, type);
            return;
        }

        if (title.equals("§5Топ кейсов")) e.setCancelled(true);
    }

    @EventHandler
    public void onMenuDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        String title = event.getView().getTitle();
        if (!title.startsWith("§5Void Cases • Лут|")) return;
        boolean touchesLootSlots = event.getRawSlots().stream().anyMatch(slot -> slot >= 0 && slot < 45);
        if (!touchesLootSlots) return;
        event.setCancelled(true);
        if (!isAdmin(p)) { p.closeInventory(); return; }
        String type = title.substring("§5Void Cases • Лут|".length());
        ItemStack cursor = event.getOldCursor();
        if (cursor != null && !cursor.getType().isAir()) {
            ItemStack copy = cursor.clone();
            p.setItemOnCursor(new ItemStack(Material.AIR));
            addRewardFromItem(p, type, copy);
        }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("case")) return false;
        if (args.length == 0) {
            if (sender instanceof Player p && isAdmin(p)) openEditorHome(p);
            else sendHelp(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("help")) { sendHelp(sender); return true; }
        if (sub.equals("daily")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Только игрок может открыть меню."); return true; }
            openDaily(p);
            return true;
        }
        if (sub.equals("top")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Только игрок может открыть топ."); return true; }
            openTop(p);
            return true;
        }
        if (sub.equals("preview")) {
            if (!(sender instanceof Player p) || args.length < 2) { sender.sendMessage("§cИспользование: /case preview <тип>"); return true; }
            String type = normalizeType(args[1]);
            if (!isConfiguredCaseType(type)) { sender.sendMessage("§cНеизвестный кейс. Используй /case help."); return true; }
            openPreview(p, type, 0);
            return true;
        }
        if (sub.equals("create")) {
            if (!isAdmin(sender)) { sender.sendMessage("§cНет прав."); return true; }
            if (!(sender instanceof Player p)) { sender.sendMessage("Только игрок может открыть редактор."); return true; }
            openCreationPanel(p);
            return true;
        }
        if (sub.equals("editor")) {
            if (!isAdmin(sender)) { sender.sendMessage("§cНет прав."); return true; }
            if (!(sender instanceof Player p)) { sender.sendMessage("Только игрок может открыть редактор."); return true; }
            openEditorHome(p);
            return true;
        }
        if (sub.equals("reload")) {
            if (!isAdmin(sender)) { sender.sendMessage("§cНет прав."); return true; }
            reloadConfig();
            sender.sendMessage("§aКонфигурация Void-cases-v2 перезагружена.");
            return true;
        }
        if (sub.equals("give") && args.length >= 3) {
            if (!isAdmin(sender)) { sender.sendMessage("§cНет прав."); return true; }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) { sender.sendMessage("§cИгрок должен быть в сети."); return true; }
            String type = normalizeType(args[2]);
            if (!isConfiguredCaseType(type)) { sender.sendMessage("§cНеизвестный тип кейса. Посмотри /case help."); return true; }
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
        sendHelp(sender);
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§5§lVoid-cases-v2 §8— §fсписок команд");
        sender.sendMessage("§d/case §7— панель управления (админ)");
        sender.sendMessage("§d/case create §7— панель создания нового кейса");
        sender.sendMessage("§d/case editor §7— список всех кейсов");
        sender.sendMessage("§d/case give <игрок> <тип> [кол-во] §7— выдать кейс");
        sender.sendMessage("§d/case preview <тип> §7— посмотреть награды и шансы");
        sender.sendMessage("§d/case daily §7— ежедневная награда");
        sender.sendMessage("§d/case top §7— статистика открытий");
        sender.sendMessage("§d/case reload §7— перезагрузить config.yml");
        if (isAdmin(sender)) sender.sendMessage("§7Встроенные типы: §fordinary, epic, legendary, void §7и созданные кейсы.");
    }

    private String normalizeType(String raw) {
        if (raw == null) return null;
        String value = raw.toLowerCase(Locale.ROOT);
        return switch (value) {
            case "ordinary", "обычный", "обычный_кейс" -> "ordinary";
            case "epic", "эпический", "эпик" -> "epic";
            case "legendary", "легендарный", "легендарка" -> "legendary";
            case "void", "пустота", "void-кейс" -> "void";
            default -> isConfiguredCaseType(value) ? value : null;
        };
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("case")) return Collections.emptyList();
        if (args.length == 1) return List.of("help", "give", "daily", "top", "preview", "create", "editor", "reload").stream()
                .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("give"))
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 3 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("preview")))
            return getConfiguredCaseTypes().stream()
                    .filter(t -> t.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 4 && args[0].equalsIgnoreCase("give")) return List.of("1", "2", "5", "10");
        return Collections.emptyList();
    }

    private String caseName(String type) { return color(getConfig().getString("cases." + type + ".name", type)); }
}
