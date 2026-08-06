package dev.maf.norefunds;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.event.entity.VillagerReplenishTradeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

public final class TradeNormalizer implements Listener {

    private final boolean removeMode;
    private final int bookPrice;
    private final int mendingMinPrice;
    private final boolean nullifyDiscounts;

    // Enchant-level rebalance (non-Mending books).
    private final boolean levelRoll;
    private final double maxTierChance;
    private final double levelBias;
    private final int levelPriceStep;

    public TradeNormalizer(NoRefunds plugin) {
        this.removeMode = "remove".equalsIgnoreCase(plugin.getConfig().getString("mode", "price"));
        this.bookPrice = plugin.getConfig().getInt("enchanted-book-price", 64);
        this.mendingMinPrice = plugin.getConfig().getInt("mending-min-price", 14);
        this.nullifyDiscounts = plugin.getConfig().getBoolean("nullify-discounts", true);
        this.levelRoll = plugin.getConfig().getBoolean("enchant-level-roll", true);
        this.maxTierChance = plugin.getConfig().getDouble("max-tier-chance", 0.05);
        this.levelBias = Math.max(0.0, plugin.getConfig().getDouble("level-bias", 2.0));
        this.levelPriceStep = Math.max(0, plugin.getConfig().getInt("level-price-step", 6));
    }

    // ---------------------------------------------------------------- events

    @EventHandler
    public void onAcquire(VillagerAcquireTradeEvent event) {
        MerchantRecipe normalized = normalize(event.getRecipe());
        if (normalized == null) {
            event.setCancelled(true);
            return;
        }
        event.setRecipe(normalized);
    }

    @EventHandler
    public void onReplenish(VillagerReplenishTradeEvent event) {
        MerchantRecipe normalized = normalize(event.getRecipe());
        if (normalized == null) {
            event.setCancelled(true);
            return;
        }
        event.setRecipe(normalized);
    }

