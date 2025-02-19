package io.github.lucaargolo.seasons;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import io.github.lucaargolo.seasons.commands.SeasonCommand;
import io.github.lucaargolo.seasons.mixed.BiomeMixed;
import io.github.lucaargolo.seasons.payload.ConfigSyncPacket;
import io.github.lucaargolo.seasons.payload.UpdateCropsPaycket;
import io.github.lucaargolo.seasons.resources.CropConfigs;
import io.github.lucaargolo.seasons.utils.GreenhouseCache;
import io.github.lucaargolo.seasons.utils.ModConfig;
import io.github.lucaargolo.seasons.utils.PlacedMeltablesState;
import io.github.lucaargolo.seasons.utils.ReplacedMeltablesState;
import io.github.lucaargolo.seasons.utils.Season;
import io.github.lucaargolo.seasons.utils.SeasonalFertilizable;
import it.unimi.dsi.fastutil.longs.LongArraySet;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.resource.ResourceType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.Pair;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;

public class FabricSeasons implements ModInitializer {

    private static final LongArraySet temporaryMeltableCache = new LongArraySet();
    public static final String MOD_ID = "seasons";
    public static final String MOD_NAME = "Fabric Seasons";

    public static final Logger LOGGER = LogManager.getLogger(MOD_NAME);

    public static ModConfig CONFIG;

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static HashMap<Item, Block> SEEDS_MAP = new HashMap<>();

