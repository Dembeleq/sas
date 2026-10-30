package tr.chicagorp;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/** Cete etiketi: isimden AYRI, sembollu ("[✦ CETE] Isim") - kafa ustu, tab listesi ve sohbette. */
public class Tags implements Listener {
    private final ChicagoRP p;
    public Tags(ChicagoRP p) { this.p = p; }

    private String prefixFor(String gang) {
        return p.getConfig().getString("gang.tag-format", "&8[&c{symbol} &f{gang}&8] ")
                .replace("{symbol}", p.gang.symbol(gang)).replace("{gang}", gang);
    }

    public void refresh(Player pl) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        String gang = p.gang.gangOf(pl.getUniqueId());
        String want = gang == null ? null : "rpg_" + Integer.toHexString(gang.hashCode());
        for (Team t : sb.getTeams()) if (t.getName().startsWith("rpg_") && t.hasEntry(pl.getName()) && !t.getName().equals(want)) t.removeEntry(pl.getName());
        if (want == null) return;
        Team t = sb.getTeam(want);
        if (t == null) t = sb.registerNewTeam(want);
        t.prefix(U.L.deserialize(prefixFor(gang)));
        if (!t.hasEntry(pl.getName())) t.addEntry(pl.getName());
    }

    public void refreshAll() { for (Player pl : Bukkit.getOnlinePlayers()) refresh(pl); }

    @EventHandler public void onJoin(PlayerJoinEvent e) { Bukkit.getScheduler().runTaskLater(p, () -> refresh(e.getPlayer()), 5L); }

    @EventHandler public void onChat(AsyncChatEvent e) {
        String gang = p.gang.gangOf(e.getPlayer().getUniqueId());
        Component tag = gang == null ? Component.empty() : U.L.deserialize(prefixFor(gang));
        e.renderer(ChatRenderer.viewerUnaware((source, name, msg) -> tag.append(name).append(U.L.deserialize(" &8» &f")).append(msg)));
    }
}
