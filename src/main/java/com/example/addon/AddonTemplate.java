package com.example.addon;

import meteordevelopment.meteorclient.systems.modules.Modules;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import org.slf4j.Logger;

public class AddonTemplate extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();

    @Override
    public void onInitialize() {
        LOG.info("Initializing AddonTemplate");

        // Registra os módulos na raiz do pacote
        Modules.get().add(new AutoMace());
        Modules.get().add(new XbowCart());
    }

    @Override
    public void onRegisterCategories() {
        // Categorias
    }

    @Override
    public String getPackage() {
        return "com.example.addon";
    }
}
