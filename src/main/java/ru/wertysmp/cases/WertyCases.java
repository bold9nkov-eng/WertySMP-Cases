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
    private int namePhase = 0;
    private final Map<UUID, Long> openingLocks = new HashMap<>();
    private final Map<UUID, Integer> dailyClaimed = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> stats = new HashMap<>();
    private final Map<UUID, Integer> dailyDay = new HashMap<>();
    private final java.time.ZoneId dailyZone = java.time.ZoneId.of("Europe/Moscow");
    private static final List<String> TYPES = List.of("ordinary", "epic", "legendary", "void");
    private static final List<String> TYPE_ALIASES = List.of("обычный", "эпический", "легендарный");
    private static final List<String> ARMOR_SLOTS = List.of("HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS");

    @Override public void onEnable() {
        saveDefaultConfig();
        itemKey = new NamespacedKey(this, "case_type");
        blockKey = new NamespacedKey(this, "placed_case_type");
        loadPersistentData();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("wcases")).setTabCompleter(this);
        if (getCommand("dailycase") != null) getCommand("dailycase").setExecutor(this);
        getLogger().info("WertySMP Cases Enhanced включён: анимации трёх кейсов.");
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
        ItemStack item = new ItemStack(caseMaterial(type));
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(animatedText(getConfig().getString("cases." + type + ".name", caseNameFallback(type)), type, namePhase));
        meta.setLore(getConfig().getStringList("cases." + type + ".lore").stream().map(line -> animatedText(line, type, namePhase)).toList());
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, type);
        item.setItemMeta(meta);
        return item;
    }

    private Material caseMaterial(String type) {
        return switch (type) {
            case "epic" -> Material.TRAPPED_CHEST;
            case "legendary" -> Material.ENDER_CHEST;
            case "void" -> Material.RESPAWN_ANCHOR;
            default -> Material.CHEST;
        };
    }

    private boolean isChestMaterial(Material material) {
        return material == Material.CHEST || material == Material.TRAPPED_CHEST || material == Material.ENDER_CHEST || material == Material.RESPAWN_ANCHOR;
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
        Location location = block.getLocation().clone().add(0.5, 0.15, 0.5);
        World world = location.getWorld();
        if (world == null) return;

        // Кейс убирается сразу, поэтому повторно запустить открытие нельзя.
        block.setType(Material.AIR, false);
        int duration = switch (type) { case "epic" -> 80; case "legendary" -> 120; case "void" -> 160; default -> 48; };
        player.sendMessage("§5§lWertySMP §8» §fОткрывается " + caseName(type) + "§f...");
        player.playSound(location, type.equals("legendary") ? Sound.BLOCK_ENDER_CHEST_OPEN : Sound.BLOCK_CHEST_OPEN, 0.9f, type.equals("ordinary") ? 1.2f : 0.7f);

        new org.bukkit.scheduler.BukkitRunnable() {
            int tick = 0;
            @Override public void run() {
                double progress = (double) tick / duration;
                double radius = 0.25 + 0.85 * progress;
                double angle = tick * (type.equals("legendary") ? 0.42 : type.equals("epic") ? 0.30 : 0.22);
                Location center = location.clone().add(0, 0.65 + Math.sin(angle) * 0.12, 0);
                for (int i = 0; i < 3; i++) {
                    double a = angle + i * Math.PI * 2 / 3;
                    Location point = center.clone().add(Math.cos(a) * radius, Math.sin(angle * 0.7 + i) * 0.18, Math.sin(a) * radius);
                    if (type.equals("legendary")) {
                        world.spawnParticle(Particle.DUST, point, 3, 0.05, 0.08, 0.05, new DustOptions(Color.fromRGB((tick % 16 < 8) ? 12 : 90, 0, (tick % 16 < 8) ? 18 : 190), 1.2f));
                        world.spawnParticle(Particle.PORTAL, point, 2, 0.04, 0.08, 0.04, 0.02);
                    } else if (type.equals("epic")) {
                        world.spawnParticle(Particle.WITCH, point, 3, 0.06, 0.08, 0.06, 0.02);
                        world.spawnParticle(Particle.PORTAL, point, 2, 0.04, 0.08, 0.04, 0.02);
                    } else {
                        world.spawnParticle(Particle.END_ROD, point, 1, 0.02, 0.04, 0.02, 0.005);
                        world.spawnParticle(Particle.HAPPY_VILLAGER, point, 2, 0.06, 0.08, 0.06, 0.01);
                    }
                }
                if (tick % 8 == 0) player.playSound(location, type.equals("legendary") ? Sound.BLOCK_BEACON_AMBIENT : Sound.BLOCK_NOTE_BLOCK_PLING, 0.35f, 0.6f + (float) progress * 1.3f);
                if (tick >= duration) {
                    world.spawnParticle(Particle.EXPLOSION, center, type.equals("legendary") ? 2 : 1, 0.2, 0.2, 0.2, 0);
                    if (type.equals("legendary")) {
                        world.spawnParticle(Particle.DUST, center, 100, 0.8, 0.8, 0.8, new DustOptions(Color.fromRGB(35, 0, 55), 2.0f));
                        world.spawnParticle(Particle.PORTAL, center, 100, 0.8, 0.8, 0.8, 0.25);
                        player.playSound(location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 0.7f);
                    } else if (type.equals("epic")) {
                        world.spawnParticle(Particle.WITCH, center, 70, 0.6, 0.7, 0.6, 0.1);
                        player.playSound(location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 0.8f);
                    } else {
                        world.spawnParticle(Particle.HAPPY_VILLAGER, center, 45, 0.5, 0.5, 0.5, 0.1);
                        player.playSound(location, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
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
        stats.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>()).merge(type, 1, Integer::sum); savePersistentData();\n        if (granted == 0) player.sendMessage("§7В этот раз ничего дополнительного не выпало. Повезёт в следующий раз!");
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


    private void loadPersistentData() {
        saveDefaultConfig();
        org.bukkit.configuration.file.YamlConfiguration data = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.File(getDataFolder(), "data.yml"));
        for (String key : data.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                dailyClaimed.put(id, data.getInt(key + ".claimed-day", 0));
                dailyDay.put(id, data.getInt(key + ".cycle-day", 1));
                Map<String,Integer> map = new HashMap<>();
                for (String type : TYPES) map.put(type, data.getInt(key + ".opens." + type, 0));
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
            for (String type : TYPES) data.set(id + ".opens." + type, stats.getOrDefault(id, Map.of()).getOrDefault(type, 0));
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
        org.bukkit.inventory.Inventory inv = Bukkit.createInventory(null, 54, "§5Награды: " + type + " §8("+(safePage+1)+"/"+pages+")");
        for(int i=safePage*45;i<Math.min(items.size(),(safePage+1)*45);i++) inv.setItem(i-safePage*45,items.get(i));
        if(safePage>0) inv.setItem(45, named(Material.ARROW,"§eПредыдущая страница"));
        inv.setItem(49,named(Material.BOOK,"§dСтраница "+(safePage+1)+" из "+pages));
        if(safePage+1<pages) inv.setItem(53,named(Material.ARROW,"§eСледующая страница"));
        p.openInventory(inv);
    }
    private ItemStack named(Material material, String name) { ItemStack i=new ItemStack(material); ItemMeta m=i.getItemMeta(); m.setDisplayName(name); i.setItemMeta(m); return i; }
    private void openTop(Player p) {
        org.bukkit.inventory.Inventory inv=Bukkit.createInventory(null,27,"§5Топ кейсов");
        String[] types={"ordinary","epic","legendary","void"};
        Material[] icons={Material.CHEST,Material.TRAPPED_CHEST,Material.ENDER_CHEST,Material.RESPAWN_ANCHOR};
        for(int i=0;i<types.length;i++){
            ItemStack item=new ItemStack(icons[i]); ItemMeta meta=item.getItemMeta();
            meta.setDisplayName("§d"+caseName(types[i]));
            List<String> lore=new ArrayList<>(); stats.entrySet().stream().sorted((a,b)->Integer.compare(b.getValue().getOrDefault(types[i],0),a.getValue().getOrDefault(types[i],0))).limit(10).forEach(e->{String name=Bukkit.getOfflinePlayer(e.getKey()).getName(); lore.add("§7"+(name==null?e.getKey().toString().substring(0,8):name)+" §8— §f"+e.getValue().getOrDefault(types[i],0));});
            if(lore.isEmpty()) lore.add("§7Пока нет открытий"); meta.setLore(lore); item.setItemMeta(meta); inv.setItem(10+i*2,item);
        }
        p.openInventory(inv);
    }
    @EventHandler public void onMenuClick(org.bukkit.event.inventory.InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title=e.getView().getTitle();
        if(title.equals("§5Ежедневные награды")) {
            e.setCancelled(true); if(e.getRawSlot()<0||e.getRawSlot()>=27) return;
            int day=e.getRawSlot()+1; int today=today(); int cycle=dailyDay.getOrDefault(p.getUniqueId(),1);
            if(day!=cycle || dailyClaimed.getOrDefault(p.getUniqueId(),-1)==today) return;
            String type=(day==5||day==10)?"epic":day==15?"legendary":"ordinary";
            ItemStack reward=createCase(type);
            if(p.getInventory().firstEmpty()==-1){p.sendMessage("§cОсвободи слот инвентаря и попробуй снова.");return;}
            p.getInventory().addItem(reward); dailyClaimed.put(p.getUniqueId(),today);
            dailyDay.put(p.getUniqueId(),day==15?1:day+1); savePersistentData(); p.sendMessage("§aЕжедневная награда получена: "+caseName(type)); p.closeInventory(); return;
        }
        if(title.startsWith("§5Награды:")) {
            e.setCancelled(true); if(e.getRawSlot()==45||e.getRawSlot()==53){String[] bits=title.split("\\("); int page=0; try{page=Integer.parseInt(bits[1].split("/")[0])-1;}catch(Exception ignored){} openPreview(p,title.contains("void")?"void":title.contains("epic")?"epic":title.contains("legendary")?"legendary":"ordinary",page+(e.getRawSlot()==53?1:-1));} return;
        }
        if(title.equals("§5Топ кейсов")) e.setCancelled(true);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("dailycase")) { if (!(sender instanceof Player p)) { sender.sendMessage("Только игрок может открыть меню."); return true; } openDaily(p); return true; }\n        if (!command.getName().equalsIgnoreCase("wcases")) return false;
        if (args.length > 0 && args[0].equalsIgnoreCase("daily")) { if (!(sender instanceof Player p)) { sender.sendMessage("Только игрок."); return true; } openDaily(p); return true; }\n        if (args.length > 0 && (args[0].equalsIgnoreCase("top"))) { if (!(sender instanceof Player p)) { sender.sendMessage("Только игрок."); return true; } openTop(p); return true; }\n        if (args.length > 0 && args[0].equalsIgnoreCase("preview")) { if (!(sender instanceof Player p) || args.length < 2) { sender.sendMessage("Использование: /wcases preview <ordinary|epic|legendary|void>"); return true; } String t=normalizeType(args[1]); if(t==null){sender.sendMessage("Неизвестный кейс.");return true;} openPreview(p,t,0); return true; }\n        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage("§6WertySMP Cases §7— §f/wcases give <игрок> <ordinary|epic|legendary> [количество]");
            sender.sendMessage("§7Также: /wcases give <игрок> <обычный|эпический|легендарный> [количество]");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("editor")) { if (!sender.hasPermission("wertysmpcases.admin")) { sender.sendMessage("§cНет прав."); return true; } sender.sendMessage("§eРедактор кейсов: настрой награды в config.yml и используй /wcases reload (перезагрузка без перезапуска)."); return true; }\n        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) { if (!sender.hasPermission("wertysmpcases.admin")) { sender.sendMessage("§cНет прав."); return true; } reloadConfig(); sender.sendMessage("§aКонфигурация перезагружена."); return true; }\n        if (!sender.hasPermission("wertysmpcases.admin")) {
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
        if (args.length == 1) return List.of("give", "help", "daily", "top", "preview", "editor", "reload");
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) return List.of("ordinary", "epic", "legendary", "обычный", "эпический", "легендарный");
        if (args.length == 4 && args[0].equalsIgnoreCase("give")) return List.of("1", "2", "5", "10");
        return Collections.emptyList();
    }

    private String caseName(String type) { return color(getConfig().getString("cases." + type + ".name", type)); }
}
