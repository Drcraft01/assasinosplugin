package br.com.assassinos;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class AssassinosPlugin extends JavaPlugin implements Listener {
    private final LinkedHashSet<UUID> queue = new LinkedHashSet<>();
    private final Map<UUID, Role> roles = new HashMap<>();
    private final Set<UUID> alive = new HashSet<>();
    private final Set<UUID> fugitiveUsed = new HashSet<>();
    private final Map<UUID, Integer> sheriffArrows = new HashMap<>();
    private Location lobby;
    private final Map<String, Location> maps = new LinkedHashMap<>();
    private boolean running = false;
    private boolean countdown = false;
    private int countdownTask = -1;
    private String currentMap = "";

    private enum Role { ASSASSINO, XERIFE, INOCENTE, BARULHENTO, CUMPLICE, FUGITIVO }

    @Override public void onEnable() {
        saveDefaultConfig();
        loadLocations();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("Assassinos carregado. Configure /assassinos setlobby e /assassinos setmap <nome>.");
    }

    @Override public void onDisable() {
        if (countdownTask != -1) Bukkit.getScheduler().cancelTask(countdownTask);
        for (UUID id : new HashSet<>(roles.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) resetPlayer(p, true);
        }
    }

    private void loadLocations() {
        if (getConfig().contains("lobby")) lobby = readLocation("lobby");
        if (getConfig().isConfigurationSection("maps")) {
            for (String key : getConfig().getConfigurationSection("maps").getKeys(false)) {
                Location loc = readLocation("maps." + key);
                if (loc != null) maps.put(key, loc);
            }
        }
    }
    private Location readLocation(String path) {
        String worldName = getConfig().getString(path + ".world");
        World w = worldName == null ? null : Bukkit.getWorld(worldName);
        if (w == null) return null;
        return new Location(w, getConfig().getDouble(path + ".x"), getConfig().getDouble(path + ".y"), getConfig().getDouble(path + ".z"), (float)getConfig().getDouble(path + ".yaw"), (float)getConfig().getDouble(path + ".pitch"));
    }
    private void saveLocation(String path, Location l) {
        getConfig().set(path + ".world", l.getWorld().getName()); getConfig().set(path + ".x", l.getX());
        getConfig().set(path + ".y", l.getY()); getConfig().set(path + ".z", l.getZ());
        getConfig().set(path + ".yaw", l.getYaw()); getConfig().set(path + ".pitch", l.getPitch()); saveConfig();
    }

    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("playassassinos")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Apenas jogadores podem entrar na fila."); return true; }
            if (running) { p.sendMessage(ChatColor.RED + "Uma partida já está acontecendo. Tente novamente depois."); return true; }
            if (queue.remove(p.getUniqueId())) { p.sendMessage(ChatColor.YELLOW + "Você saiu da fila do Assassinos. (" + queue.size() + "/8)"); return true; }
            if (queue.size() >= 8) { p.sendMessage(ChatColor.RED + "A fila está cheia (8/8)."); return true; }
            queue.add(p.getUniqueId()); p.sendMessage(ChatColor.GREEN + "Você entrou na fila do Assassinos! " + ChatColor.YELLOW + "(" + queue.size() + "/8)");
            for (UUID id : queue) { Player q = Bukkit.getPlayer(id); if (q != null && !q.equals(p)) q.sendMessage(ChatColor.YELLOW + p.getName() + " entrou na fila (" + queue.size() + "/8)."); }
            if (queue.size() >= 3 && !countdown) startCountdown();
            return true;
        }
        if (!cmd.getName().equalsIgnoreCase("assassinos")) return false;
        if (args.length == 0) { sender.sendMessage(ChatColor.GOLD + "Assassinos: /assassinos setlobby | setmap <nome> | start | stop | status"); return true; }
        if (args[0].equalsIgnoreCase("status")) { sender.sendMessage("Partida: " + (running ? "em andamento" : countdown ? "contagem" : "parada") + " | Fila: " + queue.size() + "/8 | Mapas: " + maps.keySet()); return true; }
        if (!sender.hasPermission("assassinos.admin") || !(sender instanceof Player p)) { sender.sendMessage(ChatColor.RED + "Você precisa ser OP e executar isso dentro do jogo."); return true; }
        switch (args[0].toLowerCase()) {
            case "setlobby" -> { lobby = p.getLocation(); saveLocation("lobby", lobby); p.sendMessage(ChatColor.GREEN + "Lobby salvo nesta posição."); }
            case "setmap" -> {
                if (args.length < 2) { p.sendMessage(ChatColor.RED + "Use /assassinos setmap <nome>. Fique no ponto de nascimento do mapa."); return true; }
                String name = args[1].toLowerCase(Locale.ROOT); maps.put(name, p.getLocation()); saveLocation("maps." + name, p.getLocation()); p.sendMessage(ChatColor.GREEN + "Ponto de nascimento do mapa '" + name + "' salvo.");
            }
            case "start" -> { if (running) p.sendMessage("Já existe uma partida."); else if (queue.size() < 3) p.sendMessage("São necessários pelo menos 3 jogadores na fila."); else startGame(); }
            case "stop" -> endGame("Partida encerrada por um administrador.", null);
            default -> p.sendMessage(ChatColor.RED + "Comando desconhecido. Use /assassinos para ver a ajuda.");
        }
        return true;
    }

    private void startCountdown() {
        if (maps.isEmpty()) { for (UUID id : queue) { Player p = Bukkit.getPlayer(id); if (p != null) p.sendMessage(ChatColor.RED + "O minigame ainda não foi configurado: um administrador precisa usar /assassinos setmap <nome>."); } return; }
        countdown = true;
        countdownTask = new BukkitRunnable() { int seconds = 15; public void run() {
            queue.removeIf(id -> Bukkit.getPlayer(id) == null);
            if (queue.size() < 3) { countdown = false; countdownTask = -1; cancel(); tellQueue(ChatColor.RED + "Contagem cancelada: são necessários 3 jogadores."); return; }
            if (seconds == 15 || seconds == 10 || seconds <= 5) tellQueue(ChatColor.GOLD + "A partida começa em " + seconds + " segundos!");
            if (seconds-- <= 0) { countdown = false; countdownTask = -1; cancel(); startGame(); }
        }}.runTaskTimer(this, 0L, 20L).getTaskId();
    }
    private void tellQueue(String msg) { for (UUID id : queue) { Player p = Bukkit.getPlayer(id); if (p != null) p.sendMessage(msg); } }

    private void startGame() {
        queue.removeIf(id -> Bukkit.getPlayer(id) == null);
        if (queue.size() < 3 || maps.isEmpty()) { tellQueue(ChatColor.RED + "Não há jogadores suficientes ou nenhum mapa configurado."); return; }
        running = true; countdown = false; roles.clear(); alive.clear(); fugitiveUsed.clear(); sheriffArrows.clear();
        List<UUID> players = new ArrayList<>(queue); queue.clear(); Collections.shuffle(players);
        List<Role> assigned = new ArrayList<>(); assigned.add(Role.ASSASSINO); assigned.add(Role.XERIFE); assigned.add(Role.BARULHENTO);
        if (players.size() >= 6) assigned.add(Role.CUMPLICE);
        if (players.size() >= 7) assigned.add(Role.FUGITIVO);
        while (assigned.size() < players.size()) assigned.add(Role.INOCENTE);
        Collections.shuffle(assigned);
        List<String> mapNames = new ArrayList<>(maps.keySet()); currentMap = mapNames.get(new Random().nextInt(mapNames.size())); Location spawn = maps.get(currentMap);
        for (int i=0;i<players.size();i++) {
            UUID id=players.get(i); Player p=Bukkit.getPlayer(id); if (p==null) continue;
            Role role=assigned.get(i); roles.put(id, role); alive.add(id); p.getInventory().clear(); p.setGameMode(GameMode.SURVIVAL); p.setHealth(20); p.setFoodLevel(20); p.setFireTicks(0); p.setFallDistance(0); p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType())); p.teleport(spawn);
            giveRoleItems(p, role); p.sendTitle(ChatColor.GOLD + "ASSASSINOS", ChatColor.YELLOW + "Mapa: " + currentMap, 10, 50, 10); p.sendMessage(ChatColor.GRAY + "Seu cargo secreto: " + roleName(role));
        }
        Bukkit.broadcastMessage(ChatColor.GREEN + "Uma partida de Assassinos começou! Mapa: " + currentMap + ".");
    }
    private String roleName(Role r) { return switch(r) { case ASSASSINO -> "Assassino"; case XERIFE -> "Xerife"; case INOCENTE -> "Inocente"; case BARULHENTO -> "Barulhento"; case CUMPLICE -> "Cúmplice"; case FUGITIVO -> "Fugitivo"; }; }
    private ItemStack named(Material m, String name) { ItemStack i = new ItemStack(m); ItemMeta meta=i.getItemMeta(); if(meta!=null){meta.setDisplayName(name); i.setItemMeta(meta);} return i; }
    private void giveRoleItems(Player p, Role r) {
        switch(r) {
            case ASSASSINO -> { ItemStack sword=named(Material.IRON_SWORD, ChatColor.RED+"Faca do Assassino"); sword.addUnsafeEnchantment(Enchantment.DAMAGE_ALL, 1); p.getInventory().setItem(0,sword); }
            case XERIFE -> { p.getInventory().setItem(0,named(Material.BOW, ChatColor.BLUE+"Arco do Xerife")); p.getInventory().setItem(8,new ItemStack(Material.ARROW,3)); sheriffArrows.put(p.getUniqueId(),3); }
            case BARULHENTO -> p.getInventory().setItem(0,named(Material.GOAT_HORN, ChatColor.YELLOW+"Berrante"));
            case FUGITIVO -> p.sendMessage(ChatColor.AQUA+"Sua primeira tentativa de eliminação será bloqueada e dará velocidade por 4 segundos.");
            default -> { }
        }
    }

    @EventHandler public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim) || !running) return;
        Player attacker = null;
        if (e.getDamager() instanceof Player direct) attacker = direct;
        else if (e.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) attacker = shooter;
        if (attacker == null) return;
        UUID vid=victim.getUniqueId(), aid=attacker.getUniqueId();
        if (!alive.contains(vid) || !alive.contains(aid)) { e.setCancelled(true); return; }
        Role ar=roles.get(aid), vr=roles.get(vid);
        if (ar==Role.ASSASSINO && vr!=Role.CUMPLICE) {
            if (vr==Role.FUGITIVO && !fugitiveUsed.contains(vid)) { e.setCancelled(true); fugitiveUsed.add(vid); victim.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,80,1)); victim.sendMessage(ChatColor.AQUA+"Você escapou do primeiro golpe!"); attacker.sendMessage(ChatColor.RED+"O alvo conseguiu escapar!"); return; }
            return;
        }
        if (ar==Role.XERIFE && vr==Role.ASSASSINO) return;
        e.setCancelled(true);
    }
    @EventHandler public void onDeath(PlayerDeathEvent e) {
        Player p=e.getEntity(); if (!running || !alive.contains(p.getUniqueId())) return;
        UUID id=p.getUniqueId(); Role role=roles.get(id); alive.remove(id); e.setDeathMessage("");
        Bukkit.getScheduler().runTask(this, () -> { p.spigot().respawn(); p.setGameMode(GameMode.SPECTATOR); p.sendMessage(ChatColor.GRAY+"Você foi eliminado. Aguarde o fim da partida."); });
        Bukkit.broadcastMessage(ChatColor.GRAY + p.getName() + " foi eliminado.");
        if (role==Role.ASSASSINO) { endGame("Os inocentes venceram! O Assassino foi eliminado.", false); return; }
        long innocents=alive.stream().filter(u -> roles.get(u)!=Role.ASSASSINO && roles.get(u)!=Role.CUMPLICE).count();
        if (innocents==0) endGame("O Assassino e seu Cúmplice venceram!", true);
    }
    @EventHandler public void onHorn(PlayerInteractEvent e) {
        Player p=e.getPlayer(); if (!running || roles.get(p.getUniqueId())!=Role.BARULHENTO) return;
        ItemStack item=e.getItem(); if (item==null || item.getType()!=Material.GOAT_HORN) return;
        for (UUID id:alive) { Player q=Bukkit.getPlayer(id); if(q!=null) q.playSound(q.getLocation(), Sound.ITEM_GOAT_HORN_SOUND_0, 2.0f, 1.0f); }
        Bukkit.broadcastMessage(ChatColor.YELLOW+"O Barulhento tocou o berrante!");
    }
    @EventHandler public void onQuit(PlayerQuitEvent e) {
        UUID id=e.getPlayer().getUniqueId(); queue.remove(id);
        if (running && alive.contains(id)) { alive.remove(id); roles.remove(id); checkWinAfterQuit(); }
    }
    private void checkWinAfterQuit() {
        boolean assassinAlive=alive.stream().anyMatch(u->roles.get(u)==Role.ASSASSINO);
        long innocents=alive.stream().filter(u->roles.get(u)!=Role.ASSASSINO && roles.get(u)!=Role.CUMPLICE).count();
        if(!assassinAlive) endGame("Os inocentes venceram!",false); else if(innocents==0) endGame("O Assassino e seu Cúmplice venceram!",true);
    }
    private void endGame(String message, Boolean assassinWon) {
        if (!running && !countdown) return;
        running=false; countdown=false;
        if (countdownTask!=-1) { Bukkit.getScheduler().cancelTask(countdownTask); countdownTask=-1; }
        Bukkit.broadcastMessage(ChatColor.GOLD+"[Assassinos] "+message);
        if (assassinWon!=null) for (Map.Entry<UUID,Role> entry:roles.entrySet()) { Player p=Bukkit.getPlayer(entry.getKey()); if(p!=null && ((entry.getValue()==Role.ASSASSINO||entry.getValue()==Role.CUMPLICE)==assassinWon)) p.sendMessage(ChatColor.GREEN+"Você venceu!"); }
        for(UUID id:new HashSet<>(roles.keySet())) { Player p=Bukkit.getPlayer(id); if(p!=null) resetPlayer(p,true); }
        roles.clear(); alive.clear(); fugitiveUsed.clear(); sheriffArrows.clear();
    }
    private void resetPlayer(Player p, boolean goLobby) {
        p.getInventory().clear(); p.setGameMode(GameMode.SURVIVAL); p.setHealth(20); p.setFoodLevel(20); p.setFireTicks(0); p.getActivePotionEffects().forEach(e->p.removePotionEffect(e.getType()));
        if(goLobby && lobby!=null) p.teleport(lobby);
    }
}
