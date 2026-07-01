package com.fran.clicks

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LauncherPhoto(
    val id: Long,
    val uri: Uri,
    val dateTakenMs: Long,
    val bucket: String
)

data class LauncherPhotoAlbum(
    val bucket: String?,
    val name: String,
    val count: Int,
    val coverUri: Uri?
)

@Composable
fun LauncherPhotos(
    hasPermission: Boolean,
    selectedBucket: String?,
    selectedPhotoId: Long?,
    brandStamp: String,
    onPhotoSelected: (Long) -> Unit,
    onOpenExternalPhoto: (Uri) -> Unit,
    onRequestPermission: () -> Unit
) {
    val context = LocalContext.current
    val photos by produceState<List<LauncherPhoto>>(emptyList(), hasPermission, selectedBucket) {
        value = if (hasPermission) loadRecentPhotos(context, selectedBucket) else emptyList()
    }
    var selected by remember(photos, selectedPhotoId) {
        mutableStateOf(photos.firstOrNull { it.id == selectedPhotoId } ?: photos.firstOrNull())
    }
    val active = selected ?: photos.firstOrNull()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PhotoPanel)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.07f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(size.width * 0.78f, size.height * 0.12f),
                        radius = size.minDimension * 0.82f
                    )
                )
            }
    ) {
        when {
            !hasPermission -> PermissionState(onRequestPermission)
            photos.isEmpty() -> EmptyPhotoState()
            active != null -> PhotoBrowser(
                active,
                photos,
                onSelect = {
                    selected = it
                    onPhotoSelected(it.id)
                },
                onOpenExternal = { onOpenExternalPhoto(active.uri) },
                brandStamp = brandStamp
            )
        }
    }
}

@Composable
fun LauncherPhotoAlbumsDock(
    hasPermission: Boolean,
    selectedBucket: String?,
    selectedPhotoId: Long?,
    onRequestPermission: () -> Unit,
    onPhotoSelected: (Long) -> Unit,
    onBucketSelected: (String?) -> Unit
) {
    val context = LocalContext.current
    val albums by produceState<List<LauncherPhotoAlbum>>(emptyList(), hasPermission) {
        value = if (hasPermission) loadPhotoAlbums(context) else emptyList()
    }
    val stripPhotos by produceState<List<LauncherPhoto>>(emptyList(), hasPermission, selectedBucket) {
        value = if (hasPermission) loadRecentPhotos(context, selectedBucket) else emptyList()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF12151A), Color(0xFF08090B), Color(0xFF020203))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        if (!hasPermission) {
            Box(
                Modifier
                    .height(42.dp)
                    .clip(RoundedCornerShape(21.dp))
                    .background(Color(0xFFDCE6FF))
                    .clickable(onClick = onRequestPermission)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    text = "ALLOW PHOTOS",
                    style = TextStyle(color = Color(0xFF080A0F), fontSize = 11.sp, fontWeight = FontWeight.Black)
                )
            }
            return@Box
        }
        if (albums.isEmpty() && stripPhotos.isEmpty()) {
            BasicText("NO ALBUMS", style = TextStyle(color = PhotoInkDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace))
            return@Box
        }
        Column(Modifier.fillMaxSize().padding(vertical = 10.dp)) {
            DockSectionLabel("FILMSTRIP")
            LazyRow(
                modifier = Modifier.fillMaxWidth().height(78.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(stripPhotos, key = { it.id }) { photo ->
                    PhotoTile(photo, selected = photo.id == selectedPhotoId, onClick = { onPhotoSelected(photo.id) })
                }
            }
            Spacer(Modifier.height(8.dp))
            DockSectionLabel("ALBUMS")
            LazyRow(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(albums, key = { it.name }) { album ->
                    AlbumDockTile(
                        album = album,
                        selected = album.bucket == selectedBucket,
                        onClick = { onBucketSelected(album.bucket) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoBrowser(
    active: LauncherPhoto,
    photos: List<LauncherPhoto>,
    onSelect: (LauncherPhoto) -> Unit,
    onOpenExternal: () -> Unit,
    brandStamp: String
) {
    Box(Modifier.fillMaxSize()) {
        HeroPhoto(active, Modifier.fillMaxSize(), onDoubleTap = onOpenExternal, brandStamp = brandStamp)
    }
}

@Composable
private fun PhotoHeader(count: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            BasicText(
                text = "ZEISS OPTICS",
                style = TextStyle(color = PhotoInk, fontSize = 18.sp, fontWeight = FontWeight.Black)
            )
            Spacer(Modifier.height(2.dp))
            BasicText(
                text = "$count recent images on this phone",
                style = TextStyle(color = PhotoInkDim, fontSize = 11.sp)
            )
        }
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFF233D8F), Color(0xFF05070D))))
                .border(1.dp, Color(0xFF3457C4), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            BasicText(
                text = "Z",
                style = TextStyle(color = Color(0xFFDCE6FF), fontSize = 13.sp, fontWeight = FontWeight.Black)
            )
        }
    }
}

