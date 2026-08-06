package dev.maf.norefunds;

import org.bukkit.plugin.java.JavaPlugin;

public final class NoRefunds extends JavaPlugin {

    private static NoRefunds instance;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(new TradeNormalizer(this), this);
        getLogger().info("NoRefunds enabled. Mending books floor-priced, never removed.");
    }

    @Override
    public void onDisable() {
        getLogger().info("NoRefunds disabled.");
    }

    public static NoRefunds getInstance() {
        return instance;
    }
}