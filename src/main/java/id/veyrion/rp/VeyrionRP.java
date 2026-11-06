package id.veyrion.rp;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VeyrionRP extends JavaPlugin implements Listener, CommandExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Pattern STRIP = Pattern.compile("(?i)\u00a7[0-9a-fk-or]");
    private static final Pattern NAME = Pattern.compile("^\\p{L}[\\p{L}'\\-]{1,15}$");
    private static final Pattern FIRST = Pattern.compile("(?im)^\\s*nama\\s*pertama\\s*:(.*)$");
    private static final Pattern LAST = Pattern.compile("(?im)^\\s*nama\\s*kedua\\s*:(.*)$");
    private static final String GRAD = "<gradient:#7b2ff7:#f107a3>";
    private static final String GRAD_END = "</gradient>";

    private record Saved(int slot, ItemStack item) {}

    private NamespacedKey bookKey;
    private File dataFile;
    private YamlConfiguration data;
    private final Map<UUID, String> names = new HashMap<>();
    private final Set<UUID> pending = new HashSet<>();
    private final Map<UUID, Saved> saved = new HashMap<>();
    private final Map<UUID, TextDisplay> tags = new HashMap<>();

    // ───────────────────────── lifecycle ─────────────────────────

    @Override
    public void onEnable() {
        saveDefaultConfig();
        bookKey = new NamespacedKey(this, "rp_form");
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        for (String key : data.getKeys(false)) {
            try {
                names.put(UUID.fromString(key),
                        data.getString(key + ".first", "?") + " " + data.getString(key + ".last", "?"));
            } catch (IllegalArgumentException ignored) {}
        }
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("vrpname").setExecutor(this);
        getCommand("veyrion").setExecutor(this);

        getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player p : getServer().getOnlinePlayers()) {
                UUID id = p.getUniqueId();
                if (pending.contains(id)) {
                    p.sendActionBar(MM.deserialize(GRAD + "Isi Nama RP dulu: ketik <bold>/vrpname" + GRAD_END));
                } else if (names.containsKey(id) && !p.isDead() && getConfig().getBoolean("nametag.enabled", true)) {
                    ensureTag(p);
                }
            }
        }, 40L, 40L);

        for (Player p : getServer().getOnlinePlayers()) {
            if (names.containsKey(p.getUniqueId())) applyName(p);
        }
    }

    @Override
    public void onDisable() {
        for (Player p : getServer().getOnlinePlayers()) {
            if (pending.contains(p.getUniqueId())) restore(p);
            removeTag(p);
        }
        pending.clear();
    }

    // ───────────────────────── name logic ─────────────────────────

    private void applyName(Player p) {
        Component n = Component.text(names.get(p.getUniqueId()));
        p.displayName(n);
        p.playerListName(n);
        if (getConfig().getBoolean("nametag.enabled", true)) ensureTag(p);
    }

    private void ensureTag(Player p) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        String tn = "vr" + p.getUniqueId().toString().replace("-", "").substring(0, 12);
        Team t = sb.getTeam(tn);
        if (t == null) t = sb.registerNewTeam(tn);
        t.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        if (!t.hasEntry(p.getName())) t.addEntry(p.getName());

        TextDisplay old = tags.get(p.getUniqueId());
        if (old != null && old.isValid() && p.getPassengers().contains(old)) return;
        if (old != null) old.remove();

        float y = (float) getConfig().getDouble("nametag.y-offset", 0.25);
        String rp = names.get(p.getUniqueId());
        TextDisplay d = p.getWorld().spawn(p.getLocation(), TextDisplay.class, x -> {
            x.text(Component.text(rp));
            x.setBillboard(Display.Billboard.CENTER);
            x.setShadowed(true);
            x.setPersistent(false);
            x.setTransformation(new Transformation(new Vector3f(0, y, 0), new AxisAngle4f(),
                    new Vector3f(1, 1, 1), new AxisAngle4f()));
        });
        p.addPassenger(d);
        p.hideEntity(this, d);
        tags.put(p.getUniqueId(), d);
    }

    private void removeTag(Player p) {
        TextDisplay d = tags.remove(p.getUniqueId());
        if (d != null) d.remove();
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team t = sb.getTeam("vr" + p.getUniqueId().toString().replace("-", "").substring(0, 12));
        if (t != null) t.unregister();
    }

    private boolean taken(String full, UUID self) {
        return names.entrySet().stream()
                .anyMatch(e -> !e.getKey().equals(self) && e.getValue().equalsIgnoreCase(full));
    }

    private Component rp(Component msg, Player p) {
        if (msg == null) return null;
        String n = names.get(p.getUniqueId());
        if (n == null) return msg;
        return msg.replaceText(b -> b.matchLiteral(p.getName()).replacement(Component.text(n)));
    }

    private void save() {
        try {
            data.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Gagal simpan data.yml: " + ex.getMessage());
        }
    }

    // ───────────────────────── GUI ─────────────────────────

    private void openIntro(Player p) {
        String url = getConfig().getString("discord", "https://discord.gg/HQ3cM2psVX");
        String shown = url.replace("https://", "").replace("http://", "");
        Component page = MM.deserialize(String.join("\n",
                GRAD + "<bold>VEYRION ROLEPLAY</bold>" + GRAD_END,
                "<dark_gray>=================</dark_gray>",
                "<dark_purple><bold>Apa itu Nama RP?</bold></dark_purple>",
                "<black>Nama karakter kamu di server ini. Tampil di chat, tab, dan atas kepala.</black>",
                "<dark_gray>Contoh:</dark_gray> <black>Naed Delahoya</black>",
                "",
                "<click:run_command:'/vrpname'><hover:show_text:'<gray>Klik untuk mengisi nama'>"
                        + "<dark_green><bold>[ ISI NAMA RP ]</bold></dark_green></hover></click>",
                "",
                "<click:open_url:'" + url + "'><hover:show_text:'<gray>Buka Discord'>"
                        + "<blue><underlined>" + shown + "</underlined></blue></hover></click>"));
        ItemStack b = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta m = (BookMeta) b.getItemMeta();
        m.setTitle("Veyrion");
        m.setAuthor("Veyrion");
        m.addPages(page);
        b.setItemMeta(m);
        p.openBook(b);
    }

    private String c(String s) {
        return getConfig().getBoolean("book-colors", true) ? s : STRIP.matcher(s).replaceAll("");
    }

    @SuppressWarnings("deprecation")
    private ItemStack template() {
        String url = getConfig().getString("discord", "https://discord.gg/HQ3cM2psVX");
        ItemStack b = new ItemStack(Material.WRITABLE_BOOK);
        BookMeta m = (BookMeta) b.getItemMeta();
        m.getPersistentDataContainer().set(bookKey, PersistentDataType.BYTE, (byte) 1);
        m.displayName(MM.deserialize("<!italic>" + GRAD + "<bold>Formulir Nama RP" + GRAD_END));
        m.setPages(
                c("\u00a75\u00a7lVEYRION ROLEPLAY\u00a7r\n\u00a78=================\n"
                        + "\u00a70Nama RP adalah nama karakter kamu. Tampil di chat, tab, dan atas kepala.\n\n"
                        + "\u00a78Contoh: \u00a70Naed Delahoya\n\n"
                        + "\u00a78Halaman 2 untuk mengisi >>"),
                c("\u00a75\u00a7lISI NAMA RP\u00a7r\n\u00a78=================\n"
                        + "\u00a70Isi setelah titik dua, lalu klik \u00a72Done\u00a70. Huruf saja, 2-16 huruf.\n\n"
                        + "\u00a70Nama pertama: \n\u00a70Nama kedua: \n\n"
                        + "\u00a71\u00a7n" + url));
        b.setItemMeta(m);
        return b;
    }

    private void giveForm(Player p) {
        UUID id = p.getUniqueId();
        PlayerInventory inv = p.getInventory();
        Saved s = saved.computeIfAbsent(id,
                k -> new Saved(inv.getHeldItemSlot(), inv.getItemInMainHand().clone()));
        inv.setItem(s.slot(), template());
        inv.setHeldItemSlot(s.slot());
        p.showTitle(Title.title(
                MM.deserialize(GRAD + "<bold>Klik kanan buku" + GRAD_END),
                MM.deserialize("<gray>Isi nama pertama & kedua, lalu klik Done")));
    }

    private void restore(Player p) {
        Saved s = saved.remove(p.getUniqueId());
        if (s != null) p.getInventory().setItem(s.slot(), s.item());
    }

    private void cleanBooks(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && it.getType() == Material.WRITABLE_BOOK && it.hasItemMeta()
                    && it.getItemMeta().getPersistentDataContainer().has(bookKey, PersistentDataType.BYTE)) {
                p.getInventory().remove(it);
            }
        }
    }

    // ───────────────────────── submit ─────────────────────────

    private static String grab(Pattern pat, String all) {
        Matcher m = pat.matcher(all);
        return m.find() ? m.group(1).trim() : "";
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @SuppressWarnings("deprecation")
    @EventHandler
    public void onEdit(PlayerEditBookEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (!pending.contains(id)) return;
        e.setCancelled(true);

        String all = STRIP.matcher(String.join("\n", e.getNewBookMeta().getPages())).replaceAll("");
        String f = cap(grab(FIRST, all));
        String l = cap(grab(LAST, all));

        String err = null;
        if (!NAME.matcher(f).matches() || !NAME.matcher(l).matches()) {
            err = "Nama pertama & kedua harus 2-16 huruf (boleh ' dan -), tanpa angka dan spasi.";
        } else if (taken(f + " " + l, id)) {
            err = "Nama itu sudah dipakai pemain lain.";
        }
        if (err != null) {
            p.sendMessage(MM.deserialize("<red>\u2716 " + err));
            getServer().getScheduler().runTask(this, () -> giveForm(p));
            return;
        }
        final String ff = f, ll = l;
        getServer().getScheduler().runTask(this, () -> finish(p, ff, ll));
    }

    private void finish(Player p, String f, String l) {
        UUID id = p.getUniqueId();
        if (!pending.remove(id)) return;
        restore(p);
        names.put(id, f + " " + l);
        data.set(id + ".first", f);
        data.set(id + ".last", l);
        data.set(id + ".real", p.getName());
        save();
        applyName(p);
        p.showTitle(Title.title(
                Component.text(f + " " + l, NamedTextColor.LIGHT_PURPLE),
                Component.text("Selamat datang di Veyrion Roleplay", NamedTextColor.GRAY)));
        Bukkit.broadcast(Component.text()
                .append(Component.text(f + " " + l, NamedTextColor.LIGHT_PURPLE))
                .append(Component.text(" telah tiba di Veyrion!", NamedTextColor.GRAY))
                .build());
    }

    // ───────────────────────── join / quit / messages ─────────────────────────

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        cleanBooks(p);
        if (names.containsKey(id)) {
            applyName(p);
            e.joinMessage(rp(e.joinMessage(), p));
            return;
        }
        e.joinMessage(null);
        pending.add(id);
        getServer().getScheduler().runTaskLater(this, () -> {
            if (p.isOnline() && pending.contains(id)) openIntro(p);
        }, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (pending.remove(p.getUniqueId())) {
            restore(p);
            e.quitMessage(null);
        } else {
            e.quitMessage(rp(e.quitMessage(), p));
        }
        removeTag(p);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        Component m = rp(e.deathMessage(), p);
        Player k = p.getKiller();
        if (k != null) m = rp(m, k);
        e.deathMessage(m);
    }

    @EventHandler
    public void onAdvancement(PlayerAdvancementDoneEvent e) {
        e.message(rp(e.message(), e.getPlayer()));
    }

    // ───────────────────────── freeze while pending ─────────────────────────

    private boolean frozen(org.bukkit.entity.Entity en) {
        return en instanceof Player p && pending.contains(p.getUniqueId());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (frozen(e.getPlayer()) && e.hasChangedPosition()) {
            Location t = e.getFrom().clone();
            t.setYaw(e.getTo().getYaw());
            t.setPitch(e.getTo().getPitch());
            e.setTo(t);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (!frozen(e.getPlayer())) return;
        e.setUseInteractedBlock(Event.Result.DENY);
        ItemStack it = e.getItem();
        if (it == null || it.getType() != Material.WRITABLE_BOOK) e.setUseItemInHand(Event.Result.DENY);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) { if (frozen(e.getPlayer())) e.setCancelled(true); }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) { if (frozen(e.getPlayer())) e.setCancelled(true); }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) { if (frozen(e.getPlayer())) e.setCancelled(true); }

    @EventHandler
    public void onHeld(PlayerItemHeldEvent e) { if (frozen(e.getPlayer())) e.setCancelled(true); }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) { if (frozen(e.getPlayer())) e.setCancelled(true); }

    @EventHandler
    public void onClick(InventoryClickEvent e) { if (frozen(e.getWhoClicked())) e.setCancelled(true); }

    @EventHandler
    public void onDamage(EntityDamageEvent e) { if (frozen(e.getEntity())) e.setCancelled(true); }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) { if (frozen(e.getEntity())) e.setCancelled(true); }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        if (frozen(e.getPlayer())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(MM.deserialize("<red>Isi Nama RP dulu. Ketik <bold>/vrpname"));
        }
    }

    @EventHandler
    public void onCmd(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!frozen(p)) return;
        String msg = e.getMessage().toLowerCase();
        if (msg.startsWith("/vrpname")) return;
        if (p.hasPermission("veyrion.admin") && (msg.startsWith("/veyrion") || msg.startsWith("/vrp "))) return;
        e.setCancelled(true);
        p.sendMessage(MM.deserialize("<red>Isi Nama RP dulu. Ketik <bold>/vrpname"));
    }

    // ───────────────────────── commands ─────────────────────────

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (cmd.getName().equalsIgnoreCase("vrpname")) {
            if (!(s instanceof Player p)) return true;
            if (!pending.contains(p.getUniqueId())) {
                p.sendMessage(MM.deserialize("<gray>Kamu sudah punya Nama RP: <light_purple>"
                        + names.getOrDefault(p.getUniqueId(), "-")));
                return true;
            }
            giveForm(p);
            return true;
        }

        if (a.length < 2) {
            s.sendMessage(Component.text("/veyrion <reset|info> <player>", NamedTextColor.RED));
            return true;
        }
        OfflinePlayer o = Bukkit.getOfflinePlayerIfCached(a[1]);
        if (o == null) {
            s.sendMessage(Component.text("Player tidak ditemukan.", NamedTextColor.RED));
            return true;
        }
        UUID id = o.getUniqueId();
        switch (a[0].toLowerCase()) {
            case "info" -> s.sendMessage(Component.text(
                    a[1] + " = " + names.getOrDefault(id, "(belum punya nama RP)"), NamedTextColor.GRAY));
            case "reset" -> {
                names.remove(id);
                data.set(id.toString(), null);
                save();
                Player on = o.getPlayer();
                if (on != null) {
                    removeTag(on);
                    on.displayName(null);
                    on.playerListName(null);
                    pending.add(id);
                    openIntro(on);
                }
                s.sendMessage(Component.text("Nama RP " + a[1] + " di-reset.", NamedTextColor.GREEN));
            }
            default -> s.sendMessage(Component.text("/veyrion <reset|info> <player>", NamedTextColor.RED));
        }
        return true;
    }
}
