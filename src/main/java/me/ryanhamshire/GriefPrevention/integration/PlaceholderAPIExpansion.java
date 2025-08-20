package me.ryanhamshire.GriefPrevention.integration;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.List;

public class PlaceholderAPIExpansion extends PlaceholderExpansion {

    private final GriefPrevention gp;

    public PlaceholderAPIExpansion(GriefPrevention gp) {
        this.gp = gp;
    }

    @Override public String getIdentifier() { return "gp3d"; }
    @Override public String getAuthor() { return String.join(", ", gp.getDescription().getAuthors()); }
    @Override public String getVersion() { return gp.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }
    @Override public boolean canRegister() { return Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null; }

    @Override
    public String onRequest(OfflinePlayer off, String params) {
        Player p = off == null ? null : off.getPlayer();
        if (p == null) return "";

        Location loc = p.getLocation();
        Claim top = gp.dataStore.getClaimAt(loc, false, null);
        Claim inner = findInnermost(top, loc);

        switch (params.toLowerCase()) {
            case "in_subdivision":
                return inner != null && inner.parent != null ? "true" : "false";
            case "in_3d_subdivision":
                return is3D(inner) ? "true" : "false";
            default:
                return "";
        }
    }

    private Claim findInnermost(Claim claim, Location loc) {
        if (claim == null || !contains(claim, loc)) return null;
        Claim deepest = claim;
        List<Claim> kids = claim.children;
        if (kids != null) {
            for (Claim child : kids) {
                Claim inner = findInnermost(child, loc);
                if (inner != null) deepest = inner;
            }
        }
        return deepest;
    }

    private boolean contains(Claim c, Location l) {
        if (c == null || l == null) return false;
        World w = l.getWorld();
        if (w == null || !c.getLesserBoundaryCorner().getWorld().equals(w)) return false;

        int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
        int minX = Math.min(c.getLesserBoundaryCorner().getBlockX(), c.getGreaterBoundaryCorner().getBlockX());
        int maxX = Math.max(c.getLesserBoundaryCorner().getBlockX(), c.getGreaterBoundaryCorner().getBlockX());
        int minZ = Math.min(c.getLesserBoundaryCorner().getBlockZ(), c.getGreaterBoundaryCorner().getBlockZ());
        int maxZ = Math.max(c.getLesserBoundaryCorner().getBlockZ(), c.getGreaterBoundaryCorner().getBlockZ());

        boolean is3d = is3D(c);
        int minY = is3d ? Math.min(c.getLesserBoundaryCorner().getBlockY(), c.getGreaterBoundaryCorner().getBlockY()) : w.getMinHeight();
        int maxY = is3d ? Math.max(c.getLesserBoundaryCorner().getBlockY(), c.getGreaterBoundaryCorner().getBlockY()) : w.getMaxHeight();

        return x >= minX && x <= maxX && z >= minZ && z <= maxZ && y >= minY && y <= maxY;
    }

    private boolean is3D(Claim c) {
        if (c == null) return false;
        try {
            Method m = c.getClass().getMethod("isCuboid");
            Object out = m.invoke(c);
            if (out instanceof Boolean) return (Boolean) out;
        } catch (Throwable ignored) {}
        // fallback heuristic
        int minY = Math.min(c.getLesserBoundaryCorner().getBlockY(), c.getGreaterBoundaryCorner().getBlockY());
        int maxY = Math.max(c.getLesserBoundaryCorner().getBlockY(), c.getGreaterBoundaryCorner().getBlockY());
        World w = c.getLesserBoundaryCorner().getWorld();
        return !(minY <= w.getMinHeight() && maxY >= w.getMaxHeight());
    }
}
