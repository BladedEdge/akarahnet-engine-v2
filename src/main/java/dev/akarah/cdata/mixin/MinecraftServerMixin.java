package dev.akarah.cdata.mixin;

import com.mojang.datafixers.DataFixer;
import dev.akarah.cdata.Main;
import dev.akarah.cdata.db.persistence.DbPersistence;
import dev.akarah.cdata.registry.ExtReloadableResources;
import dev.akarah.cdata.registry.entity.CustomEntity;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.Services;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.progress.ChunkProgressListenerFactory;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.Proxy;
import java.time.Instant;
import java.time.temporal.TemporalAmount;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {
    @Shadow private int tickCount;

    @Shadow @Nullable private ServerStatus status;

    @Inject(at = @At("CTOR_HEAD"), method = "<init>")
    private void getInstanceAndStartWork(Thread thread, LevelStorageSource.LevelStorageAccess levelStorageAccess, PackRepository packRepository, WorldStem worldStem, Proxy proxy, DataFixer dataFixer, Services services, ChunkProgressListenerFactory chunkProgressListenerFactory, CallbackInfo ci) {
        Main.SERVER = (MinecraftServer) (Object) this;
    }

    @Inject(at = @At("HEAD"), method = "tickChildren")
    public void onTick(BooleanSupplier booleanSupplier, CallbackInfo ci) {
        if(this.tickCount == 1) {
            for(var entity : ExtReloadableResources.customEntity().registry().entrySet()) {
                System.out.println("Fetching game profile for " + entity.getValue().playerSkinName());
                SkullBlockEntity.fetchGameProfile(entity.getValue().playerSkinName()).thenApply(Optional::orElseThrow).thenAccept(gp -> {
                    CustomEntity.GAME_PROFILES.put(entity.getValue().playerSkinName(), gp);
                }).join();
            }
        }
        ExtReloadableResources.statManager().loopPlayers();
        if(this.tickCount % 200 == 0) {
            ExtReloadableResources.statManager().refreshPlayerInventories();
        }
    }

    @Inject(at = @At("HEAD"), method = "runServer")
    public void onRun(CallbackInfo ci) {
        System.out.println("Loading persistent data from file system...");
        var start = Instant.now().toEpochMilli();
        DbPersistence.loadPersistentDb(Main.SERVER.registryAccess()).join();
        var end = Instant.now().toEpochMilli();
        System.out.println("All done! Finished in " + (end - start) + "ms");
    }

    @Inject(at = @At("HEAD"), method = "stopServer")
    public void stopServer(CallbackInfo ci) {
        System.out.println("Saving persistent data to file system...");
        var start = Instant.now().toEpochMilli();
        DbPersistence.savePersistentDb(Main.SERVER.registryAccess()).join();
        var end = Instant.now().toEpochMilli();
        System.out.println("All done! Finished in " + (end - start) + "ms");
    }
}
