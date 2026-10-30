package tr.chicagorp;

import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.TileState;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import java.util.*;

/** Modsuz arac sistemi: surucu = gorunmez at, yolcular = gorunmez koltuk entity'leri, govde = 3D ItemDisplay modeli.
 *  Seviye kapisi, kilit, yolcu izni, atma, galeri menusu (NPC komutuyla acilir). */
public class Vehicles implements Listener {
    public record Car(String id, String name, int level, double price, double speed, int seats, double scale, String model, Material mat,
                      String ia, double dx, double dz, List<double[]> seatOff) {}

    static class Active {
        Horse horse; ItemDisplay body; Interaction hit; List<ArmorStand> seats = new ArrayList<>();
        UUID owner; Car car; boolean modelUsed, locked = true, anyone = false; Set<UUID> allowed = new HashSet<>();
    }

    private final ChicagoRP p;
    private final Map<UUID, Active> active = new HashMap<>();
    private final Map<UUID, UUID> clickOwner = new HashMap<>();   // at / etkilesim / koltuk -> sahip

    public Vehicles(ChicagoRP p) {
        this.p = p;
        Bukkit.getScheduler().runTaskTimer(p, this::tick, 2L, 1L);
    }

    private static double num(List<?> l, int i) { return l != null && l.size() > i && l.get(i) instanceof Number n ? n.doubleValue() : 0; }

    public Map<String, Car> cars() {
        Map<String, Car> m = new LinkedHashMap<>();
        var s = p.getConfig().getConfigurationSection("vehicles");
        if (s == null) return m;
        for (String id : s.getKeys(false)) {
            var c = s.getConfigurationSection(id);
            Material mat = Material.matchMaterial(c.getString("material", "GRAY_CONCRETE"));
            List<double[]> so = new ArrayList<>();
            List<?> sl = c.getList("seat-offsets");
            if (sl != null) for (Object o : sl) if (o instanceof List<?> l2) so.add(new double[]{num(l2, 0), num(l2, 1)});
            List<?> dr = c.getList("driver");
            m.put(id, new Car(id, c.getString("name", id), c.getInt("level", 1), c.getDouble("price"), c.getDouble("speed", 0.2), c.getInt("seats", 2),
                    c.getDouble("scale", 1.875), c.getString("model", "car_" + id), mat == null ? Material.GRAY_CONCRETE : mat, c.getString("ia", ""),
                    num(dr, 0), num(dr, 1), so));
        }
        return m;
    }

    public List<String> owned(UUID u) {
        List<String> l = new ArrayList<>();
        Object o = p.data.get(u, "cars", null);
        if (o instanceof List<?> li) for (Object x : li) l.add(String.valueOf(x));
        return l;
    }

    private static int kmh(Car c) { return (int) (c.speed() * 43 * 3.6); }

    // ------------------------------------------------------------------ satin alma / admin
    public void buy(Player pl, String id) {
        Car c = cars().get(id);
        UUID u = pl.getUniqueId();
        if (c == null) return;
        List<String> l = owned(u);
        if (l.contains(id)) { U.msg(pl, "Bu araca zaten sahipsin."); return; }
        if (p.data.level(u) < c.level()) { U.msg(pl, "&cBu araç için Seviye " + c.level() + " gerekli (senin seviyen: " + p.data.level(u) + ")."); return; }
        if (!p.bank.pay(u, c.price())) { U.msg(pl, "&cYetersiz bakiye: " + (long) c.price() + " TL (nakit + banka)"); return; }
        l.add(id); p.data.set(u, "cars", l);
        U.msg(pl, "&a" + c.name() + " satın alındı! &7Telefon > Araçlar'dan çıkarabilirsin.");
    }