    @Override
    public void onInitialize() {
        Path configPath = FabricLoader.getInstance().getConfigDir();
        File configFile = new File(configPath + File.separator + "seasons.json");

        LOGGER.info("[" + MOD_NAME + "] Trying to read config file...");
        try {
            if (configFile.createNewFile()) {
                LOGGER.info("[" + MOD_NAME + "] No config file found, creating a new one...");
                String json = GSON.toJson(JsonParser.parseString(GSON.toJson(new ModConfig())));
                try (PrintWriter out = new PrintWriter(configFile)) {
                    out.println(json);
                }
                CONFIG = new ModConfig();
                LOGGER.info("[" + MOD_NAME + "] Successfully created default config file.");
            } else {
                LOGGER.info("[" + MOD_NAME + "] A config file was found, loading it..");
                CONFIG = GSON.fromJson(new String(Files.readAllBytes(configFile.toPath())), ModConfig.class);
                if (CONFIG == null) {
                    throw new NullPointerException("[" + MOD_NAME + "] The config file was empty.");
                } else {
                    LOGGER.info("[" + MOD_NAME + "] Successfully loaded config file.");
                }
            }
        } catch (Exception exception) {
            LOGGER.error("[" + MOD_NAME + "] There was an error creating/loading the config file!", exception);
            CONFIG = new ModConfig();
            LOGGER.warn("[" + MOD_NAME + "] Defaulting to original config.");
        }

        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated, ignored) -> SeasonCommand.register(dispatcher));

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            SEEDS_MAP.clear();
            Registries.ITEM.forEach(item -> {
                if (item instanceof BlockItem) {
                    Block block = ((BlockItem) item).getBlock();
                    if (block instanceof SeasonalFertilizable) {
                        FabricSeasons.SEEDS_MAP.put(item, ((BlockItem) item).getBlock());
                    }
                }
            });
        });

        PayloadTypeRegistry.playS2C().register(UpdateCropsPaycket.ID, UpdateCropsPaycket.CODEC);
        ServerLifecycleEvents.SYNC_DATA_PACK_CONTENTS.register((player, joined) -> {
            ServerPlayNetworking.send(player, UpdateCropsPaycket.fromConfig(CropConfigs.getDefaultCropConfig(), CropConfigs.getCropConfigMap()));
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            GreenhouseCache.tick(server);
            temporaryMeltableCache.clear();
        });

        PayloadTypeRegistry.playS2C().register(ConfigSyncPacket.ID, ConfigSyncPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(ConfigSyncPacket.ID, ConfigSyncPacket.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(ConfigSyncPacket.ID, (payload, context) -> {
            String configJson = GSON.toJson(JsonParser.parseString(GSON.toJson(CONFIG)));
            ServerPlayNetworking.send(context.player(), new ConfigSyncPacket(configJson));
        });

        ResourceManagerHelper.get(ResourceType.SERVER_DATA).registerReloadListener(new CropConfigs());
    }

    public static Identifier identifier(String path) {
        return Identifier.of(MOD_ID, path);
    }

    public static void setMeltable(BlockPos blockPos) {
        temporaryMeltableCache.add(blockPos.asLong());
    }

    public static boolean isMeltable(BlockPos blockPos) {
        return temporaryMeltableCache.contains(blockPos.asLong());
    }

    public static PlacedMeltablesState getPlacedMeltablesState(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(PlacedMeltablesState.getPersistentStateType(), "seasons_placed_meltables");
    }

    public static ReplacedMeltablesState getReplacedMeltablesState(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(ReplacedMeltablesState.getPersistentStateType(), "seasons_replaced_meltables");
    }

    public static long getTimeToNextSeason(World world) {
        return getTimeToNextSeason(world, false);
    }

    public static long getTimeToNextSeason(World world, boolean ignoreDimension) {
        RegistryKey<World> dimension = world.getRegistryKey();
        if ((ignoreDimension || CONFIG.isValidInDimension(dimension)) && !CONFIG.isSeasonLocked()) {
            if(CONFIG.isSeasonTiedWithSystemTime()) {
                return getTimeToNextSystemSeason() * 24000;
            }
            long springTime = world.getTimeOfDay() % CONFIG.getYearLength();
            long summerTime = springTime - CONFIG.getSpringLength();
            long fallTime = summerTime - CONFIG.getSummerLength();
            long winterTime = fallTime - CONFIG.getFallLength();

            long seasonTime = switch (getCurrentSeason(world)) {
                case SPRING -> springTime;
                case SUMMER -> summerTime;
                case FALL -> fallTime;
                case WINTER -> winterTime;
            };
            return getCurrentSeason(world).getSeasonLength() - seasonTime;
        }
        return Long.MAX_VALUE;
    }

    public static Season getNextSeason(World world) {
        return getNextSeason(world, false);
    }

    public static Season getNextSeason(World world, boolean ignoreDimension) {
        RegistryKey<World> dimension = world.getRegistryKey();
        if (ignoreDimension || CONFIG.isValidInDimension(dimension)) {
            if(CONFIG.isSeasonLocked()) {
                return CONFIG.getLockedSeason();
            }
            if(CONFIG.isSeasonTiedWithSystemTime()) {
                return getCurrentSystemSeason().getNext();
            }

            long springTime = world.getTimeOfDay() % CONFIG.getYearLength();
            long summerTime = springTime - CONFIG.getSpringLength();
            long fallTime = summerTime - CONFIG.getSummerLength();
            long winterTime = fallTime - CONFIG.getFallLength();

            long seasonTime = switch (getCurrentSeason(world)) {
                case SPRING -> CONFIG.getSpringLength() - springTime;
                case SUMMER -> CONFIG.getSummerLength() - summerTime;
                case FALL -> CONFIG.getFallLength() - fallTime;
                case WINTER -> CONFIG.getWinterLength() - winterTime;
            };

            long worldTime = world.getTimeOfDay() + seasonTime;

            springTime = worldTime % CONFIG.getYearLength();
            summerTime = springTime - CONFIG.getSpringLength();
            fallTime = summerTime - CONFIG.getSummerLength();
            winterTime = fallTime - CONFIG.getFallLength();

            if(winterTime >= 0 && CONFIG.getWinterLength() > 0) {
                return Season.WINTER;
            }else if(fallTime >= 0 && CONFIG.getFallLength() > 0) {
                return Season.FALL;
            }else if(summerTime >= 0 && CONFIG.getSummerLength() > 0) {
                return Season.SUMMER;
            }else if(springTime >= 0 && CONFIG.getSpringLength() > 0) {
                return Season.SPRING;
            }
        }
        return Season.SPRING;
    }

    public static Season getCurrentSeason(World world) {
        return getCurrentSeason(world, false);
    }

    public static Season getCurrentSeason(World world, boolean ignoreDimension) {
        RegistryKey<World> dimension = world.getRegistryKey();
        if (ignoreDimension || CONFIG.isValidInDimension(dimension)) {
            if(CONFIG.isSeasonLocked()) {
                return CONFIG.getLockedSeason();
            }
            if(CONFIG.isSeasonTiedWithSystemTime()) {
                return getCurrentSystemSeason();
            }

            long springTime = world.getTimeOfDay() % CONFIG.getYearLength();
            long summerTime = springTime - CONFIG.getSpringLength();
            long fallTime = summerTime - CONFIG.getSummerLength();
            long winterTime = fallTime - CONFIG.getFallLength();

            if(winterTime >= 0 && CONFIG.getWinterLength() > 0) {
                return Season.WINTER;
            }else if(fallTime >= 0 && CONFIG.getFallLength() > 0) {
                return Season.FALL;
            }else if(summerTime >= 0 && CONFIG.getSummerLength() > 0) {
                return Season.SUMMER;
            }else if(springTime >= 0 && CONFIG.getSpringLength() > 0) {
                return Season.SPRING;
            }
        }
        return Season.SPRING;
    }

    @Environment(EnvType.CLIENT)
    public static Season getCurrentSeason() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if(player != null && player.getWorld() != null) {
            return getCurrentSeason(player.getWorld());
        }
        return Season.SPRING;
    }

    private static long getTimeToNextSystemSeason() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextSeasonStart;

        Season currentSeason = getCurrentSystemSeason();
        if(CONFIG.isInNorthHemisphere()) {
            nextSeasonStart = switch (currentSeason) {
                case WINTER -> LocalDateTime.of(now.getYear(), 3, 20, 0, 0);
                case SPRING -> LocalDateTime.of(now.getYear(), 6, 21, 0, 0);
                case SUMMER -> LocalDateTime.of(now.getYear(), 9, 22, 0, 0);
                case FALL -> LocalDateTime.of(now.getYear(), 12, 21, 0, 0);
            };
        }else{
            nextSeasonStart = switch (currentSeason) {
                case SUMMER -> LocalDateTime.of(now.getYear(), 3, 20, 0, 0);
                case FALL -> LocalDateTime.of(now.getYear(), 6, 21, 0, 0);
                case WINTER -> LocalDateTime.of(now.getYear(), 9, 22, 0, 0);
                case SPRING -> LocalDateTime.of(now.getYear(), 12, 21, 0, 0);
            };
        }

        Duration timeToNextSeason = Duration.between(now, nextSeasonStart);
        return timeToNextSeason.toDays();
    }

    private static Season getCurrentSystemSeason() {
        LocalDateTime date = LocalDateTime.now();
        int m = date.getMonthValue();
        int d = date.getDayOfMonth();
        Season season;

        if (CONFIG.isInNorthHemisphere()) {
            if (m == 1 || m == 2 || m == 3)
                season = Season.WINTER;
            else if (m == 4 || m == 5 || m == 6)
                season = Season.SPRING;
            else if (m == 7 || m == 8 || m == 9)
                season = Season.SUMMER;
            else
                season = Season.FALL;

            if (m == 3 && d > 19)
                season = Season.SPRING;
            else if (m == 6 && d > 20)
                season = Season.SUMMER;
            else if (m == 9 && d > 21)
                season = Season.FALL;
            else if (m == 12 && d > 20)
                season = Season.WINTER;
        } else {
            if (m == 1 || m == 2 || m == 3)
                season = Season.SUMMER;
            else if (m == 4 || m == 5 || m == 6)
                season = Season.FALL;
            else if (m == 7 || m == 8 || m == 9)
                season = Season.WINTER;
            else
                season = Season.SPRING;

            if (m == 3 && d > 19)
                season = Season.FALL;
            else if (m == 6 && d > 20)
                season = Season.WINTER;
            else if (m == 9 && d > 21)
                season = Season.SPRING;
            else if (m == 12 && d > 20)
                season = Season.SUMMER;
        }

        return season;
    }

    private static final TagKey<Biome> IGNORED_CATEGORIES_TAG = TagKey.of(RegistryKeys.BIOME, FabricSeasons.identifier("ignored"));

    public static void injectBiomeTemperature(RegistryEntry<Biome> entry, World world) {
        if(entry.isIn(IGNORED_CATEGORIES_TAG))
            return;

        // legacy, prefer use of tag where possible
        Biome biome = entry.value();
        Identifier biomeId = entry.getKey().orElse(BiomeKeys.PLAINS).getValue();
        if(!CONFIG.doTemperatureChanges(biomeId)) return;

        Biome.Weather currentWeather = biome.weather;
        Biome.Weather originalWeather = ((BiomeMixed) (Object) biome).getOriginalWeather();
        if (originalWeather == null) {
            originalWeather = new Biome.Weather(currentWeather.hasPrecipitation(), currentWeather.temperature(), currentWeather.temperatureModifier(), currentWeather.downfall());
            ((BiomeMixed) (Object) biome).setOriginalWeather(originalWeather);
        }
        Season season = FabricSeasons.getCurrentSeason(world);

        Pair<Boolean, Float> modifiedWeather = getSeasonWeather(season, biomeId, originalWeather.hasPrecipitation, originalWeather.temperature);
        currentWeather.hasPrecipitation = modifiedWeather.getLeft();
        currentWeather.temperature = modifiedWeather.getRight();
    }

    public static Pair<Boolean, Float> getSeasonWeather(Season season, Identifier biomeId, Boolean hasPrecipitation, float temp) {
        if(!CONFIG.doTemperatureChanges(biomeId)) {
            return new Pair<>(hasPrecipitation, temp);
        }
        if(CONFIG.isSnowForcedInBiome(biomeId) && season == Season.WINTER) {
            return new Pair<>(hasPrecipitation, 0.14f);
        }else if(temp <= -0.51) {
            //Permanently Frozen Biomes
            return switch (season) {
                case SPRING -> CONFIG.isFallAndSpringReversed() ? new Pair<>(hasPrecipitation, temp - 0.3f) : new Pair<>(hasPrecipitation, temp);
                case SUMMER -> new Pair<>(hasPrecipitation, temp + 0.84f);
                case WINTER -> new Pair<>(hasPrecipitation, temp - 0.7f);
                case FALL -> CONFIG.isFallAndSpringReversed() ? new Pair<>(hasPrecipitation, temp) : new Pair<>(hasPrecipitation, temp - 0.3f);
            };
        }else if(temp <= 0.15) {
            //Usually Frozen Biomes
            return switch (season) {
                case SPRING -> CONFIG.isFallAndSpringReversed() ? new Pair<>(hasPrecipitation, temp - 0.25f) : new Pair<>(hasPrecipitation, temp);
                case SUMMER -> new Pair<>(hasPrecipitation, temp + (CONFIG.shouldSnowyBiomesMeltInSummer() ? 0.66f : 0f));
                case WINTER -> new Pair<>(hasPrecipitation, temp - 0.75f);
                case FALL -> CONFIG.isFallAndSpringReversed() ? new Pair<>(hasPrecipitation, temp) : new Pair<>(hasPrecipitation, temp - 0.25f);
            };
        }else if(temp <= 0.49) {
            //Temparate Biomes
            return switch (season) {
                case SPRING -> CONFIG.isFallAndSpringReversed() ? new Pair<>(hasPrecipitation, temp - 0.16f) : new Pair<>(hasPrecipitation, temp);
                case SUMMER -> new Pair<>(hasPrecipitation, temp + 0.66f);
                case WINTER -> new Pair<>(hasPrecipitation, temp - 0.8f);
                case FALL  -> CONFIG.isFallAndSpringReversed() ? new Pair<>(hasPrecipitation, temp) : new Pair<>(hasPrecipitation, temp - 0.16f);
            };
        }else if(temp <= 0.79) {
            //Usually Ice Free Biomes
            return switch (season) {
                case SPRING -> CONFIG.isFallAndSpringReversed() ? new Pair<>(hasPrecipitation, temp - 0.34f) : new Pair<>(hasPrecipitation, temp);
                case SUMMER -> new Pair<>(hasPrecipitation, temp + 0.46f);
                case WINTER -> new Pair<>(hasPrecipitation, temp - 0.56f);
                case FALL -> CONFIG.isFallAndSpringReversed() ? new Pair<>(hasPrecipitation, temp) : new Pair<>(hasPrecipitation, temp - 0.34f);
            };
        }else{
            // Ice Free Biomes
            return switch (season) {
                case SPRING -> CONFIG.isFallAndSpringReversed() ? new Pair<>(hasPrecipitation, temp - 0.34f) : new Pair<>(hasPrecipitation, temp);
                case SUMMER -> new Pair<>(hasPrecipitation, temp + 0.4f);
                case WINTER -> new Pair<>(true, temp - 0.64f);
                case FALL -> CONFIG.isFallAndSpringReversed() ? new Pair<>(hasPrecipitation, temp) : new Pair<>(hasPrecipitation, temp - 0.34f);
            };
        }
    }

}
