package eu.kanade.tachiyomi.data.backup

import android.Manifest
import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.backup.BackupCreateFlags.BACKUP_APP_PREFS
import eu.kanade.tachiyomi.data.backup.BackupCreateFlags.BACKUP_CATEGORY
import eu.kanade.tachiyomi.data.backup.BackupCreateFlags.BACKUP_CHAPTER
import eu.kanade.tachiyomi.data.backup.BackupCreateFlags.BACKUP_CUSTOM_INFO
import eu.kanade.tachiyomi.data.backup.BackupCreateFlags.BACKUP_HISTORY
import eu.kanade.tachiyomi.data.backup.BackupCreateFlags.BACKUP_READ_MANGA
import eu.kanade.tachiyomi.data.backup.BackupCreateFlags.BACKUP_SOURCE_PREFS
import eu.kanade.tachiyomi.data.backup.BackupCreateFlags.BACKUP_TRACK
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupFlatMetadata
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSavedSearch
import eu.kanade.tachiyomi.data.backup.models.BackupSerializer
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.FloatPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.LongPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.backupCategoryMapper
import eu.kanade.tachiyomi.data.backup.models.backupChapterMapper
import eu.kanade.tachiyomi.data.backup.models.backupMergedMangaReferenceMapper
import eu.kanade.tachiyomi.data.backup.models.backupSavedSearchMapper
import eu.kanade.tachiyomi.data.backup.models.backupTrackMapper
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.source.preferenceKey
import eu.kanade.tachiyomi.source.sourcePreferences
import eu.kanade.tachiyomi.util.system.hasPermission
import exh.source.MERGED_SOURCE_ID
import exh.source.getMainSource
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import okio.buffer
import okio.gzip
import okio.sink
import tachiyomi.core.preference.Preference
import tachiyomi.core.preference.PreferenceStore
import tachiyomi.core.util.system.logcat
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.manga.MangaMapper
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetMergedManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.FileOutputStream

