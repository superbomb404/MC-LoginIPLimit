package ljsure.cn;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.StringUtil;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class LoginIPLimit extends JavaPlugin implements Listener {

    private FileConfiguration config;
    private DatabaseManager databaseManager;

    private Set<String> bypassIPs = new HashSet<>();
    private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    @Override
    public void onEnable() {
        // 设置时区
        dateFormat.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));

        // 加载配置文件
        saveDefaultConfig();
        config = getConfig();

        // 初始化数据库管理器
        databaseManager = new DatabaseManager(this);
        if (!databaseManager.initialize()) {
            getLogger().severe("数据库初始化失败，插件将禁用!");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // 注册事件
        getServer().getPluginManager().registerEvents(this, this);

        // 注册命令和自动补全
        IPCommand ipCommand = new IPCommand();
        Objects.requireNonNull(getCommand("ip")).setExecutor(ipCommand);
        Objects.requireNonNull(getCommand("ip")).setTabCompleter(ipCommand);

        // 加载绕过IP列表
        bypassIPs.addAll(config.getStringList("bypass-ips"));

        // 启动定时清理任务
        startCleanupTask();

        getLogger().info("LoginIPLimit 插件已启用! 使用" + (config.getBoolean("mysql.enabled", false) ? "MySQL" : "YAML") + "存储");
    }

    @Override
    public void onDisable() {
        if (databaseManager != null) {
            databaseManager.close();
        }
        getLogger().info("LoginIPLimit 插件已禁用!");
    }

    private void startCleanupTask() {
        // 每分钟清理一次过期的IP记录
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            databaseManager.cleanupExpiredIPs();
        }, 0L, 20L * 60L); // 每分钟执行一次
    }

    @EventHandler
    public void onPlayerLogin(PlayerLoginEvent event) {
        if (!config.getBoolean("enabled", true)) {
            return;
        }

        Player player = event.getPlayer();
        InetAddress address = event.getAddress();
        String ip = address.getHostAddress();
        UUID playerUUID = player.getUniqueId();
        String playerName = player.getName();

        // 检查是否在绕过列表中
        if (bypassIPs.contains(ip)) {
            return;
        }

        // 检查IP是否在冷却中
        IPData ipData = databaseManager.getIPData(ip);
        if (ipData != null) {
            long endTime = ipData.getEndTime();
            long currentTime = System.currentTimeMillis();

            // 永久绑定或冷却时间内
            if (endTime == 0 || endTime > currentTime) {
                // 如果尝试登录的玩家不是绑定的玩家
                if (!ipData.getPlayerUUID().equals(playerUUID)) {
                    String kickMessage;
                    if (endTime == 0) {
                        kickMessage = createPermanentKickMessage(ip, ipData.getPlayerUUID(), ipData.getPlayerName());
                    } else {
                        kickMessage = createTemporaryKickMessage(ip, endTime, currentTime, ipData.getPlayerUUID(), ipData.getPlayerName());
                    }
                    event.disallow(PlayerLoginEvent.Result.KICK_OTHER, kickMessage);
                    return;
                }
            } else {
                // 冷却时间结束，移除记录
                databaseManager.removeIPData(ip);
            }
        }

        // 更新玩家数据
        int timeLimit = config.getInt("time-limit", 10);
        long endTime = timeLimit == 0 ? 0 : System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(timeLimit);

        databaseManager.saveIPData(ip, playerUUID, playerName, endTime);

        if (timeLimit > 0) {
            player.sendMessage(ChatColor.GREEN + "您的IP将在 " + timeLimit + " 分钟后解除绑定");
        } else {
            player.sendMessage(ChatColor.GREEN + "您的IP已永久绑定至当前账号");
        }
    }

    private String createPermanentKickMessage(String ip, UUID boundPlayerUUID, String boundPlayerName) {
        String playerName = boundPlayerName != null ? boundPlayerName : getPlayerName(boundPlayerUUID);

        return ChatColor.translateAlternateColorCodes('&',
                "&c&lIP登录限制\n" +
                        "&7========================\n" +
                        "&f您的IP地址: &e" + ip + "\n" +
                        "&f限制类型: &c永久绑定\n" +
                        "&f绑定玩家: &6" + playerName + "\n" +
                        "&f状态: &e已绑定至其他玩家\n" +
                        "&6如需解除限制，请联系管理员\n" +
                        "&7========================\n"
        );
    }

    private String createTemporaryKickMessage(String ip, long endTime, long currentTime, UUID boundPlayerUUID, String boundPlayerName) {
        long remainingTime = endTime - currentTime;
        String timeLeft = formatTime(remainingTime);
        String endTimeStr = dateFormat.format(new java.util.Date(endTime));
        String playerName = boundPlayerName != null ? boundPlayerName : getPlayerName(boundPlayerUUID);

        return ChatColor.translateAlternateColorCodes('&',
                "&c&lIP登录限制\n" +
                        "&7========================\n" +
                        "&f您的IP地址: &e" + ip + "\n" +
                        "&f限制类型: &6临时绑定\n" +
                        "&f绑定玩家: &6" + playerName + "\n" +
                        "&f剩余时间: &e" + timeLeft + "\n" +
                        "&f解封时间: &a" + endTimeStr + "\n" +
                        "&6请等待冷却结束或联系管理员\n" +
                        "&f状态: &c冷却中\n" +
                        "&7========================\n"
        );
    }

    private String getPlayerName(UUID playerUUID) {
        // 尝试通过Bukkit API获取
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerUUID);
        if (offlinePlayer != null && offlinePlayer.getName() != null) {
            return offlinePlayer.getName();
        }

        // 如果无法获取，返回UUID的简短形式
        return playerUUID.toString().substring(0, 8) + "...";
    }

    private String formatTime(long milliseconds) {
        if (milliseconds <= 0) return "0分钟";

        long days = TimeUnit.MILLISECONDS.toDays(milliseconds);
        long hours = TimeUnit.MILLISECONDS.toHours(milliseconds) % 24;
        long minutes = TimeUnit.MILLISECONDS.toMinutes(milliseconds) % 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("天");
        }
        if (hours > 0) {
            sb.append(hours).append("小时");
        }
        if (minutes > 0 || sb.isEmpty()) {
            sb.append(minutes).append("分钟");
        }

        return sb.toString();
    }

    public class IPCommand implements org.bukkit.command.CommandExecutor, TabCompleter {

        private final List<String> subCommands = Arrays.asList("enable", "disable", "timelimit", "erase", "bypass", "unbypass", "status", "list");

        @Override
        public boolean onCommand(CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
            if (!sender.hasPermission("loginiplimit.admin")) {
                sender.sendMessage(ChatColor.RED + "你没有权限使用此命令!");
                return true;
            }

            if (args.length == 0) {
                sendHelp(sender);
                return true;
            }

            switch (args[0].toLowerCase()) {
                case "enable":
                    config.set("enabled", true);
                    saveConfig();
                    sender.sendMessage(ChatColor.GREEN + "IP限制功能已启用!");
                    break;

                case "disable":
                    config.set("enabled", false);
                    saveConfig();
                    sender.sendMessage(ChatColor.GREEN + "IP限制功能已禁用!");
                    break;

                case "timelimit":
                    if (args.length < 2) {
                        sender.sendMessage(ChatColor.RED + "用法: /ip timelimit <分钟>");
                        return true;
                    }
                    try {
                        int minutes = Integer.parseInt(args[1]);
                        if (minutes < 0) {
                            sender.sendMessage(ChatColor.RED + "时间不能为负数!");
                            return true;
                        }
                        config.set("time-limit", minutes);
                        saveConfig();
                        sender.sendMessage(ChatColor.GREEN + "IP冷却时间已设置为: " + minutes + "分钟");
                    } catch (NumberFormatException e) {
                        sender.sendMessage(ChatColor.RED + "请输入有效的数字!");
                    }
                    break;

                case "erase":
                    if (args.length < 2) {
                        sender.sendMessage(ChatColor.RED + "用法: /ip erase <IP地址>");
                        return true;
                    }
                    String ipToErase = args[1];
                    if (databaseManager.removeIPData(ipToErase)) {
                        sender.sendMessage(ChatColor.GREEN + "IP " + ipToErase + " 的限制已移除!");
                    } else {
                        sender.sendMessage(ChatColor.YELLOW + "IP " + ipToErase + " 没有限制记录");
                    }
                    break;

                case "bypass":
                    if (args.length < 2) {
                        sender.sendMessage(ChatColor.RED + "用法: /ip bypass <IP地址>");
                        return true;
                    }
                    String ipToBypass = args[1];
                    if (bypassIPs.contains(ipToBypass)) {
                        sender.sendMessage(ChatColor.YELLOW + "IP " + ipToBypass + " 已在绕过列表中");
                    } else {
                        bypassIPs.add(ipToBypass);
                        List<String> bypassList = config.getStringList("bypass-ips");
                        bypassList.add(ipToBypass);
                        config.set("bypass-ips", bypassList);
                        saveConfig();
                        sender.sendMessage(ChatColor.GREEN + "已添加IP " + ipToBypass + " 到绕过列表");
                    }
                    break;

                case "unbypass":
                    if (args.length < 2) {
                        sender.sendMessage(ChatColor.RED + "用法: /ip unbypass <IP地址>");
                        return true;
                    }
                    String ipToUnbypass = args[1];
                    if (bypassIPs.contains(ipToUnbypass)) {
                        bypassIPs.remove(ipToUnbypass);
                        List<String> bypassList = config.getStringList("bypass-ips");
                        bypassList.remove(ipToUnbypass);
                        config.set("bypass-ips", bypassList);
                        saveConfig();
                        sender.sendMessage(ChatColor.GREEN + "已移除IP " + ipToUnbypass + " 的绕过权限");
                    } else {
                        sender.sendMessage(ChatColor.YELLOW + "IP " + ipToUnbypass + " 不在绕过列表中");
                    }
                    break;

                case "status":
                    sender.sendMessage(ChatColor.GOLD + "=== IP限制插件状态 ===");
                    sender.sendMessage(ChatColor.YELLOW + "插件状态: " +
                            (config.getBoolean("enabled", true) ? ChatColor.GREEN + "已启用" : ChatColor.RED + "已禁用"));
                    sender.sendMessage(ChatColor.YELLOW + "冷却时间: " + ChatColor.AQUA +
                            config.getInt("time-limit", 10) + "分钟");
                    sender.sendMessage(ChatColor.YELLOW + "存储方式: " + ChatColor.AQUA +
                            (config.getBoolean("mysql.enabled", false) ? "MySQL" : "YAML"));
                    sender.sendMessage(ChatColor.YELLOW + "当前限制IP数量: " + ChatColor.AQUA + databaseManager.getIPCount());
                    sender.sendMessage(ChatColor.YELLOW + "绕过IP数量: " + ChatColor.AQUA + bypassIPs.size());
                    break;

                case "list":
                    if (args.length > 1 && args[1].equalsIgnoreCase("bypass")) {
                        // 显示绕过列表
                        sender.sendMessage(ChatColor.GOLD + "=== 绕过IP列表 ===");
                        if (bypassIPs.isEmpty()) {
                            sender.sendMessage(ChatColor.YELLOW + "没有绕过IP");
                        } else {
                            for (String ip : bypassIPs) {
                                sender.sendMessage(ChatColor.AQUA + "- " + ip);
                            }
                        }
                    } else {
                        // 显示限制IP列表
                        List<IPData> ipList = databaseManager.getAllIPData();
                        sender.sendMessage(ChatColor.GOLD + "=== 受限制IP列表 (" + ipList.size() + "个) ===");
                        if (ipList.isEmpty()) {
                            sender.sendMessage(ChatColor.YELLOW + "没有受限制的IP");
                        } else {
                            long currentTime = System.currentTimeMillis();
                            for (IPData ipData : ipList) {
                                String ip = ipData.getIp();
                                long endTime = ipData.getEndTime();
                                String playerName = ipData.getPlayerName();

                                if (endTime == 0) {
                                    sender.sendMessage(ChatColor.RED + "- " + ip + " (永久绑定) -> " + playerName);
                                } else if (endTime > currentTime) {
                                    String timeLeft = formatTime(endTime - currentTime);
                                    sender.sendMessage(ChatColor.YELLOW + "- " + ip + " (" + timeLeft + ") -> " + playerName);
                                }
                            }
                        }
                    }
                    break;

                default:
                    sendHelp(sender);
                    break;
            }

            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, org.bukkit.command.Command command, String alias, String[] args) {
            List<String> completions = new ArrayList<>();

            if (args.length == 1) {
                // 子命令补全
                StringUtil.copyPartialMatches(args[0], subCommands, completions);
            } else if (args.length == 2) {
                switch (args[0].toLowerCase()) {
                    case "erase":
                        // IP地址补全
                        List<IPData> ipList = databaseManager.getAllIPData();
                        List<String> ipAddresses = new ArrayList<>();
                        for (IPData ipData : ipList) {
                            ipAddresses.add(ipData.getIp());
                        }
                        StringUtil.copyPartialMatches(args[1], ipAddresses, completions);
                        break;
                    case "bypass":
                        // 无特定补全
                        break;
                    case "unbypass":
                        // 绕过IP补全
                        StringUtil.copyPartialMatches(args[1], new ArrayList<>(bypassIPs), completions);
                        break;
                    case "timelimit":
                        // 时间建议
                        List<String> timeSuggestions = Arrays.asList("0", "10", "30", "60", "120", "1440");
                        StringUtil.copyPartialMatches(args[1], timeSuggestions, completions);
                        break;
                    case "list":
                        // 列表类型补全
                        List<String> listTypes = Arrays.asList("bypass");
                        StringUtil.copyPartialMatches(args[1], listTypes, completions);
                        break;
                }
            }

            Collections.sort(completions);
            return completions;
        }

        private void sendHelp(CommandSender sender) {
            sender.sendMessage(ChatColor.GOLD + "=== IP限制管理命令 ===");
            sender.sendMessage(ChatColor.YELLOW + "/ip enable - 启用IP限制");
            sender.sendMessage(ChatColor.YELLOW + "/ip disable - 禁用IP限制");
            sender.sendMessage(ChatColor.YELLOW + "/ip timelimit <分钟> - 设置IP冷却时间(0为永久)");
            sender.sendMessage(ChatColor.YELLOW + "/ip erase <IP> - 强制移除IP限制");
            sender.sendMessage(ChatColor.YELLOW + "/ip bypass <IP> - 添加IP到绕过列表");
            sender.sendMessage(ChatColor.YELLOW + "/ip unbypass <IP> - 从绕过列表移除IP");
            sender.sendMessage(ChatColor.YELLOW + "/ip list - 查看受限制IP列表");
            sender.sendMessage(ChatColor.YELLOW + "/ip list bypass - 查看绕过IP列表");
            sender.sendMessage(ChatColor.YELLOW + "/ip status - 查看插件状态");
        }
    }

    // IP数据类
    public static class IPData {
        private final String ip;
        private final UUID playerUUID;
        private final String playerName;
        private final long endTime;
        private final long createdAt;

        public IPData(String ip, UUID playerUUID, String playerName, long endTime, long createdAt) {
            this.ip = ip;
            this.playerUUID = playerUUID;
            this.playerName = playerName;
            this.endTime = endTime;
            this.createdAt = createdAt;
        }

        public String getIp() { return ip; }
        public UUID getPlayerUUID() { return playerUUID; }
        public String getPlayerName() { return playerName; }
        public long getEndTime() { return endTime; }
        public long getCreatedAt() { return createdAt; }
    }

    // 数据库管理类
    public static class DatabaseManager {
        private final LoginIPLimit plugin;
        private Connection connection;
        private final String tableName;

        public DatabaseManager(LoginIPLimit plugin) {
            this.plugin = plugin;
            this.tableName = plugin.getConfig().getString("mysql.table-prefix", "iplimit_") + "data";
        }

        public boolean initialize() {
            if (plugin.getConfig().getBoolean("mysql.enabled", false)) {
                return initializeMySQL();
            } else {
                return initializeYAML();
            }
        }

        private boolean initializeMySQL() {
            try {
                String host = plugin.getConfig().getString("mysql.host", "localhost");
                int port = plugin.getConfig().getInt("mysql.port", 3306);
                String database = plugin.getConfig().getString("mysql.database", "minecraft");
                String username = plugin.getConfig().getString("mysql.username", "root");
                String password = plugin.getConfig().getString("mysql.password", "");
                boolean useSSL = plugin.getConfig().getBoolean("mysql.use-ssl", false);

                String url = "jdbc:mysql://" + host + ":" + port + "/" + database +
                        "?useSSL=" + useSSL + "&allowPublicKeyRetrieval=true&useUnicode=true&characterEncoding=UTF-8";

                connection = DriverManager.getConnection(url, username, password);
                createTable();
                plugin.getLogger().info("MySQL数据库连接成功!");
                return true;
            } catch (SQLException e) {
                plugin.getLogger().severe("MySQL数据库连接失败: " + e.getMessage());
                return false;
            }
        }

        private boolean initializeYAML() {
            // 对于YAML存储，我们不需要特殊的初始化
            plugin.getLogger().info("使用YAML文件存储");
            return true;
        }

        private void createTable() throws SQLException {
            String sql = "CREATE TABLE IF NOT EXISTS " + tableName + " (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY," +
                    "ip VARCHAR(45) NOT NULL UNIQUE," +
                    "player_uuid VARCHAR(36) NOT NULL," +
                    "player_name VARCHAR(16) NOT NULL," +
                    "end_time BIGINT NOT NULL," +
                    "created_at BIGINT NOT NULL," +
                    "INDEX idx_ip (ip)," +
                    "INDEX idx_end_time (end_time)" +
                    ")";
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }

        public IPData getIPData(String ip) {
            if (plugin.getConfig().getBoolean("mysql.enabled", false)) {
                return getIPDataFromMySQL(ip);
            } else {
                return getIPDataFromYAML(ip);
            }
        }

        private IPData getIPDataFromMySQL(String ip) {
            String sql = "SELECT * FROM " + tableName + " WHERE ip = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, ip);
                ResultSet resultSet = statement.executeQuery();
                if (resultSet.next()) {
                    UUID playerUUID = UUID.fromString(resultSet.getString("player_uuid"));
                    String playerName = resultSet.getString("player_name");
                    long endTime = resultSet.getLong("end_time");
                    long createdAt = resultSet.getLong("created_at");
                    return new IPData(ip, playerUUID, playerName, endTime, createdAt);
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("从MySQL获取IP数据失败: " + e.getMessage());
            }
            return null;
        }

        private IPData getIPDataFromYAML(String ip) {
            File dataFile = new File(plugin.getDataFolder(), "data.yml");
            if (!dataFile.exists()) {
                return null;
            }

            YamlConfiguration dataConfig = YamlConfiguration.loadConfiguration(dataFile);
            if (!dataConfig.contains("player-data." + ip)) {
                return null;
            }

            String uuidString = dataConfig.getString("player-data." + ip + ".uuid");
            String playerName = dataConfig.getString("player-data." + ip + ".player-name");
            long endTime = dataConfig.getLong("player-data." + ip + ".end-time");

            if (uuidString == null) {
                return null;
            }

            try {
                UUID playerUUID = UUID.fromString(uuidString);
                return new IPData(ip, playerUUID, playerName, endTime, System.currentTimeMillis());
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("无效的UUID格式: " + uuidString);
                return null;
            }
        }

        public boolean saveIPData(String ip, UUID playerUUID, String playerName, long endTime) {
            if (plugin.getConfig().getBoolean("mysql.enabled", false)) {
                return saveIPDataToMySQL(ip, playerUUID, playerName, endTime);
            } else {
                return saveIPDataToYAML(ip, playerUUID, playerName, endTime);
            }
        }

        private boolean saveIPDataToMySQL(String ip, UUID playerUUID, String playerName, long endTime) {
            String sql = "INSERT INTO " + tableName + " (ip, player_uuid, player_name, end_time, created_at) " +
                    "VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE " +
                    "player_uuid = VALUES(player_uuid), player_name = VALUES(player_name), " +
                    "end_time = VALUES(end_time), created_at = VALUES(created_at)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, ip);
                statement.setString(2, playerUUID.toString());
                statement.setString(3, playerName);
                statement.setLong(4, endTime);
                statement.setLong(5, System.currentTimeMillis());
                statement.executeUpdate();
                return true;
            } catch (SQLException e) {
                plugin.getLogger().severe("保存IP数据到MySQL失败: " + e.getMessage());
                return false;
            }
        }

        private boolean saveIPDataToYAML(String ip, UUID playerUUID, String playerName, long endTime) {
            File dataFile = new File(plugin.getDataFolder(), "data.yml");
            YamlConfiguration dataConfig = YamlConfiguration.loadConfiguration(dataFile);

            dataConfig.set("player-data." + ip + ".uuid", playerUUID.toString());
            dataConfig.set("player-data." + ip + ".player-name", playerName);
            dataConfig.set("player-data." + ip + ".end-time", endTime);
            dataConfig.set("player-data." + ip + ".created-at", System.currentTimeMillis());

            try {
                dataConfig.save(dataFile);
                return true;
            } catch (IOException e) {
                plugin.getLogger().severe("保存IP数据到YAML失败: " + e.getMessage());
                return false;
            }
        }

        public boolean removeIPData(String ip) {
            if (plugin.getConfig().getBoolean("mysql.enabled", false)) {
                return removeIPDataFromMySQL(ip);
            } else {
                return removeIPDataFromYAML(ip);
            }
        }

        private boolean removeIPDataFromMySQL(String ip) {
            String sql = "DELETE FROM " + tableName + " WHERE ip = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, ip);
                int affectedRows = statement.executeUpdate();
                return affectedRows > 0;
            } catch (SQLException e) {
                plugin.getLogger().severe("从MySQL删除IP数据失败: " + e.getMessage());
                return false;
            }
        }

        private boolean removeIPDataFromYAML(String ip) {
            File dataFile = new File(plugin.getDataFolder(), "data.yml");
            if (!dataFile.exists()) {
                return false;
            }

            YamlConfiguration dataConfig = YamlConfiguration.loadConfiguration(dataFile);
            if (!dataConfig.contains("player-data." + ip)) {
                return false;
            }

            dataConfig.set("player-data." + ip, null);

            try {
                dataConfig.save(dataFile);
                return true;
            } catch (IOException e) {
                plugin.getLogger().severe("从YAML删除IP数据失败: " + e.getMessage());
                return false;
            }
        }

        public List<IPData> getAllIPData() {
            if (plugin.getConfig().getBoolean("mysql.enabled", false)) {
                return getAllIPDataFromMySQL();
            } else {
                return getAllIPDataFromYAML();
            }
        }

        private List<IPData> getAllIPDataFromMySQL() {
            List<IPData> ipList = new ArrayList<>();
            String sql = "SELECT * FROM " + tableName;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                ResultSet resultSet = statement.executeQuery();
                while (resultSet.next()) {
                    String ip = resultSet.getString("ip");
                    UUID playerUUID = UUID.fromString(resultSet.getString("player_uuid"));
                    String playerName = resultSet.getString("player_name");
                    long endTime = resultSet.getLong("end_time");
                    long createdAt = resultSet.getLong("created_at");
                    ipList.add(new IPData(ip, playerUUID, playerName, endTime, createdAt));
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("从MySQL获取所有IP数据失败: " + e.getMessage());
            }
            return ipList;
        }

        private List<IPData> getAllIPDataFromYAML() {
            List<IPData> ipList = new ArrayList<>();
            File dataFile = new File(plugin.getDataFolder(), "data.yml");
            if (!dataFile.exists()) {
                return ipList;
            }

            YamlConfiguration dataConfig = YamlConfiguration.loadConfiguration(dataFile);
            if (!dataConfig.contains("player-data")) {
                return ipList;
            }

            for (String ip : dataConfig.getConfigurationSection("player-data").getKeys(false)) {
                String uuidString = dataConfig.getString("player-data." + ip + ".uuid");
                String playerName = dataConfig.getString("player-data." + ip + ".player-name");
                long endTime = dataConfig.getLong("player-data." + ip + ".end-time");

                if (uuidString != null) {
                    try {
                        UUID playerUUID = UUID.fromString(uuidString);
                        ipList.add(new IPData(ip, playerUUID, playerName, endTime, System.currentTimeMillis()));
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("无效的UUID格式: " + uuidString);
                    }
                }
            }
            return ipList;
        }

        public int getIPCount() {
            if (plugin.getConfig().getBoolean("mysql.enabled", false)) {
                return getIPCountFromMySQL();
            } else {
                return getIPCountFromYAML();
            }
        }

        private int getIPCountFromMySQL() {
            String sql = "SELECT COUNT(*) FROM " + tableName;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                ResultSet resultSet = statement.executeQuery();
                if (resultSet.next()) {
                    return resultSet.getInt(1);
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("从MySQL获取IP数量失败: " + e.getMessage());
            }
            return 0;
        }

        private int getIPCountFromYAML() {
            File dataFile = new File(plugin.getDataFolder(), "data.yml");
            if (!dataFile.exists()) {
                return 0;
            }

            YamlConfiguration dataConfig = YamlConfiguration.loadConfiguration(dataFile);
            if (!dataConfig.contains("player-data")) {
                return 0;
            }

            return dataConfig.getConfigurationSection("player-data").getKeys(false).size();
        }

        public void cleanupExpiredIPs() {
            if (plugin.getConfig().getBoolean("mysql.enabled", false)) {
                cleanupExpiredIPsFromMySQL();
            } else {
                cleanupExpiredIPsFromYAML();
            }
        }

        private void cleanupExpiredIPsFromMySQL() {
            long currentTime = System.currentTimeMillis();
            String sql = "DELETE FROM " + tableName + " WHERE end_time > 0 AND end_time <= ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, currentTime);
                int deleted = statement.executeUpdate();
                if (deleted > 0) {
                    plugin.getLogger().info("清理了 " + deleted + " 个过期的IP记录");
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("清理MySQL过期IP记录失败: " + e.getMessage());
            }
        }

        private void cleanupExpiredIPsFromYAML() {
            File dataFile = new File(plugin.getDataFolder(), "data.yml");
            if (!dataFile.exists()) {
                return;
            }

            YamlConfiguration dataConfig = YamlConfiguration.loadConfiguration(dataFile);
            if (!dataConfig.contains("player-data")) {
                return;
            }

            long currentTime = System.currentTimeMillis();
            int cleaned = 0;

            for (String ip : dataConfig.getConfigurationSection("player-data").getKeys(false)) {
                long endTime = dataConfig.getLong("player-data." + ip + ".end-time");
                if (endTime > 0 && endTime <= currentTime) {
                    dataConfig.set("player-data." + ip, null);
                    cleaned++;
                }
            }

            if (cleaned > 0) {
                try {
                    dataConfig.save(dataFile);
                    plugin.getLogger().info("清理了 " + cleaned + " 个过期的IP记录");
                } catch (IOException e) {
                    plugin.getLogger().severe("保存清理后的YAML数据失败: " + e.getMessage());
                }
            }
        }

        public void close() {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    plugin.getLogger().severe("关闭数据库连接失败: " + e.getMessage());
                }
            }
        }
    }
}