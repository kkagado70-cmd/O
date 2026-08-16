package com.example.addon;

import com.example.addon.modules.XbowCart;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AddonTemplate extends MeteorAddon {
    public static final Logger LOG = LoggerFactory.getLogger("XbowAddon");
    public static final Category CATEGORY = new Category("CPVP");

    @Override
    public void onInitialize() {
        LOG.info("Inicializando Xbow Cart Addon...");

        // Registra o seu modulo dentro do Meteor Client
        Modules.get().add(new XbowCart());
    }

    @Override
    public String getPackage() {
        return "com.example.addon";
    }
}        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.example.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("MeteorDevelopment", "meteor-addon-template");
    }
}
