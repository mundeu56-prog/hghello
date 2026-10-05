package dev.apocalypse;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.concurrent.ThreadLocalRandom;

/** 몬스터에게 체력/속도/추적/장비 강화를 적용한다. */
public final class MobBuffer {

    private static final Material[] HELMETS = {Material.LEATHER_HELMET, Material.CHAINMAIL_HELMET,
            Material.IRON_HELMET, Material.DIAMOND_HELMET, Material.NETHERITE_HELMET};
    private static final Material[] CHESTPLATES = {Material.LEATHER_CHESTPLATE, Material.CHAINMAIL_CHESTPLATE,
            Material.IRON_CHESTPLATE, Material.DIAMOND_CHESTPLATE, Material.NETHERITE_CHESTPLATE};
    private static final Material[] LEGGINGS = {Material.LEATHER_LEGGINGS, Material.CHAINMAIL_LEGGINGS,
            Material.IRON_LEGGINGS, Material.DIAMOND_LEGGINGS, Material.NETHERITE_LEGGINGS};
    private static final Material[] BOOTS = {Material.LEATHER_BOOTS, Material.CHAINMAIL_BOOTS,
            Material.IRON_BOOTS, Material.DIAMOND_BOOTS, Material.NETHERITE_BOOTS};
    private static final Material[] SWORDS = {Material.WOODEN_SWORD, Material.STONE_SWORD,
            Material.IRON_SWORD, Material.DIAMOND_SWORD, Material.NETHERITE_SWORD};
    private static final Material[] AXES = {null, null,
            Material.IRON_AXE, Material.DIAMOND_AXE, Material.NETHERITE_AXE};

    private final DifficultyPlugin plugin;
    private final NamespacedKey key;

