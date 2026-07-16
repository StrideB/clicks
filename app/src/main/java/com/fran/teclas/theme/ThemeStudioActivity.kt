package com.fran.teclas.theme

import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Drawable
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.util.Xml
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout as AndroidLinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.fran.teclas.KeyboardThemeDrawables
import com.fran.teclas.Neu
import com.fran.teclas.NeuMode
import com.fran.teclas.brief.Brief
import com.fran.teclas.brief.BriefCategory
import com.fran.teclas.brief.BriefItem
import com.fran.teclas.brief.BriefThemes
import com.fran.teclas.brief.DailyBriefCard
import com.fran.teclas.resolveTeclasNeuTokens
import com.fran.teclas.weather.Condition
import com.fran.teclas.weather.HourSlot
import com.fran.teclas.weather.WEATHER_STYLES
import com.fran.teclas.weather.WEATHER_STYLE_CLASSIC_ID
import com.fran.teclas.weather.WeatherData
import com.fran.teclas.weather.weatherStyleById
import java.util.Locale
import org.xmlpull.v1.XmlPullParser

class ThemeStudioActivity : ComponentActivity() {
    private lateinit var repository: ThemeRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = ThemeRepository(this)
        setContent { ThemeStudioScreen(repository) }
    }

    @Composable
    private fun ThemeStudioScreen(repository: ThemeRepository) {
        val prefs = remember { getSharedPreferences(ThemeRepository.PREFS_NAME, MODE_PRIVATE) }
        val tokens = remember { resolveTeclasNeuTokens(prefs.getString("theme_mode", "system")) }
        var applied by remember { mutableStateOf(repository.active()) }
        var staged by remember { mutableStateOf(applied) }
        var selectedTab by remember { mutableStateOf(StudioTab.Brief) }
        var appliedPulse by remember { mutableIntStateOf(0) }
        val wallpapers = remember { WallpaperRegistry(this).entries() }
        val wallpaperPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                staged = staged.copy(wallpaperId = WallpaperRegistry.userWallpaperId(uri.toString()), builtIn = false, id = "custom", name = "Custom")
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .background(Color(tokens.base))
                .padding(start = 16.dp, top = 42.dp, end = 16.dp, bottom = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Theme Studio", color = Color(tokens.ink), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    Text("bundle the launcher in one tap", color = Color(tokens.inkDim), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Text(
                    "APPLY",
                    color = Color(staged.accentColor),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .background(if (staged != applied) Color(staged.accentColor).copy(alpha = 0.20f) else Color.White.copy(alpha = 0.06f))
                        .border(1.dp, if (staged != applied) Color(staged.accentColor) else Color.White.copy(alpha = 0.10f), RoundedCornerShape(99.dp))
                        .clickable(enabled = staged != applied) {
                            repository.applyTheme(staged)
                            applied = staged
                            appliedPulse++
                            Toast.makeText(this@ThemeStudioActivity, "${staged.name} applied", Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.07f))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
                        .clickable { finish() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("×", color = Color(tokens.ink), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }

            Box(
                Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .weight(0.72f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(previewWallpaperBrush(staged.wallpaperId))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(28.dp))
            ) {
                WallpaperPreviewLayer(staged.wallpaperId)
                HomePreview(staged)
                if (appliedPulse > 0) {
                    Text(
                        "APPLIED · ${applied.name.uppercase(Locale.US)}",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(Color.Black.copy(alpha = 0.34f))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            Row(
                Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .height(34.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                StudioTab.entries.forEach { tab ->
                    val dirty = tab.layerDiffers(staged, applied)
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selectedTab == tab) Color(staged.accentColor).copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f))
                            .border(1.dp, if (selectedTab == tab) Color(staged.accentColor).copy(alpha = 0.55f) else Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                            .clickable { selectedTab = tab },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(tab.label, color = Color(tokens.ink), fontSize = 9.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        if (dirty) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(5.dp).clip(CircleShape).background(Color(staged.accentColor)))
                    }
                }
            }

            Box(
                Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth()
                    .weight(0.35f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF20232A), Color(0xFF14161B))))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(22.dp))
                    .padding(12.dp)
            ) {
                when (selectedTab) {
                    StudioTab.Brief -> BriefThemeGrid(
                        selected = staged.briefThemeId,
                        accent = staged.accentColor
                    ) { staged = staged.withBriefTheme(it) }
                    StudioTab.Weather -> WeatherThemeGrid(
                        selected = staged.weatherStyleId,
                        accent = staged.accentColor
                    ) { staged = staged.withWeatherStyle(it) }
                    StudioTab.Keyboard -> KeyboardThemeGrid(
                        options = keyboardOptions(),
                        selected = staged.keyboardTheme,
                        accent = staged.accentColor
                    ) { staged = staged.asCustom().copy(keyboardTheme = it) }
                    StudioTab.Icons -> IconThemeGrid(
                        options = iconOptions(),
                        selected = staged.iconPack,
                        accent = staged.accentColor
                    ) { staged = staged.asCustom().copy(iconPack = it) }
                    StudioTab.Wallpaper -> {
                        Column {
                            OptionGrid(
                                wallpapers.map { it.name to it.id } + ("Pick file" to "pick"),
                                selected = staged.wallpaperId,
                                accent = staged.accentColor
                            ) {
                                if (it == "pick") wallpaperPicker.launch(arrayOf("image/*"))
                                else staged = staged.asCustom().copy(wallpaperId = it)
                            }
                        }
                    }
                    StudioTab.Accent -> OptionGrid(
                        accentOptions().map { it.first to it.second },
                        selected = staged.accentColor,
                        accent = staged.accentColor
                    ) { staged = staged.asCustom().copy(accentColor = it) }
                }
            }
        }
    }

    @Composable
    private fun PresetChip(theme: LauncherTheme, selected: Boolean, onClick: () -> Unit) {
        Row(
            Modifier
                .clip(RoundedCornerShape(99.dp))
                .background(if (selected) Color(theme.accentColor).copy(alpha = 0.28f) else Color.White.copy(alpha = 0.08f))
                .border(1.dp, if (selected) Color(theme.accentColor) else Color.White.copy(alpha = 0.10f), RoundedCornerShape(99.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(Color(theme.accentColor)))
            Spacer(Modifier.width(7.dp))
            Text(theme.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    private fun WallpaperPreviewLayer(id: String) {
        val drawable = remember(id) { WallpaperRegistry(this).loadDrawable(id) }
        if (drawable != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                },
                update = { view ->
                    view.setImageDrawable(drawable.constantState?.newDrawable(resources)?.mutate() ?: drawable)
                }
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f)))
        }
    }

    @Composable
    private fun HomePreview(theme: LauncherTheme) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            if (theme.weatherVisible) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(128.dp)
                        .clipToBounds(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    WeatherPreview(theme)
                }
            }
            if (theme.briefVisible) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 58.dp)
                        .fillMaxWidth()
                        .height(176.dp)
                        .padding(horizontal = 0.dp),
                    contentAlignment = Alignment.Center
                ) {
                    BriefPreview(theme)
                }
            }
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DockPreview(theme)
                KeyboardPreview(theme)
            }
        }
    }

    @Composable
    private fun WeatherPreview(theme: LauncherTheme) {
        Box(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.TopCenter) {
            if (theme.weatherStyleId == WEATHER_STYLE_CLASSIC_ID) {
                ClassicWeatherPreview(sampleWeather(), theme.accentColor)
            } else {
                WeatherStylePreviewContent(theme.weatherStyleId, theme.accentColor, scale = 0.42f)
            }
        }
    }

    @Composable
    private fun WeatherStylePreviewContent(styleId: String, accent: Int, scale: Float) {
        Box(
            Modifier
                .width(340.dp)
                .height(168.dp)
                .clipToBounds()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                ),
            contentAlignment = Alignment.Center
        ) {
            weatherStyleById(styleId).render(sampleWeather(), Color(accent), Modifier)
        }
    }

    @Composable
    private fun BriefPreview(theme: LauncherTheme) {
        Box(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
            Box(
                Modifier.graphicsLayer(
                    scaleX = 1.00f,
                    scaleY = 0.88f,
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                )
            ) {
                DailyBriefCard(BriefThemes.themeForPref(theme.briefThemeId), sampleBrief(), Modifier.fillMaxWidth())
            }
        }
    }

    @Composable
    private fun DockPreview(theme: LauncherTheme) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0x6614161B))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(22.dp)),
            factory = { context ->
                AndroidLinearLayout(context).apply {
                    orientation = AndroidLinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setPadding(dpPx(12), 0, dpPx(12), 0)
                }
            },
            update = { row ->
                row.removeAllViews()
                stagedDockIcons(theme.iconPack).forEach { drawable ->
                    val wrap = android.widget.FrameLayout(row.context).apply {
                        background = android.graphics.drawable.GradientDrawable(
                            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(0x33000000, 0x18000000)
                        ).apply {
                            cornerRadius = row.context.dpPx(16).toFloat()
                        }
                        setPadding(row.context.dpPx(4), row.context.dpPx(4), row.context.dpPx(4), row.context.dpPx(4))
                    }
                    wrap.addView(ImageView(row.context).apply {
                        setImageDrawable(drawable.constantState?.newDrawable(resources)?.mutate() ?: drawable)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }, android.widget.FrameLayout.LayoutParams(row.context.dpPx(30), row.context.dpPx(30), Gravity.CENTER))
                    row.addView(wrap, AndroidLinearLayout.LayoutParams(row.context.dpPx(38), row.context.dpPx(38)).apply {
                        leftMargin = row.context.dpPx(5)
                        rightMargin = row.context.dpPx(5)
                    })
                }
            }
        )
    }

    @Composable
    private fun KeyboardPreview(theme: LauncherTheme) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .height(142.dp),
            factory = { context ->
                AndroidLinearLayout(context).apply {
                    orientation = AndroidLinearLayout.VERTICAL
                    setPadding(dpPx(9), dpPx(9), dpPx(9), dpPx(10))
                }
            },
            update = { panel ->
                panel.removeAllViews()
                val darkMode = true
                panel.background = KeyboardThemeDrawables.panel(panel.context, theme.keyboardTheme, darkMode)
                keyboardPreviewRows().forEachIndexed { rowIndex, specs ->
                    val row = AndroidLinearLayout(panel.context).apply {
                        orientation = AndroidLinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                    }
                    specs.forEach { spec ->
                        row.addView(previewKeyView(row.context, theme, spec, darkMode), AndroidLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, spec.weight).apply {
                            leftMargin = row.context.dpPx(2)
                            rightMargin = row.context.dpPx(2)
                        })
                    }
                    panel.addView(row, AndroidLinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                        if (rowIndex > 0) topMargin = panel.context.dpPx(4)
                    })
                }
            }
        )
    }

    private data class PreviewKeySpec(val rawLabel: String, val displayLabel: String, val weight: Float)

    private fun keyboardPreviewRows(): List<List<PreviewKeySpec>> = listOf(
        "qwertyuiop".map { PreviewKeySpec(it.toString(), it.toString(), 1f) },
        "asdfghjkl".map { PreviewKeySpec(it.toString(), it.toString(), 1f) },
        listOf(PreviewKeySpec("shift", "⇧", 1.34f)) +
            "zxcvbnm".map { PreviewKeySpec(it.toString(), it.toString(), 1f) } +
            listOf(PreviewKeySpec("back", "⌫", 1.34f)),
        listOf(
            PreviewKeySpec("123", "123", 1.22f),
            PreviewKeySpec("teclas", "teclas", 1.28f),
            PreviewKeySpec("space", "space", 3.85f),
            PreviewKeySpec(".", ".", 1.05f),
            PreviewKeySpec("enter", "GO", 1.30f)
        )
    )

    private fun previewKeyView(
        context: android.content.Context,
        theme: LauncherTheme,
        spec: PreviewKeySpec,
        darkMode: Boolean
    ): TextView =
        TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            text = spec.displayLabel
            textSize = when {
                spec.displayLabel == "teclas" -> 7.5f
                spec.displayLabel.length > 1 -> 8.5f
                else -> 11f
            }
            typeface = KeyboardThemeDrawables.typeface(theme.keyboardTheme, spec.rawLabel) ?: typeface
            setTextColor(KeyboardThemeDrawables.textColor(theme.keyboardTheme, spec.rawLabel, darkMode, theme.accentColor))
            background = KeyboardThemeDrawables.keyLayer(
                context = context,
                theme = theme.keyboardTheme,
                label = spec.rawLabel,
                pressed = false,
                darkMode = darkMode,
                goColor = theme.accentColor
            )
        }

    private fun stagedDockIcons(ref: IconPackRef): List<Drawable> {
        val components = sampleDockComponents()
        val icons = components.mapNotNull { component -> iconForComponent(component, ref) }
        if (icons.size >= 5) return icons.take(5)
        return icons + previewFallbackIcons(5 - icons.size)
    }

    private fun sampleDockComponents(): List<ComponentName> {
        val preferred = listOf(
            "com.whatsapp",
            "com.android.chrome",
            "com.spotify.music",
            "org.telegram.messenger",
            "com.instagram.android",
            "com.google.android.gm",
            "com.google.android.apps.maps"
        )
        val launchables = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0
        ).mapNotNull { resolve ->
            val info = resolve.activityInfo ?: return@mapNotNull null
            ComponentName(info.packageName, info.name)
        }.filter { it.packageName != packageName }

        val preferredComponents = preferred.mapNotNull { pkg -> launchables.firstOrNull { it.packageName == pkg } }
        return (preferredComponents + launchables).distinctBy { it.flattenToString() }.take(5)
    }

    private fun iconForComponent(component: ComponentName, ref: IconPackRef): Drawable? =
        when (ref) {
            is IconPackRef.InstalledPack -> iconPackDrawableForComponent(ref.packageId, component)
            else -> null
        } ?: runCatching { packageManager.getActivityIcon(component) }.getOrNull()
            ?: runCatching { packageManager.getApplicationIcon(component.packageName) }.getOrNull()

    private fun iconPackDrawableForComponent(packageId: String, component: ComponentName): Drawable? {
        val drawableName = matchingIconPackDrawableName(packageId, component) ?: return null
        val res = runCatching { packageManager.getResourcesForApplication(packageId) }.getOrNull() ?: return null
        val id = res.getIdentifier(drawableName, "drawable", packageId)
        return if (id == 0) null else runCatching { res.getDrawable(id, this@ThemeStudioActivity.theme) }.getOrNull()
    }

    private fun matchingIconPackDrawableName(packageId: String, component: ComponentName): String? {
        val res = runCatching { packageManager.getResourcesForApplication(packageId) }.getOrNull() ?: return null
        val names = setOf(
            "ComponentInfo{${component.packageName}/${component.className}}",
            "ComponentInfo{${component.flattenToString()}}"
        )
        return runCatching {
            res.assets.open("appfilter.xml").use { stream ->
                val parser = Xml.newPullParser()
                parser.setInput(stream, null)
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && parser.name == "item") {
                        val itemComponent = parser.getAttributeValue(null, "component")
                        val drawable = parser.getAttributeValue(null, "drawable")
                        if (drawable != null && itemComponent in names) return@use drawable
                    }
                    event = parser.next()
                }
                null
            }
        }.getOrNull()
    }

    private fun previewFallbackIcons(count: Int): List<Drawable> =
        packageManager.getInstalledApplications(0)
            .asSequence()
            .filter { it.packageName != packageName }
            .mapNotNull { info -> runCatching { packageManager.getApplicationIcon(info.packageName) }.getOrNull() }
            .take(count)
            .toList()

    private fun android.content.Context.dpPx(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun dpPx(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    @Composable
    private fun <T> OptionGrid(options: List<Pair<String, T>>, selected: T, accent: Int, onSelect: (T) -> Unit) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            val rows = options.chunked(3)
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (label, value) ->
                        val isSelected = value == selected
                        Box(
                            Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (isSelected) Color(accent).copy(alpha = 0.24f) else Color.White.copy(alpha = 0.06f))
                                .border(1.dp, if (isSelected) Color(accent) else Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                                .clickable { onSelect(value) }
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label, color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    @Composable
    private fun IconThemeGrid(options: List<Pair<String, IconPackRef>>, selected: IconPackRef, accent: Int, onSelect: (IconPackRef) -> Unit) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            options.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (label, ref) ->
                        PreviewCard(
                            label = label,
                            selected = ref == selected,
                            accent = accent,
                            modifier = Modifier.weight(1f).height(108.dp),
                            onClick = { onSelect(ref) }
                        ) {
                            IconPackPreview(ref, accent)
                        }
                    }
                    repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    @Composable
    private fun IconPackPreview(ref: IconPackRef, accent: Int) {
        val drawables = remember(ref) {
            when (ref) {
                is IconPackRef.InstalledPack -> previewDrawablesForPack(ref.packageId)
                else -> emptyList()
            }
        }
        if (drawables.isNotEmpty()) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(42.dp),
                factory = { context ->
                    AndroidLinearLayout(context).apply {
                        orientation = AndroidLinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                    }
                },
                update = { row ->
                    row.removeAllViews()
                    drawables.take(5).forEach { drawable ->
                        row.addView(ImageView(row.context).apply {
                            setImageDrawable(drawable.constantState?.newDrawable(resources)?.mutate() ?: drawable)
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            adjustViewBounds = true
                            setPadding(4, 4, 4, 4)
                        }, AndroidLinearLayout.LayoutParams(42, 42).apply {
                            leftMargin = 4
                            rightMargin = 4
                        })
                    }
                }
            )
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                repeat(5) { idx ->
                    val brush = when (ref) {
                        is IconPackRef.BuiltInStyle -> if (ref.id == "mono") {
                            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.88f), Color.White.copy(alpha = 0.58f)))
                        } else {
                            previewIconBrush(accent, idx)
                        }
                        else -> previewIconBrush(accent, idx)
                    }
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(10.dp)).background(brush))
                }
            }
        }
    }

    @Composable
    private fun BriefThemeGrid(selected: String, accent: Int, onSelect: (String) -> Unit) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BriefThemes.all.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { theme ->
                        val selectedTheme = theme.id.toString() == BriefThemes.themeForPref(selected).id.toString()
                        PreviewCard(
                            label = theme.name,
                            selected = selectedTheme,
                            accent = accent,
                            modifier = Modifier.weight(1f).height(132.dp),
                            onClick = { onSelect(theme.id.toString()) }
                        ) {
                            Box(Modifier.fillMaxWidth().graphicsLayer(scaleX = 0.72f, scaleY = 0.72f), contentAlignment = Alignment.Center) {
                                DailyBriefCard(theme, sampleBrief(), Modifier.fillMaxWidth())
                            }
                        }
                    }
                    repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    @Composable
    private fun WeatherThemeGrid(selected: String, accent: Int, onSelect: (String) -> Unit) {
        val styles = listOf<Any>(WEATHER_STYLE_CLASSIC_ID) + WEATHER_STYLES
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            styles.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { item ->
                        val id = if (item is String) item else (item as com.fran.teclas.weather.WeatherStyle).id
                        val name = if (item is String) "Classic" else (item as com.fran.teclas.weather.WeatherStyle).name
                        PreviewCard(
                            label = name,
                            selected = id == selected,
                            accent = accent,
                            modifier = Modifier.weight(1f).height(124.dp),
                            onClick = { onSelect(id) }
                        ) {
                            Box(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
                                if (id == WEATHER_STYLE_CLASSIC_ID) ClassicWeatherPreview(sampleWeather(), accent)
                                else WeatherStylePreviewContent(id, accent, scale = 0.46f)
                            }
                        }
                    }
                    repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    @Composable
    private fun KeyboardThemeGrid(options: List<String>, selected: String, accent: Int, onSelect: (String) -> Unit) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            options.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { themeId ->
                        PreviewCard(
                            label = KeyboardThemeDrawables.displayName(themeId),
                            selected = themeId == selected,
                            accent = accent,
                            modifier = Modifier.weight(1f).height(116.dp),
                            onClick = { onSelect(themeId) }
                        ) {
                            KeyboardPreview(stagedKeyboardTheme(themeId, accent))
                        }
                    }
                    repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    @Composable
    private fun PreviewCard(label: String, selected: Boolean, accent: Int, modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
        Column(
            modifier
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF0F1116))
                .border(1.dp, if (selected) Color(accent) else Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
                .clickable(onClick = onClick)
                .padding(8.dp)
        ) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { content() }
            Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                if (selected) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(Color(accent)))
                    Spacer(Modifier.width(5.dp))
                }
                Text(label.uppercase(Locale.US), color = Color.White.copy(alpha = if (selected) 0.95f else 0.58f), fontSize = 8.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    private fun LauncherTheme.asCustom(): LauncherTheme =
        if (builtIn) copy(id = "custom", name = "Custom", builtIn = false) else this

    private fun LauncherTheme.withBriefTheme(id: String): LauncherTheme =
        BriefThemes.themeForPref(id).let { briefTheme ->
            asCustom().copy(
                briefThemeId = id,
                briefStyle = ThemeRepository.briefStyleForPref(id),
                briefVisible = true,
                wallpaperId = briefTheme.wallpaperId ?: wallpaperId
            )
        }

    private fun LauncherTheme.withWeatherStyle(id: String): LauncherTheme =
        asCustom().copy(weatherStyleId = id, weatherStyle = ThemeRepository.weatherStyleForPref(id), weatherVisible = true)

    private fun stagedKeyboardTheme(themeId: String, accent: Int): LauncherTheme =
        LauncherTheme(
            id = "preview",
            name = "Preview",
            briefStyle = BriefStyle.AGENDA,
            briefThemeId = "1",
            briefVisible = true,
            weatherStyle = WeatherStyle.HEADER,
            weatherStyleId = WEATHER_STYLE_CLASSIC_ID,
            weatherVisible = true,
            keyboardTheme = themeId,
            iconPack = IconPackRef.System,
            wallpaperId = "midnight",
            accentColor = accent
        )

    private fun keyboardOptions(): List<String> =
        (listOf("default", "teclas", "skeuo", "gokeys", "brushed", "seeme") + KeyboardThemeDrawables.cycleThemes).distinct()

    private fun iconOptions(): List<Pair<String, IconPackRef>> =
        listOf(
            "System" to IconPackRef.System,
            "Mono" to IconPackRef.BuiltInStyle("mono"),
            "Tinted" to IconPackRef.BuiltInStyle("tinted")
        ) + installedIconPacks().map { it.first to IconPackRef.InstalledPack(it.second) }

    private fun installedIconPacks(): List<Pair<String, String>> =
        packageManager.getInstalledApplications(0).mapNotNull { info ->
            val res = runCatching { packageManager.getResourcesForApplication(info.packageName) }.getOrNull() ?: return@mapNotNull null
            val hasFilter = runCatching { res.assets.open("appfilter.xml").use { true } }.getOrDefault(false)
            if (hasFilter) packageManager.getApplicationLabel(info).toString() to info.packageName else null
        }.sortedBy { it.first.lowercase(Locale.US) }

    private fun previewDrawablesForPack(packageId: String): List<Drawable> {
        val res = runCatching { packageManager.getResourcesForApplication(packageId) }.getOrNull() ?: return emptyList()
        val drawableNames = runCatching {
            res.assets.open("appfilter.xml").use { stream ->
                val parser = Xml.newPullParser()
                parser.setInput(stream, null)
                val names = linkedSetOf<String>()
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT && names.size < 10) {
                    if (event == XmlPullParser.START_TAG && parser.name == "item") {
                        parser.getAttributeValue(null, "drawable")
                            ?.takeIf { it.isNotBlank() }
                            ?.let { names.add(it) }
                    }
                    event = parser.next()
                }
                names.toList()
            }
        }.getOrDefault(emptyList())
        return drawableNames.mapNotNull { name ->
            runCatching {
                val id = res.getIdentifier(name, "drawable", packageId)
                if (id == 0) null else res.getDrawable(id, theme)
            }.getOrNull()
        }.take(5)
    }

    private fun accentOptions(): List<Pair<String, Int>> = listOf(
        "Violet" to AndroidColor.parseColor("#C9A7FF"),
        "Blue" to AndroidColor.parseColor("#6EA8FE"),
        "Tiffany" to AndroidColor.parseColor("#0ABAB5"),
        "Mint" to AndroidColor.parseColor("#57E3B6"),
        "Amber" to AndroidColor.parseColor("#FFB454"),
        "Red" to AndroidColor.parseColor("#FF3B30"),
        "Coral" to AndroidColor.parseColor("#FF5A3C"),
        "Pink" to AndroidColor.parseColor("#FF5AA5"),
        "Lime" to AndroidColor.parseColor("#B7F34A"),
        "White" to AndroidColor.parseColor("#E9EDF5")
    )

    private fun sampleBrief(): Brief = Brief(
        items = listOf(
            BriefItem("1", "Reply to Sarah about Q3 deck", "Gmail · before 2pm", BriefCategory.EMAIL, "Open"),
            BriefItem("2", "Review dinner plan with Mara", "Messages · tonight at 9", BriefCategory.MESSAGE, "Reply"),
            BriefItem("3", "Join product sync", "Calendar · 4:30", BriefCategory.CALENDAR, "Open")
        ),
        generatedAt = System.currentTimeMillis(),
        source = Brief.Source.RULES
    )

    private fun sampleWeather(): WeatherData = WeatherData(
        temp = 77,
        feelsLike = 86,
        humidity = 64,
        windMph = 3,
        condition = Condition.SUNNY,
        conditionLabel = "Clear",
        place = "Home",
        hi = 82,
        lo = 68,
        hourly = listOf(
            HourSlot("NOW", Condition.SUNNY, 77),
            HourSlot("9P", Condition.CLOUDY, 74),
            HourSlot("12A", Condition.CLOUDY, 71)
        )
    )

    @Composable
    private fun ClassicWeatherPreview(data: WeatherData, accent: Int) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${data.temp}°", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(data.conditionLabel.uppercase(Locale.US), color = Color.White.copy(alpha = 0.72f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text("Feels ${data.feelsLike}° · ${data.windMph} mph", color = Color.White.copy(alpha = 0.62f), fontSize = 10.sp)
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(30.dp).clip(CircleShape).background(Color(accent)))
        }
    }

    private fun previewWallpaperBrush(id: String): Brush = when (id) {
        "slate" -> Brush.linearGradient(listOf(Color(0xFF20242C), Color(0xFF070A0E)))
        "ember" -> Brush.linearGradient(listOf(Color(0xFF3A1D13), Color(0xFF0A0908)))
        "mist" -> Brush.linearGradient(listOf(Color(0xFFE6EBEF), Color(0xFFAAB3BF)))
        "system" -> Brush.linearGradient(listOf(Color(0xFF1A2A32), Color(0xFF090E12)))
        else -> Brush.linearGradient(listOf(Color(0xFF111923), Color(0xFF05070A), Color(0xFF141016)))
    }

    private fun previewIconBrush(accent: Int, index: Int): Brush {
        val base = Color(accent)
        val colors = listOf(base.copy(alpha = 0.95f), Color.White.copy(alpha = 0.14f), Color.Black.copy(alpha = 0.28f))
        return if (index % 2 == 0) Brush.radialGradient(colors) else Brush.linearGradient(colors)
    }

    private fun readableOn(color: Int): Color {
        val r = AndroidColor.red(color)
        val g = AndroidColor.green(color)
        val b = AndroidColor.blue(color)
        val luma = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        return if (luma > 0.62) Color(0xFF111318) else Color.White
    }
}

private enum class StudioTab(val label: String) {
    Brief("Brief"),
    Weather("Weather"),
    Keyboard("Keys"),
    Icons("Icons"),
    Wallpaper("Wall"),
    Accent("Accent");

    fun layerDiffers(a: LauncherTheme, b: LauncherTheme): Boolean = when (this) {
        Brief -> a.briefThemeId != b.briefThemeId || a.briefVisible != b.briefVisible
        Weather -> a.weatherStyleId != b.weatherStyleId || a.weatherVisible != b.weatherVisible
        Keyboard -> a.keyboardTheme != b.keyboardTheme
        Icons -> a.iconPack != b.iconPack
        Wallpaper -> a.wallpaperId != b.wallpaperId
        Accent -> a.accentColor != b.accentColor
    }
}
