package com.music.bitchord.ui.screens

import com.music.zotifymusic.R

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AppearanceLookOverrides
import com.music.bitchord.data.settings.AppearanceSkinLibrary
import com.music.bitchord.data.settings.AppearanceSkinState
import com.music.bitchord.data.settings.UiSkin

private data class BuiltInAppearancePreset(
    val id: String,
    val nameRes: Int,
    val subtitleRes: Int,
    val descriptionRes: Int,
    val uiSkin: UiSkin,
    val overrides: AppearanceLookOverrides,
    val background: Color,
    val surface: Color,
    val accent: Color,
    val foreground: Color,
    val muted: Color,
)

private val BuiltInAppearancePresets = listOf(
    BuiltInAppearancePreset(
        id = "zotify",
        nameRes = R.string.appearance_preset_zotify_name,
        subtitleRes = R.string.appearance_preset_zotify_subtitle,
        descriptionRes = R.string.appearance_preset_zotify_description,
        uiSkin = UiSkin.ZOTIFY,
        overrides = AppearanceLookOverrides(),
        background = Color(0xFF101114),
        surface = Color(0xFF202228),
        accent = Color(0xFFF4F4F5),
        foreground = Color.White,
        muted = Color(0xFF9A9CA4),
    ),
    BuiltInAppearancePreset(
        id = "mochy",
        nameRes = R.string.appearance_preset_mochy_name,
        subtitleRes = R.string.appearance_preset_mochy_subtitle,
        descriptionRes = R.string.appearance_preset_mochy_description,
        uiSkin = UiSkin.MOCHY,
        overrides = AppearanceLookOverrides(),
        background = Color.Black,
        surface = Color(0xFF080808),
        accent = Color(0xFFE0E0E0),
        foreground = Color.White,
        muted = Color(0xFF9A9A9A),
    ),
    BuiltInAppearancePreset(
        id = "amoled-cyan",
        nameRes = R.string.appearance_preset_amoled_name,
        subtitleRes = R.string.appearance_preset_amoled_subtitle,
        descriptionRes = R.string.appearance_preset_amoled_description,
        uiSkin = UiSkin.ZOTIFY,
        overrides = AppearanceLookOverrides(
            accentArgb = Color(0xFF5DD6FF).toArgb(),
            pureBlack = true,
            thumbnailCornerRadius = 22,
        ),
        background = Color.Black,
        surface = Color(0xFF090E12),
        accent = Color(0xFF5DD6FF),
        foreground = Color.White,
        muted = Color(0xFF8AA1AC),
    ),
    BuiltInAppearancePreset(
        id = "midnight-violet",
        nameRes = R.string.appearance_preset_violet_name,
        subtitleRes = R.string.appearance_preset_violet_subtitle,
        descriptionRes = R.string.appearance_preset_violet_description,
        uiSkin = UiSkin.ZOTIFY,
        overrides = AppearanceLookOverrides(
            accentArgb = Color(0xFFB39DFF).toArgb(),
            pureBlack = true,
            thumbnailCornerRadius = 24,
        ),
        background = Color(0xFF050408),
        surface = Color(0xFF17121F),
        accent = Color(0xFFB39DFF),
        foreground = Color.White,
        muted = Color(0xFFA69BB8),
    ),
    BuiltInAppearancePreset(
        id = "emerald-night",
        nameRes = R.string.appearance_preset_emerald_name,
        subtitleRes = R.string.appearance_preset_emerald_subtitle,
        descriptionRes = R.string.appearance_preset_emerald_description,
        uiSkin = UiSkin.ZOTIFY,
        overrides = AppearanceLookOverrides(
            accentArgb = Color(0xFF63D6A5).toArgb(),
            pureBlack = true,
            thumbnailCornerRadius = 20,
        ),
        background = Color(0xFF030907),
        surface = Color(0xFF102019),
        accent = Color(0xFF63D6A5),
        foreground = Color.White,
        muted = Color(0xFF93B6A6),
    ),
    BuiltInAppearancePreset(
        id = "rose-night",
        nameRes = R.string.appearance_preset_rose_name,
        subtitleRes = R.string.appearance_preset_rose_subtitle,
        descriptionRes = R.string.appearance_preset_rose_description,
        uiSkin = UiSkin.ZOTIFY,
        overrides = AppearanceLookOverrides(
            accentArgb = Color(0xFFFF829D).toArgb(),
            pureBlack = true,
            thumbnailCornerRadius = 24,
        ),
        background = Color(0xFF0C0508),
        surface = Color(0xFF211018),
        accent = Color(0xFFFF829D),
        foreground = Color.White,
        muted = Color(0xFFC5A0AA),
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSkinLibrarySheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uiSkin by AppSettings.uiSkin.collectAsStateWithLifecycle()
    val accentArgb by AppearanceSkinState.accentArgb.collectAsStateWithLifecycle()
    val pureBlack by AppearanceSkinState.pureBlack.collectAsStateWithLifecycle()
    val cornerRadius by AppearanceSkinState.thumbnailCornerRadius.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf<String?>(null) }
    var applyingId by remember { mutableStateOf<String?>(null) }

    fun isSelected(preset: BuiltInAppearancePreset): Boolean =
        uiSkin == preset.uiSkin &&
            accentArgb == preset.overrides.accentArgb &&
            pureBlack == preset.overrides.pureBlack &&
            cornerRadius == preset.overrides.thumbnailCornerRadius

    fun applyPreset(preset: BuiltInAppearancePreset) {
        if (applyingId != null) return
        applyingId = preset.id
        status = null
        runCatching {
            // Retire a previously imported pack first so its overrides cannot
            // silently sit on top of a built-in theme.
            if (AppearanceSkinLibrary.activeId(context) != null) {
                AppearanceSkinLibrary.disable(context)
            }
            AppearanceSkinState.apply(preset.overrides)
            AppSettings.setUiSkin(preset.uiSkin)
        }.onSuccess {
            status = context.getString(R.string.appearance_preset_applied, context.getString(preset.nameRes))
        }.onFailure {
            status = it.message ?: context.getString(R.string.appearance_preset_apply_failed)
        }
        applyingId = null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                ) {
                    Icon(
                        Icons.Rounded.Palette,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(11.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.appearance_gallery_title),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.appearance_gallery_subtitle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.appearance_gallery_notice),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(14.dp),
                )
            }

            Spacer(Modifier.height(22.dp))
            Text(stringResource(R.string.appearance_gallery_section), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.appearance_gallery_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))

            BuiltInAppearancePresets.forEach { preset ->
                val selected = isSelected(preset)
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    border = BorderStroke(
                        width = if (selected) 1.5.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                    ),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        SkinPreview(preset)
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(preset.nameRes), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                Text(
                                    stringResource(preset.subtitleRes),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (selected) {
                                Icon(
                                    Icons.Rounded.CheckCircle,
                                    contentDescription = stringResource(R.string.appearance_preset_selected),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(7.dp))
                        Text(
                            stringResource(preset.descriptionRes),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(12.dp))
                        if (selected) {
                            OutlinedButton(
                                onClick = {},
                                enabled = false,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.appearance_preset_currently_applied))
                            }
                        } else {
                            Button(
                                onClick = { applyPreset(preset) },
                                enabled = applyingId == null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    if (applyingId == preset.id) stringResource(R.string.appearance_preset_applying) else stringResource(R.string.appearance_preset_apply),
                                )
                            }
                        }
                    }
                }
            }

            status?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            )
            Text(
                stringResource(R.string.appearance_gallery_footer),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SkinPreview(preset: BuiltInAppearancePreset) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(116.dp),
        color = preset.background,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, preset.accent.copy(alpha = 0.24f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (preset.id == "mochy") R.string.appearance_preview_mochy else R.string.appearance_preview_zotify),
                    color = preset.foreground,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.1.sp,
                )
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(preset.accent),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                repeat(3) { index ->
                    Surface(
                        modifier = Modifier.size(42.dp),
                        color = if (index == 1) preset.accent.copy(alpha = 0.26f) else preset.surface,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Box(
                                modifier = Modifier
                                    .size(if (index == 1) 14.dp else 11.dp)
                                    .clip(CircleShape)
                                    .background(preset.accent.copy(alpha = if (index == 1) 0.95f else 0.55f)),
                            )
                        }
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.appearance_preview_now_playing),
                        color = preset.foreground,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.appearance_preview_tagline),
                        color = preset.muted,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(preset.surface),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.42f)
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(preset.accent),
                )
            }
        }
    }
}