    public MobBuffer(DifficultyPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "buffed");
    }

    /** 강화 대상 몬스터인지 (보스/워든 제외) */
    public static boolean isApocalypseMob(Entity e) {
        return e instanceof Mob && e instanceof Enemy && !(e instanceof Boss) && !(e instanceof Warden);
    }

    public double damageMultiplier(double n) {
        double max = plugin.getConfig().getDouble("scaling.damage-multiplier-at-max", 6.0);
        return 1.0 + n * (max - 1.0);
    }

    public void apply(Mob mob, double n) {
        var pdc = mob.getPersistentDataContainer();
        if (pdc.has(key, PersistentDataType.BYTE)) return;
        pdc.set(key, PersistentDataType.BYTE, (byte) 1);

        var cfg = plugin.getConfig();

        // 체력
        double hpMul = 1.0 + n * (cfg.getDouble("scaling.health-multiplier-at-max", 12.0) - 1.0);
        AttributeInstance hp = mob.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) {
            hp.setBaseValue(Math.min(1000.0, hp.getBaseValue() * hpMul));
            mob.setHealth(hp.getBaseValue());
        }

        // 이동 속도
        double spdMul = 1.0 + n * (cfg.getDouble("scaling.speed-multiplier-at-max", 1.35) - 1.0);
        AttributeInstance spd = mob.getAttribute(Attribute.MOVEMENT_SPEED);
        if (spd != null) spd.setBaseValue(spd.getBaseValue() * spdMul);

        // 추적 범위
        double followMax = cfg.getDouble("scaling.follow-range-at-max", 100.0);
        AttributeInstance follow = mob.getAttribute(Attribute.FOLLOW_RANGE);
        if (follow != null) {
            follow.setBaseValue(Math.max(follow.getBaseValue(), 35.0 + n * (followMax - 35.0)));
        }

        // 넉백 저항
        AttributeInstance kb = mob.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        if (kb != null) kb.setBaseValue(Math.min(0.9, kb.getBaseValue() + n * 0.6));

        // 언데드가 햇빛에 안 타게 (난이도 12 이후)
        if (n >= 0.12 && (mob instanceof Zombie || mob instanceof AbstractSkeleton)) {
            mob.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE,
                    PotionEffect.INFINITE_DURATION, 0, false, false));
        }

        // 크리퍼 강화
        if (mob instanceof Creeper creeper) {
            creeper.setExplosionRadius(3 + (int) Math.floor(n * 5));
            creeper.setMaxFuseTicks(30 - (int) Math.floor(n * 18));
            if (n > 0.7 && ThreadLocalRandom.current().nextDouble() < 0.4) creeper.setPowered(true);
        }

        equip(mob, n);
    }

    private void equip(Mob mob, double n) {
        EntityEquipment eq = mob.getEquipment();
        if (eq == null) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();

        int tier = (int) Math.floor(n * 5 + r.nextDouble(-0.8, 0.6));
        tier = Math.max(0, Math.min(4, tier));

        boolean wearsArmor = mob instanceof Zombie || mob instanceof AbstractSkeleton;
        if (wearsArmor) {
            double chance = Math.min(1.0, 0.15 + n);
            if (r.nextDouble() < chance) eq.setHelmet(armor(HELMETS[tier], n, r));
            if (r.nextDouble() < chance) eq.setChestplate(armor(CHESTPLATES[tier], n, r));
            if (r.nextDouble() < chance) eq.setLeggings(armor(LEGGINGS[tier], n, r));
            if (r.nextDouble() < chance) eq.setBoots(armor(BOOTS[tier], n, r));
            eq.setHelmetDropChance(0.03f);
            eq.setChestplateDropChance(0.03f);
            eq.setLeggingsDropChance(0.03f);
            eq.setBootsDropChance(0.03f);
        }

        ItemStack weapon = null;
        if (mob instanceof WitherSkeleton || mob instanceof Zombie || mob instanceof Vindicator) {
            if (r.nextDouble() < Math.min(1.0, 0.35 + n * 1.2)) weapon = melee(tier, n, r);
        } else if (mob instanceof AbstractSkeleton) {
            weapon = bow(n);
        } else if (mob instanceof Pillager) {
            weapon = crossbow(n);
        }
        if (weapon != null) {
            eq.setItemInMainHand(weapon);
            eq.setItemInMainHandDropChance(0.03f);
        }
    }

    private ItemStack armor(Material m, double n, ThreadLocalRandom r) {
        ItemStack it = new ItemStack(m);
        int prot = Math.min(4, (int) Math.floor(n * 5));
        if (prot > 0) it.addUnsafeEnchantment(Enchantment.PROTECTION, prot);
        if (n > 0.55) it.addUnsafeEnchantment(Enchantment.THORNS, 1 + r.nextInt(3));
        return it;
    }

    private ItemStack melee(int tier, double n, ThreadLocalRandom r) {
        Material m = SWORDS[tier];
        if (AXES[tier] != null && r.nextDouble() < 0.3) m = AXES[tier];
        ItemStack it = new ItemStack(m);
        int sharp = Math.min(5, (int) Math.floor(n * 6));
        if (sharp > 0) it.addUnsafeEnchantment(Enchantment.SHARPNESS, sharp);
        if (n > 0.5) it.addUnsafeEnchantment(Enchantment.FIRE_ASPECT, n > 0.8 ? 2 : 1);
        if (n > 0.6) it.addUnsafeEnchantment(Enchantment.KNOCKBACK, 1);
        return it;
    }

    private ItemStack bow(double n) {
        ItemStack it = new ItemStack(Material.BOW);
        int power = Math.min(5, (int) Math.floor(n * 6));
        if (power > 0) it.addUnsafeEnchantment(Enchantment.POWER, power);
        if (n > 0.5) it.addUnsafeEnchantment(Enchantment.PUNCH, n > 0.8 ? 2 : 1);
        if (n > 0.35) it.addUnsafeEnchantment(Enchantment.FLAME, 1);
        return it;
    }

    private ItemStack crossbow(double n) {
        ItemStack it = new ItemStack(Material.CROSSBOW);
        int quick = Math.min(3, (int) Math.floor(n * 4));
        if (quick > 0) it.addUnsafeEnchantment(Enchantment.QUICK_CHARGE, quick);
        if (n > 0.6) it.addUnsafeEnchantment(Enchantment.MULTISHOT, 1);
        return it;
    }
}
