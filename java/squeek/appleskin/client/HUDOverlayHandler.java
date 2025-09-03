package squeek.appleskin.client;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Effects;
import net.minecraft.util.FoodStats;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.gui.ForgeIngameGui;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import org.lwjgl.opengl.GL11;
import squeek.appleskin.ModConfig;
import squeek.appleskin.ModInfo;
import squeek.appleskin.api.event.FoodValuesEvent;
import squeek.appleskin.api.event.HUDOverlayEvent;
import squeek.appleskin.api.food.FoodValues;
import squeek.appleskin.helpers.FoodHelper;
import squeek.appleskin.helpers.HungerHelper;
import squeek.appleskin.util.IntPoint;

import java.util.Random;
import java.util.Vector;

@OnlyIn(Dist.CLIENT)
public class HUDOverlayHandler
{
    private float unclampedFlashAlpha = 0f;
    private float flashAlpha = 0f;
    private byte alphaDir = 1;
    protected int foodIconsOffset;

    public final Vector<IntPoint> healthBarOffsets = new Vector<>();
    public final Vector<IntPoint> foodBarOffsets = new Vector<>();

    private final Random random = new Random();
    private static final ResourceLocation modIcons = new ResourceLocation(ModInfo.MODID_LOWER, "textures/icons.png");

    // FIX: Animation toggle moved to class level
    private boolean shouldAnimatedFood;