    public void adminGive(CommandSender s, String name, String id, boolean give) {
        Player t = Bukkit.getPlayer(name);
        Car c = cars().get(id);
        if (t == null || c == null) { U.msg(s, "Oyuncu veya araç yok. Araçlar: " + String.join(", ", cars().keySet())); return; }
        List<String> l = owned(t.getUniqueId());
        if (give) { if (!l.contains(id)) l.add(id); } else { l.remove(id); remove(t.getUniqueId()); }
        p.data.set(t.getUniqueId(), "cars", l);
        U.msg(s, (give ? "Verildi: " : "Silindi: ") + c.name() + " -> " + t.getName());
        if (give) U.msg(t, "&aYeni aracın: &e" + c.name());
    }

    // ------------------------------------------------------------------ arac olusturma
    public void spawn(Player pl, String id, boolean free) {
        Car c = cars().get(id);
        UUID u = pl.getUniqueId();
        if (c == null) return;
        if (!(boolean) p.data.get(u, "ehliyet", false)) { U.msg(pl, "Ehliyetin yok. /ehliyet"); return; }
        if (!free && !owned(u).contains(id)) { U.msg(pl, "Bu araç senin değil."); return; }
        remove(u);
        Location l = pl.getLocation().add(pl.getLocation().getDirection().setY(0).normalize().multiply(3));
        World w = pl.getWorld();
        Active a = new Active();
        a.owner = u; a.car = c;
        a.locked = (boolean) p.data.get(u, "car_locked", true);
        a.anyone = (boolean) p.data.get(u, "car_anyone", false);
        Object al = p.data.get(u, "car_allowed", null);
        if (al instanceof List<?> li) for (Object x : li) { try { a.allowed.add(UUID.fromString(String.valueOf(x))); } catch (Exception ignored) { } }
        double hs = p.getConfig().getDouble("vehicle.horse-scale", 0.6);
        a.horse = w.spawn(l, Horse.class, h -> {
            h.setAdult(); h.setTamed(true);
            h.getInventory().setSaddle(new ItemStack(Material.SADDLE));
            h.setInvisible(true); h.setSilent(true); h.setInvulnerable(true);
            h.setRemoveWhenFarAway(false); h.setPersistent(false);
            h.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(c.speed());
            var sc = h.getAttribute(Attribute.SCALE);
            if (sc != null) sc.setBaseValue(hs);
        });
        try { Bukkit.getMobGoals().removeAllGoals(a.horse); } catch (Throwable ignored) { }
        ItemStack ia = U.custom(c.ia(), null);
        boolean models = p.getConfig().getBoolean("models.enabled", true);
        float sc = (float) c.scale();
        if (ia != null) a.body = display(w, l, ia, 1f, 1f, 1f);
        else if (models) { a.body = display(w, l, U.withModel(new ItemStack(Material.PAPER), c.model()), sc, sc, sc); a.modelUsed = true; }
        else a.body = display(w, l, new ItemStack(c.mat()), 1.9f, 0.7f, 4.0f);
        a.hit = w.spawn(l, Interaction.class, i -> { i.setInteractionWidth(2.3f); i.setInteractionHeight(1.5f); i.setPersistent(false); });
        for (int k = 0; k < c.seatOff().size(); k++) {
            ArmorStand st = w.spawn(l, ArmorStand.class, s -> {
                s.setVisible(false); s.setGravity(false); s.setMarker(true); s.setSmall(true);
                s.setInvulnerable(true); s.setPersistent(false); s.setSilent(true);
            });
            a.seats.add(st);
            clickOwner.put(st.getUniqueId(), u);
        }
        active.put(u, a);
        clickOwner.put(a.horse.getUniqueId(), u);
        clickOwner.put(a.hit.getUniqueId(), u);
        a.horse.addPassenger(pl);
        U.msg(pl, "&e" + c.name() + " hazır. &7Ayarlar: /arac ayar  •  İnmek için shift.");
    }