class BackupCreator(
    private val context: Context,
) {

    private val handler: DatabaseHandler = Injekt.get()
    private val sourceManager: SourceManager = Injekt.get()
    private val backupPreferences: BackupPreferences = Injekt.get()
    private val getCategories: GetCategories = Injekt.get()
    private val getFavorites: GetFavorites = Injekt.get()
    private val getHistory: GetHistory = Injekt.get()
    private val preferenceStore: PreferenceStore = Injekt.get()

    // SY -->
    private val getMergedManga: GetMergedManga = Injekt.get()
    private val getCustomMangaInfo: GetCustomMangaInfo = Injekt.get()
    private val getFlatMetadataById: GetFlatMetadataById = Injekt.get()
    // SY <--

    internal val parser = ProtoBuf

    /**
     * Create backup file.
     *
     * @param uri path of Uri
     * @param isAutoBackup backup called from scheduled backup job
     */
    suspend fun createBackup(uri: Uri, flags: Int, isAutoBackup: Boolean): String {
        if (!context.hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            throw IllegalStateException(context.getString(R.string.missing_storage_permission))
        }

        val databaseManga = getFavorites.await() /* SY --> */ +
            if (flags and BACKUP_READ_MANGA == BACKUP_READ_MANGA) {
                handler.awaitList { mangasQueries.getReadMangaNotInLibrary(MangaMapper::mapManga) }
            } else {
                emptyList()
            } + getMergedManga.await() // SY <--
        val backup = Backup(
            backupMangas(databaseManga, flags),
            backupCategories(flags),
            emptyList(),
            prepExtensionInfoForSync(databaseManga),
            backupAppPreferences(flags),
            backupSourcePreferences(flags),
            // SY -->
            backupSavedSearches(),
            // SY <--
        )

        var file: UniFile? = null
        try {
            file = (
                if (isAutoBackup) {
                    // Get dir of file and create
                    var dir = UniFile.fromUri(context, uri)
                    dir = dir.createDirectory("automatic")

                    // Delete older backups
                    val numberOfBackups = backupPreferences.numberOfBackups().get()
                    dir.listFiles { _, filename -> Backup.filenameRegex.matches(filename) }
                        .orEmpty()
                        .sortedByDescending { it.name }
                        .drop(numberOfBackups - 1)
                        .forEach { it.delete() }

                    // Create new file to place backup
                    dir.createFile(Backup.getFilename())
                } else {
                    UniFile.fromUri(context, uri)
                }
                )
                ?: throw Exception(context.getString(R.string.create_backup_file_error))

            if (!file.isFile) {
                throw IllegalStateException("Failed to get handle on a backup file")
            }

            val byteArray = parser.encodeToByteArray(BackupSerializer, backup)
            if (byteArray.isEmpty()) {
                throw IllegalStateException(context.getString(R.string.empty_backup_error))
            }

            file.openOutputStream().also {
                // Force overwrite old file
                (it as? FileOutputStream)?.channel?.truncate(0)
            }.sink().gzip().buffer().use { it.write(byteArray) }
            val fileUri = file.uri

            // Make sure it's a valid backup file
            BackupFileValidator().validate(context, fileUri)

            return fileUri.toString()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            file?.delete()
            throw e
        }
    }

    private fun prepExtensionInfoForSync(mangas: List<Manga>): List<BackupSource> {
        return mangas
            .asSequence()
            .map(Manga::source)
            .distinct()
            .map(sourceManager::getOrStub)
            .map(BackupSource::copyFrom)
            .toList()
    }

    /**
     * Backup the categories of library
     *
     * @return list of [BackupCategory] to be backed up
     */
    private suspend fun backupCategories(options: Int): List<BackupCategory> {
        // Check if user wants category information in backup
        return if (options and BACKUP_CATEGORY == BACKUP_CATEGORY) {
            getCategories.await()
                .filterNot(Category::isSystemCategory)
                .map(backupCategoryMapper)
        } else {
            emptyList()
        }
    }

    private suspend fun backupMangas(mangas: List<Manga>, flags: Int): List<BackupManga> {
        return mangas.map {
            backupManga(it, flags)
        }
    }

    // SY -->
    /**
     * Backup the saved searches from sources
     *
     * @return list of [BackupSavedSearch] to be backed up
     */
    private suspend fun backupSavedSearches(): List<BackupSavedSearch> {
        return handler.awaitList { saved_searchQueries.selectAll(backupSavedSearchMapper) }
    }
    // SY <--

    /**
     * Convert a manga to Json
     *
     * @param manga manga that gets converted
     * @param options options for the backup
     * @return [BackupManga] containing manga in a serializable form
     */
    private suspend fun backupManga(manga: Manga, options: Int): BackupManga {
        // Entry for this manga
        val mangaObject = BackupManga.copyFrom(
            manga,
            // SY -->
            if (options and BACKUP_CUSTOM_INFO == BACKUP_CUSTOM_INFO) {
                getCustomMangaInfo.get(manga.id)
            } else {
                null
            }, /* SY <-- */
        )

        // SY -->
        if (manga.source == MERGED_SOURCE_ID) {
            mangaObject.mergedMangaReferences = handler.awaitList {
                mergedQueries.selectByMergeId(manga.id, backupMergedMangaReferenceMapper)
            }
        }

        val source = sourceManager.get(manga.source)?.getMainSource<MetadataSource<*, *>>()
        if (source != null) {
            getFlatMetadataById.await(manga.id)?.let { flatMetadata ->
                mangaObject.flatMetadata = BackupFlatMetadata.copyFrom(flatMetadata)
            }
        }
        // SY <--

        // Check if user wants chapter information in backup
        if (options and BACKUP_CHAPTER == BACKUP_CHAPTER) {
            // Backup all the chapters
            handler.awaitList {
                chaptersQueries.getChaptersByMangaId(
                    mangaId = manga.id,
                    applyScanlatorFilter = 0, // false
                    mapper = backupChapterMapper,
                )
            }
                .takeUnless(List<BackupChapter>::isEmpty)
                ?.let { mangaObject.chapters = it }
        }

        // Check if user wants category information in backup
        if (options and BACKUP_CATEGORY == BACKUP_CATEGORY) {
            // Backup categories for this manga
            val categoriesForManga = getCategories.await(manga.id)
            if (categoriesForManga.isNotEmpty()) {
                mangaObject.categories = categoriesForManga.map { it.order }
            }
        }

        // Check if user wants track information in backup
        if (options and BACKUP_TRACK == BACKUP_TRACK) {
            val tracks = handler.awaitList { manga_syncQueries.getTracksByMangaId(manga.id, backupTrackMapper) }
            if (tracks.isNotEmpty()) {
                mangaObject.tracking = tracks
            }
        }

        // Check if user wants history information in backup
        if (options and BACKUP_HISTORY == BACKUP_HISTORY) {
            val historyByMangaId = getHistory.await(manga.id)
            if (historyByMangaId.isNotEmpty()) {
                val history = historyByMangaId.map { history ->
                    val chapter = handler.awaitOne { chaptersQueries.getChapterById(history.chapterId) }
                    BackupHistory(chapter.url, history.readAt?.time ?: 0L, history.readDuration)
                }
                if (history.isNotEmpty()) {
                    mangaObject.history = history
                }
            }
        }

        return mangaObject
    }

    private fun backupAppPreferences(flags: Int): List<BackupPreference> {
        if (flags and BACKUP_APP_PREFS != BACKUP_APP_PREFS) return emptyList()

        return preferenceStore.getAll().toBackupPreferences()
    }

    private fun backupSourcePreferences(flags: Int): List<BackupSourcePreferences> {
        if (flags and BACKUP_SOURCE_PREFS != BACKUP_SOURCE_PREFS) return emptyList()

        return sourceManager.getCatalogueSources()
            .filterIsInstance<ConfigurableSource>()
            .map {
                BackupSourcePreferences(
                    it.preferenceKey(),
                    it.sourcePreferences().all.toBackupPreferences(),
                )
            }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, *>.toBackupPreferences(): List<BackupPreference> {
        return this.filterKeys {
            !Preference.isPrivate(it) && !Preference.isAppState(it)
        }
            .mapNotNull { (key, value) ->
                when (value) {
                    is Int -> BackupPreference(key, IntPreferenceValue(value))
                    is Long -> BackupPreference(key, LongPreferenceValue(value))
                    is Float -> BackupPreference(key, FloatPreferenceValue(value))
                    is String -> BackupPreference(key, StringPreferenceValue(value))
                    is Boolean -> BackupPreference(key, BooleanPreferenceValue(value))
                    is Set<*> -> (value as? Set<String>)?.let {
                        BackupPreference(key, StringSetPreferenceValue(it))
                    }
                    else -> null
                }
            }
    }
}