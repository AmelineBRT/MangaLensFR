package app.mangalens.ui

import android.os.Build

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mangalens.capture.ScreenCaptureService
import app.mangalens.settings.AiReasoning
import app.mangalens.settings.AiVisionMode
import app.mangalens.settings.AppSettings
import app.mangalens.settings.CaptureMode
import app.mangalens.settings.EngineKind
import app.mangalens.settings.LlmProvider
import app.mangalens.settings.SettingsRepository
import app.mangalens.settings.SourceLang
import app.mangalens.translate.GoogleFreeEngine
import app.mangalens.translate.DeepLEngine
import app.mangalens.translate.MyMemoryEngine
import app.mangalens.translate.LlmEngine
import app.mangalens.translate.MlKitEngine
import app.mangalens.translate.ModelCatalog
import app.mangalens.update.UpdateChecker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    repo: SettingsRepository,
    onStart: () -> Unit,
    onArrêter: () -> Unit,
    onGrantOverlay: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by repo.flow.collectAsState(initial = AppSettings())
    val running by ScreenCaptureService.running.collectAsState()

    var overlayGranted by remember {
        mutableStateOf(android.provider.Settings.canDrawOverlays(context))
    }
    LaunchedEffect(Unit) {
        while (true) {
            overlayGranted = android.provider.Settings.canDrawOverlays(context)
            delay(1000)
        }
    }

    var update by remember { mutableStateOf<UpdateChecker.Update?>(null) }
    LaunchedEffect(Unit) {
        val installed = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        if (installed != null) {
            update = UpdateChecker.check(
                currentVersion = installed,
                sdkInt = Build.VERSION.SDK_INT,
                signingTrack = UpdateChecker.installedSigningTrack(context),
            )
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Header()
            Spacer(Modifier.height(18.dp))
            StatusCard(running, overlayGranted, onStart, onArrêter, onGrantOverlay)
            Spacer(Modifier.height(14.dp))
            update?.let {
                UpdateCard(it)
                Spacer(Modifier.height(14.dp))
            }
            EngineCard(settings, repo)
            Spacer(Modifier.height(14.dp))
            LectureCard(settings, repo)
            Spacer(Modifier.height(14.dp))
            TipsCard()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("文A", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("MangaLens", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Traduction en direct des manhwa · manga · manhua dans toutes les applications",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusCard(
    running: Boolean,
    overlayGranted: Boolean,
    onStart: () -> Unit,
    onArrêter: () -> Unit,
    onGrantOverlay: () -> Unit,
) {
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(
                            if (running) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.outline,
                            CircleShape
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (running) "Traduction de l'écran en cours" else "Arrêtée",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Spacer(Modifier.height(10.dp))
            if (!overlayGranted) {
                Text(
                    "Étape 1 · Autorisez MangaLens à s'afficher par-dessus les autres applications",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onGrantOverlay) { Text("Autoriser l'affichage par-dessus les autres applications") }
                Spacer(Modifier.height(10.dp))
            }
            if (running) {
                Button(onClick = onArrêter, modifier = Modifier.fillMaxWidth()) { Text("Arrêter") }
            } else {
                Button(
                    onClick = onStart,
                    enabled = overlayGranted,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (overlayGranted) "Démarrer la traduction" else "Autorisez d'abord l'affichage") }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Ouvrez ensuite Brave et lisez. Quand vous arrêtez de faire défiler la page, les bulles sont traduites directement. " +
                    "Touchez le bouton flottant 文A pour activer ou désactiver la traduction ; faites un appui long pour ouvrir le menu rapide (traduire maintenant, aperçu, réglages).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun EngineCard(settings: AppSettings, repo: SettingsRepository) {
    val scope = rememberCoroutineScope()
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var keyDraft by remember(settings.provider) { mutableStateOf(settings.apiKey) }
    var keyEdited by remember(settings.provider) { mutableStateOf(false) }
    var modelDraft by remember(settings.provider) { mutableStateOf(settings.model) }
    var modelEdited by remember(settings.provider) { mutableStateOf(false) }
    var customUrlDraft by remember { mutableStateOf(settings.customUrl) }
    var customUrlEdited by remember { mutableStateOf(false) }

    LaunchedEffect(settings.provider, settings.apiKey) {
        // collectAsState starts with defaults. Accept the first real DataStore
        // value, but never echo an older write over text being typed.
        if (!keyEdited) keyDraft = settings.apiKey
    }
    LaunchedEffect(settings.provider, settings.model) {
        if (!modelEdited) modelDraft = settings.model
    }
    LaunchedEffect(settings.customUrl) {
        if (!customUrlEdited) customUrlDraft = settings.customUrl
    }
    val draftSettings = settings.copy(
        apiKey = keyDraft.trim(),
        model = modelDraft.trim(),
        customUrl = customUrlDraft.trim(),
    )

    Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("Moteur de traduction")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Google", settings.engine == EngineKind.GOOGLE) {
                    scope.launch { repo.setEngine(EngineKind.GOOGLE) }
                }
                Chip("MyMemory", settings.engine == EngineKind.MYMEMORY) {
                    scope.launch { repo.setEngine(EngineKind.MYMEMORY) }
                }
                Chip("DeepL", settings.engine == EngineKind.DEEPL) {
                    scope.launch { repo.setEngine(EngineKind.DEEPL) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("IA Pro ✨", settings.engine == EngineKind.LLM) {
                    scope.launch { repo.setEngine(EngineKind.LLM) }
                }
                Chip("Hors ligne", settings.engine == EngineKind.MLKIT) {
                    scope.launch { repo.setEngine(EngineKind.MLKIT) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when (settings.engine) {
                    EngineKind.GOOGLE -> "Sans clé. Si Google bloque temporairement les requêtes, MangaLens essaie MyMemory puis le moteur hors ligne."
                    EngineKind.MYMEMORY -> "Service en ligne sans clé. Il a ses propres quotas et peut lui aussi être indisponible ; Google et le moteur hors ligne servent de secours."
                    EngineKind.DEEPL -> "DeepL API. Une clé est nécessaire ; une clé DeepL API Free peut bénéficier d’un quota gratuit. Sinon les tarifs et limites de ton compte s’appliquent. En cas d’échec, MangaLens essaie Google puis MyMemory."
                    EngineKind.LLM -> "L’IA lit les pages entières (y compris l’image) avec le contexte de l’histoire, un glossaire des noms, un ton naturel et les honorifiques. Une première traduction apparaît rapidement puis est améliorée. Une clé est nécessaire pour ce mode — celle de Gemini peut être gratuite."
                    EngineKind.MLKIT -> "100 % hors ligne après le téléchargement initial d'environ 30 Mo par langue. Qualité la plus simple."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (settings.engine == EngineKind.DEEPL) {
                Spacer(Modifier.height(12.dp))
                var showDeepLKey by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = settings.deeplApiKey,
                    onValueChange = { value -> scope.launch { repo.setDeepLApiKey(value.trim()) } },
                    label = { Text("Clé API DeepL") },
                    singleLine = true,
                    visualTransformation = if (showDeepLKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        Text(
                            if (showDeepLKey) "masquer" else "afficher",
                            modifier = Modifier.clickable { showDeepLKey = !showDeepLKey }.padding(end = 10.dp),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                val uriHandler = LocalUriHandler.current
                Text("Créer une clé DeepL API →", modifier = Modifier.clickable { uriHandler.openUri("https://www.deepl.com/pro-api") }.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }

            if (settings.engine == EngineKind.LLM) {
                Spacer(Modifier.height(12.dp))
                ProviderPicker(settings, repo)
                Spacer(Modifier.height(10.dp))
                var showKey by remember(settings.provider) { mutableStateOf(false) }
                OutlinedTextField(
                    value = keyDraft,
                    onValueChange = {
                        keyDraft = it
                        keyEdited = true
                        val provider = settings.provider
                        scope.launch { repo.setApiKey(provider, it.trim()) }
                    },
                    label = { Text(apiKeyLabel(settings.provider)) },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        Text(
                            if (showKey) "masquer" else "afficher",
                            modifier = Modifier
                                .clickable { showKey = !showKey }
                                .padding(end = 10.dp),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = modelDraft,
                    onValueChange = {
                        modelDraft = it
                        modelEdited = true
                        val provider = settings.provider
                        scope.launch { repo.setModel(provider, it.trim()) }
                    },
                    label = { Text("Modèle (vide = ${draftSettings.copy(model = "").effectiveModel()})") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (settings.provider == LlmProvider.GEMINI) {
                    Spacer(Modifier.height(6.dp))
                    GeminiModelRow(apiKey = keyDraft.trim()) { picked ->
                        modelDraft = picked
                        modelEdited = true
                        val provider = settings.provider
                        scope.launch { repo.setModel(provider, picked) }
                    }
                }
                if (settings.provider == LlmProvider.CUSTOM) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customUrlDraft,
                        onValueChange = {
                            customUrlDraft = it
                            customUrlEdited = true
                            scope.launch { repo.setCustomUrl(it.trim()) }
                        },
                        label = { Text("URL du point d'accès Chat Completions") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text("Vision IA — laisser l’IA lire directement l’image de la page", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Vision IA (recommandé)", settings.aiVision != AiVisionMode.OFF) {
                        scope.launch { repo.setAiVision(AiVisionMode.AUTO) }
                    }
                    Chip("Texte uniquement", settings.aiVision == AiVisionMode.OFF) {
                        scope.launch { repo.setAiVision(AiVisionMode.OFF) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (settings.aiVision != AiVisionMode.OFF)
                        "L’IA lit directement l’image — elle peut repérer l’écriture manuscrite, les lettrages stylisés et ce que l’OCR ne détecte pas (~150–300 Ko par page, moins avec l’économie de données). En cas d’échec, passage automatique au texte seul puis à Google."
                    else
                        "Seul le texte reconnu par l’OCR est envoyé (quelques Ko). Idéal avec une connexion très lente ; les lettrages stylisés dépendent de l’OCR de l’appareil.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Text("Raisonnement IA — durée de réflexion du modèle par page", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Rapide", settings.aiReasoning == AiReasoning.FAST) {
                        scope.launch { repo.setAiReasoning(AiReasoning.FAST) }
                    }
                    Chip("Équilibré", settings.aiReasoning == AiReasoning.BALANCED) {
                        scope.launch { repo.setAiReasoning(AiReasoning.BALANCED) }
                    }
                    Chip("Approfondi", settings.aiReasoning == AiReasoning.THOROUGH) {
                        scope.launch { repo.setAiReasoning(AiReasoning.THOROUGH) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    when (settings.aiReasoning) {
                        AiReasoning.FAST -> "Réflexion minimale : l’amélioration arrive le plus vite. Convient aux lettrages simples et horizontaux."
                        AiReasoning.BALANCED -> "Un peu de réflexion sur l’image, minimale sur le texte. Les bulles apparaissent progressivement dans les deux cas."
                        AiReasoning.THOROUGH -> "Réflexion maximale : meilleure attribution des personnages et des lettrages difficiles, avec une attente plus longue avant la première bulle."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Économie de données — envoi d’images plus petites", settings.dataSaver) {
                        scope.launch { repo.setDataSaver(!settings.dataSaver) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Diagnostics — afficher ce qui a été détecté", settings.diagnostics) {
                        scope.launch { repo.setDiagnostics(!settings.diagnostics) }
                    }
                }
                Text(
                    "Entoure chaque bulle détectée et affiche : " +
                        "OCR (lignes lues) · bulles (détectées sur la page) · " +
                        "régions (envoyées en traduction) · cartes (affichées). " +
                        "Si une bulle n’est pas traduite, cela indique à quelle étape elle a été perdue.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                val uriHandler = LocalUriHandler.current
                val keyHelp = providerKeyHelp(settings.provider)
                Text(
                    keyHelp.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                keyHelp.url?.let { url ->
                    Text(
                        keyHelp.linkLabel,
                        modifier = Modifier
                            .clickable { uriHandler.openUri(url) }
                            .padding(vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    enabled = !testing,
                    onClick = {
                        testing = true
                        testResult = null
                        scope.launch {
                            testResult = try {
                                val sample = listOf("I'll stay with you. It's okay.")
                                val out = when (settings.engine) {
                                    EngineKind.LLM -> LlmEngine(draftSettings).translate(sample, SourceLang.AUTO)
                                    EngineKind.MLKIT -> MlKitEngine().translate(sample, SourceLang.AUTO)
                                    EngineKind.GOOGLE -> GoogleFreeEngine().translate(sample, SourceLang.AUTO)
                                    EngineKind.MYMEMORY -> MyMemoryEngine().translate(sample, SourceLang.AUTO)
                                    EngineKind.DEEPL -> DeepLEngine(settings.deeplApiKey).translate(sample, SourceLang.AUTO)
                                }
                                "“I'll stay with you. It's okay.” → “" + out.first() + "”"
                            } catch (e: Exception) {
                                "⚠ " + (e.message ?: "échec")
                            } finally {
                                testing = false
                            }
                        }
                    }
                ) { Text(if (testing) "Test en cours…" else "Tester la traduction") }
            }
            testResult?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ProviderPicker(settings: AppSettings, repo: SettingsRepository) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(
                "Fournisseur : " + when (settings.provider) {
                    LlmProvider.ANTHROPIC -> "Anthropic Claude (recommandé)"
                    LlmProvider.OPENAI -> "OpenAI"
                    LlmProvider.GEMINI -> "Google Gemini"
                    LlmProvider.OPENROUTER -> "OpenRouter"
                    LlmProvider.CUSTOM -> "Point d’accès personnalisé"
                }
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LlmProvider.entries.forEach { p ->
                DropdownMenuItem(
                    text = {
                        Text(
                            when (p) {
                                LlmProvider.ANTHROPIC -> "Anthropic Claude (recommandé)"
                                LlmProvider.OPENAI -> "OpenAI"
                                LlmProvider.GEMINI -> "Google Gemini (offre gratuite)"
                                LlmProvider.OPENROUTER -> "OpenRouter"
                                LlmProvider.CUSTOM -> "Point d’accès personnalisé compatible OpenAI"
                            }
                        )
                    },
                    onClick = {
                        open = false
                        scope.launch { repo.setProvider(p) }
                    }
                )
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String,
    onCommit: (Float) -> Unit,
) {
    var v by remember(value) { mutableFloatStateOf(value) }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                display(v),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = v,
            onValueChange = { v = it },
            valueRange = range,
            onValueChangeFinished = { onCommit(v) }
        )
    }
}

@Composable
private fun LectureCard(settings: AppSettings, repo: SettingsRepository) {
    val scope = rememberCoroutineScope()
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("Lecture")
            Spacer(Modifier.height(10.dp))
            Text("Langue source · Détection automatique de l'anglais ou des langues asiatiques", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Auto", settings.sourceLang == SourceLang.AUTO) {
                    scope.launch { repo.setSourceLang(SourceLang.AUTO) }
                }
                Chip("English", settings.sourceLang == SourceLang.EN) {
                    scope.launch { repo.setSourceLang(SourceLang.EN) }
                }
                Chip("한국어", settings.sourceLang == SourceLang.KO) {
                    scope.launch { repo.setSourceLang(SourceLang.KO) }
                }
                Chip("日本語", settings.sourceLang == SourceLang.JA) {
                    scope.launch { repo.setSourceLang(SourceLang.JA) }
                }
                Chip("中文", settings.sourceLang == SourceLang.ZH) {
                    scope.launch { repo.setSourceLang(SourceLang.ZH) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Mode", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Automatique (sans intervention)", settings.mode == CaptureMode.AUTO) {
                    scope.launch { repo.setMode(CaptureMode.AUTO) }
                }
                Chip("Toucher pour traduire", settings.mode == CaptureMode.MANUAL) {
                    scope.launch { repo.setMode(CaptureMode.MANUAL) }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("Mode de rendu pour les tests", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Classique", !settings.useAccessibilityOverlay) {
                    scope.launch { repo.setUseAccessibilityOverlay(false) }
                }
                Chip("Accessibilité (test)", settings.useAccessibilityOverlay) {
                    scope.launch { repo.setUseAccessibilityOverlay(true) }
                }
            }
            Text(
                if (settings.useAccessibilityOverlay)
                    "Le mode de test utilise la couche d’accessibilité Android pour rendre les masques plus opaques. Il faut activer le service MangaLens dans les réglages Android."
                else
                    "Mode classique, recommandé par défaut : aucune activation du service d’accessibilité n’est nécessaire.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
            LabeledSlider(
                "Temps de réaction",
                settings.stabilityMs.toFloat(),
                200f..900f,
                { "${it.toInt()} ms" },
            ) { scope.launch { repo.setStabilityMs(it.toInt()) } }
            LabeledSlider(
                "Taille du texte",
                settings.textScale,
                0.8f..1.5f,
                { "${(it * 100).toInt()}%" },
            ) { scope.launch { repo.setTextScale(it) } }
            LabeledSlider(
                "Ignorer le haut de l'écran (barre du navigateur)",
                settings.ignoreTopPct,
                0f..0.15f,
                { "${(it * 100).toInt()}%" },
            ) { scope.launch { repo.setIgnoreTopPct(it) } }
        }
    }
}

/**
 * Quiet update banner for a sideloaded app: one anonymous check per app open,
 * a card only when a newer release exists, and a button that opens the exact
 * APK asset for this install's signing history. The browser still owns the
 * download and Android still asks the user before installing it.
 */
@Composable
private fun UpdateCard(update: UpdateChecker.Update) {
    val uriHandler = LocalUriHandler.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Mise à jour disponible", fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        update.requiresReinstall ->
                            "MangaLens ${update.version} est disponible, mais cette installation ne peut pas être mise à jour avec la clé de signature officielle. Notez vos clés API, téléchargez l’APK, désinstallez MangaLens puis installez-le. Les clés API seront effacées."
                        update.legacyBridge ->
                            "MangaLens ${update.version} est disponible. Cet APK compatible, utilisé une seule fois, conserve vos données tout en passant de 0.9.1 à la clé de publication privée."
                        else -> "MangaLens ${update.version} est disponible."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(10.dp))
            Button(onClick = { uriHandler.openUri(update.url) }) {
                Text(if (update.requiresReinstall) "Télécharger" else "Télécharger l'APK")
            }
        }
    }
}

private data class ProviderKeyHelp(val message: String, val linkLabel: String = "", val url: String? = null)

private fun apiKeyLabel(provider: LlmProvider): String = when (provider) {
    LlmProvider.ANTHROPIC -> "Clé API Anthropic"
    LlmProvider.OPENAI -> "Clé API OpenAI"
    LlmProvider.GEMINI -> "Clé API Gemini"
    LlmProvider.OPENROUTER -> "Clé API OpenRouter"
    LlmProvider.CUSTOM -> "Jeton Bearer (facultatif)"
}

private fun providerKeyHelp(provider: LlmProvider): ProviderKeyHelp = when (provider) {
    LlmProvider.ANTHROPIC -> ProviderKeyHelp(
        "Cette clé est enregistrée uniquement pour Anthropic.",
        "Créer une clé Anthropic →",
        "https://console.anthropic.com/settings/keys",
    )
    LlmProvider.OPENAI -> ProviderKeyHelp(
        "Cette clé est enregistrée uniquement pour OpenAI.",
        "Créer une clé OpenAI →",
        "https://platform.openai.com/api-keys",
    )
    LlmProvider.GEMINI -> ProviderKeyHelp(
        "Gemini propose une offre gratuite (aucune carte bancaire nécessaire). Cette clé est enregistrée uniquement pour Gemini.",
        "Créer une clé Gemini →",
        "https://aistudio.google.com/apikey",
    )
    LlmProvider.OPENROUTER -> ProviderKeyHelp(
        "Collez ici votre clé OpenRouter. Elle reste séparée de vos clés Anthropic, OpenAI et Gemini.",
        "Créer ou choisir une clé OpenRouter →",
        "https://openrouter.ai/settings/keys",
    )
    LlmProvider.CUSTOM -> ProviderKeyHelp(
        "Jeton Bearer facultatif pour ce point d’accès personnalisé. Par sécurité, les anciennes clés partagées ne sont pas transférées ici ; saisissez le jeton prévu pour cette URL.",
    )
}

/**
 * Fetches Google's live model list on demand and offers it as a menu — the
 * newest Flash first — so the picker shows models released long after this
 * build shipped. Manual typing in the field above always stays available.
 */
@Composable
private fun GeminiModelRow(apiKey: String, onPick: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<ModelCatalog.LiveModel>>(emptyList()) }
    Box {
        OutlinedButton(
            enabled = !loading,
            onClick = {
                if (apiKey.isBlank()) {
                    error = "Saisissez d’abord votre clé API : la liste provient de votre compte."
                    return@OutlinedButton
                }
                error = null
                if (models.isNotEmpty()) {
                    open = true
                    return@OutlinedButton
                }
                loading = true
                scope.launch {
                    try {
                        models = ModelCatalog.gemini(apiKey)
                        open = models.isNotEmpty()
                        if (models.isEmpty()) error = "Google n’a renvoyé aucun modèle utilisable."
                    } catch (e: Exception) {
                        error = "Impossible de récupérer les modèles : " + (e.message ?: "erreur réseau")
                    } finally {
                        loading = false
                    }
                }
            }
        ) { Text(if (loading) "Récupération de la liste des modèles…" else "Choisir dans la liste des modèles Google ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            models.forEach { m ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(m.label)
                            Text(
                                m.id,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        open = false
                        onPick(m.id)
                    }
                )
            }
        }
    }
    error?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun TipsCard() {
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("À savoir")
            Spacer(Modifier.height(8.dp))
            Text(
                "• Les onglets privés de Brave bloquent la capture d’écran (ils apparaissent noirs). Utilisez un onglet normal.\n" +
                    "• Les superpositions ne bloquent jamais les touches : faites défiler normalement.\n" +
                    "• Faire défiler masque immédiatement les superpositions ; lorsque vous vous arrêtez, la page est retraduite. C’est le fonctionnement du mode en direct.\n" +
                    "• L’IA Pro affiche immédiatement une première traduction rapide, puis la remplace par une version améliorée : une connexion lente ne bloque pas la lecture.\n" +
                    "• Le mode texte envoie uniquement le texte des bulles ; Vision IA envoie l’image de la page, uniquement au fournisseur que vous avez choisi.\n" +
                    "• Les noms restent cohérents : l’IA conserve un glossaire des personnages et des termes au fil de votre lecture.\n" +
                    "• Vous aimez lire des raws ? Soutenez la sortie officielle lorsqu’elle existe.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )
        }
    }
}
