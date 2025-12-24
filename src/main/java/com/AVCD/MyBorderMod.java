package com.example;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class MyBorderMod implements ModInitializer {

    private static int worldRadius = 1000;
    private static int safeRadius = 100;
    private static float movementSpeed = 0.001f;
    private static boolean isActive = false;
    private static double angle = 0.0;
    private static double currentCenterX = 0.0;
    private static double currentCenterZ = 0.0;

    private static Path configPath;
    private static final String CONFIG_FILE_NAME = "dynamicborder.config";

    @Override
    public void onInitialize() {
        // Загружаем конфигурацию при запуске
        loadConfig();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerCommand(dispatcher);
        });

        ServerTickEvents.START_SERVER_TICK.register(server -> {
            onServerTick(server);
        });

        // Сохраняем конфигурацию при остановке сервера
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            saveConfig();
            System.out.println("[DynamicBorder] Конфигурация сохранена при остановке сервера");
        });
    }

    private void registerCommand(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("setdynamicborder")
            .requires(source -> source.hasPermissionLevel(4))
            .then(CommandManager.argument("worldRadius", IntegerArgumentType.integer(100))
            .then(CommandManager.argument("safeRadius", IntegerArgumentType.integer(10))
            .then(CommandManager.argument("speed", FloatArgumentType.floatArg(0.0001f, 0.02f))
            .executes(context -> {
                int newWorldRadius = IntegerArgumentType.getInteger(context, "worldRadius");
                int newSafeRadius = IntegerArgumentType.getInteger(context, "safeRadius");
                float newSpeed = FloatArgumentType.getFloat(context, "speed");

                if (newSafeRadius >= newWorldRadius) {
                    context.getSource().sendError(Text.literal("Безопасный радиус должен быть меньше радиуса мира"));
                    return -1;
                }

                worldRadius = newWorldRadius;
                safeRadius = newSafeRadius;
                movementSpeed = newSpeed;
                isActive = true;

                saveConfig();

                context.getSource().sendFeedback(Text.literal(
                    "Динамическая граница установлена: мир=" + worldRadius +
                    ", безопасный=" + safeRadius +
                    ", скорость=" + String.format("%.4f", movementSpeed)), true);

                return 1;
            }))))
        );

        dispatcher.register(CommandManager.literal("stopdynamicborder")
            .requires(source -> source.hasPermissionLevel(4))
            .executes(context -> {
                isActive = false;
                saveConfig();
                context.getSource().sendFeedback(Text.literal("Динамическая граница остановлена"), true);
                return 1;
            })
        );

        dispatcher.register(CommandManager.literal("startdynamicborder")
            .requires(source -> source.hasPermissionLevel(4))
            .executes(context -> {
                isActive = true;
                saveConfig();
                context.getSource().sendFeedback(Text.literal(
                    "Динамическая граница возобновлена: мир=" + worldRadius +
                    ", безопасный=" + safeRadius +
                    ", скорость=" + String.format("%.4f", movementSpeed)), true);
                return 1;
            })
        );

        dispatcher.register(CommandManager.literal("borderdebug")
            .requires(source -> source.hasPermissionLevel(4))
            .executes(context -> {
                ServerPlayerEntity player = context.getSource().getPlayer();
                if (player != null) {
                    WorldBorder worldBorder = context.getSource().getServer().getOverworld().getWorldBorder();
                    double playerX = player.getX();
                    double playerZ = player.getZ();

                    double distanceToBorder = worldBorder.getDistanceInsideBorder(playerX, playerZ);
                    boolean isInside = worldBorder.contains(playerX, playerZ);
                    String worldType = getWorldType(player.getWorld());

                    context.getSource().sendFeedback(Text.literal(
                        "§6=== Отладка границы ===\n" +
                        "§fТип мира: §e" + worldType + "\n" +
                        "§fЦентр границы: §a" + String.format("%.1f", currentCenterX) + "§f, §a" + String.format("%.1f", currentCenterZ) + "\n" +
                        "§fКоординаты игрока: §b" + String.format("%.1f", playerX) + "§f, §b" + String.format("%.1f", playerZ) + "\n" +
                        "§fРасстояние до границы: §e" + String.format("%.1f", distanceToBorder) + "\n" +
                        "§fВнутри границы: §" + (isInside ? "aДа" : "cНет") + "\n" +
                        "§fРадиус границы: §c" + worldRadius + "\n" +
                        "§fБезопасный радиус: §a" + safeRadius + "\n" +
                        "§fСкорость: §e" + String.format("%.4f", movementSpeed) + "\n" +
                        "§fТекущий угол: §e" + String.format("%.4f", angle) + "\n" +
                        "§fАктивна: §" + (isActive ? "aДа" : "cНет")
                    ), false);
                }
                return 1;
            })
        );

        dispatcher.register(CommandManager.literal("borderreload")
            .requires(source -> source.hasPermissionLevel(4))
            .executes(context -> {
                loadConfig();
                context.getSource().sendFeedback(Text.literal(
                    "Конфигурация загружена: мир=" + worldRadius +
                    ", безопасный=" + safeRadius +
                    ", скорость=" + String.format("%.4f", movementSpeed) +
                    ", угол=" + String.format("%.4f", angle)), true);
                return 1;
            })
        );

        // Команда для сброса угла (если нужно начать с начала)
        dispatcher.register(CommandManager.literal("borderresetangle")
            .requires(source -> source.hasPermissionLevel(4))
            .executes(context -> {
                angle = 0.0;
                saveConfig();
                context.getSource().sendFeedback(Text.literal("Угол движения границы сброшен"), true);
                return 1;
            })
        );
    }

    private void onServerTick(MinecraftServer server) {
        if (!isActive) return;

        // Работаем только с верхним миром
        WorldBorder worldBorder = server.getOverworld().getWorldBorder();

        double movementRadius = worldRadius - safeRadius;

        // Обновляем угол движения
        angle += movementSpeed;
        if (angle > 2 * Math.PI) {
            angle = 0;
            // Сохраняем конфиг при полном обороте
            saveConfig();
        }

        // Вычисляем новый центр границы
        currentCenterX = Math.round(Math.cos(angle) * movementRadius * 100.0) / 100.0;
        currentCenterZ = Math.round(Math.sin(angle) * movementRadius * 100.0) / 100.0;

        // Устанавливаем границу мира
        worldBorder.setCenter(currentCenterX, currentCenterZ);
        worldBorder.setSize(worldRadius * 2);

        checkPlayers(server);
    }

    private void checkPlayers(MinecraftServer server) {
        WorldBorder worldBorder = server.getOverworld().getWorldBorder();

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            // Проверяем только игроков в верхнем мире
            if (!isOverworld(player.getWorld())) {
                continue;
            }

            double playerX = player.getX();
            double playerZ = player.getZ();

            double distanceToBorder = worldBorder.getDistanceInsideBorder(playerX, playerZ);

            if (distanceToBorder < 0) {
                double oldX = player.getX();
                double oldY = player.getY();
                double oldZ = player.getZ();

                Vec3d borderPos = findClosestBorderPoint(worldBorder, playerX, playerZ);
                double newX = borderPos.x;
                double newZ = borderPos.z;

                double safeY = findSafeY(server, newX, newZ, oldY);

                spawnTeleportParticles(server, oldX, oldY, oldZ);

                player.teleport(server.getOverworld(), newX, safeY, newZ,
                               player.getYaw(), player.getPitch());

                player.sendMessage(Text.literal("§cВы были возвращены внутрь границы мира!"), false);

                server.sendMessage(Text.literal(
                    "§6[Граница] Игрок " + player.getEntityName() + " телепортирован\n" +
                    "  Мир: " + getWorldType(player.getWorld()) + "\n" +
                    "  Координаты игрока: " + String.format("%.1f", oldX) + ", " + String.format("%.1f", oldZ) + "\n" +
                    "  Расстояние до границы: " + String.format("%.2f", distanceToBorder) + "\n" +
                    "  Новая позиция: " + String.format("%.1f", newX) + ", " + String.format("%.1f", newZ) + "\n" +
                    "  Смещение: " + String.format("%.2f", Math.abs(oldX - newX)) + ", " + String.format("%.2f", Math.abs(oldZ - newZ))
                ));
            }
        }
    }

    private Vec3d findClosestBorderPoint(WorldBorder worldBorder, double playerX, double playerZ) {
        double centerX = worldBorder.getCenterX();
        double centerZ = worldBorder.getCenterZ();
        double radius = worldBorder.getSize() / 2;

        double dx = playerX - centerX;
        double dz = playerZ - centerZ;
        double distance = Math.sqrt(dx * dx + dz * dz);

        if (distance == 0) {
            return new Vec3d(centerX + radius - 0.1, 0, centerZ);
        }

        double directionX = dx / distance;
        double directionZ = dz / distance;

        // Телепортация точно на 0.1 блока внутрь границы
        double borderX = centerX + directionX * (radius - 0.1);
        double borderZ = centerZ + directionZ * (radius - 0.1);

        return new Vec3d(borderX, 0, borderZ);
    }

    private double findSafeY(MinecraftServer server, double x, double z, double currentY) {
        int surfaceY = server.getOverworld().getTopY(Heightmap.Type.MOTION_BLOCKING, (int) x, (int) z);

        BlockPos currentPos = new BlockPos((int) x, (int) currentY, (int) z);
        if (currentY > surfaceY &&
            server.getOverworld().getBlockState(currentPos).isAir() &&
            server.getOverworld().getBlockState(currentPos.up()).isAir()) {
            return currentY;
        }

        BlockPos checkPos = new BlockPos((int) x, surfaceY + 2, (int) z);
        if (server.getOverworld().getBlockState(checkPos).isAir() &&
            server.getOverworld().getBlockState(checkPos.up()).isAir()) {
            return surfaceY + 2.0;
        }

        for (int y = surfaceY + 2; y < server.getOverworld().getHeight(); y++) {
            BlockPos pos = new BlockPos((int) x, y, (int) z);
            BlockPos posAbove = new BlockPos((int) x, y + 1, (int) z);

            if (server.getOverworld().getBlockState(pos).isAir() &&
                server.getOverworld().getBlockState(posAbove).isAir()) {
                return y + 0.5;
            }
        }

        return surfaceY + 3.0;
    }

    private void spawnTeleportParticles(MinecraftServer server, double x, double y, double z) {
        server.getOverworld().spawnParticles(
            ParticleTypes.CLOUD,
            x, y + 1, z,
            15,
            0.3, 0.5, 0.3,
            0.02
        );

        server.getOverworld().spawnParticles(
            ParticleTypes.WHITE_ASH,
            x, y + 0.5, z,
            8,
            0.2, 0.3, 0.2,
            0.01
        );
    }

    // Проверка что мир - верхний мир
    private boolean isOverworld(World world) {
        return world.getRegistryKey().getValue().getPath().equals("overworld");
    }

    private String getWorldType(World world) {
        String path = world.getRegistryKey().getValue().getPath();
        switch (path) {
            case "overworld": return "Верхний мир";
            case "the_nether": return "Незер";
            case "the_end": return "Энд";
            default: return path;
        }
    }

    // ========== КОНФИГУРАЦИЯ ==========

    private void loadConfig() {
        try {
            if (configPath == null) {
                configPath = new File(".").toPath().resolve(CONFIG_FILE_NAME);
            }

            if (!Files.exists(configPath)) {
                saveConfig();
                return;
            }

            Properties props = new Properties();
            try (FileInputStream in = new FileInputStream(configPath.toFile())) {
                props.load(in);
            }

            // Загружаем основные настройки
            if (props.containsKey("worldRadius")) {
                worldRadius = Integer.parseInt(props.getProperty("worldRadius"));
            }
            if (props.containsKey("safeRadius")) {
                safeRadius = Integer.parseInt(props.getProperty("safeRadius"));
            }
            if (props.containsKey("movementSpeed")) {
                movementSpeed = Float.parseFloat(props.getProperty("movementSpeed"));
            }
            if (props.containsKey("isActive")) {
                isActive = Boolean.parseBoolean(props.getProperty("isActive"));
            }

            // ЗАГРУЖАЕМ УГОЛ ДВИЖЕНИЯ (НОВАЯ ФУНКЦИОНАЛЬНОСТЬ)
            if (props.containsKey("angle")) {
                angle = Double.parseDouble(props.getProperty("angle"));
            }

            System.out.println("[DynamicBorder] Конфигурация загружена: worldRadius=" + worldRadius +
                             ", safeRadius=" + safeRadius + ", movementSpeed=" + movementSpeed +
                             ", isActive=" + isActive + ", angle=" + angle);

        } catch (Exception e) {
            System.err.println("[DynamicBorder] Ошибка загрузки конфигурации: " + e.getMessage());
            worldRadius = 1000;
            safeRadius = 100;
            movementSpeed = 0.001f;
            isActive = false;
            angle = 0.0;
        }
    }

    private void saveConfig() {
        try {
            if (configPath == null) {
                configPath = new File(".").toPath().resolve(CONFIG_FILE_NAME);
            }

            Properties props = new Properties();
            props.setProperty("worldRadius", String.valueOf(worldRadius));
            props.setProperty("safeRadius", String.valueOf(safeRadius));
            props.setProperty("movementSpeed", String.valueOf(movementSpeed));
            props.setProperty("isActive", String.valueOf(isActive));

            // СОХРАНЯЕМ УГОЛ ДВИЖЕНИЯ (НОВАЯ ФУНКЦИОНАЛЬНОСТЬ)
            props.setProperty("angle", String.valueOf(angle));

            try (FileOutputStream out = new FileOutputStream(configPath.toFile())) {
                props.store(out, "Dynamic World Border Configuration");
            }

            System.out.println("[DynamicBorder] Конфигурация сохранена: worldRadius=" + worldRadius +
                             ", safeRadius=" + safeRadius + ", movementSpeed=" + movementSpeed +
                             ", isActive=" + isActive + ", angle=" + angle);

        } catch (Exception e) {
            System.err.println("[DynamicBorder] Ошибка сохранения конфигурации: " + e.getMessage());
        }
    }
}