    private ItemDisplay display(World w, Location l, ItemStack it, float sx, float sy, float sz) {
        return w.spawn(l, ItemDisplay.class, d -> {
            d.setItemStack(it);
            d.setTransformation(new Transformation(new Vector3f(0f, 0f, 0f), new AxisAngle4f(0f, 0f, 0f, 1f), new Vector3f(sx, sy, sz), new AxisAngle4f(0f, 0f, 0f, 1f)));
            d.setPersistent(false);
            d.setTeleportDuration(1);
            d.setViewRange(2f);
        });
    }

    public void remove(UUID u) {
        Active a = active.remove(u);
        if (a == null) return;
        clickOwner.remove(a.horse.getUniqueId()); clickOwner.remove(a.hit.getUniqueId());
        for (ArmorStand s : a.seats) { clickOwner.remove(s.getUniqueId()); s.remove(); }
        a.horse.remove(); a.body.remove(); a.hit.remove();
    }

    public void removeAll() { for (UUID u : new ArrayList<>(active.keySet())) remove(u); }
    public boolean hasCar(UUID u) { return active.containsKey(u); }

    private boolean canRide(Player pl, Active a) {
        if (pl.getUniqueId().equals(a.owner)) return true;
        if (a.locked) return false;
        return a.anyone || a.allowed.contains(pl.getUniqueId());
    }

    private void tick() {
        float off = (float) p.getConfig().getDouble("models.vehicle-yaw-offset", 0);
        double seatY = p.getConfig().getDouble("vehicle.seat-y", -0.15);
        for (UUID u : new ArrayList<>(active.keySet())) {
            Active a = active.get(u);
            if (!a.horse.isValid()) { remove(u); continue; }
            Location h = a.horse.getLocation();
            double r = Math.toRadians(h.getYaw()), cs = Math.cos(r), sn = Math.sin(r);
            Car c = a.car;
            Location center = h.clone().add(-(cs * c.dx() - sn * c.dz()), 0, -(sn * c.dx() + cs * c.dz()));
            Location disp = center.clone();
            disp.setYaw(a.modelUsed ? h.getYaw() + off : h.getYaw());
            a.body.teleport(disp);
            a.hit.teleport(center);
            for (int k = 0; k < a.seats.size(); k++) {
                double[] so = c.seatOff().get(k);
                Location sl = h.clone().add(cs * so[0] - sn * so[1], seatY, sn * so[0] + cs * so[1]);
                sl.setYaw(h.getYaw());
                a.seats.get(k).teleport(sl, TeleportFlag.EntityState.RETAIN_PASSENGERS);
            }
            for (Entity e : new ArrayList<>(a.horse.getPassengers()))
                if (e instanceof Player pl && !pl.getUniqueId().equals(a.owner)) a.horse.removePassenger(pl);
            for (ArmorStand st : a.seats)
                for (Entity e : new ArrayList<>(st.getPassengers()))
                    if (e instanceof Player pl && !canRide(pl, a)) { st.removePassenger(pl); U.msg(pl, "&cAraçtan atıldın."); }
        }
    }

    private void board(Player pl, Active a) {
        if (pl.getUniqueId().equals(a.owner)) {
            if (a.horse.getPassengers().contains(pl)) return;
            for (ArmorStand st : a.seats) st.removePassenger(pl);
            a.horse.addPassenger(pl);
            return;
        }
        if (!canRide(pl, a)) { U.msg(pl, "&cBu araç kilitli ya da izin verilmemiş."); return; }
        for (ArmorStand st : a.seats) if (st.getPassengers().isEmpty()) { st.addPassenger(pl); return; }
        U.msg(pl, "&cAraç dolu.");
    }

    // ------------------------------------------------------------------ sahip ayarlari
    public void toggleLock(Player pl) {
        Active a = active.get(pl.getUniqueId());
        boolean now = a == null ? !(boolean) p.data.get(pl.getUniqueId(), "car_locked", true) : !a.locked;
        if (a != null) a.locked = now;
        p.data.set(pl.getUniqueId(), "car_locked", now);
        U.msg(pl, now ? "&cAraç kilitli: sadece sen binebilirsin." : "&aAraç kilidi açıldı.");
    }

