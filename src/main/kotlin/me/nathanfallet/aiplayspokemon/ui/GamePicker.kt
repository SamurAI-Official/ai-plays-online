package me.nathanfallet.aiplayspokemon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.nathanfallet.aiplayspokemon.config.AppConfig
import me.nathanfallet.aiplayspokemon.games.AgentKind
import me.nathanfallet.aiplayspokemon.games.DetectedRom
import me.nathanfallet.aiplayspokemon.games.GameCatalog
import me.nathanfallet.aiplayspokemon.games.WalkthroughCatalog
import java.nio.file.Path

/**
 * Where a session starts: pick the game to play.
 *
 * Nothing is loaded until a ROM is chosen here — the app never opens a console by itself. The list is the ROMs found
 * in the ROMs folder (`<data>/roms` by default, `AI_PLAYS_ONLINE_ROMS_DIR` to change it), each one identified by its
 * own header and shown with what the app knows about it; the ROM played last time is marked, and **Browse…** takes a
 * file from anywhere.
 */
@Composable
fun GamePickerScreen(config: AppConfig, onPick: (Path) -> Unit, onBrowse: () -> Path?) {
    val games = remember { GameCatalog.scan(config.romsDirectory, config.dataDirectory) }
    val remembered = config.rememberedRomPath

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("AI plays Online", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Choose a game. Bring your own ROM: no game is included with this app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (remembered != null && games.none { it.path == remembered }) {
                    GameRow(
                        game = DetectedRom(remembered, profileOf(remembered, config)),
                        label = "Last played",
                        config = config,
                        onPick = onPick,
                    )
                }
                games.forEach { game ->
                    GameRow(
                        game = game,
                        label = if (game.path == remembered) "Last played" else null,
                        config = config,
                        onPick = onPick,
                    )
                }
                if (games.isEmpty() && remembered == null) {
                    Text("No .nds file in ${config.romsDirectory}.", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Put your ROMs there (or point AI_PLAYS_ONLINE_ROMS_DIR at the folder you keep them in), or " +
                            "use Browse… to open one from anywhere. The folder is created for you the first time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { onBrowse()?.let(onPick) }) { Text("Browse…") }
                    Text(
                        "ROMs folder: ${config.romsDirectory}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "The human can take the controls at any time; once a game is open, the panel on the right is " +
                        "where the AI is chosen and started.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The profile of a ROM outside the ROMs folder (the last played one, which may have moved). */
private fun profileOf(rom: Path, config: AppConfig) =
    runCatching { GameCatalog.detect(GameCatalog.headerOf(rom), config.dataDirectory) }
        .getOrDefault(GameCatalog.generic)

/** One game in the list: what it is, what the AI would get, and how to open it. */
@Composable
private fun GameRow(game: DetectedRom, label: String?, config: AppConfig, onPick: (Path) -> Unit) {
    val profile = game.profile
    val walkthrough = WalkthroughCatalog.of(profile, config.dataDirectory)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onPick(game.path) }) { Text(profile.title) }
            label?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        Text(
            buildString {
                append(if (profile.agent == AgentKind.TYPED) "typed agent (reads the game)" else "screen only")
                profile.gameCode.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                walkthrough?.let { append(" · walkthrough: ${it.chapters.size} chapters") }
                profile.tested.takeIf { it.isNotBlank() }?.let { append(" · $it") }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            game.path.fileName.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
