package com.ganovism.addon;

import com.ganovism.addon.hud.HudExample;
import com.ganovism.addon.modules.AirPocketFinder;
import com.ganovism.addon.modules.CoordCopier;
import com.ganovism.addon.modules.CrystalMacro;
import com.ganovism.addon.modules.FastCrystal;
import com.ganovism.addon.modules.LegitTotem;
import com.ganovism.addon.modules.RotatedDeepslateFinder;
import com.ganovism.addon.modules.BatteryESP;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class GanovismAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Ganovism Addon");
    public static final HudGroup HUD_GROUP = new HudGroup("Ganovism Addon");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Ganovism Addon");

        // Modules
        Modules.get().add(new AirPocketFinder());
        Modules.get().add(new CoordCopier());
        Modules.get().add(new CrystalMacro());
        Modules.get().add(new FastCrystal());
        Modules.get().add(new LegitTotem());
        Modules.get().add(new RotatedDeepslateFinder());
        Modules.get().add(new BatteryESP());
        // Commands (if you have any commands, add them here)
        // Commands.add(new CommandExample());

        // HUD Elements
        Hud.get().register(HudExample.INFO);
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.ganovism.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("YourGithubUserOrOrg", "YourRepoName");  // Replace with your actual repo info
    }
}