    public void toggleAnyone(Player pl) {
        Active a = active.get(pl.getUniqueId());
        boolean now = a == null ? !(boolean) p.data.get(pl.getUniqueId(), "car_anyone", false) : !a.anyone;
        if (a != null) a.anyone = now;
        p.data.set(pl.getUniqueId(), "car_anyone", now);
        U.msg(pl, now ? "&aKilit açıkken herkes yolcu olabilir." : "&eSadece izin verdiklerin yolcu olabilir.");
    }

    public void toggleAllow(Player pl, String name) {
        Player t = Bukkit.getPlayer(name);
        if (t == null) { U.msg(pl, "Oyuncu bulunamadı."); return; }
        Active a = active.get(pl.getUniqueId());
        Set<UUID> set = a != null ? a.allowed : new HashSet<>();
        if (a == null) { Object al = p.data.get(pl.getUniqueId(), "car_allowed", null); if (al instanceof List<?> li) for (Object x : li) set.add(UUID.fromString(String.valueOf(x))); }
        boolean added = set.add(t.getUniqueId());
        if (!added) set.remove(t.getUniqueId());
        List<String> out = new ArrayList<>(); for (UUID x : set) out.add(x.toString());
        p.data.set(pl.getUniqueId(), "car_allowed", out);
        U.msg(pl, added ? "&a" + t.getName() + " yolcu olabilir (araç kilitli değilse)." : "&e" + t.getName() + " izni kaldırıldı.");
    }

    private List<Player> passengers(Active a) {
        List<Player> l = new ArrayList<>();
        for (ArmorStand st : a.seats) for (Entity e : st.getPassengers()) if (e instanceof Player t) l.add(t);
        return l;
    }

    public void kick(Player pl, Player target) {
        Active a = active.get(pl.getUniqueId());
        if (a == null || target == null || target.equals(pl)) return;
        for (ArmorStand st : a.seats) if (st.getPassengers().contains(target)) {
            st.removePassenger(target); U.msg(target, "&cAraçtan atıldın."); U.msg(pl, target.getName() + " araçtan atıldı."); return;
        }
    }

    public void kickAll(Player pl) {
        Active a = active.get(pl.getUniqueId());
        if (a == null) return;
        for (Player t : passengers(a)) kick(pl, t);
    }

    public void openSettings(Player pl) {
        Active a = active.get(pl.getUniqueId());
        if (a == null) { U.msg(pl, "Önce bir araç çıkar: /telefon > Araçlar"); return; }
        Gui g = new Gui("aracayar", 36, "&8Araç Ayarları");
        for (int i = 0; i < 36; i++) g.put(i, U.icon(i < 9 ? Material.MAGENTA_STAINED_GLASS_PANE : i < 27 ? Material.PURPLE_STAINED_GLASS_PANE : Material.BLUE_STAINED_GLASS_PANE, " "), null);
        g.put(10, U.app("app_kilit", Material.IRON_DOOR, a.locked ? "&cKilit: KİLİTLİ" : "&aKilit: AÇIK", "&7Kilitliyken sadece sen binebilirsin", "&eTıkla: değiştir"), "lock");
        g.put(11, U.app("app_sosyal", Material.PLAYER_HEAD, a.anyone ? "&aHerkes yolcu olabilir" : "&eSadece izinliler yolcu olur", "&7Kilit açıkken geçerli", "&eTıkla: değiştir"), "anyone");
        g.put(13, U.app("app_cete", Material.BARRIER, "&cTüm yolcuları at"), "kickall");
        List<String> names = new ArrayList<>();
        for (UUID x : a.allowed) { var op = Bukkit.getOfflinePlayer(x); names.add("&7- " + (op.getName() == null ? x.toString().substring(0, 6) : op.getName())); }
        g.put(15, U.icon(Material.BOOK, "&eİzinli yolcular", names.isEmpty() ? new String[]{"&7(boş)  /arac izin <oyuncu>"} : names.toArray(new String[0])), null);
        int s = 19;
        for (Player t : passengers(a)) {
            if (s > 26) break;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta m = (SkullMeta) head.getItemMeta(); m.setOwningPlayer(t); m.displayName(U.L.deserialize("&e" + t.getName() + " &7(at)")); head.setItemMeta(m);
            g.put(s++, head, "kick:" + t.getName());
        }
        g.put(35, U.icon(Material.ARROW, "&7Geri"), "back");
        pl.openInventory(g.inv);
    }

