/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kernitus.plugin.OldCombatMechanics.module;

import kernitus.plugin.OldCombatMechanics.OCMMain;
import kernitus.plugin.OldCombatMechanics.utilities.Messenger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import static kernitus.plugin.OldCombatMechanics.versions.materials.MaterialRegistry.ENCHANTED_GOLDEN_APPLE;

/**
 * Customise the golden apple effects.
 */
public class ModuleGoldenApple extends OCMModule {

    private List<PotionEffect> enchantedGoldenAppleEffects, goldenAppleEffects;
    private ShapedRecipe enchantedAppleRecipe;

    private Map<UUID, LastEaten> lastEaten;
    private Map<UUID, Collection<PotionEffect>> previousPotionEffects;
    private Cooldown cooldown;

    private String normalCooldownMessage, enchantedCooldownMessage;

    public ModuleGoldenApple(OCMMain plugin) {
        super(plugin, "old-golden-apples");
    }

    @SuppressWarnings("deprecated")
    @Override
    public void reload() {
        normalCooldownMessage = module().getString("cooldown.message-normal");
        enchantedCooldownMessage = module().getString("cooldown.message-enchanted");

        cooldown = new Cooldown(
                module().getLong("cooldown.normal"),
                module().getLong("cooldown.enchanted"),
                module().getBoolean("cooldown.is-shared")
        );
        lastEaten = new WeakHashMap<>();
        previousPotionEffects = new WeakHashMap<>();

        enchantedGoldenAppleEffects = getPotionEffects("napple");
        goldenAppleEffects = getPotionEffects("gapple");

        try {
            enchantedAppleRecipe = new ShapedRecipe(
                    new NamespacedKey(plugin, "MINECRAFT"),
                    ENCHANTED_GOLDEN_APPLE.newInstance()
            );
        } catch (NoClassDefFoundError e) {
            enchantedAppleRecipe = new ShapedRecipe(ENCHANTED_GOLDEN_APPLE.newInstance());
        }
        enchantedAppleRecipe
                .shape("ggg", "gag", "ggg")
                .setIngredient('g', Material.GOLD_BLOCK)
                .setIngredient('a', Material.APPLE);

        registerCrafting();
    }

