package iriscompare;

import io.github.hecbatista.metalshaders.test.PlaytestTour;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.irisshaders.iris.Iris;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Turns Iris on with the first pack in shaderpacks/ (default settings, no pack settings file) before
 * the tour runs. Iris reads config/iris.properties once, in onEarlyInitialize, before any game test
 * can write it, so we set it through Iris's own config object and reload (Iris.reload, as its
 * reload key does). Listed first in fabric.mod.json, so it runs before PlaytestTour.
 */
@SuppressWarnings("UnstableApiUsage")
public class IrisSetup implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        PlaytestTour.copyShaderPacks();
        Path packs = Iris.getShaderpacksDirectory();
        String pack;
        try (Stream<Path> list = Files.list(packs)) {
            pack = list.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".zip")).sorted().findFirst().orElse(null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (pack == null) {
            throw new IllegalStateException("No shader pack zip in " + packs);
        }
        context.runOnClient(mc -> {
            try {
                Iris.getIrisConfig().setShaderPackName(pack);
                Iris.getIrisConfig().setShadersEnabled(true);
                Iris.getIrisConfig().save();
                Iris.reload();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        context.waitTicks(5);
        context.runOnClient(mc -> {
            String current = Iris.getCurrentPackName();
            System.out.println("[Iris compare] Iris pack in use: " + current);
            // The pipeline itself is created when the world loads (there is no level yet).
            if (!pack.equals(current)) {
                throw new IllegalStateException("Iris did not load the pack " + pack);
            }
        });
    }
}