    // Existing villagers already have their trades rolled. Normalize the whole
    // list the moment a player right-clicks, before the trade UI opens.
    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof Villager villager) {
            normalizeVillager(villager);
        }
    }

    // ------------------------------------------------------------- normalize

    /**
     * Returns a normalized copy of the recipe, or the original recipe if it
     * needs no changes. Returns null when the trade must be removed entirely
     * (remove mode). Only enchanted BOOK trades are touched - everything else
     * (gear, plain items) passes through untouched.
     *
     * The MENDING book trade is never removed, but it is never allowed to be
     * trivially cheap either: if its price falls below `mending-min-price` it
     * is set to mending-min-price + a random 1-8, so Mending stays obtainable
     * without being a 1-emerald handout.
     *
     * Non-Mending enchanted books are re-rolled toward LOWER enchantment
     * levels (the max level only has a small chance to appear) and priced
     * relative to how far the rolled level sits below the enchant's max - the
     * closer to the top tier, the more it costs.
     */
    private MerchantRecipe normalize(MerchantRecipe recipe) {
        ItemStack result = recipe.getResult();
        if (result == null || !isEnchantedBook(result)) {
            return recipe;
        }

        boolean mending = isMendingBook(result);
        int currentPrice = emeraldPrice(recipe);

        if (removeMode && !mending) {
            return null;
        }

        Enchantment enchant = firstEnchantment(result);
        int maxLevel = enchant == null ? 1 : enchant.getMaxLevel();
        int rolledLevel = enchant == null ? 1 : enchantLevel(result, enchant);

        // Will the enchant level actually be re-rolled?
        boolean reroll = !mending && levelRoll && enchant != null && maxLevel > 1;
        if (reroll) {
            rolledLevel = rollLevel(maxLevel, maxTierChance, levelBias);
        }

        // Engages a copy if: discounts must be scrubbed, a price goal is
        // active, or the book's enchant level actually changed.
        boolean priceGoal = mending ? currentPrice < mendingMinPrice : bookPrice > 0;
        boolean levelChanged = reroll && rolledLevel != enchantLevel(result, enchant);
        if (!nullifyDiscounts && !priceGoal && !levelChanged) {
            return recipe;
        }

        // Build the copy via getters, NOT the MerchantRecipe(MerchantRecipe)
        // copy constructor. CraftMerchantRecipe (Paper's impl) keeps the
        // superclass fields at their defaults (maxUses=0, experienceReward=
        // false, priceMultiplier=0) and only overrides the getters to delegate
        // to the NMS handle - so the copy ctor would silently zero maxUses and
        // friends, leaving every normalized trade permanently sold out.
        ItemStack newResult = levelChanged ? reEnchantBook(result, enchant, rolledLevel) : result;

        MerchantRecipe copy = new MerchantRecipe(
                newResult,
                recipe.getUses(),
                recipe.getMaxUses(),
                recipe.hasExperienceReward(),
                recipe.getVillagerExperience(),
                recipe.getPriceMultiplier(),
                recipe.getDemand(),
                recipe.getSpecialPrice(),
                recipe.shouldIgnoreDiscounts());
        copy.setIngredients(recipe.getIngredients());

        if (nullifyDiscounts) {
            copy.setIgnoreDiscounts(true);
            copy.setSpecialPrice(0);
            copy.setDemand(0);
        }

        int price;
        if (mending) {
            price = (currentPrice < mendingMinPrice)
                    ? mendingMinPrice + ThreadLocalRandom.current().nextInt(1, 9)
                    : currentPrice;
        } else if (reroll) {
            // Mending perf: if level didn't change, we corrected nothing about
            // the level, but a re-roll was requested; still price by the (same)
            // level. For non-Mending with no re-roll, fall through to bookPrice.
            price = levelPrice(rolledLevel, maxLevel);
        } else {
            price = bookPrice > 0 ? bookPrice : currentPrice;
        }

        setEmeraldPrice(copy, price);
        return copy;
    }

    // ---------------------------------------------------------------- checks

    private static boolean isMendingBook(ItemStack item) {
        if (item.getType() != Material.ENCHANTED_BOOK) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta instanceof EnchantmentStorageMeta storage
                && storage.hasStoredEnchant(Enchantment.MENDING);
    }

    private static boolean isEnchantedBook(ItemStack item) {
        if (item.getType() != Material.ENCHANTED_BOOK) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta instanceof EnchantmentStorageMeta storage && storage.hasStoredEnchants();
    }

    private static Enchantment firstEnchantment(ItemStack book) {
        ItemMeta meta = book.getItemMeta();
        if (!(meta instanceof EnchantmentStorageMeta storage)) {
            return null;
        }
        for (Map.Entry<Enchantment, Integer> e : storage.getStoredEnchants().entrySet()) {
            return e.getKey();
        }
        return null;
    }

    private static int enchantLevel(ItemStack book, Enchantment enchant) {
        ItemMeta meta = book.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta storage) {
            return storage.getStoredEnchantLevel(enchant);
        }
        return 0;
    }

    private static ItemStack reEnchantBook(ItemStack book, Enchantment enchant, int level) {
        ItemStack copy = book.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta storage) {
            if (storage.hasStoredEnchant(enchant)) {
                storage.removeStoredEnchant(enchant);
            }
            storage.addStoredEnchant(enchant, level, true);
            copy.setItemMeta(storage);
        }
        return copy;
    }

    /**
     * Rolls an enchantment level, heavily biased toward low tiers. The max
     * level still gets `maxTierChance` chance to appear; the rest of the time
     * a level in [1, max-1] is drawn with weight (max - level)^levelBias so the
     * floor is far more likely than anything near the cap.
     */
    private static int rollLevel(int maxLevel, double maxTierChance, double levelBias) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (maxLevel <= 1) {
            return 1;
        }
        if (rnd.nextDouble() < maxTierChance) {
            return maxLevel;
        }
        double total = 0.0;
        for (int l = 1; l < maxLevel; l++) {
            total += Math.pow(maxLevel - l, levelBias);
        }
        double pick = rnd.nextDouble() * total;
        for (int l = 1; l < maxLevel; l++) {
            pick -= Math.pow(maxLevel - l, levelBias);
            if (pick <= 0) {
                return l;
            }
        }
        return maxLevel - 1;
    }

    /** Price scales down from bookPrice the further the level sits below max. */
    private int levelPrice(int level, int maxLevel) {
        if (maxLevel <= 1) {
            return bookPrice;
        }
        int below = Math.max(0, maxLevel - level);
        return Math.max(1, bookPrice - below * levelPriceStep);
    }

    private static int emeraldPrice(MerchantRecipe recipe) {
        for (ItemStack ingredient : recipe.getIngredients()) {
            if (ingredient.getType() == Material.EMERALD) {
                return ingredient.getAmount();
            }
        }
        return 0;
    }

    private static void setEmeraldPrice(MerchantRecipe recipe, int price) {
        List<ItemStack> ingredients = new ArrayList<>();
        boolean priced = false;
        for (ItemStack ingredient : recipe.getIngredients()) {
            ItemStack item = ingredient.clone();
            if (!priced && item.getType() == Material.EMERALD) {
                item.setAmount(Math.min(Math.max(price, 1), 64));
                priced = true;
            }
            ingredients.add(item);
        }
        if (!priced && !ingredients.isEmpty()) {
            ItemStack first = ingredients.get(0);
            first.setAmount(Math.min(Math.max(price, 1), 64));
        }
        recipe.setIngredients(ingredients);
    }

    private void normalizeVillager(Villager villager) {
        List<MerchantRecipe> original = villager.getRecipes();
        List<MerchantRecipe> normalized = new ArrayList<>(original.size());
        boolean changed = false;
        for (MerchantRecipe recipe : original) {
            MerchantRecipe result = normalize(recipe);
            if (result == null) {
                changed = true;
                continue;
            }
            normalized.add(result);
            if (result != recipe) {
                changed = true;
            }
        }
        if (changed) {
            villager.setRecipes(normalized);
        }
    }
}