@Composable
private fun HeroPhoto(photo: LauncherPhoto, modifier: Modifier = Modifier, onDoubleTap: () -> Unit = {}, brandStamp: String = "") {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(0.dp))
            .background(Color(0xFF0A0B0F))
            .pointerInput(photo.id) {
                detectTapGestures(onDoubleTap = { onDoubleTap() })
            }
    ) {
        PhotoThumb(photo.uri, Modifier.fillMaxSize(), large = true)
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.68f))
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
        ) {
            BasicText(
                text = photo.bucket.ifBlank { "Camera" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = PhotoInk, fontSize = 18.sp, fontWeight = FontWeight.Black)
            )
            Spacer(Modifier.height(3.dp))
            BasicText(
                text = formatPhotoDate(photo.dateTakenMs),
                style = TextStyle(color = PhotoInkDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            )
        }
        if (brandStamp.isNotBlank()) {
            PhotoBrandStamp(
                label = brandStamp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            )
        }
    }
}

@Composable
private fun PhotoBrandStamp(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.38f), Color.Black.copy(alpha = 0.22f))
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
            .padding(horizontal = 11.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = label,
            maxLines = 1,
            style = TextStyle(
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 9.sp,
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        )
    }
}

@Composable
private fun DockSectionLabel(label: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                color = PhotoInkDim,
                fontSize = 8.5.sp,
                letterSpacing = 1.4.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.height(1.dp).weight(1f).background(Color.White.copy(alpha = 0.08f)))
    }
}

@Composable
private fun AlbumDockTile(album: LauncherPhotoAlbum, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .width(if (album.bucket == null) 118.dp else 92.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(width = if (album.bucket == null) 112.dp else 86.dp, height = if (album.bucket == null) 88.dp else 74.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF0D0F13))
                .border(
                    1.dp,
                    if (selected) Color(0xFFDCE6FF).copy(alpha = 0.88f) else Color.White.copy(alpha = 0.07f),
                    RoundedCornerShape(18.dp)
                )
        ) {
            if (album.coverUri != null) {
                PhotoThumb(album.coverUri, Modifier.fillMaxSize(), large = false)
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.64f))))
            )
            BasicText(
                text = album.count.toString(),
                style = TextStyle(color = PhotoInk, fontSize = 10.sp, fontWeight = FontWeight.Black),
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
            )
        }
        Spacer(Modifier.height(5.dp))
        BasicText(
            text = album.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = if (selected) Color(0xFFDCE6FF) else PhotoInkDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        )
    }
}

@Composable
private fun FullScreenPhoto(photo: LauncherPhoto, onClose: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(onClick = onClose)
    ) {
        PhotoThumb(photo.uri, Modifier.fillMaxSize(), large = true)
        Box(
            Modifier
                .fillMaxWidth()
                .height(96.dp)
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent)))
        )
        BasicText(
            text = "TAP TO CLOSE",
            style = TextStyle(color = Color.White.copy(alpha = 0.72f), fontSize = 9.sp, letterSpacing = 1.4.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 18.dp)
        )
    }
}

@Composable
private fun PhotoTile(photo: LauncherPhoto, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF0D0F13))
            .border(
                1.dp,
                if (selected) Color(0xFFDCE6FF).copy(alpha = 0.82f) else Color.White.copy(alpha = 0.07f),
                RoundedCornerShape(18.dp)
            )
            .clickable(onClick = onClick)
    ) {
        PhotoThumb(photo.uri, Modifier.fillMaxSize(), large = false)
        if (selected) {
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.08f)))
        }
    }
}

