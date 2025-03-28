package dev.jdtech.jellyfin.utils

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import androidx.core.net.toUri
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.FindroidSources
import dev.jdtech.jellyfin.models.FindroidTrickplayInfo
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.toFindroidEpisodeDto
import dev.jdtech.jellyfin.models.toFindroidMediaStreamDto
import dev.jdtech.jellyfin.models.toFindroidMovieDto
import dev.jdtech.jellyfin.models.toFindroidSeasonDto
import dev.jdtech.jellyfin.models.toFindroidSegmentsDto
import dev.jdtech.jellyfin.models.toFindroidShowDto
import dev.jdtech.jellyfin.models.toFindroidSourceDto
import dev.jdtech.jellyfin.models.toFindroidTrickplayInfoDto
import dev.jdtech.jellyfin.models.toFindroidUserDataDto
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.io.File
import java.util.UUID
import kotlin.Exception
import kotlin.math.ceil
import dev.jdtech.jellyfin.core.R as CoreR

class DownloaderImpl(
    private val context: Context,
    private val database: ServerDatabaseDao,
    private val jellyfinRepository: JellyfinRepository,
    private val appPreferences: AppPreferences,
) : Downloader {
    private val downloadManager = context.getSystemService(DownloadManager::class.java)

    override suspend fun downloadItem(
        item: FindroidItem,
        sourceId: String,
        storageIndex: Int,
    ): Pair<Long, UiText?> {
        try {
            val source = jellyfinRepository.getMediaSources(item.id, true).first { it.id == sourceId }
            val segments = jellyfinRepository.getSegments(item.id)
            val trickplayInfo = if (item is FindroidSources) {
                item.trickplayInfo?.get(sourceId)
            } else {
                null
            }
            val storageLocation = context.getExternalFilesDirs(null)[storageIndex]
            if (storageLocation == null || Environment.getExternalStorageState(storageLocation) != Environment.MEDIA_MOUNTED) {
                return Pair(-1, UiText.StringResource(CoreR.string.storage_unavailable))
            }
            val path =
                Uri.fromFile(File(storageLocation, "downloads/${item.id}.${source.id}.download"))
            val stats = StatFs(storageLocation.path)
            if (stats.availableBytes < source.size) {
                return Pair(
                    -1,
                    UiText.StringResource(
                        CoreR.string.not_enough_storage,
                        Formatter.formatFileSize(context, source.size),
                        Formatter.formatFileSize(context, stats.availableBytes),
                    ),
                )
            }
            when (item) {
                is FindroidMovie -> {
                    database.insertMovie(item.toFindroidMovieDto(appPreferences.getValue(appPreferences.currentServer)))
                    database.insertSource(source.toFindroidSourceDto(item.id, path.path.orEmpty()))
                    database.insertUserData(item.toFindroidUserDataDto(jellyfinRepository.getUserId()))
                    downloadExternalMediaStreams(item, source, storageIndex)
                    if (trickplayInfo != null) {
                        downloadTrickplayData(item.id, sourceId, trickplayInfo)
                    }
                    segments.forEach {
                        database.insertSegment(it.toFindroidSegmentsDto(item.id))
                    }
                    val request = DownloadManager.Request(source.path.toUri())
                        .setTitle(item.name)
                        .setAllowedOverMetered(appPreferences.getValue(appPreferences.downloadOverMobileData))
                        .setAllowedOverRoaming(appPreferences.getValue(appPreferences.downloadWhenRoaming))
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationUri(path)
                    val downloadId = downloadManager.enqueue(request)
                    database.setSourceDownloadId(source.id, downloadId)
                    return Pair(downloadId, null)
                }

                is FindroidEpisode -> {
                    database.insertShow(
                        jellyfinRepository.getShow(item.seriesId)
                            .toFindroidShowDto(appPreferences.getValue(appPreferences.currentServer)),
                    )
                    database.insertSeason(
                        jellyfinRepository.getSeason(item.seasonId).toFindroidSeasonDto(),
                    )
                    database.insertEpisode(item.toFindroidEpisodeDto(appPreferences.getValue(appPreferences.currentServer)))
                    database.insertSource(source.toFindroidSourceDto(item.id, path.path.orEmpty()))
                    database.insertUserData(item.toFindroidUserDataDto(jellyfinRepository.getUserId()))
                    downloadExternalMediaStreams(item, source, storageIndex)
                    if (trickplayInfo != null) {
                        downloadTrickplayData(item.id, sourceId, trickplayInfo)
                    }
                    segments.forEach {
                        database.insertSegment(it.toFindroidSegmentsDto(item.id))
                    }
                    val request = DownloadManager.Request(source.path.toUri())
                        .setTitle(item.name)
                        .setAllowedOverMetered(appPreferences.getValue(appPreferences.downloadOverMobileData))
                        .setAllowedOverRoaming(appPreferences.getValue(appPreferences.downloadWhenRoaming))
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationUri(path)
                    val downloadId = downloadManager.enqueue(request)
                    database.setSourceDownloadId(source.id, downloadId)
                    return Pair(downloadId, null)
                }
            }
            return Pair(-1, null)
        } catch (e: Exception) {
            try {
                val source = jellyfinRepository.getMediaSources(item.id).first { it.id == sourceId }
                deleteItem(item, source)
            } catch (_: Exception) {}

            return Pair(-1, if (e.message != null) UiText.DynamicString(e.message!!) else UiText.StringResource(CoreR.string.unknown_error))
        }
    }

    override suspend fun cancelDownload(item: FindroidItem, source: FindroidSource) {
        if (source.downloadId != null) {
            downloadManager.remove(source.downloadId!!)
        }
        deleteItem(item, source)
    }

    override suspend fun deleteItem(item: FindroidItem, source: FindroidSource) {
        when (item) {
            is FindroidMovie -> {
                database.deleteMovie(item.id)
            }
            is FindroidEpisode -> {
                database.deleteEpisode(item.id)
                val remainingEpisodes = database.getEpisodesBySeasonId(item.seasonId)
                if (remainingEpisodes.isEmpty()) {
                    database.deleteSeason(item.seasonId)
                    database.deleteUserData(item.seasonId)
                    val remainingSeasons = database.getSeasonsByShowId(item.seriesId)
                    if (remainingSeasons.isEmpty()) {
                        database.deleteShow(item.seriesId)
                        database.deleteUserData(item.seriesId)
                    }
                }
            }
        }

        database.deleteSource(source.id)
        File(source.path).delete()

        val mediaStreams = database.getMediaStreamsBySourceId(source.id)
        for (mediaStream in mediaStreams) {
            File(mediaStream.path).delete()
        }
        database.deleteMediaStreamsBySourceId(source.id)

        database.deleteUserData(item.id)

        File(context.filesDir, "trickplay/${item.id}").deleteRecursively()
    }

    override suspend fun getProgress(downloadId: Long?): Pair<Int, Int> {
        var downloadStatus = -1
        var progress = -1
        if (downloadId == null) {
            return Pair(downloadStatus, progress)
        }
        val query = DownloadManager.Query()
            .setFilterById(downloadId)
        val cursor = downloadManager.query(query)
        if (cursor.moveToFirst()) {
            downloadStatus = cursor.getInt(
                cursor.getColumnIndexOrThrow(
                    DownloadManager.COLUMN_STATUS,
                ),
            )
            when (downloadStatus) {
                DownloadManager.STATUS_RUNNING -> {
                    val totalBytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    if (totalBytes > 0) {
                        val downloadedBytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        progress = downloadedBytes.times(100).div(totalBytes).toInt()
                    }
                }
                DownloadManager.STATUS_SUCCESSFUL -> {
                    progress = 100
                }
            }
        } else {
            downloadStatus = DownloadManager.STATUS_FAILED
        }
        return Pair(downloadStatus, progress)
    }

    private fun downloadExternalMediaStreams(
        item: FindroidItem,
        source: FindroidSource,
        storageIndex: Int = 0,
    ) {
        val storageLocation = context.getExternalFilesDirs(null)[storageIndex]
        for (mediaStream in source.mediaStreams.filter { it.isExternal }) {
            val id = UUID.randomUUID()
            val streamPath = Uri.fromFile(File(storageLocation, "downloads/${item.id}.${source.id}.$id.download"))
            database.insertMediaStream(mediaStream.toFindroidMediaStreamDto(id, source.id, streamPath.path.orEmpty()))
            val request = DownloadManager.Request(Uri.parse(mediaStream.path))
                .setTitle(mediaStream.title)
                .setAllowedOverMetered(appPreferences.getValue(appPreferences.downloadOverMobileData))
                .setAllowedOverRoaming(appPreferences.getValue(appPreferences.downloadWhenRoaming))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
                .setDestinationUri(streamPath)
            val downloadId = downloadManager.enqueue(request)
            database.setMediaStreamDownloadId(id, downloadId)
        }
    }

    private suspend fun downloadTrickplayData(
        itemId: UUID,
        sourceId: String,
        trickplayInfo: FindroidTrickplayInfo,
    ) {
        val maxIndex = ceil(trickplayInfo.thumbnailCount.toDouble().div(trickplayInfo.tileWidth * trickplayInfo.tileHeight)).toInt()
        val byteArrays = mutableListOf<ByteArray>()
        for (i in 0..maxIndex) {
            jellyfinRepository.getTrickplayData(
                itemId,
                trickplayInfo.width,
                i,
            )?.let { byteArray ->
                byteArrays.add(byteArray)
            }
        }
        saveTrickplayData(itemId, sourceId, trickplayInfo, byteArrays)
    }

    private fun saveTrickplayData(
        itemId: UUID,
        sourceId: String,
        trickplayInfo: FindroidTrickplayInfo,
        byteArrays: List<ByteArray>,
    ) {
        val basePath = "trickplay/$itemId/$sourceId"
        database.insertTrickplayInfo(trickplayInfo.toFindroidTrickplayInfoDto(sourceId))
        File(context.filesDir, basePath).mkdirs()
        for ((i, byteArray) in byteArrays.withIndex()) {
            val file = File(context.filesDir, "$basePath/$i")
            file.writeBytes(byteArray)
        }
    }
}
