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
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class LoginIPLimit extends JavaPlugin implements Listener {

    private FileConfiguration config;
    private FileConfiguration dataConfig;
    private File dataFile;

    private Map<String, Long> ipCooldowns = new HashMap<>();
    private Set<String> bypassIPs = new HashSet<>();
    private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    @Override
    public void onEnable() {
        // 设置时区
        dateFormat.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));

        // 加载配置文件
        saveDefaultConfig();
        config = getConfig();

        // 加载数据文件
        loadDataFile();

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

        getLogger().info("LoginIPLimit 插件已启用!");
    }

    @Override
    public void onDisable() {
        saveData();
        getLogger().info("LoginIPLimit 插件已禁用!");
    }

    private void loadDataFile() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            dataFile.getParentFile().mkdirs();
            saveResource("data.yml", false);
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);

        // 加载IP冷却数据
        if (dataConfig.contains("ip-cooldowns")) {
            for (String ip : dataConfig.getConfigurationSection("ip-cooldowns").getKeys(false)) {
                long endTime = dataConfig.getLong("ip-cooldowns." + ip);
                ipCooldowns.put(ip, endTime);
            }
        }
    }

    private void saveData() {
        try {
            // 保存IP冷却数据
            for (Map.Entry<String, Long> entry : ipCooldowns.entrySet()) {
                dataConfig.set("ip-cooldowns." + entry.getKey(), entry.getValue());
            }
            dataConfig.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("保存数据文件时出错: " + e.getMessage());
        }
    }

    private void startCleanupTask() {
        // 每分钟清理一次过期的IP记录
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            long currentTime = System.currentTimeMillis();
            ipCooldowns.entrySet().removeIf(entry -> {
                if (entry.getValue() > 0 && entry.getValue() <= currentTime) {
                    getLogger().info("IP " + entry.getKey() + " 的冷却时间已结束");
                    return true;
                }
                return false;
            });
            saveData();
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

        // 检查是否在绕过列表中
        if (bypassIPs.contains(ip)) {
            return;
        }

        // 检查IP是否在冷却中
        if (ipCooldowns.containsKey(ip)) {
            long endTime = ipCooldowns.get(ip);
            long currentTime = System.currentTimeMillis();

            // 永久绑定或冷却时间内
            if (endTime == 0 || endTime > currentTime) {
                // 检查是否有玩家数据记录
                String storedUUID = dataConfig.getString("player-data." + ip + ".uuid");

                if (storedUUID != null) {
                    UUID storedPlayerUUID = UUID.fromString(storedUUID);

                    // 如果尝试登录的玩家不是绑定的玩家
                    if (!storedPlayerUUID.equals(playerUUID)) {
                        String kickMessage;
                        if (endTime == 0) {
                            kickMessage = createPermanentKickMessage(ip, storedPlayerUUID);
                        } else {
                            kickMessage = createTemporaryKickMessage(ip, endTime, currentTime, storedPlayerUUID);
                        }
                        event.disallow(PlayerLoginEvent.Result.KICK_OTHER, kickMessage);
                        return;
                    }
                }
            } else {
                // 冷却时间结束，移除记录
                ipCooldowns.remove(ip);
                dataConfig.set("player-data." + ip, null);
                saveData();
            }
        }

        // 更新玩家数据
        int timeLimit = config.getInt("time-limit", 10);
        long endTime = timeLimit == 0 ? 0 : System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(timeLimit);

        ipCooldowns.put(ip, endTime);
        dataConfig.set("player-data." + ip + ".uuid", playerUUID.toString());
        dataConfig.set("player-data." + ip + ".ip", ip);
        dataConfig.set("player-data." + ip + ".end-time", endTime);
        dataConfig.set("player-data." + ip + ".player-name", player.getName());

        saveData();

        if (timeLimit > 0) {
            player.sendMessage(ChatColor.GREEN + "您的IP将在 " + timeLimit + " 分钟后解除绑定");
        } else {
            player.sendMessage(ChatColor.GREEN + "您的IP已永久绑定至当前账号");
        }
    }

    private String createPermanentKickMessage(String ip, UUID boundPlayerUUID) {
        String playerName = getPlayerName(boundPlayerUUID);

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

    private String createTemporaryKickMessage(String ip, long endTime, long currentTime, UUID boundPlayerUUID) {
        long remainingTime = endTime - currentTime;
        String timeLeft = formatTime(remainingTime);
        String endTimeStr = dateFormat.format(new Date(endTime));
        String playerName = getPlayerName(boundPlayerUUID);

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
        // 首先尝试从数据文件中获取存储的玩家名
        for (String ip : dataConfig.getConfigurationSection("player-data").getKeys(false)) {
            String storedUUID = dataConfig.getString("player-data." + ip + ".uuid");
            if (storedUUID != null && storedUUID.equals(playerUUID.toString())) {
                String storedName = dataConfig.getString("player-data." + ip + ".player-name");
                if (storedName != null) {
                    return storedName;
                }
            }
        }

        // 如果数据文件中没有，尝试通过Bukkit API获取
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerUUID);
        if (offlinePlayer != null && offlinePlayer.getName() != null) {
            return offlinePlayer.getName();
        }

        // 如果都无法获取，返回UUID的简短形式
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
        if (minutes > 0 || sb.length() == 0) {
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
                    if (ipCooldowns.containsKey(ipToErase)) {
                        ipCooldowns.remove(ipToErase);
                        dataConfig.set("player-data." + ipToErase, null);
                        saveData();
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
                    sender.sendMessage(ChatColor.YELLOW + "当前限制IP数量: " + ChatColor.AQUA + ipCooldowns.size());
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
                        sender.sendMessage(ChatColor.GOLD + "=== 受限制IP列表 ===");
                        if (ipCooldowns.isEmpty()) {
                            sender.sendMessage(ChatColor.YELLOW + "没有受限制的IP");
                        } else {
                            long currentTime = System.currentTimeMillis();
                            for (Map.Entry<String, Long> entry : ipCooldowns.entrySet()) {
                                String ip = entry.getKey();
                                long endTime = entry.getValue();
                                String playerName = getPlayerNameFromIP(ip);

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

        private String getPlayerNameFromIP(String ip) {
            String uuidString = dataConfig.getString("player-data." + ip + ".uuid");
            if (uuidString != null) {
                try {
                    UUID playerUUID = UUID.fromString(uuidString);
                    return getPlayerName(playerUUID);
                } catch (IllegalArgumentException e) {
                    return "未知玩家";
                }
            }
            return "未知玩家";
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
                        StringUtil.copyPartialMatches(args[1], new ArrayList<>(ipCooldowns.keySet()), completions);
                        break;
                    case "bypass":
                    case "unbypass":
                        // 绕过IP补全
                        if ("unbypass".equals(args[0].toLowerCase())) {
                            StringUtil.copyPartialMatches(args[1], new ArrayList<>(bypassIPs), completions);
                        }
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
}