    private void registerCrafting() {
        if (isEnabled() && module().getBoolean("enchanted-golden-apple-crafting")) {
            if (Bukkit.getRecipesFor(ENCHANTED_GOLDEN_APPLE.newInstance()).size() > 0) return;
            Bukkit.addRecipe(enchantedAppleRecipe);
            debug("Added napple recipe");
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareItemCraft(PrepareItemCraftEvent e) {
        final ItemStack item = e.getInventory().getResult();
        if (item == null)
            return; // This should never ever ever ever run. If it does then you probably screwed something up.

        if (ENCHANTED_GOLDEN_APPLE.isSame(item)) {
            final World world = e.getView().getPlayer().getWorld();

            if (isSettingEnabled("no-conflict-mode")) return;

            if (!isEnabled(world) || !isSettingEnabled("enchanted-golden-apple-crafting"))
                e.getInventory().setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemConsume(PlayerItemConsumeEvent e) {
        final Player player = e.getPlayer();

        if (!isEnabled(player.getWorld())) return;

        final ItemStack originalItem = e.getItem();
        final Material consumedMaterial = originalItem.getType();

        if (consumedMaterial != Material.GOLDEN_APPLE &&
                !ENCHANTED_GOLDEN_APPLE.isSame(originalItem)) return;

        final UUID uuid = player.getUniqueId();

        // Check if the cooldown has expired yet
        lastEaten.putIfAbsent(uuid, new LastEaten());

        // If on cooldown send appropriate cooldown message
        if (cooldown.isOnCooldown(originalItem, lastEaten.get(uuid))) {
            final LastEaten le = lastEaten.get(uuid);

            final long baseCooldown;
            Instant current;
            final String message;

            if (consumedMaterial == Material.GOLDEN_APPLE) {
                baseCooldown = cooldown.normal;
                current = le.lastNormalEaten;
                message = normalCooldownMessage;
            } else {
                baseCooldown = cooldown.enchanted;
                current = le.lastEnchantedEaten;
                message = enchantedCooldownMessage;
            }

            final Optional<Instant> newestEatTime = le.getNewestEatTime();
            if (cooldown.sharedCooldown && newestEatTime.isPresent())
                current = newestEatTime.get();

            final long seconds = baseCooldown - (Instant.now().getEpochSecond() - current.getEpochSecond());

            if (message != null && !message.isEmpty())
                Messenger.sendNormalMessage(player, message.replaceAll("%seconds%", String.valueOf(seconds)));

            e.setCancelled(true);
            return;
        }

        lastEaten.get(uuid).setForItem(originalItem);

        if (!isSettingEnabled("old-potion-effects")) return;

        // Save player's current potion effects
        previousPotionEffects.put(uuid, player.getActivePotionEffects());

        final List<PotionEffect> newEffects = ENCHANTED_GOLDEN_APPLE.isSame(originalItem) ?
                enchantedGoldenAppleEffects : goldenAppleEffects;

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (previousPotionEffects.containsKey(uuid)) {
                final Collection<PotionEffect> previousEffects = previousPotionEffects.get(uuid);
                final Set<PotionEffectType> previousTypes = previousEffects.stream().map(PotionEffect::getType).collect(Collectors.toSet());
                // Remove extraneous potion effects, in case we don't want some of the default effects
                player.getActivePotionEffects().stream()
                        .map(PotionEffect::getType)
                        .filter(type -> !previousTypes.contains(type))
                        .forEach(player::removePotionEffect);
                // Add previous potion effects from before eating the apple
                // This overrides existing effects, including downgrading amplifier if necessary
                player.addPotionEffects(previousEffects);
                // Add new custom effects from eating the apple
                applyEffects(player, newEffects);
            } else {
                Messenger.warn("Could not find previous potion effects for player " + player.getName());
            }
        }, 1L);
    }

    private void applyEffects(LivingEntity target, List<PotionEffect> effects) {
        for (PotionEffect effect : effects) {
            final OptionalInt maxActiveAmplifier = target.getActivePotionEffects().stream()
                    .filter(potionEffect -> potionEffect.getType() == effect.getType())
                    .mapToInt(PotionEffect::getAmplifier)
                    .max();

            // If active effect stronger, do not apply weaker one
            if (maxActiveAmplifier.orElse(-1) > effect.getAmplifier()) continue;

            // If active effect weaker, remove it and apply new one
            maxActiveAmplifier.ifPresent(ignored -> target.removePotionEffect(effect.getType()));

            target.addPotionEffect(effect);
        }
    }

    private List<PotionEffect> getPotionEffects(String apple) {
        final List<PotionEffect> appleEffects = new ArrayList<>();

        final ConfigurationSection sect = module().getConfigurationSection(apple + "-effects");
        for (String key : sect.getKeys(false)) {
            final int duration = sect.getInt(key + ".duration");
            final int amplifier = sect.getInt(key + ".amplifier");

            final PotionEffectType type = PotionEffectType.getByName(key);
            Objects.requireNonNull(type, String.format("Invalid potion effect type '%s'!", key));

            final PotionEffect fx = new PotionEffect(type, duration, amplifier);
            appleEffects.add(fx);
        }
        return appleEffects;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        if (lastEaten != null) lastEaten.remove(e.getPlayer().getUniqueId());
    }

    private static class LastEaten {
        private Instant lastNormalEaten;
        private Instant lastEnchantedEaten;

        private Optional<Instant> getForItem(ItemStack item) {
            return ENCHANTED_GOLDEN_APPLE.isSame(item)
                    ? Optional.ofNullable(lastEnchantedEaten)
                    : Optional.ofNullable(lastNormalEaten);
        }

        private Optional<Instant> getNewestEatTime() {
            if (lastEnchantedEaten == null) {
                return Optional.ofNullable(lastNormalEaten);
            }
            if (lastNormalEaten == null) {
                return Optional.of(lastEnchantedEaten);
            }
            return Optional.of(
                    lastNormalEaten.compareTo(lastEnchantedEaten) < 0 ? lastEnchantedEaten : lastNormalEaten
            );
        }

        private void setForItem(ItemStack item) {
            if (ENCHANTED_GOLDEN_APPLE.isSame(item)) {
                lastEnchantedEaten = Instant.now();
            } else {
                lastNormalEaten = Instant.now();
            }
        }
    }

    private static class Cooldown {
        private final long normal;
        private final long enchanted;
        private final boolean sharedCooldown;

        Cooldown(long normal, long enchanted, boolean sharedCooldown) {
            this.normal = normal;
            this.enchanted = enchanted;
            this.sharedCooldown = sharedCooldown;
        }

        private long getCooldownForItem(ItemStack item) {
            return ENCHANTED_GOLDEN_APPLE.isSame(item) ? enchanted : normal;
        }

        boolean isOnCooldown(ItemStack item, LastEaten lastEaten) {
            return (sharedCooldown ? lastEaten.getNewestEatTime() : lastEaten.getForItem(item))
                    .map(it -> ChronoUnit.SECONDS.between(it, Instant.now()))
                    .map(it -> it < getCooldownForItem(item))
                    .orElse(false);
        }
    }
}