    // ------------------------------------------------------------------ menuler
    public void openGarage(Player pl) {
        Gui g = new Gui("garaj", 36, "&8Garaj");
        for (int i = 0; i < 36; i++) g.put(i, U.icon(i < 18 ? Material.ORANGE_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE, " "), null);
        Map<String, Car> all = cars();
        int s = 0;
        for (String id : owned(pl.getUniqueId())) {
            Car c = all.get(id);
            if (c == null || s >= 18) continue;
            g.put(s++, U.withModel(U.icon(Material.PAPER, "&e" + c.name(), "&7Koltuk: &f" + c.seats() + " kişilik", "&7Hız: &f" + kmh(c) + " km/h", "&aTıkla: çıkar"), c.model()), "car:" + id);
        }
        g.put(30, U.app("app_arac", Material.BARRIER, "&cAracı park et"), "carpark");
        g.put(31, U.app("app_kilit", Material.IRON_DOOR, "&eAraç ayarları", "&7Kilit, yolcu izni, atma"), "settings");
        g.put(35, U.icon(Material.ARROW, "&7Geri"), "back");
        pl.openInventory(g.inv);
    }

    /** Galeri NPC'si bu menuyu acar (konsol: galerimenu <oyuncu>). */
    public void openGallery(Player pl) {
        UUID u = pl.getUniqueId();
        int lv = p.data.level(u);
        Gui g = new Gui("galeri", 54, "&8&lARAÇ GALERİSİ");
        for (int i = 0; i < 54; i++) g.put(i, U.icon(i < 9 || i >= 45 ? Material.BLACK_STAINED_GLASS_PANE : (i / 9 % 2 == 1 ? Material.GRAY_STAINED_GLASS_PANE : Material.LIGHT_GRAY_STAINED_GLASS_PANE), " "), null);
        g.put(4, U.app("app_para", Material.GOLD_INGOT, "&6Nakit: &f" + (long) p.data.money(u) + " TL", "&7Banka: &f" + (long) p.data.bank(u) + " TL", "&7Seviyen: &b" + lv), null);
        List<Car> list = new ArrayList<>(cars().values());
        list.sort(Comparator.comparingInt(Car::level));
        int[] slots = {11, 12, 14, 15, 29, 30, 32, 33};
        List<String> mine = owned(u);
        for (int i = 0; i < list.size() && i < slots.length; i++) {
            Car c = list.get(i);
            boolean has = mine.contains(c.id()), ok = lv >= c.level();
            g.put(slots[i], U.withModel(U.icon(Material.PAPER, (has ? "&a" : ok ? "&e" : "&c") + c.name(),
                    "&8━━━━━━━━━━━━━━",
                    "&7Seviye: " + (ok ? "&a✔ Lv." : "&c✖ Lv.") + c.level(),
                    "&7Koltuk: &f" + c.seats() + " kişilik",
                    "&7Hız: &f" + kmh(c) + " km/h",
                    "&7Fiyat: &6" + (long) c.price() + " TL",
                    "&8━━━━━━━━━━━━━━",
                    has ? "&a✔ Sahipsin" : ok ? "&eTıkla: satın al" : "&cSeviye " + c.level() + " gerekli"), c.model()),
                    (!has && ok) ? "pick:" + c.id() : null);
        }
        g.put(49, U.app("app_kapat", Material.BARRIER, "&cKapat"), "close");
        pl.openInventory(g.inv);
    }

