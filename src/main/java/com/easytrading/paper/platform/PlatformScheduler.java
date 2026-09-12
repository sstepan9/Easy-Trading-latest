package com.easytrading.paper.platform;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.function.Consumer;

public final class PlatformScheduler {

    private static final Runnable NOOP = () -> {
    };
    private static final String FOLIA_SCHEDULED_TASK = "io.papermc.paper.threadedregions.scheduler.ScheduledTask";

    private final Plugin plugin;
    private final boolean folia;

    public PlatformScheduler(Plugin plugin) {
        this.plugin = plugin;
        // Paper also exposes Folia-compatible scheduler methods. The regionized
        // server class is the official discriminator between Paper and Folia.
        this.folia = hasClass("io.papermc.paper.threadedregions.RegionizedServer");
    }

    public boolean isFolia() {
        return folia;
    }

    public TaskHandle runGlobalRepeating(Runnable task, long delayTicks, long periodTicks) {
        if (!folia) {
            BukkitTask handle = Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
            return new TaskHandle(handle::cancel);
        }

        Object scheduler = invoke(Bukkit.getServer(), "getGlobalRegionScheduler");
        Object handle = invoke(scheduler, "runAtFixedRate", plugin, consumer(task), delayTicks, periodTicks);
        return new TaskHandle(() -> cancelReflective(handle));
    }

    public TaskHandle runPlayerRepeating(Player player, Runnable task, long delayTicks, long periodTicks) {
        if (!folia) {
            BukkitTask handle = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (player.isOnline()) {
                    task.run();
                }
            }, delayTicks, periodTicks);
            return new TaskHandle(handle::cancel);
        }

        Object scheduler = invoke(player, "getScheduler");
        Object handle = invoke(scheduler, "runAtFixedRate", plugin, consumer(task), NOOP, delayTicks, periodTicks);
        return new TaskHandle(() -> cancelReflective(handle));
    }

    public void runPlayer(Player player, Runnable task) {
        if (!folia) {
            if (Bukkit.isPrimaryThread()) {
                task.run();
            } else {
                Bukkit.getScheduler().runTask(plugin, task);
            }
            return;
        }

        Object scheduler = invoke(player, "getScheduler");
        invoke(scheduler, "run", plugin, consumer(task), NOOP);
    }

    private Consumer<Object> consumer(Runnable task) {
        return ignored -> task.run();
    }

    private static boolean hasClass(String className) {
        try {
            Class.forName(className, false, PlatformScheduler.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    private static Object invoke(Object target, String methodName, Object... args) {
        Method[] methods = target.getClass().getMethods();
        for (Method method : methods) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != args.length) {
                continue;
            }
            try {
                return method.invoke(target, args);
            } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
                // Try the next overload.
            }
        }
        throw new IllegalStateException("Could not invoke method " + methodName + " on " + target.getClass().getName());
    }

    private static void cancelReflective(Object handle) {
        if (handle == null) {
            return;
        }
        try {
            findCancelMethod(handle.getClass()).invoke(handle);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not cancel scheduled task", e);
        }
    }

    private static Method findCancelMethod(Class<?> handleClass) throws NoSuchMethodException {
        for (Class<?> iface : handleClass.getInterfaces()) {
            if (iface.getName().equals(FOLIA_SCHEDULED_TASK)) {
                return iface.getMethod("cancel");
            }
        }
        return handleClass.getMethod("cancel");
    }

    public static final class TaskHandle {
        private final Runnable cancel;

        private TaskHandle(Runnable cancel) {
            this.cancel = cancel;
        }

        public void cancel() {
            cancel.run();
        }
    }
}
