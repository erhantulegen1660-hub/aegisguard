package ru.aegis.auth;

import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AegisAuth extends JavaPlugin implements Listener, CommandExecutor {

    private enum AuthState {
        CAPTCHA,
        REGISTER,
        LOGIN
    }

    private final Map<UUID, AuthState> unauthedPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, String> captchaCodes = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> captchaAttempts = new ConcurrentHashMap<>();
    private final Map<UUID, ItemStack[]> savedInventories = new ConcurrentHashMap<>();

    private File usersFile;
    private FileConfiguration usersConfig;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        setupUsersFile();

        // Регистрируем канал для переноса между серверами через Velocity / BungeeCord
        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");

        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("register")).setExecutor(this);
        Objects.requireNonNull(getCommand("login")).setExecutor(this);
        Objects.requireNonNull(getCommand("aegisauth")).setExecutor(this);

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            int minLen = getConfig().getInt("min-password-length", 6);
            for (Map.Entry<UUID, AuthState> entry : unauthedPlayers.entrySet()) {
                Player p = Bukkit.getPlayer(entry.getKey());
                if (p == null || !p.isOnline()) continue;

                switch (entry.getValue()) {
                    case CAPTCHA -> p.sendMessage("§6[Защита] §fПосмотрите на карту в руке и введите §e4 цифры §fв чат.");
                    case REGISTER -> p.sendMessage("§6[Защита] §fЗарегистрируйтесь: §a/register <пароль> <повтор> §7(мин. " + minLen + " символов)");
                    case LOGIN -> p.sendMessage("§6[Защита] §fВойдите в аккаунт: §a/login <пароль>");
                }
            }
        }, 40L, 60L);

        getLogger().info("AegisAuth успешно запущен!");
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
    }

    private void setupUsersFile() {
        usersFile = new File(getDataFolder(), "users.yml");
        if (!usersFile.exists()) {
            try {
                getDataFolder().mkdirs();
                usersFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        usersConfig = YamlConfiguration.loadConfiguration(usersFile);
    }

    private void saveUsersFile() {
        try {
            usersConfig.save(usersFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ==================== ВХОД И ВЫХОД ====================

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        int maxQueue = getConfig().getInt("max-queue-size", 5);
        if (unauthedPlayers.size() >= maxQueue) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_FULL,
                    "§cСейчас в зоне проверки уже находится " + maxQueue + " человек.\n§eПопробуйте зайти через пару секунд!");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        savedInventories.put(uuid, player.getInventory().getContents());
        player.getInventory().clear();

        Location cubeLoc = getLocationFromConfig("cube");
        if (cubeLoc != null) {
            player.teleport(cubeLoc);
        }

        String code = String.format("%04d", new Random().nextInt(10000));
        captchaCodes.put(uuid, code);
        captchaAttempts.put(uuid, 0);
        unauthedPlayers.put(uuid, AuthState.CAPTCHA);

        giveCaptchaMap(player, code);

        player.sendMessage("§8§m----------------------------------------");
        player.sendMessage("§6§lAEGIS §8» §fПроверка на бота!");
        player.sendMessage("§fВведите в чат §e4 цифры§f, нарисованные на карте в вашей руке.");
        player.sendMessage("§8§m----------------------------------------");
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (savedInventories.containsKey(uuid)) {
            event.getPlayer().getInventory().setContents(savedInventories.remove(uuid));
        }
        unauthedPlayers.remove(uuid);
        captchaCodes.remove(uuid);
        captchaAttempts.remove(uuid);
    }

    // ==================== ЗАВЕРШЕНИЕ АВТОРИЗАЦИИ ====================

    private void completeAuthentication(Player player) {
        UUID uuid = player.getUniqueId();

        unauthedPlayers.remove(uuid);
        captchaCodes.remove(uuid);
        captchaAttempts.remove(uuid);

        player.getInventory().clear();
        if (savedInventories.containsKey(uuid)) {
            player.getInventory().setContents(savedInventories.remove(uuid));
        }

        player.setGameMode(GameMode.SURVIVAL);

        player.sendMessage("§a§l✔ Вы успешно вошли на сервер! Приятной игры.");
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);

        String mode = getConfig().getString("transfer-mode", "teleport");
        if ("proxy".equalsIgnoreCase(mode)) {
            String targetServer = getConfig().getString("target-proxy-server", "survival");
            sendPlayerToServer(player, targetServer);
        } else {
            Location mainSpawn = getLocationFromConfig("spawn");
            if (mainSpawn != null) {
                player.teleport(mainSpawn);
            }
        }
    }

    private void sendPlayerToServer(Player player, String serverName) {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(b);
            out.writeUTF("Connect");
            out.writeUTF(serverName);
            player.sendPluginMessage(this, "BungeeCord", b.toByteArray());
        } catch (IOException e) {
            getLogger().warning("Не удалось отправить игрока " + player.getName() + " на сервер " + serverName);
        }
    }

    // ==================== КОМАНДЫ /register, /login, /aegisauth ====================

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Команда доступна только игрокам.");
            return true;
        }

        String cmd = command.getName().toLowerCase();
        UUID uuid = player.getUniqueId();
        String nameKey = player.getName().toLowerCase();

        if (cmd.equals("aegisauth")) {
            if (!player.hasPermission("aegisauth.admin")) {
                player.sendMessage("§cНет прав.");
                return true;
            }
            if (args.length == 1 && args[0].equalsIgnoreCase("setcube")) {
                saveLocationToConfig("cube", player.getLocation());
                player.sendMessage("§aТочка куба проверки установлена!");
                return true;
            }
            if (args.length == 1 && args[0].equalsIgnoreCase("setspawn")) {
                saveLocationToConfig("spawn", player.getLocation());
                player.sendMessage("§aТочка основного спавна / мира выживания установлена!");
                return true;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("setserver")) {
                getConfig().set("transfer-mode", "proxy");
                getConfig().set("target-proxy-server", args[1]);
                saveConfig();
                player.sendMessage("§aВключен перенос через прокси на сервер: §e" + args[1]);
                return true;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("setmode")) {
                getConfig().set("transfer-mode", args[1].toLowerCase());
                saveConfig();
                player.sendMessage("§aРежим переноса изменен на: §e" + args[1].toLowerCase());
                return true;
            }
            player.sendMessage("§eКоманды настройки:");
            player.sendMessage("§6/aegisauth setcube §7- установить точку куба проверки");
            player.sendMessage("§6/aegisauth setspawn §7- установить точку спавна / мира после входа");
            player.sendMessage("§6/aegisauth setserver <название> §7- перенос на другой сервер (Velocity)");
            player.sendMessage("§6/aegisauth setmode <teleport|proxy> §7- выбрать способ переноса");
            return true;
        }

        AuthState state = unauthedPlayers.get(uuid);
        if (state == null) {
            player.sendMessage("§aВы уже авторизованы!");
            return true;
        }

        if (state == AuthState.CAPTCHA) {
            player.sendMessage("§cСначала введите 4 цифры с карты в чат!");
            return true;
        }

        int minLen = getConfig().getInt("min-password-length", 6);

        if (cmd.equals("register")) {
            if (usersConfig.contains("users." + nameKey)) {
                player.sendMessage("§cВы уже зарегистрированы! Используйте: §e/login <пароль>");
                unauthedPlayers.put(uuid, AuthState.LOGIN);
                return true;
            }
            if (args.length != 2) {
                player.sendMessage("§cИспользование: §e/register <пароль> <повтор_пароля>");
                return true;
            }
            String pass1 = args[0];
            String pass2 = args[1];

            if (pass1.length() < minLen) {
                player.sendMessage("§cПароль слишком короткий! Минимум §e" + minLen + " букв или цифр§c.");
                return true;
            }
            if (!pass1.matches("[a-zA-Z0-9_!@#$%^&*]+")) {
                player.sendMessage("§cПароль должен содержать только английские буквы и цифры!");
                return true;
            }
            if (!pass1.equals(pass2)) {
                player.sendMessage("§cПароли не совпадают! Попробуйте еще раз.");
                return true;
            }

            usersConfig.set("users." + nameKey, hashPassword(pass1));
            saveUsersFile();
            completeAuthentication(player);
            return true;
        }

        if (cmd.equals("login")) {
            if (!usersConfig.contains("users." + nameKey)) {
                player.sendMessage("§cВы еще не зарегистрированы! Используйте: §e/register <пароль> <повтор>");
                unauthedPlayers.put(uuid, AuthState.REGISTER);
                return true;
            }
            if (args.length != 1) {
                player.sendMessage("§cИспользование: §e/login <пароль>");
                return true;
            }
            String savedHash = usersConfig.getString("users." + nameKey);
            if (savedHash != null && savedHash.equals(hashPassword(args[0]))) {
                completeAuthentication(player);
            } else {
                player.sendMessage("§cНеверный пароль! Попробуйте снова.");
            }
            return true;
        }

        return true;
    }

    // ==================== ОБРАБОТКА ЧАТА (ПРОВЕРКА КАПЧИ) ====================

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!unauthedPlayers.containsKey(uuid)) return;
        event.setCancelled(true);

        AuthState state = unauthedPlayers.get(uuid);
        if (state != AuthState.CAPTCHA) {
            player.sendMessage("§cЧат закрыт до входа. Используйте §e/login §cили §e/register§c.");
            return;
        }

        String input = event.getMessage().trim();
        String expected = captchaCodes.get(uuid);

        if (expected != null && expected.equals(input)) {
            String nameKey = player.getName().toLowerCase();
            int minLen = getConfig().getInt("min-password-length", 6);
            if (usersConfig.contains("users." + nameKey)) {
                unauthedPlayers.put(uuid, AuthState.LOGIN);
                player.sendMessage("§aКапча пройдена! Теперь введите: §e/login <пароль>");
            } else {
                unauthedPlayers.put(uuid, AuthState.REGISTER);
                player.sendMessage("§aКапча пройдена! Придумайте пароль (мин. " + minLen + " символов): §e/register <пароль> <повтор>");
            }
        } else {
            int attempts = captchaAttempts.getOrDefault(uuid, 0) + 1;
            captchaAttempts.put(uuid, attempts);
            if (attempts >= 3) {
                Bukkit.getScheduler().runTask(this, () ->
                        player.kickPlayer("§cВы 3 раза неверно ввели капчу!"));
            } else {
                player.sendMessage("§cНеверные цифры! Осталось попыток: §e" + (3 - attempts));
            }
        }
    }

    // ==================== БЛОКИРОВКИ ТОЛЬКО ДО АВТОРИЗАЦИИ ====================

    @EventHandler
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        if (!unauthedPlayers.containsKey(event.getPlayer().getUniqueId())) return;
        String msg = event.getMessage().toLowerCase();
        if (!msg.startsWith("/login ") && !msg.startsWith("/register ") && !msg.startsWith("/l ") && !msg.startsWith("/reg ")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cСначала пройдите проверку и войдите в аккаунт!");
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!unauthedPlayers.containsKey(event.getPlayer().getUniqueId())) return;
        if (event.getFrom().getX() != event.getTo().getX() || event.getFrom().getZ() != event.getTo().getZ()) {
            event.setTo(event.getFrom());
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (unauthedPlayers.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (unauthedPlayers.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (unauthedPlayers.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (unauthedPlayers.containsKey(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p && unauthedPlayers.containsKey(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p && unauthedPlayers.containsKey(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // ==================== ОТРИСОВКА КАРТЫ С ЦИФРАМИ ====================

    private void giveCaptchaMap(Player player, String code) {
        MapView view = Bukkit.createMap(player.getWorld());
        view.getRenderers().clear();
        view.addRenderer(new CaptchaMapRenderer(code));

        ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) mapItem.getItemMeta();
        if (meta != null) {
            meta.setMapView(view);
            meta.setDisplayName("§eВведите код с карты в чат");
            mapItem.setItemMeta(meta);
        }
        player.getInventory().setItemInMainHand(mapItem);
    }

    private static class CaptchaMapRenderer extends MapRenderer {
        private final String code;
        private boolean rendered = false;

        private static final int[][][] DIGITS = {
                {{1,1,1,1,1},{1,0,0,0,1},{1,0,0,1,1},{1,0,1,0,1},{1,1,0,0,1},{1,0,0,0,1},{1,1,1,1,1}}, // 0
                {{0,0,1,0,0},{0,1,1,0,0},{1,0,1,0,0},{0,0,1,0,0},{0,0,1,0,0},{0,0,1,0,0},{1,1,1,1,1}}, // 1
                {{1,1,1,1,1},{0,0,0,0,1},{0,0,0,0,1},{1,1,1,1,1},{1,0,0,0,0},{1,0,0,0,0},{1,1,1,1,1}}, // 2
                {{1,1,1,1,1},{0,0,0,0,1},{0,0,0,0,1},{0,1,1,1,1},{0,0,0,0,1},{0,0,0,0,1},{1,1,1,1,1}}, // 3
                {{1,0,0,0,1},{1,0,0,0,1},{1,0,0,0,1},{1,1,1,1,1},{0,0,0,0,1},{0,0,0,0,1},{0,0,0,0,1}}, // 4
                {{1,1,1,1,1},{1,0,0,0,0},{1,0,0,0,0},{1,1,1,1,1},{0,0,0,0,1},{0,0,0,0,1},{1,1,1,1,1}}, // 5
                {{1,1,1,1,1},{1,0,0,0,0},{1,0,0,0,0},{1,1,1,1,1},{1,0,0,0,1},{1,0,0,0,1},{1,1,1,1,1}}, // 6
                {{1,1,1,1,1},{0,0,0,0,1},{0,0,0,1,0},{0,0,1,0,0},{0,1,0,0,0},{0,1,0,0,0},{0,1,0,0,0}}, // 7
                {{1,1,1,1,1},{1,0,0,0,1},{1,0,0,0,1},{1,1,1,1,1},{1,0,0,0,1},{1,0,0,0,1},{1,1,1,1,1}}, // 8
                {{1,1,1,1,1},{1,0,0,0,1},{1,0,0,0,1},{1,1,1,1,1},{0,0,0,0,1},{0,0,0,0,1},{1,1,1,1,1}}  // 9
        };

        public CaptchaMapRenderer(String code) {
            this.code = code;
        }

        @Override
        public void render(@NotNull MapView map, @NotNull MapCanvas canvas, @NotNull Player player) {
            if (rendered) return;
            rendered = true;

            Random rnd = new Random();
            for (int x = 0; x < 128; x++) {
                for (int y = 0; y < 128; y++) {
                    if (rnd.nextInt(18) == 0) {
                        canvas.setPixelColor(x, y, new Color(70, 70, 90));
                    } else {
                        canvas.setPixelColor(x, y, new Color(18, 18, 24));
                    }
                }
            }

            int scale = 4;
            int startX = 14;
            for (int i = 0; i < code.length(); i++) {
                int digit = Character.getNumericValue(code.charAt(i));
                int[][] matrix = DIGITS[digit];
                int offsetY = 44 + rnd.nextInt(16) - 8;

                for (int row = 0; row < 7; row++) {
                    for (int col = 0; col < 5; col++) {
                        if (matrix[row][col] == 1) {
                            for (int dx = 0; dx < scale; dx++) {
                                for (int dy = 0; dy < scale; dy++) {
                                    canvas.setPixelColor(startX + col * scale + dx, offsetY + row * scale + dy, new Color(255, 200, 40));
                                }
                            }
                        }
                    }
                }
                startX += 26;
            }

            for (int l = 0; l < 4; l++) {
                int y1 = 35 + rnd.nextInt(55);
                int y2 = 35 + rnd.nextInt(55);
                for (int x = 10; x < 118; x++) {
                    int y = y1 + (y2 - y1) * (x - 10) / 108;
                    canvas.setPixelColor(x, y, new Color(220, 60, 60));
                }
            }
        }
    }

    // ==================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ====================

    private void saveLocationToConfig(String path, Location loc) {
        getConfig().set(path + ".world", loc.getWorld().getName());
        getConfig().set(path + ".x", loc.getX());
        getConfig().set(path + ".y", loc.getY());
        getConfig().set(path + ".z", loc.getZ());
        getConfig().set(path + ".yaw", loc.getYaw());
        getConfig().set(path + ".pitch", loc.getPitch());
        saveConfig();
    }

    private Location getLocationFromConfig(String path) {
        String worldName = getConfig().getString(path + ".world");
        if (worldName == null) return null;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        double x = getConfig().getDouble(path + ".x");
        double y = getConfig().getDouble(path + ".y");
        double z = getConfig().getDouble(path + ".z");
        float yaw = (float) getConfig().getDouble(path + ".yaw");
        float pitch = (float) getConfig().getDouble(path + ".pitch");
        return new Location(world, x, y, z, yaw, pitch);
    }

    private String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return password;
        }
    }
}