    private void openConfirm(Player pl, String id) {
        Car c = cars().get(id);
        if (c == null) return;
        Gui g = new Gui("galeri-onay", 27, "&8Satın alma onayı");
        for (int i = 0; i < 27; i++) g.put(i, U.icon(Material.BLACK_STAINED_GLASS_PANE, " "), null);
        g.put(13, U.withModel(U.icon(Material.PAPER, "&e" + c.name(), "&7Koltuk: &f" + c.seats(), "&7Hız: &f" + kmh(c) + " km/h", "&7Fiyat: &6" + (long) c.price() + " TL"), c.model()), null);
        g.put(11, U.icon(Material.LIME_CONCRETE, "&a&lSATIN AL", "&7-" + (long) c.price() + " TL"), "yes:" + id);
        g.put(15, U.icon(Material.RED_CONCRETE, "&c&lİPTAL"), "no");
        pl.openInventory(g.inv);
    }

    @EventHandler public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Gui g)) return;
        if (!g.page.equals("garaj") && !g.page.equals("galeri") && !g.page.equals("galeri-onay") && !g.page.equals("aracayar")) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player pl)) return;
        String a = g.act.get(e.getRawSlot());
        if (a == null) return;
        if (a.startsWith("car:")) { pl.closeInventory(); spawn(pl, a.substring(4), false); }
        else if (a.equals("carpark")) { remove(pl.getUniqueId()); pl.closeInventory(); }
        else if (a.equals("settings")) openSettings(pl);
        else if (a.equals("lock")) { toggleLock(pl); openSettings(pl); }
        else if (a.equals("anyone")) { toggleAnyone(pl); openSettings(pl); }
        else if (a.equals("kickall")) { kickAll(pl); openSettings(pl); }
        else if (a.startsWith("kick:")) { kick(pl, Bukkit.getPlayer(a.substring(5))); openSettings(pl); }
        else if (a.equals("back")) p.phone.open(pl);
        else if (a.equals("close")) pl.closeInventory();
        else if (a.startsWith("pick:")) openConfirm(pl, a.substring(5));
        else if (a.equals("no")) openGallery(pl);
        else if (a.startsWith("yes:")) { buy(pl, a.substring(4)); openGallery(pl); }
    }

    // ------------------------------------------------------------------ olaylar
    @EventHandler public void onMount(EntityMountEvent e) {
        UUID owner = clickOwner.get(e.getMount().getUniqueId());
        if (owner == null || !(e.getEntity() instanceof Player pl)) return;
        Active a = active.get(owner);
        if (a == null) return;
        boolean isHorse = e.getMount().getUniqueId().equals(a.horse.getUniqueId());
        if (isHorse ? !pl.getUniqueId().equals(owner) : !canRide(pl, a)) { e.setCancelled(true); U.msg(pl, "&cBu araca binemezsin (kilitli ya da izin yok)."); }
    }

    @EventHandler public void onInteractCar(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        UUID owner = clickOwner.get(e.getRightClicked().getUniqueId());
        if (owner == null) return;
        e.setCancelled(true);
        Active a = active.get(owner);
        if (a != null) board(e.getPlayer(), a);
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) { remove(e.getPlayer().getUniqueId()); }

    @EventHandler public void onShopBlock(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        if (!(e.getClickedBlock().getState() instanceof TileState ts)) return;
        String t = ts.getPersistentDataContainer().get(U.SHOP, PersistentDataType.STRING);
        if (t == null) return;
        e.setCancelled(true);
        if (t.equals("jenerator")) U.msg(e.getPlayer(), "Jeneratör almak için kendi arsanda &e/jenerator al &fyaz.");
    }
}