    public static void init()
    {
        MinecraftForge.EVENT_BUS.register(new HUDOverlayHandler());
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onPreRender(RenderGameOverlayEvent.Pre event)
    {
        if (event.getType() != RenderGameOverlayEvent.ElementType.FOOD)
            return;

        foodIconsOffset = ForgeIngameGui.right_height;

        if (event.isCanceled())
            return;

        if (!ModConfig.SHOW_FOOD_EXHAUSTION_UNDERLAY.get())
            return;

        Minecraft mc = Minecraft.getInstance();
        PlayerEntity player = mc.player;
        assert player != null;

        int right = mc.getMainWindow().getScaledWidth() / 2 + 91;
        int top = mc.getMainWindow().getScaledHeight() - foodIconsOffset;
        float exhaustion = HungerHelper.getExhaustion(player);

        HUDOverlayEvent.Exhaustion renderEvent = new HUDOverlayEvent.Exhaustion(exhaustion, right, top, event.getMatrixStack());
        MinecraftForge.EVENT_BUS.post(renderEvent);
        if (!renderEvent.isCanceled())
            drawExhaustionOverlay(renderEvent, mc, 1f);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onRender(RenderGameOverlayEvent.Post event)
    {
        if (event.getType() != RenderGameOverlayEvent.ElementType.FOOD && event.getType() != RenderGameOverlayEvent.ElementType.HEALTH)
            return;

        if (event.isCanceled())
            return;

        if (!shouldRenderAnyOverlays())
            return;

        Minecraft mc = Minecraft.getInstance();
        PlayerEntity player = mc.player;
        assert player != null;
        FoodStats stats = player.getFoodStats();
        MatrixStack matrixStack = event.getMatrixStack();

        int top = mc.getMainWindow().getScaledHeight() - foodIconsOffset;
        int left = mc.getMainWindow().getScaledWidth() / 2 - 91;
        int right = mc.getMainWindow().getScaledWidth() / 2 + 91;

        // Handle SpiffyHUD offset
        if (ModList.get().isLoaded("spiffyhud")) {
            // Keep vanilla HUD location for health/hunger, gold overlay matches position
            left += 90;
            right += 90;
        }

        if (event.getType() == RenderGameOverlayEvent.ElementType.HEALTH)
            generateHealthBarOffsets(top, left, right, mc.ingameGUI.getTicks(), player);
        if (event.getType() == RenderGameOverlayEvent.ElementType.FOOD)
            generateHungerBarOffsets(top, left, right, mc.ingameGUI.getTicks(), player, false);

        HUDOverlayEvent.Saturation saturationRenderEvent = null;
        if (event.getType() == RenderGameOverlayEvent.ElementType.FOOD)
        {
            saturationRenderEvent = new HUDOverlayEvent.Saturation(stats.getSaturationLevel(), right, top, matrixStack);
            if (!ModConfig.SHOW_SATURATION_OVERLAY.get())
                saturationRenderEvent.setCanceled(true);

            if (!saturationRenderEvent.isCanceled())
                MinecraftForge.EVENT_BUS.post(saturationRenderEvent);

            if (!saturationRenderEvent.isCanceled())
                drawSaturationOverlay(saturationRenderEvent, mc, 0, 1f);
        }

        ItemStack heldItem = player.getHeldItemMainhand();
        if (ModConfig.SHOW_FOOD_VALUES_OVERLAY_WHEN_OFFHAND.get() && !FoodHelper.canConsume(heldItem, player))
            heldItem = player.getHeldItemOffhand();

        boolean shouldRenderHeldItemValues = !heldItem.isEmpty() && FoodHelper.canConsume(heldItem, player);
        if (!shouldRenderHeldItemValues)
        {
            resetFlash();
            return;
        }

        FoodValues modifiedFoodValues = FoodHelper.getModifiedFoodValues(heldItem, player);
        FoodValuesEvent foodValuesEvent = new FoodValuesEvent(player, heldItem, FoodHelper.getDefaultFoodValues(heldItem), modifiedFoodValues);
        MinecraftForge.EVENT_BUS.post(foodValuesEvent);
        modifiedFoodValues = foodValuesEvent.modifiedFoodValues;

        if (event.getType() == RenderGameOverlayEvent.ElementType.HEALTH)
        {
            if (healthBarOffsets.size() == 0)
                return;

            if (!shouldShowEstimatedHealth(heldItem, modifiedFoodValues))
                return;

            float foodHealthIncrement = FoodHelper.getEstimatedHealthIncrement(heldItem, modifiedFoodValues, player);
            float currentHealth = player.getHealth();
            float modifiedHealth = Math.min(currentHealth + foodHealthIncrement, player.getMaxHealth());

            HUDOverlayEvent.HealthRestored healthRenderEvent = null;
            if (currentHealth < modifiedHealth)
                healthRenderEvent = new HUDOverlayEvent.HealthRestored(modifiedHealth, heldItem, modifiedFoodValues, left, top, matrixStack);

            if (healthRenderEvent != null)
                MinecraftForge.EVENT_BUS.post(healthRenderEvent);

            if (healthRenderEvent != null && !healthRenderEvent.isCanceled())
                drawHealthOverlay(healthRenderEvent, mc, flashAlpha);
        }
        else if (event.getType() == RenderGameOverlayEvent.ElementType.FOOD)
        {
            if (!ModConfig.SHOW_FOOD_VALUES_OVERLAY.get())
                return;

            HUDOverlayEvent.HungerRestored renderRenderEvent = new HUDOverlayEvent.HungerRestored(stats.getFoodLevel(), heldItem, modifiedFoodValues, right, top, matrixStack);
            MinecraftForge.EVENT_BUS.post(renderRenderEvent);
            if (renderRenderEvent.isCanceled())
                return;

            int foodHunger = modifiedFoodValues.hunger;
            float foodSaturationIncrement = modifiedFoodValues.getSaturationIncrement();

            // Draw restored hunger overlay (gold bar) directly over vanilla hunger bar
            drawHungerOverlay(renderRenderEvent, mc, foodHunger, flashAlpha, FoodHelper.isRotten(heldItem), true);

            assert saturationRenderEvent != null;
            if (!saturationRenderEvent.isCanceled())
            {
                int newFoodValue = stats.getFoodLevel() + foodHunger;
                float newSaturationValue = stats.getSaturationLevel() + foodSaturationIncrement;
                float saturationGained = newSaturationValue > newFoodValue ? newFoodValue - stats.getSaturationLevel() : foodSaturationIncrement;
                drawSaturationOverlay(saturationRenderEvent, mc, saturationGained, flashAlpha);
            }
        }
    }

    // ----- Helper methods for generating offsets -----
    private void generateHealthBarOffsets(int top, int left, int right, int ticks, PlayerEntity player)
    {
        random.setSeed((long) (ticks * 312871L));

        final int preferHealthBars = 10;
        final float maxHealth = player.getMaxHealth();
        final float absorptionHealth = (float) Math.ceil(player.getAbsorptionAmount());

        int healthBars = (int) Math.ceil((maxHealth + absorptionHealth) / 2.0F);
        if (healthBars < 0 || healthBars > 1000) {
            healthBarOffsets.setSize(0);
            return;
        }

        healthBarOffsets.setSize(healthBars);

        for (int i = 0; i < healthBars; ++i)
        {
            int row = i / 10;
            int column = i % 10;

            IntPoint point = new IntPoint();
            point.x = column * 8;
            point.y = -row * 8;
            healthBarOffsets.set(i, point);
        }
    }

    private void generateHungerBarOffsets(int top, int left, int right, int ticks, PlayerEntity player, boolean renderLeft)
    {
        final int preferFoodBars = 10;
        foodBarOffsets.setSize(preferFoodBars);

        if (ModConfig.SHOW_FOOD_VALUES_OVERLAY.get()) {
            FoodStats stats = player.getFoodStats();
            float saturationLevel = stats.getSaturationLevel();
            int foodLevel = stats.getFoodLevel();
            shouldAnimatedFood = saturationLevel <= 0.0F && ticks % (foodLevel * 3 + 1) == 0;
        }

        int baseX = renderLeft ? left : right;
        int spacing = 8;
        int iconSize = 9;

        for (int i = 0; i < preferFoodBars; ++i) {
            int x = renderLeft ? baseX + i * spacing : baseX + i * spacing - iconSize;
            int y = top;

            if (shouldAnimatedFood)
                y += random.nextInt(3) - 1;

            IntPoint point = foodBarOffsets.get(i);
            if (point == null) {
                point = new IntPoint();
                foodBarOffsets.set(i, point);
            }

            point.x = x - baseX;
            point.y = y - top;
        }
    }
}
