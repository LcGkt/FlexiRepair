package FlexiRepair;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public class Main extends JavaPlugin implements Listener {

    private static Main instance;
    private static Economy economy;
    private final HashMap<UUID, Long> cooldown = new HashMap<>();
    private static final long COOLDOWN_TIME = 3000L;

    private File messagesFile;
    private FileConfiguration messagesConfig;
    private FileConfiguration config;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();

        messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            saveResource("messages.yml", false);
        }
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
        config = getConfig();

        if (!setupEconomy()) {
            Bukkit.getConsoleSender().sendMessage("§c[FlexiRepair] Vault não encontrado. Plugin desativado.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getConsoleSender().sendMessage("§bPlugin Repair habilitado!");

        // Verificação de atualização assíncrona
        if (config.getBoolean("update", true)) {
            iniciarVerificacaoPeriodica();
        }
    }

    @Override
    public void onDisable() {
        Bukkit.getConsoleSender().sendMessage("§cPlugin Repair desabilitado.");
    }

    private boolean setupEconomy() {
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }

    public static Economy getEconomy() {
        return economy;
    }

    public static Main getInstance() {
        return instance;
    }

    // ---------------- EVENTOS ---------------- //

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        if (block == null || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!(block.getState() instanceof Sign)) return;

        Sign sign = (Sign) block.getState();
        if (!sign.getLine(0).equalsIgnoreCase("§4[reparar]")) return;

        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (cooldown.containsKey(playerId)) {
            long lastUsed = cooldown.get(playerId);
            long diff = now - lastUsed;
            if (diff < COOLDOWN_TIME) {
                long secondsLeft = (COOLDOWN_TIME - diff) / 1000 + 1;
                player.sendMessage(getMessage("cooldown").replace("{seconds}", String.valueOf(secondsLeft)));
                return;
            }
        }

        String permissionUse = config.getString("repair.use", "repair.use");
        if (!player.hasPermission(permissionUse)) {
            player.sendMessage(getMessage("no-permission"));
            return;
        }

        int costItem = config.getInt("repair-cost-item", 500);
        ItemStack item = player.getInventory().getItemInHand();

        if (!isRepairable(item)) {
            player.sendMessage(getMessage("no-repairable-item"));
            return;
        }

        if (economy != null && !economy.has(player, costItem)) {
            player.sendMessage(getMessage("not-enough-money").replace("{cost}", formatMoney(costItem)));
            return;
        }

        item.setDurability((short) 0);
        if (economy != null) economy.withdrawPlayer(player, costItem);

        player.updateInventory();
        player.sendMessage(getMessage("repaired-item").replace("{cost}", formatMoney(costItem)));
        cooldown.put(playerId, now);
    }

    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        String permissionSignCreate = config.getString("repair.sign-create", "repair.sign-create");

        if (event.getLine(0).equalsIgnoreCase("[reparar]") && player.hasPermission(permissionSignCreate)) {
            event.setLine(0, "§4[reparar]");
            event.setLine(1, "§0Item");
            event.setLine(2, ""); // linha do meio vazia ou info extra
            int cost = config.getInt("repair-cost-item", 500);
            event.setLine(3, formatMoney(cost)); // valor do reparo na última linha
            player.sendMessage("§aPlaca criada com sucesso");
        }

        if (event.getLine(0).equalsIgnoreCase("§4[reparar]") && !player.hasPermission(permissionSignCreate)) {
            event.getBlock().breakNaturally();
            player.sendMessage(getMessage("no-permission-sign"));
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        cooldown.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerKick(PlayerKickEvent event) {
        cooldown.remove(event.getPlayer().getUniqueId());
    }

    private boolean isRepairable(ItemStack item) {
        return item != null && item.getType() != Material.AIR && !item.getType().isBlock() &&
                item.getType().getMaxDurability() > 0 && item.getDurability() != 0;
    }

    // ---------------- COMANDO RELOAD ---------------- //

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Este comando só pode ser usado por jogadores.");
            return true;
        }

        Player player = (Player) sender;
        String principal = config.getString("ComandoReload.Principal", "flexirepair");
        List<String> aliases = config.getStringList("ComandoReload.Aliases");

        if (label.equalsIgnoreCase(principal) || aliases.contains(label.toLowerCase())) {
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                String permission = "repair.reload";
                if (!player.hasPermission(permission)) {
                    player.sendMessage(getMessage("no-permission-reload"));
                    return true;
                }
                reloadConfig();
                messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
                player.sendMessage(getMessage("success"));
                return true;
            }
        }
        return true;
    }

    // ---------------- UTILITÁRIOS ---------------- //

    public String getMessage(String path) {
        return ChatColor.translateAlternateColorCodes('&',
                messagesConfig.getString("Mensagens." + path, "&cMensagem não encontrada: " + path));
    }

    public String formatMoney(double value) {
        if (value >= 1_000_000_000_000_000_000L) return (value / 1_000_000_000_000_000_000L) + "QQ";
        if (value >= 1_000_000_000_000_000L) return (value / 1_000_000_000_000_000L) + "Q";
        if (value >= 1_000_000_000_000L) return (value / 1_000_000_000_000L) + "T";
        if (value >= 1_000_000_000L) return (value / 1_000_000_000L) + "B";
        if (value >= 1_000_000L) return (value / 1_000_000L) + "M";
        if (value >= 1_000L) return (value / 1_000L) + "K";
        return String.valueOf((int) value);
    }

    // ---------------- VERIFICAÇÃO DE ATUALIZAÇÃO ---------------- //

    private void iniciarVerificacaoPeriodica() {
        int intervaloMinutos = config.getInt("check-update-minutes", 30);
        long intervaloTicks = intervaloMinutos * 60L * 20L;

        new BukkitRunnable() {
            @Override
            public void run() {
                verificarAtualizacao();
            }
        }.runTaskTimerAsynchronously(this, 0L, intervaloTicks);
    }

    private void verificarAtualizacao() {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    String currentVersion = getDescription().getVersion().trim();
                    URL url = new URL("https://api.spigotmc.org/legacy/update.php?resource=128105");
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(5000);
                    connection.setReadTimeout(5000);

                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                        String latestVersion = reader.readLine().trim();
                        if (isNewerVersion(latestVersion, currentVersion)) {
                            Bukkit.getScheduler().runTask(Main.this, () -> {
                                for (Player p : Bukkit.getOnlinePlayers()) {
                                    if (p.hasPermission("repair.admin")) {
                                        p.sendMessage("§c[FlexiRepair] §eNova versão disponível!");
                                        p.sendMessage("§eSua versão: §f" + currentVersion + " §e| Nova versão: §f" + latestVersion);
                                        p.sendMessage("§eBaixe em: §fhttps://www.spigotmc.org/resources/flexirepair.128105/");
                                    }
                                }
                            });
                            getLogger().warning("Nova versão do FlexiRepair disponível: " + latestVersion);
                        } else {
                            getLogger().info("Você está usando a versão mais recente do FlexiRepair (" + currentVersion + ").");
                        }
                    }
                } catch (Exception e) {
                    getLogger().warning("Não foi possível verificar atualizações: " + e.getMessage());
                }
            }
        }.runTaskAsynchronously(this);
    }

    private boolean isNewerVersion(String latest, String current) {
        String[] latestParts = latest.split("[^0-9]+");
        String[] currentParts = current.split("[^0-9]+");

        int length = Math.max(latestParts.length, currentParts.length);
        for (int i = 0; i < length; i++) {
            int latestNum = i < latestParts.length ? parseInt(latestParts[i]) : 0;
            int currentNum = i < currentParts.length ? parseInt(currentParts[i]) : 0;
            if (latestNum > currentNum) return true;
            if (latestNum < currentNum) return false;
        }
        return false;
    }

    private int parseInt(String value) {
        try { return Integer.parseInt(value); } catch (NumberFormatException e) { return 0; }
    }
}
