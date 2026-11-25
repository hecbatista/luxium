package leadlightcompare;

import dev.leadlight.render.PackChoice;
import io.github.hecbatista.metalshaders.test.PlaytestTour;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Turns LeadLight on with the first pack in shaderpacks/ (default settings, no pack settings file)
 * before the tour runs. LeadLight reads leadlight/pack.txt once at client setup, before any game
 * test can write it (and the test game folder is cleared before each run), so we write the file
 * and ask LeadLight to read it again, as its settings screen does (PackChoice.reload). Listed
 * first in fabric.mod.json, so it runs before PlaytestTour.
 */
@SuppressWarnings("UnstableApiUsage")
public class LeadlightSetup implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        PlaytestTour.copyShaderPacks();
        Path gameDir = FabricLoader.getInstance().getGameDir();
        String pack;
        try (Stream<Path> list = Files.list(gameDir.resolve("shaderpacks"))) {
            pack = list.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".zip")).sorted().findFirst().orElse(null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (pack == null) {
            throw new IllegalStateException("No shader pack zip in " + gameDir.resolve("shaderpacks"));
        }
        try {
            Path file = PackChoice.packFile(gameDir);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "pack=" + pack + "\nenabled=true\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        context.runOnClient(mc -> PackChoice.reload(gameDir));
        context.waitTicks(5);
        context.runOnClient(mc -> {
            String asked = PackChoice.askedFor() == null ? null : PackChoice.askedFor().name();
            System.out.println("[LeadLight compare] pack asked for: " + asked
                    + ", session: " + PackChoice.session().map(s -> s.packFileName()).orElse("none")
                    + ", missing: " + PackChoice.packMissing()
                    + ", error: " + PackChoice.lastError().orElse("none"));
            if (PackChoice.session().isEmpty()) {
                throw new IllegalStateException("LeadLight did not load the pack " + pack
                        + ": " + PackChoice.lastError().orElse("no error given"));
            }
        });
    }
}
