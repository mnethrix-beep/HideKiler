package com.netrix.HIDEKILER;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class HIDEKILER extends JavaPlugin implements Listener, CommandExecutor {

    enum GameState { LOBBY, INGAME, MEETING, ENDED }
    private GameState gameState = GameState.LOBBY;

    private Location lobbyLoc, startRoomLoc, meetingLoc;
    
    private final List<UUID> allPlayers = new ArrayList<>();
    private final List<UUID> survivors = new ArrayList<>();
    private UUID imposter = null;
    
    private boolean imposterKilledThisRound = false;
    private long lastKillTime = 0; 
    private final int KILL_COOLDOWN_MS = 30000; 
    private final int MAX_VOTING_TIME = 120; 

    private final Map<ArmorStand, Location> deadBodies = new HashMap<>();
    private final Set<UUID> votedPlayers = new HashSet<>(); 
    private final Map<UUID, UUID> votes = new HashMap<>(); 
    
    private int votingTimer = MAX_VOTING_TIME;
    private BukkitRunnable votingTask;
    private BossBar votingBar;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadLocations();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("hk")).setExecutor(this);
        Objects.requireNonNull(getCommand("vote")).setExecutor(this);
        
        votingBar = Bukkit.createBossBar("§6§lVOTING TIME", BarColor.GOLD, BarStyle.SOLID);
        getLogger().info("Hide Kiler Plugin 10/10 Fixed by NETHRIC!");
    }

    @Override
    public void onDisable() {
        clearDeadBodies();
        if (votingBar != null) {
            votingBar.removeAll();
        }
        if (votingTask != null) {
            votingTask.cancel();
        }
        getLogger().info("Hide Kiler Plugin Disabled and Cleaned Up Successfully!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("hk") && sender instanceof Player) {
            Player p = (Player) sender;
            if (args.length > 0) {
                if (args[0].equalsIgnoreCase("setlobby")) {
                    lobbyLoc = p.getLocation();
                    saveLocation("lobby", lobbyLoc);
                    p.sendMessage("§aLobby Location Set!");
                    return true;
                } else if (args[0].equalsIgnoreCase("setstartroom")) {
                    startRoomLoc = p.getLocation();
                    saveLocation("startroom", startRoomLoc);
                    p.sendMessage("§aStarting Room Location Set!");
                    return true;
                } else if (args[0].equalsIgnoreCase("setmeeting")) {
                    meetingLoc = p.getLocation();
                    saveLocation("meeting", meetingLoc);
                    p.sendMessage("§aMeeting Room Location Set!");
                    return true;
                } else if (args[0].equalsIgnoreCase("start")) {
                    if (gameState == GameState.LOBBY) startGame();
                    return true;
                }
            }
            p.sendMessage("§cUsage: /hk [setlobby|setstartroom|setmeeting|start]");
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("vote") && sender instanceof Player) {
            Player p = (Player) sender;
            if (gameState != GameState.MEETING) {
                p.sendMessage("§cAbhi koi meeting nahi chal rahi hai!");
                return true;
            }
            if (votedPlayers.contains(p.getUniqueId())) {
                p.sendMessage("§cAap pehle hi vote kar chuke hain!");
                return true;
            }
            if (args.length < 1) {
                p.sendMessage("§cUsage: /vote <PlayerName>");
                return true;
            }
            Player target = Bukkit.getPlayer(args[0]);
            if (target == null || (!survivors.contains(target.getUniqueId()) && !target.getUniqueId().equals(imposter))) {
                p.sendMessage("§cPlayer nahi mila ya wo game mein nahi hai!");
                return true;
            }
            votes.put(p.getUniqueId(), target.getUniqueId());
            votedPlayers.add(p.getUniqueId());
            p.sendMessage("§aAapne " + target.getName() + " ko vote diya!");
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
            return true;
        }
        return false;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (gameState == GameState.LOBBY) {
            if (!allPlayers.contains(p.getUniqueId())) {
                allPlayers.add(p.getUniqueId());
            }
            p.setGameMode(GameMode.SURVIVAL);
            if (lobbyLoc != null) p.teleport(lobbyLoc);

            if (gameState == GameState.LOBBY && allPlayers.size() >= 5) {
                startGame();
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID quitId = e.getPlayer().getUniqueId();
        allPlayers.remove(quitId);
        
        if (gameState != GameState.LOBBY) {
            if (quitId.equals(imposter)) {
                Bukkit.broadcast(Component.text("§cImposter game chhor kar chala gaya!"));
                imposter = null;
                checkGameEnd();
            } else if (survivors.contains(quitId)) {
                survivors.remove(quitId);
                Bukkit.broadcast(Component.text("§cEk Survivor disconnect ho gaya."));
                checkGameEnd();
            }
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (gameState != GameState.LOBBY) {
            if (!p.getUniqueId().equals(imposter) && !survivors.contains(p.getUniqueId())) {
                e.setRespawnLocation(meetingLoc != null ? meetingLoc : p.getLocation());
                Bukkit.getScheduler().runTask(this, () -> p.setGameMode(GameMode.SPECTATOR));
            }
        } else {
            if (lobbyLoc != null) e.setRespawnLocation(lobbyLoc);
        }
    }

    private void startGame() {
        if (allPlayers.size() < 5) {
            Bukkit.broadcast(Component.text("§cGame shuru karne ke liye kam se kam 5 players chahiye!"));
            return;
        }
        if (startRoomLoc == null || meetingLoc == null) {
            Bukkit.broadcast(Component.text("§cServer locations set nahi hain! Please /hk commands use karein."));
            return;
        }
        
        gameState = GameState.INGAME;
        survivors.clear();
        clearDeadBodies();
        votes.clear();
        votedPlayers.clear();
        votingBar.removeAll();
        
        Collections.shuffle(allPlayers);
        imposter = allPlayers.get(0);
        
        for (int i = 1; i < allPlayers.size(); i++) {
            survivors.add(allPlayers.get(i));
        }

        for (UUID uuid : allPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.setGameMode(GameMode.SURVIVAL);
                p.teleport(startRoomLoc);
                p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 1f, 1f);
                if (uuid.equals(imposter)) {
                    p.showTitle(Title.title(Component.text("IMPOSTER", NamedTextColor.RED), Component.text("Sabko khatam karo!", NamedTextColor.GRAY)));
                } else {
                    p.showTitle(Title.title(Component.text("SURVIVOR", NamedTextColor.GREEN), Component.text("Imposter se bacho!", NamedTextColor.GRAY)));
                }
            }
        }
        imposterKilledThisRound = false;
        lastKillTime = 0; 
    }

    @EventHandler
    public void onPlayerDamage(EntityDamageByEntityEvent e) {
        if (gameState != GameState.INGAME) {
            e.setCancelled(true);
            return;
        }

        if (e.getDamager() instanceof Player && e.getEntity() instanceof Player) {
            Player damager = (Player) e.getDamager();
            Player victim = (Player) e.getEntity();

            if (damager.getUniqueId().equals(imposter)) {
                e.setCancelled(true);

                long timePassed = System.currentTimeMillis() - lastKillTime;
                if (timePassed < KILL_COOLDOWN_MS) {
                    long secondsLeft = (KILL_COOLDOWN_MS - timePassed) / 1000;
                    damager.sendMessage("§cKill cooldown active! Wait " + secondsLeft + "s.");
                    return;
                }

                if (imposterKilledThisRound) {
                    damager.sendMessage("§cAap is round mein pehle hi ek kill kar chuke hain!");
                    return;
                }

                if (!survivors.contains(victim.getUniqueId())) return;

                imposterKilledThisRound = true;
                lastKillTime = System.currentTimeMillis();
                damager.sendMessage("§eTarget marked! Player 10 seconds baad marega.");

                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (gameState == GameState.INGAME && victim.isOnline() && survivors.contains(victim.getUniqueId())) {
                            Location killLoc = victim.getLocation();
                            
                            ArmorStand body = (ArmorStand) killLoc.getWorld().spawnEntity(killLoc, EntityType.ARMOR_STAND);
                            body.setGravity(false);
                            body.setCustomNameVisible(true);
                            body.customName(Component.text("☠ DEAD BODY (Pass jao report karne)", NamedTextColor.RED));
                            body.setInvisible(true);
                            body.setMarker(true);
                            body.setInvulnerable(true);
                            body.setSmall(true);
                            
                            deadBodies.put(body, killLoc);
                            survivors.remove(victim.getUniqueId());
                            victim.setGameMode(GameMode.SPECTATOR); 
                            
                            victim.playSound(victim.getLocation(), Sound.ENTITY_PLAYER_DEATH, 1f, 1f);
                            Bukkit.broadcast(Component.text("§cKisi ka khoon hua hai! Body dhoondho."));
                            checkGameEnd();
                        }
                    }
                }.runTaskLater(this, 200L);
            } else {
                e.setCancelled(true); 
            }
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent e) {
        if (gameState != GameState.INGAME) return;
        Player p = e.getPlayer();
        if (p.getUniqueId().equals(imposter) || p.getGameMode() == GameMode.SPECTATOR) return;

        for (Map.Entry<ArmorStand, Location> entry : new HashMap<>(deadBodies).entrySet()) {
            if (!p.getWorld().equals(entry.getValue().getWorld())) continue;

            if (p.getLocation().distance(entry.getValue()) <= 2.0) {
                startMeeting();
                break;
            }
        }
    }

    private void startMeeting() {
        if (votingTask != null) {
            votingTask.cancel();
        }

        gameState = GameState.MEETING;
        votes.clear();
        votedPlayers.clear();
        votingTimer = MAX_VOTING_TIME;
        clearDeadBodies();
        votingBar.removeAll();

        for (UUID uuid : allPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && (survivors.contains(uuid) || uuid.equals(imposter))) {
                p.teleport(meetingLoc);
                p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1f, 1f);
                p.showTitle(Title.title(Component.text("EMERGENCY MEETING", NamedTextColor.GOLD), Component.text("Vote karne ke liye /vote <name> use karein", NamedTextColor.YELLOW)));
                votingBar.addPlayer(p);
            }
        }

        votingTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (votingTimer <= 0) {
                    votingBar.removeAll();
                    endVoting();
                    cancel();
                    return;
                }
                
                double progress = (double) votingTimer / (double) MAX_VOTING_TIME;
                votingBar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
                votingBar.setTitle("§6§lVOTING TIME: §e§l" + votingTimer + "s");

                if (votingTimer == 60 || votingTimer == 30 || votingTimer <= 5) {
                    Bukkit.broadcast(Component.text("§e§lVoting ends in " + votingTimer + " seconds!"));
                    for (UUID uuid : allPlayers) {
                        Player p = Bukkit.getPlayer(uuid);
                        if (p != null) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f);
                    }
                }
                votingTimer--;
            }
        };
        votingTask.runTaskTimer(this, 0L, 20L);
    }

    private void endVoting() {
        if (gameState != GameState.MEETING) return;
        votingBar.removeAll();

        Map<UUID, Integer> voteCounts = new HashMap<>();
        for (UUID votedFor : votes.values()) {
            voteCounts.put(votedFor, voteCounts.getOrDefault(votedFor, 0) + 1);
        }

        UUID mostVoted = null;
        int maxVotes = -1;
        boolean tie = false;

        for (Map.Entry<UUID, Integer> entry : voteCounts.entrySet()) {
            if (entry.getValue() > maxVotes) {
                maxVotes = entry.getValue();
                mostVoted = entry.getKey();
                tie = false;
            } else if (entry.getValue() == maxVotes) {
                tie = true;
            }
        }

        if (tie || mostVoted == null) {
            Bukkit.broadcast(Component.text("§eTie hua! Koi bhi eliminate nahi hua."));
        } else {
            Player eliminated = Bukkit.getPlayer(mostVoted);
            if (eliminated != null) {
                for (UUID uuid : allPlayers) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) {
                        p.showTitle(Title.title(
                                Component.text(eliminated.getName().toUpperCase() + " IS KILLED", NamedTextColor.RED),
                                Component.text("Eliminated by vote!", NamedTextColor.GRAY)
                        ));
                    }
                }

                if (mostVoted.equals(imposter)) {
                    imposter = null;
                } else {
                    survivors.remove(mostVoted);
                }
                eliminated.setGameMode(GameMode.SPECTATOR);
            }
        }

        if (!checkGameEnd()) {
            gameState = GameState.INGAME;
            imposterKilledThisRound = false;
            for (UUID uuid : allPlayers) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && (survivors.contains(uuid) || uuid.equals(imposter))) {
                    p.teleport(startRoomLoc);
                    p.sendMessage("§aNaya Round Shuru! Imposter phir se active hai.");
                }
            }
        }
    }

    private boolean checkGameEnd() {
        if (imposter == null) {
            gameState = GameState.ENDED;
            if (votingTask != null) votingTask.cancel();
            votingBar.removeAll();

            for (UUID uuid : allPlayers) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    p.showTitle(Title.title(Component.text("PLAYERS WIN!", NamedTextColor.GREEN), Component.text("Imposter ko pakar liya gaya!", NamedTextColor.GOLD)));
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "eco give " + p.getName() + " 2000");
                }
            }
            resetToLobby();
            return true;
        } else if (survivors.size() <= 1) { 
            gameState = GameState.ENDED;
            if (votingTask != null) votingTask.cancel();
            votingBar.removeAll();

            Player impPlayer = Bukkit.getPlayer(imposter);
            String impName = impPlayer != null ? impPlayer.getName() : "Imposter";

            for (UUID uuid : allPlayers) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    p.showTitle(Title.title(Component.text("IMPOSTER WINS!", NamedTextColor.RED), Component.text(impName + " ne sabko maar diya!", NamedTextColor.GOLD)));
                }
            }
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "eco give " + impName + " 5000");
            resetToLobby();
            return true;
        }
        return false;
    }

    private void resetToLobby() {
        clearDeadBodies();
        votingBar.removeAll();
        new BukkitRunnable() {
            @Override
            public void run() {
                gameState = GameState.LOBBY;
                allPlayers.clear();
                survivors.clear();
                imposter = null;
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.setGameMode(GameMode.SURVIVAL);
                    if (lobbyLoc != null) p.teleport(lobbyLoc);
                    allPlayers.add(p.getUniqueId());
                }
                Bukkit.broadcast(Component.text("§eGame Reset ho gaya hai. Agle match ke liye players ka wait ho raha hai..."));
            }
        }.runTaskLater(this, 100L);
    }

    private void clearDeadBodies() {
        for (ArmorStand stand : deadBodies.keySet()) {
            if (stand.isValid()) stand.remove();
        }
        deadBodies.clear();
    }

    private void saveLocation(String path, Location loc) {
        getConfig().set(path + ".world", Objects.requireNonNull(loc.getWorld()).getName());
        getConfig().set(path + ".x", loc.getX());
        getConfig().set(path + ".y", loc.getY());
        getConfig().set(path + ".z", loc.getZ()); 
        getConfig().set(path + ".yaw", loc.getYaw());
        getConfig().set(path + ".pitch", loc.getPitch());
        saveConfig();
    }

    private void loadLocations() {
        if (getConfig().contains("lobby.world")) {
            lobbyLoc = new Location(Bukkit.getWorld(Objects.requireNonNull(getConfig().getString("lobby.world"))),
                    getConfig().getDouble("lobby.x"), getConfig().getDouble("lobby.y"), getConfig().getDouble("lobby.z"),
                    (float) getConfig().getDouble("lobby.yaw"), (float) getConfig().getDouble("lobby.pitch"));
        }
        if (getConfig().contains("startroom.world")) {
            startRoomLoc = new Location(Bukkit.getWorld(Objects.requireNonNull(getConfig().getString("startroom.world"))),
                    getConfig().getDouble("startroom.x"), getConfig().getDouble("startroom.y"), getConfig().getDouble("startroom.z"),
                    (float) getConfig().getDouble("startroom.yaw"), (float) getConfig().getDouble("