@Composable
private fun PhotoThumb(uri: Uri, modifier: Modifier, large: Boolean) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, uri, large) {
        value = withContext(Dispatchers.IO) {
            loadPhotoBitmap(context, uri, large)
        }
    }
    if (bitmap != null) {
        Image(bitmap!!.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop)
    } else {
        Box(
            modifier.background(
                Brush.radialGradient(listOf(Color(0xFF2A2E36), Color(0xFF090A0D)))
            )
        )
    }
}

private fun loadPhotoBitmap(context: Context, uri: Uri, large: Boolean): Bitmap? {
    return runCatching {
        if (!large && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return@runCatching context.contentResolver.loadThumbnail(uri, Size(280, 280), null)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val maxSide = if (large) 2400 else 420
                val width = info.size.width.coerceAtLeast(1)
                val height = info.size.height.coerceAtLeast(1)
                val longest = maxOf(width, height)
                if (longest > maxSide) {
                    val scale = maxSide.toFloat() / longest.toFloat()
                    decoder.setTargetSize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
    }.getOrNull()
}

@Composable
private fun PermissionState(onRequestPermission: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        BasicText(
            text = "Photos need permission",
            style = TextStyle(color = PhotoInk, fontSize = 20.sp, fontWeight = FontWeight.Black)
        )
        Spacer(Modifier.height(8.dp))
        BasicText(
            text = "Allow Clicks to read your recent images.",
            style = TextStyle(color = PhotoInkDim, fontSize = 12.sp)
        )
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier
                .height(42.dp)
                .clip(RoundedCornerShape(21.dp))
                .background(Color(0xFFDCE6FF))
                .clickable(onClick = onRequestPermission)
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            BasicText(
                text = "ALLOW PHOTOS",
                style = TextStyle(color = Color(0xFF080A0F), fontSize = 11.sp, fontWeight = FontWeight.Black)
            )
        }
    }
}

@Composable
private fun EmptyPhotoState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BasicText(
            text = "No recent photos found.",
            style = TextStyle(color = PhotoInkDim, fontSize = 13.sp)
        )
    }
}

fun loadRecentPhotos(context: Context, selectedBucket: String? = null): List<LauncherPhoto> {
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        @Suppress("DEPRECATION")
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME
    )
    val sort = "${MediaStore.Images.Media.DATE_TAKEN} DESC"
    val selection = selectedBucket?.let { "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME}=?" }
    val selectionArgs = selectedBucket?.let { arrayOf(it) }
    val photos = mutableListOf<LauncherPhoto>()
    runCatching {
        context.contentResolver.query(collection, projection, selection, selectionArgs, sort)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext() && photos.size < 60) {
                val id = cursor.getLong(idCol)
                photos += LauncherPhoto(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    dateTakenMs = cursor.getLong(dateCol),
                    bucket = cursor.getString(bucketCol).orEmpty()
                )
            }
        }
    }
    return photos
}

fun loadPhotoAlbums(context: Context): List<LauncherPhotoAlbum> {
    val photos = loadRecentPhotos(context, null)
    if (photos.isEmpty()) return emptyList()
    val grouped = linkedMapOf<String, MutableList<LauncherPhoto>>()
    photos.forEach { photo ->
        grouped.getOrPut(photo.bucket.ifBlank { "Camera" }) { mutableListOf() }.add(photo)
    }
    return listOf(LauncherPhotoAlbum(null, "All", photos.size, photos.firstOrNull()?.uri)) +
        grouped.map { (bucket, bucketPhotos) ->
            LauncherPhotoAlbum(bucket, bucket, bucketPhotos.size, bucketPhotos.firstOrNull()?.uri)
        }
}

private fun formatPhotoDate(value: Long): String {
    if (value <= 0L) return "Recently"
    return SimpleDateFormat("EEE, MMM d  h:mm a", Locale.US).format(Date(value))
}

private val PhotoPanel = Color(0xFF101216)
private val PhotoInk = Color(0xFFF3F0E7)
private val PhotoInkDim = Color(0xFF9A9EA8)
